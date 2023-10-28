/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.codecs.lucene99;

import static org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat.DIRECT_MONOTONIC_BLOCK_SHIFT;
import static org.apache.lucene.codecs.lucene99.Lucene99ScalarQuantizedVectorsFormat.QUANTIZED_VECTOR_COMPONENT;
import static org.apache.lucene.codecs.lucene99.Lucene99ScalarQuantizedVectorsFormat.calculateDefaultQuantile;
import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene95.OffHeapByteVectorValues;
import org.apache.lucene.codecs.lucene95.OffHeapFloatVectorValues;
import org.apache.lucene.codecs.lucene95.OrdToDocDISIReaderConfiguration;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.DocsWithFieldSet;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Sorter;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.lucene.util.ScalarQuantizer;
import org.apache.lucene.util.hnsw.CloseableRandomVectorScorerSupplier;
import org.apache.lucene.util.hnsw.ConcurrentHnswMerger;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.HnswGraph.NodesIterator;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.HnswGraphMerger;
import org.apache.lucene.util.hnsw.IncrementalHnswGraphMerger;
import org.apache.lucene.util.hnsw.NeighborArray;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomAccessVectorValues;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;
import org.apache.lucene.util.packed.DirectMonotonicWriter;

/**
 * Writes vector values and knn graphs to index segments.
 *
 * @lucene.experimental
 */
public final class Lucene99HnswVectorsWriter extends KnnVectorsWriter {

  private final SegmentWriteState segmentWriteState;
  private final IndexOutput meta, vectorData, quantizedVectorData, vectorIndex;
  private final int M;
  private final int beamWidth;
  private final Lucene99ScalarQuantizedVectorsWriter quantizedVectorsWriter;
  private final int numMergeWorkers;
  private final ExecutorService mergeExec;

  private final List<FieldWriter<?>> fields = new ArrayList<>();
  private boolean finished;

  Lucene99HnswVectorsWriter(
      SegmentWriteState state,
      int M,
      int beamWidth,
      Lucene99ScalarQuantizedVectorsFormat quantizedVectorsFormat,
      int numMergeWorkers,
      ExecutorService mergeExec)
      throws IOException {
    this.M = M;
    this.beamWidth = beamWidth;
    this.numMergeWorkers = numMergeWorkers;
    this.mergeExec = mergeExec;
    segmentWriteState = state;
    String metaFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name, state.segmentSuffix, Lucene99HnswVectorsFormat.META_EXTENSION);

    String vectorDataFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            Lucene99HnswVectorsFormat.VECTOR_DATA_EXTENSION);

    String indexDataFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            Lucene99HnswVectorsFormat.VECTOR_INDEX_EXTENSION);

    final String quantizedVectorDataFileName =
        quantizedVectorsFormat != null
            ? IndexFileNames.segmentFileName(
                state.segmentInfo.name,
                state.segmentSuffix,
                Lucene99ScalarQuantizedVectorsFormat.QUANTIZED_VECTOR_DATA_EXTENSION)
            : null;
    boolean success = false;
    try {
      meta = state.directory.createOutput(metaFileName, state.context);
      vectorData = state.directory.createOutput(vectorDataFileName, state.context);
      vectorIndex = state.directory.createOutput(indexDataFileName, state.context);

      CodecUtil.writeIndexHeader(
          meta,
          Lucene99HnswVectorsFormat.META_CODEC_NAME,
          Lucene99HnswVectorsFormat.VERSION_CURRENT,
          state.segmentInfo.getId(),
          state.segmentSuffix);
      CodecUtil.writeIndexHeader(
          vectorData,
          Lucene99HnswVectorsFormat.VECTOR_DATA_CODEC_NAME,
          Lucene99HnswVectorsFormat.VERSION_CURRENT,
          state.segmentInfo.getId(),
          state.segmentSuffix);
      CodecUtil.writeIndexHeader(
          vectorIndex,
          Lucene99HnswVectorsFormat.VECTOR_INDEX_CODEC_NAME,
          Lucene99HnswVectorsFormat.VERSION_CURRENT,
          state.segmentInfo.getId(),
          state.segmentSuffix);
      if (quantizedVectorDataFileName != null) {
        quantizedVectorData =
            state.directory.createOutput(quantizedVectorDataFileName, state.context);
        CodecUtil.writeIndexHeader(
            quantizedVectorData,
            Lucene99ScalarQuantizedVectorsFormat.QUANTIZED_VECTOR_DATA_CODEC_NAME,
            Lucene99ScalarQuantizedVectorsFormat.VERSION_CURRENT,
            state.segmentInfo.getId(),
            state.segmentSuffix);
        quantizedVectorsWriter =
            new Lucene99ScalarQuantizedVectorsWriter(
                quantizedVectorData, quantizedVectorsFormat.quantile);
      } else {
        quantizedVectorData = null;
        quantizedVectorsWriter = null;
      }
      success = true;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(this);
      }
    }
  }

  @Override
  public KnnFieldVectorsWriter<?> addField(FieldInfo fieldInfo) throws IOException {
    Lucene99ScalarQuantizedVectorsWriter.QuantizationFieldVectorWriter quantizedVectorFieldWriter =
        null;
    // Quantization only supports FLOAT32 for now
    if (quantizedVectorsWriter != null
        && fieldInfo.getVectorEncoding().equals(VectorEncoding.FLOAT32)) {
      quantizedVectorFieldWriter =
          quantizedVectorsWriter.addField(fieldInfo, segmentWriteState.infoStream);
    }
    FieldWriter<?> newField =
        FieldWriter.create(
            fieldInfo, M, beamWidth, segmentWriteState.infoStream, quantizedVectorFieldWriter);
    fields.add(newField);
    return newField;
  }

  @Override
  public void flush(int maxDoc, Sorter.DocMap sortMap) throws IOException {
    for (FieldWriter<?> field : fields) {
      long[] quantizedVectorOffsetAndLen = null;
      if (field.quantizedWriter != null) {
        assert quantizedVectorsWriter != null;
        quantizedVectorOffsetAndLen =
            quantizedVectorsWriter.flush(sortMap, field.quantizedWriter, field.docsWithField);
      }
      if (sortMap == null) {
        writeField(field, maxDoc, quantizedVectorOffsetAndLen);
      } else {
        writeSortingField(field, maxDoc, sortMap, quantizedVectorOffsetAndLen);
      }
    }
  }

  @Override
  public void finish() throws IOException {
    if (finished) {
      throw new IllegalStateException("already finished");
    }
    finished = true;
    if (quantizedVectorsWriter != null) {
      quantizedVectorsWriter.finish();
    }

    if (meta != null) {
      // write end of fields marker
      meta.writeInt(-1);
      CodecUtil.writeFooter(meta);
    }
    if (vectorData != null) {
      CodecUtil.writeFooter(vectorData);
      CodecUtil.writeFooter(vectorIndex);
    }
  }

  @Override
  public long ramBytesUsed() {
    long total = 0;
    for (FieldWriter<?> field : fields) {
      total += field.ramBytesUsed();
    }
    return total;
  }

  private void writeField(FieldWriter<?> fieldData, int maxDoc, long[] quantizedVecOffsetAndLen)
      throws IOException {
    // write vector values
    long vectorDataOffset = vectorData.alignFilePointer(Float.BYTES);
    switch (fieldData.fieldInfo.getVectorEncoding()) {
      case BYTE:
        writeByteVectors(fieldData);
        break;
      case FLOAT32:
        writeFloat32Vectors(fieldData);
        break;
    }
    long vectorDataLength = vectorData.getFilePointer() - vectorDataOffset;

    // write graph
    long vectorIndexOffset = vectorIndex.getFilePointer();
    OnHeapHnswGraph graph = fieldData.getGraph();
    int[][] graphLevelNodeOffsets = writeGraph(graph);
    long vectorIndexLength = vectorIndex.getFilePointer() - vectorIndexOffset;

    writeMeta(
        fieldData.isQuantized(),
        fieldData.fieldInfo,
        maxDoc,
        fieldData.getConfiguredQuantile(),
        fieldData.getMinQuantile(),
        fieldData.getMaxQuantile(),
        quantizedVecOffsetAndLen,
        vectorDataOffset,
        vectorDataLength,
        vectorIndexOffset,
        vectorIndexLength,
        fieldData.docsWithField,
        graph,
        graphLevelNodeOffsets);
  }

  private void writeFloat32Vectors(FieldWriter<?> fieldData) throws IOException {
    final ByteBuffer buffer =
        ByteBuffer.allocate(fieldData.dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (Object v : fieldData.vectors) {
      buffer.asFloatBuffer().put((float[]) v);
      vectorData.writeBytes(buffer.array(), buffer.array().length);
    }
  }

  private void writeByteVectors(FieldWriter<?> fieldData) throws IOException {
    for (Object v : fieldData.vectors) {
      byte[] vector = (byte[]) v;
      vectorData.writeBytes(vector, vector.length);
    }
  }

  private void writeSortingField(
      FieldWriter<?> fieldData,
      int maxDoc,
      Sorter.DocMap sortMap,
      long[] quantizedVectorOffsetAndLen)
      throws IOException {
    final int[] docIdOffsets = new int[sortMap.size()];
    int offset = 1; // 0 means no vector for this (field, document)
    DocIdSetIterator iterator = fieldData.docsWithField.iterator();
    for (int docID = iterator.nextDoc();
        docID != DocIdSetIterator.NO_MORE_DOCS;
        docID = iterator.nextDoc()) {
      int newDocID = sortMap.oldToNew(docID);
      docIdOffsets[newDocID] = offset++;
    }
    DocsWithFieldSet newDocsWithField = new DocsWithFieldSet();
    final int[] ordMap = new int[offset - 1]; // new ord to old ord
    final int[] oldOrdMap = new int[offset - 1]; // old ord to new ord
    int ord = 0;
    int doc = 0;
    for (int docIdOffset : docIdOffsets) {
      if (docIdOffset != 0) {
        ordMap[ord] = docIdOffset - 1;
        oldOrdMap[docIdOffset - 1] = ord;
        newDocsWithField.add(doc);
        ord++;
      }
      doc++;
    }

    // write vector values
    final long vectorDataOffset;
    switch (fieldData.fieldInfo.getVectorEncoding()) {
      case BYTE:
        vectorDataOffset = writeSortedByteVectors(fieldData, ordMap);
        break;
      case FLOAT32:
        vectorDataOffset = writeSortedFloat32Vectors(fieldData, ordMap);
        break;
      default:
        throw new IllegalStateException(
            "Unsupported vector encoding: " + fieldData.fieldInfo.getVectorEncoding());
    }
    long vectorDataLength = vectorData.getFilePointer() - vectorDataOffset;

    // write graph
    long vectorIndexOffset = vectorIndex.getFilePointer();
    OnHeapHnswGraph graph = fieldData.getGraph();
    int[][] graphLevelNodeOffsets = graph == null ? new int[0][] : new int[graph.numLevels()][];
    HnswGraph mockGraph = reconstructAndWriteGraph(graph, ordMap, oldOrdMap, graphLevelNodeOffsets);
    long vectorIndexLength = vectorIndex.getFilePointer() - vectorIndexOffset;

    writeMeta(
        fieldData.isQuantized(),
        fieldData.fieldInfo,
        maxDoc,
        fieldData.getConfiguredQuantile(),
        fieldData.getMinQuantile(),
        fieldData.getMaxQuantile(),
        quantizedVectorOffsetAndLen,
        vectorDataOffset,
        vectorDataLength,
        vectorIndexOffset,
        vectorIndexLength,
        newDocsWithField,
        mockGraph,
        graphLevelNodeOffsets);
  }

  private long writeSortedFloat32Vectors(FieldWriter<?> fieldData, int[] ordMap)
      throws IOException {
    long vectorDataOffset = vectorData.alignFilePointer(Float.BYTES);
    final ByteBuffer buffer =
        ByteBuffer.allocate(fieldData.dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (int ordinal : ordMap) {
      float[] vector = (float[]) fieldData.vectors.get(ordinal);
      buffer.asFloatBuffer().put(vector);
      vectorData.writeBytes(buffer.array(), buffer.array().length);
    }
    return vectorDataOffset;
  }

  private long writeSortedByteVectors(FieldWriter<?> fieldData, int[] ordMap) throws IOException {
    long vectorDataOffset = vectorData.alignFilePointer(Float.BYTES);
    for (int ordinal : ordMap) {
      byte[] vector = (byte[]) fieldData.vectors.get(ordinal);
      vectorData.writeBytes(vector, vector.length);
    }
    return vectorDataOffset;
  }

  /**
   * Reconstructs the graph given the old and new node ids.
   *
   * <p>Additionally, the graph node connections are written to the vectorIndex.
   *
   * @param graph The current on heap graph
   * @param newToOldMap the new node ids indexed to the old node ids
   * @param oldToNewMap the old node ids indexed to the new node ids
   * @param levelNodeOffsets where to place the new offsets for the nodes in the vector index.
   * @return The graph
   * @throws IOException if writing to vectorIndex fails
   */
  private HnswGraph reconstructAndWriteGraph(
      OnHeapHnswGraph graph, int[] newToOldMap, int[] oldToNewMap, int[][] levelNodeOffsets)
      throws IOException {
    if (graph == null) return null;

    List<int[]> nodesByLevel = new ArrayList<>(graph.numLevels());
    nodesByLevel.add(null);

    int maxOrd = graph.size();
    NodesIterator nodesOnLevel0 = graph.getNodesOnLevel(0);
    levelNodeOffsets[0] = new int[nodesOnLevel0.size()];
    while (nodesOnLevel0.hasNext()) {
      int node = nodesOnLevel0.nextInt();
      NeighborArray neighbors = graph.getNeighbors(0, newToOldMap[node]);
      long offset = vectorIndex.getFilePointer();
      reconstructAndWriteNeighbours(neighbors, oldToNewMap, maxOrd);
      levelNodeOffsets[0][node] = Math.toIntExact(vectorIndex.getFilePointer() - offset);
    }

    for (int level = 1; level < graph.numLevels(); level++) {
      NodesIterator nodesOnLevel = graph.getNodesOnLevel(level);
      int[] newNodes = new int[nodesOnLevel.size()];
      for (int n = 0; nodesOnLevel.hasNext(); n++) {
        newNodes[n] = oldToNewMap[nodesOnLevel.nextInt()];
      }
      Arrays.sort(newNodes);
      nodesByLevel.add(newNodes);
      levelNodeOffsets[level] = new int[newNodes.length];
      int nodeOffsetIndex = 0;
      for (int node : newNodes) {
        NeighborArray neighbors = graph.getNeighbors(level, newToOldMap[node]);
        long offset = vectorIndex.getFilePointer();
        reconstructAndWriteNeighbours(neighbors, oldToNewMap, maxOrd);
        levelNodeOffsets[level][nodeOffsetIndex++] =
            Math.toIntExact(vectorIndex.getFilePointer() - offset);
      }
    }
    return new HnswGraph() {
      @Override
      public int nextNeighbor() {
        throw new UnsupportedOperationException("Not supported on a mock graph");
      }

      @Override
      public void seek(int level, int target) {
        throw new UnsupportedOperationException("Not supported on a mock graph");
      }

      @Override
      public int size() {
        return graph.size();
      }

      @Override
      public int numLevels() {
        return graph.numLevels();
      }

      @Override
      public int entryNode() {
        throw new UnsupportedOperationException("Not supported on a mock graph");
      }

      @Override
      public NodesIterator getNodesOnLevel(int level) {
        if (level == 0) {
          return graph.getNodesOnLevel(0);
        } else {
          return new ArrayNodesIterator(nodesByLevel.get(level), nodesByLevel.get(level).length);
        }
      }
    };
  }

  private void reconstructAndWriteNeighbours(NeighborArray neighbors, int[] oldToNewMap, int maxOrd)
      throws IOException {
    int size = neighbors.size();
    vectorIndex.writeVInt(size);

    // Destructively modify; it's ok we are discarding it after this
    int[] nnodes = neighbors.node();
    for (int i = 0; i < size; i++) {
      nnodes[i] = oldToNewMap[nnodes[i]];
    }
    Arrays.sort(nnodes, 0, size);
    // Now that we have sorted, do delta encoding to minimize the required bits to store the
    // information
    for (int i = size - 1; i > 0; --i) {
      assert nnodes[i] < maxOrd : "node too large: " + nnodes[i] + ">=" + maxOrd;
      nnodes[i] -= nnodes[i - 1];
    }
    for (int i = 0; i < size; i++) {
      vectorIndex.writeVInt(nnodes[i]);
    }
  }

  @Override
  public void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
    long vectorDataOffset = vectorData.alignFilePointer(Float.BYTES);
    IndexOutput tempVectorData = null;
    IndexInput vectorDataInput = null;
    CloseableRandomVectorScorerSupplier scorerSupplier = null;
    boolean success = false;
    try {
      ScalarQuantizer scalarQuantizer = null;
      long[] quantizedVectorDataOffsetAndLength = null;
      // If we have configured quantization and are FLOAT32
      if (quantizedVectorsWriter != null
          && fieldInfo.getVectorEncoding().equals(VectorEncoding.FLOAT32)) {
        // We need the quantization parameters to write to the meta file
        scalarQuantizer = quantizedVectorsWriter.mergeQuantiles(fieldInfo, mergeState);
        if (segmentWriteState.infoStream.isEnabled(QUANTIZED_VECTOR_COMPONENT)) {
          segmentWriteState.infoStream.message(
              QUANTIZED_VECTOR_COMPONENT,
              "Merged quantiles field: "
                  + fieldInfo.name
                  + " newly merged quantile: "
                  + scalarQuantizer);
        }
        assert scalarQuantizer != null;
        quantizedVectorDataOffsetAndLength = new long[2];
        quantizedVectorDataOffsetAndLength[0] = quantizedVectorData.alignFilePointer(Float.BYTES);
        scorerSupplier =
            quantizedVectorsWriter.mergeOneField(
                segmentWriteState, fieldInfo, mergeState, scalarQuantizer);
        quantizedVectorDataOffsetAndLength[1] =
            quantizedVectorData.getFilePointer() - quantizedVectorDataOffsetAndLength[0];
      }
      final DocsWithFieldSet docsWithField;
      int byteSize = fieldInfo.getVectorDimension() * fieldInfo.getVectorEncoding().byteSize;

      // If we extract vector storage, this could be cleaner.
      // But for now, vector storage & index creation/storage live together.
      if (scorerSupplier == null) {
        tempVectorData =
            segmentWriteState.directory.createTempOutput(
                vectorData.getName(), "temp", segmentWriteState.context);
        switch (fieldInfo.getVectorEncoding()) {
          case BYTE:
            docsWithField =
                writeByteVectorData(
                    tempVectorData,
                    MergedVectorValues.mergeByteVectorValues(fieldInfo, mergeState));
            break;
          case FLOAT32:
            docsWithField =
                writeVectorData(
                    tempVectorData,
                    MergedVectorValues.mergeFloatVectorValues(fieldInfo, mergeState));
            break;
          default:
            throw new IllegalStateException(
                "Unsupported vector encoding: " + fieldInfo.getVectorEncoding());
        }
        CodecUtil.writeFooter(tempVectorData);
        IOUtils.close(tempVectorData);
        // copy the temporary file vectors to the actual data file
        vectorDataInput =
            segmentWriteState.directory.openInput(
                tempVectorData.getName(), segmentWriteState.context);
        vectorData.copyBytes(vectorDataInput, vectorDataInput.length() - CodecUtil.footerLength());
        CodecUtil.retrieveChecksum(vectorDataInput);
        final RandomVectorScorerSupplier innerScoreSupplier;
        switch (fieldInfo.getVectorEncoding()) {
          case BYTE:
            innerScoreSupplier =
                RandomVectorScorerSupplier.createBytes(
                    new OffHeapByteVectorValues.DenseOffHeapVectorValues(
                        fieldInfo.getVectorDimension(),
                        docsWithField.cardinality(),
                        vectorDataInput,
                        byteSize),
                    fieldInfo.getVectorSimilarityFunction());
            break;
          case FLOAT32:
            innerScoreSupplier =
                RandomVectorScorerSupplier.createFloats(
                    new OffHeapFloatVectorValues.DenseOffHeapVectorValues(
                        fieldInfo.getVectorDimension(),
                        docsWithField.cardinality(),
                        vectorDataInput,
                        byteSize),
                    fieldInfo.getVectorSimilarityFunction());
            break;
          default:
            throw new IllegalStateException(
                "Unsupported vector encoding: " + fieldInfo.getVectorEncoding());
        }
        final String tempFileName = tempVectorData.getName();
        final IndexInput finalVectorDataInput = vectorDataInput;
        scorerSupplier =
            new CloseableRandomVectorScorerSupplier() {
              boolean closed = false;

              @Override
              public RandomVectorScorer scorer(int ord) throws IOException {
                return innerScoreSupplier.scorer(ord);
              }

              @Override
              public void close() throws IOException {
                if (closed) {
                  return;
                }
                closed = true;
                IOUtils.close(finalVectorDataInput);
                segmentWriteState.directory.deleteFile(tempFileName);
              }

              @Override
              public RandomVectorScorerSupplier copy() throws IOException {
                // here we just return the inner out since we only need to close this outside copy
                return innerScoreSupplier.copy();
              }
            };
      } else {
        // No need to use temporary file as we don't have to re-open for reading
        switch (fieldInfo.getVectorEncoding()) {
          case BYTE:
            docsWithField =
                writeByteVectorData(
                    vectorData, MergedVectorValues.mergeByteVectorValues(fieldInfo, mergeState));
            break;
          case FLOAT32:
            docsWithField =
                writeVectorData(
                    vectorData, MergedVectorValues.mergeFloatVectorValues(fieldInfo, mergeState));
            break;
          default:
            throw new IllegalStateException(
                "Unsupported vector encoding: " + fieldInfo.getVectorEncoding());
        }
      }

      long vectorDataLength = vectorData.getFilePointer() - vectorDataOffset;
      long vectorIndexOffset = vectorIndex.getFilePointer();
      // build the graph using the temporary vector data
      // we use Lucene99HnswVectorsReader.DenseOffHeapVectorValues for the graph construction
      // doesn't need to know docIds
      // TODO: separate random access vector values from DocIdSetIterator?
      OnHeapHnswGraph graph = null;
      int[][] vectorIndexNodeOffsets = null;
      if (docsWithField.cardinality() != 0) {
        // build graph
        HnswGraphMerger merger = createGraphMerger(fieldInfo, scorerSupplier);
        for (int i = 0; i < mergeState.liveDocs.length; i++) {
          merger.addReader(
              mergeState.knnVectorsReaders[i], mergeState.docMaps[i], mergeState.liveDocs[i]);
        }
        final DocIdSetIterator mergedVectorIterator;
        switch (fieldInfo.getVectorEncoding()) {
          case BYTE:
            mergedVectorIterator =
                KnnVectorsWriter.MergedVectorValues.mergeByteVectorValues(fieldInfo, mergeState);
            break;
          case FLOAT32:
            mergedVectorIterator =
                KnnVectorsWriter.MergedVectorValues.mergeFloatVectorValues(fieldInfo, mergeState);
            break;
          default:
            throw new IllegalStateException(
                "Unsupported vector encoding: " + fieldInfo.getVectorEncoding());
        }
        graph =
            merger.merge(
                mergedVectorIterator, segmentWriteState.infoStream, docsWithField.cardinality());
        vectorIndexNodeOffsets = writeGraph(graph);
      }
      long vectorIndexLength = vectorIndex.getFilePointer() - vectorIndexOffset;
      writeMeta(
          scalarQuantizer != null,
          fieldInfo,
          segmentWriteState.segmentInfo.maxDoc(),
          scalarQuantizer == null ? null : scalarQuantizer.getConfiguredQuantile(),
          scalarQuantizer == null ? null : scalarQuantizer.getLowerQuantile(),
          scalarQuantizer == null ? null : scalarQuantizer.getUpperQuantile(),
          quantizedVectorDataOffsetAndLength,
          vectorDataOffset,
          vectorDataLength,
          vectorIndexOffset,
          vectorIndexLength,
          docsWithField,
          graph,
          vectorIndexNodeOffsets);
      success = true;
    } finally {
      if (success) {
        IOUtils.close(scorerSupplier);
      } else {
        IOUtils.closeWhileHandlingException(scorerSupplier, vectorDataInput, tempVectorData);
        if (tempVectorData != null) {
          IOUtils.deleteFilesIgnoringExceptions(
              segmentWriteState.directory, tempVectorData.getName());
        }
      }
    }
  }

  /**
   * @param graph Write the graph in a compressed format
   * @return The non-cumulative offsets for the nodes. Should be used to create cumulative offsets.
   * @throws IOException if writing to vectorIndex fails
   */
  private int[][] writeGraph(OnHeapHnswGraph graph) throws IOException {
    if (graph == null) return new int[0][0];
    // write vectors' neighbours on each level into the vectorIndex file
    int countOnLevel0 = graph.size();
    int[][] offsets = new int[graph.numLevels()][];
    for (int level = 0; level < graph.numLevels(); level++) {
      int[] sortedNodes = getSortedNodes(graph.getNodesOnLevel(level));
      offsets[level] = new int[sortedNodes.length];
      int nodeOffsetId = 0;
      for (int node : sortedNodes) {
        NeighborArray neighbors = graph.getNeighbors(level, node);
        int size = neighbors.size();
        // Write size in VInt as the neighbors list is typically small
        long offsetStart = vectorIndex.getFilePointer();
        vectorIndex.writeVInt(size);
        // Destructively modify; it's ok we are discarding it after this
        int[] nnodes = neighbors.node();
        Arrays.sort(nnodes, 0, size);
        // Now that we have sorted, do delta encoding to minimize the required bits to store the
        // information
        for (int i = size - 1; i > 0; --i) {
          assert nnodes[i] < countOnLevel0 : "node too large: " + nnodes[i] + ">=" + countOnLevel0;
          nnodes[i] -= nnodes[i - 1];
        }
        for (int i = 0; i < size; i++) {
          vectorIndex.writeVInt(nnodes[i]);
        }
        offsets[level][nodeOffsetId++] =
            Math.toIntExact(vectorIndex.getFilePointer() - offsetStart);
      }
    }
    return offsets;
  }

  public static int[] getSortedNodes(NodesIterator nodesOnLevel) {
    int[] sortedNodes = new int[nodesOnLevel.size()];
    for (int n = 0; nodesOnLevel.hasNext(); n++) {
      sortedNodes[n] = nodesOnLevel.nextInt();
    }
    Arrays.sort(sortedNodes);
    return sortedNodes;
  }

  private HnswGraphMerger createGraphMerger(
      FieldInfo fieldInfo, RandomVectorScorerSupplier scorerSupplier) {
    if (mergeExec != null) {
      return new ConcurrentHnswMerger(
          fieldInfo, scorerSupplier, M, beamWidth, mergeExec, numMergeWorkers);
    }
    return new IncrementalHnswGraphMerger(fieldInfo, scorerSupplier, M, beamWidth);
  }

  private void writeMeta(
      boolean isQuantized,
      FieldInfo field,
      int maxDoc,
      Float configuredQuantizationQuantile,
      Float lowerQuantile,
      Float upperQuantile,
      long[] quantizedVectorDataOffsetAndLen,
      long vectorDataOffset,
      long vectorDataLength,
      long vectorIndexOffset,
      long vectorIndexLength,
      DocsWithFieldSet docsWithField,
      HnswGraph graph,
      int[][] graphLevelNodeOffsets)
      throws IOException {
    meta.writeInt(field.number);
    meta.writeInt(field.getVectorEncoding().ordinal());
    meta.writeInt(field.getVectorSimilarityFunction().ordinal());
    meta.writeByte(isQuantized ? (byte) 1 : (byte) 0);
    if (isQuantized) {
      assert lowerQuantile != null
          && upperQuantile != null
          && quantizedVectorDataOffsetAndLen != null;
      assert quantizedVectorDataOffsetAndLen.length == 2;
      meta.writeInt(
          Float.floatToIntBits(
              configuredQuantizationQuantile != null
                  ? configuredQuantizationQuantile
                  : calculateDefaultQuantile(field.getVectorDimension())));
      meta.writeInt(Float.floatToIntBits(lowerQuantile));
      meta.writeInt(Float.floatToIntBits(upperQuantile));
      meta.writeVLong(quantizedVectorDataOffsetAndLen[0]);
      meta.writeVLong(quantizedVectorDataOffsetAndLen[1]);
    } else {
      assert configuredQuantizationQuantile == null
          && lowerQuantile == null
          && upperQuantile == null
          && quantizedVectorDataOffsetAndLen == null;
    }
    meta.writeVLong(vectorDataOffset);
    meta.writeVLong(vectorDataLength);
    meta.writeVLong(vectorIndexOffset);
    meta.writeVLong(vectorIndexLength);
    meta.writeVInt(field.getVectorDimension());

    // write docIDs
    int count = docsWithField.cardinality();
    meta.writeInt(count);
    if (isQuantized) {
      OrdToDocDISIReaderConfiguration.writeStoredMeta(
          DIRECT_MONOTONIC_BLOCK_SHIFT, meta, quantizedVectorData, count, maxDoc, docsWithField);
    }
    OrdToDocDISIReaderConfiguration.writeStoredMeta(
        DIRECT_MONOTONIC_BLOCK_SHIFT, meta, vectorData, count, maxDoc, docsWithField);

    meta.writeVInt(M);
    // write graph nodes on each level
    if (graph == null) {
      meta.writeVInt(0);
    } else {
      meta.writeVInt(graph.numLevels());
      long valueCount = 0;
      for (int level = 0; level < graph.numLevels(); level++) {
        NodesIterator nodesOnLevel = graph.getNodesOnLevel(level);
        valueCount += nodesOnLevel.size();
        if (level > 0) {
          int[] nol = new int[nodesOnLevel.size()];
          int numberConsumed = nodesOnLevel.consume(nol);
          Arrays.sort(nol);
          assert numberConsumed == nodesOnLevel.size();
          meta.writeVInt(nol.length); // number of nodes on a level
          for (int i = nodesOnLevel.size() - 1; i > 0; --i) {
            nol[i] -= nol[i - 1];
          }
          for (int n : nol) {
            assert n >= 0 : "delta encoding for nodes failed; expected nodes to be sorted";
            meta.writeVInt(n);
          }
        } else {
          assert nodesOnLevel.size() == count : "Level 0 expects to have all nodes";
        }
      }
      long start = vectorIndex.getFilePointer();
      meta.writeLong(start);
      meta.writeVInt(DIRECT_MONOTONIC_BLOCK_SHIFT);
      final DirectMonotonicWriter memoryOffsetsWriter =
          DirectMonotonicWriter.getInstance(
              meta, vectorIndex, valueCount, DIRECT_MONOTONIC_BLOCK_SHIFT);
      long cumulativeOffsetSum = 0;
      for (int[] levelOffsets : graphLevelNodeOffsets) {
        for (int v : levelOffsets) {
          memoryOffsetsWriter.add(cumulativeOffsetSum);
          cumulativeOffsetSum += v;
        }
      }
      memoryOffsetsWriter.finish();
      meta.writeLong(vectorIndex.getFilePointer() - start);
    }
  }

  /**
   * Writes the byte vector values to the output and returns a set of documents that contains
   * vectors.
   */
  private static DocsWithFieldSet writeByteVectorData(
      IndexOutput output, ByteVectorValues byteVectorValues) throws IOException {
    DocsWithFieldSet docsWithField = new DocsWithFieldSet();
    for (int docV = byteVectorValues.nextDoc();
        docV != NO_MORE_DOCS;
        docV = byteVectorValues.nextDoc()) {
      // write vector
      byte[] binaryValue = byteVectorValues.vectorValue();
      assert binaryValue.length == byteVectorValues.dimension() * VectorEncoding.BYTE.byteSize;
      output.writeBytes(binaryValue, binaryValue.length);
      docsWithField.add(docV);
    }
    return docsWithField;
  }

  /**
   * Writes the vector values to the output and returns a set of documents that contains vectors.
   */
  private static DocsWithFieldSet writeVectorData(
      IndexOutput output, FloatVectorValues floatVectorValues) throws IOException {
    DocsWithFieldSet docsWithField = new DocsWithFieldSet();
    ByteBuffer buffer =
        ByteBuffer.allocate(floatVectorValues.dimension() * VectorEncoding.FLOAT32.byteSize)
            .order(ByteOrder.LITTLE_ENDIAN);
    for (int docV = floatVectorValues.nextDoc();
        docV != NO_MORE_DOCS;
        docV = floatVectorValues.nextDoc()) {
      // write vector
      float[] value = floatVectorValues.vectorValue();
      buffer.asFloatBuffer().put(value);
      output.writeBytes(buffer.array(), buffer.limit());
      docsWithField.add(docV);
    }
    return docsWithField;
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(meta, vectorData, vectorIndex, quantizedVectorData);
    if (mergeExec != null) {
      mergeExec.shutdownNow();
    }
  }

  private abstract static class FieldWriter<T> extends KnnFieldVectorsWriter<T> {
    private final FieldInfo fieldInfo;
    private final int dim;
    private final DocsWithFieldSet docsWithField;
    private final List<T> vectors;
    private final HnswGraphBuilder hnswGraphBuilder;
    private final Lucene99ScalarQuantizedVectorsWriter.QuantizationFieldVectorWriter
        quantizedWriter;

    private int lastDocID = -1;
    private int node = 0;

    static FieldWriter<?> create(
        FieldInfo fieldInfo,
        int M,
        int beamWidth,
        InfoStream infoStream,
        Lucene99ScalarQuantizedVectorsWriter.QuantizationFieldVectorWriter writer)
        throws IOException {
      int dim = fieldInfo.getVectorDimension();
      switch (fieldInfo.getVectorEncoding()) {
        case BYTE:
          return new FieldWriter<byte[]>(fieldInfo, M, beamWidth, infoStream, writer) {
            @Override
            public byte[] copyValue(byte[] value) {
              return ArrayUtil.copyOfSubArray(value, 0, dim);
            }
          };
        case FLOAT32:
          return new FieldWriter<float[]>(fieldInfo, M, beamWidth, infoStream, writer) {
            @Override
            public float[] copyValue(float[] value) {
              return ArrayUtil.copyOfSubArray(value, 0, dim);
            }
          };
        default:
          throw new IllegalStateException(
              "Unsupported vector encoding: " + fieldInfo.getVectorEncoding());
      }
    }

    @SuppressWarnings("unchecked")
    FieldWriter(
        FieldInfo fieldInfo,
        int M,
        int beamWidth,
        InfoStream infoStream,
        Lucene99ScalarQuantizedVectorsWriter.QuantizationFieldVectorWriter quantizedWriter)
        throws IOException {
      this.fieldInfo = fieldInfo;
      this.dim = fieldInfo.getVectorDimension();
      this.docsWithField = new DocsWithFieldSet();
      this.quantizedWriter = quantizedWriter;
      vectors = new ArrayList<>();
      if (quantizedWriter != null
          && fieldInfo.getVectorEncoding().equals(VectorEncoding.FLOAT32) == false) {
        throw new IllegalArgumentException(
            "Vector encoding ["
                + VectorEncoding.FLOAT32
                + "] required for quantized vectors; provided="
                + fieldInfo.getVectorEncoding());
      }
      RAVectorValues<T> raVectors = new RAVectorValues<>(vectors, dim);
      final RandomVectorScorerSupplier scorerSupplier;
      switch (fieldInfo.getVectorEncoding()) {
        case BYTE:
          scorerSupplier =
              RandomVectorScorerSupplier.createBytes(
                  (RandomAccessVectorValues<byte[]>) raVectors,
                  fieldInfo.getVectorSimilarityFunction());
          break;
        case FLOAT32:
          scorerSupplier =
              RandomVectorScorerSupplier.createFloats(
                  (RandomAccessVectorValues<float[]>) raVectors,
                  fieldInfo.getVectorSimilarityFunction());
          break;
        default:
          throw new IllegalStateException(
              "Unsupported vector encoding: " + fieldInfo.getVectorEncoding());
      }
      hnswGraphBuilder =
          HnswGraphBuilder.create(scorerSupplier, M, beamWidth, HnswGraphBuilder.randSeed);
      hnswGraphBuilder.setInfoStream(infoStream);
    }

    @Override
    public void addValue(int docID, T vectorValue) throws IOException {
      if (docID == lastDocID) {
        throw new IllegalArgumentException(
            "VectorValuesField \""
                + fieldInfo.name
                + "\" appears more than once in this document (only one value is allowed per field)");
      }
      assert docID > lastDocID;
      T copy = copyValue(vectorValue);
      if (quantizedWriter != null) {
        assert vectorValue instanceof float[];
        quantizedWriter.addValue((float[]) copy);
      }
      docsWithField.add(docID);
      vectors.add(copy);
      hnswGraphBuilder.addGraphNode(node);
      node++;
      lastDocID = docID;
    }

    OnHeapHnswGraph getGraph() {
      if (vectors.size() > 0) {
        return hnswGraphBuilder.getGraph();
      } else {
        return null;
      }
    }

    @Override
    public long ramBytesUsed() {
      if (vectors.size() == 0) return 0;
      long quantizationSpace = quantizedWriter != null ? quantizedWriter.ramBytesUsed() : 0L;
      return docsWithField.ramBytesUsed()
          + (long) vectors.size()
              * (RamUsageEstimator.NUM_BYTES_OBJECT_REF + RamUsageEstimator.NUM_BYTES_ARRAY_HEADER)
          + (long) vectors.size()
              * fieldInfo.getVectorDimension()
              * fieldInfo.getVectorEncoding().byteSize
          + hnswGraphBuilder.getGraph().ramBytesUsed()
          + quantizationSpace;
    }

    Float getConfiguredQuantile() {
      return quantizedWriter == null ? null : quantizedWriter.getQuantile();
    }

    Float getMinQuantile() {
      return quantizedWriter == null ? null : quantizedWriter.getMinQuantile();
    }

    Float getMaxQuantile() {
      return quantizedWriter == null ? null : quantizedWriter.getMaxQuantile();
    }

    boolean isQuantized() {
      return quantizedWriter != null;
    }
  }

  private static class RAVectorValues<T> implements RandomAccessVectorValues<T> {
    private final List<T> vectors;
    private final int dim;

    RAVectorValues(List<T> vectors, int dim) {
      this.vectors = vectors;
      this.dim = dim;
    }

    @Override
    public int size() {
      return vectors.size();
    }

    @Override
    public int dimension() {
      return dim;
    }

    @Override
    public T vectorValue(int targetOrd) throws IOException {
      return vectors.get(targetOrd);
    }

    @Override
    public RandomAccessVectorValues<T> copy() throws IOException {
      return this;
    }
  }
}
