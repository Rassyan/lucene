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
package org.apache.lucene.search;

import java.io.IOException;
import org.apache.lucene.index.Term;
import org.apache.lucene.tests.search.CheckHits;
import org.apache.lucene.tests.search.QueryUtils;
import org.apache.lucene.util.automaton.Operations;
import org.apache.lucene.util.automaton.RegExp;

/** Tests the FieldcacheRewriteMethod with random regular expressions */
public class TestFieldCacheRewriteMethod extends TestRegexpRandom2 {

  /** Test fieldcache rewrite against filter rewrite */
  @Override
  protected void assertSame(String regexp) throws IOException {
    RegexpQuery fieldCache =
        new RegexpQuery(
            new Term(fieldName, regexp),
            RegExp.NONE,
            0,
            _ -> null,
            Operations.DEFAULT_DETERMINIZE_WORK_LIMIT,
            new DocValuesRewriteMethod());

    RegexpQuery filter =
        new RegexpQuery(
            new Term(fieldName, regexp),
            RegExp.NONE,
            0,
            _ -> null,
            Operations.DEFAULT_DETERMINIZE_WORK_LIMIT,
            MultiTermQuery.CONSTANT_SCORE_REWRITE);

    RegexpQuery filter2 =
        new RegexpQuery(
            new Term(fieldName, regexp),
            RegExp.NONE,
            0,
            _ -> null,
            Operations.DEFAULT_DETERMINIZE_WORK_LIMIT,
            MultiTermQuery.CONSTANT_SCORE_BLENDED_REWRITE);

    TopDocs fieldCacheDocs = searcher1.search(fieldCache, 25);
    TopDocs filterDocs = searcher2.search(filter, 25);
    TopDocs filter2Docs = searcher2.search(filter2, 25);

    CheckHits.checkEqual(fieldCache, fieldCacheDocs.scoreDocs, filterDocs.scoreDocs);
    CheckHits.checkEqual(fieldCache, fieldCacheDocs.scoreDocs, filter2Docs.scoreDocs);
  }

  public void testEquals() throws Exception {
    {
      RegexpQuery a1 = new RegexpQuery(new Term(fieldName, "[aA]"), RegExp.NONE);
      RegexpQuery a2 = new RegexpQuery(new Term(fieldName, "[aA]"), RegExp.NONE);
      RegexpQuery b = new RegexpQuery(new Term(fieldName, "[bB]"), RegExp.NONE);
      assertEquals(a1, a2);
      assertFalse(a1.equals(b));
      QueryUtils.check(a1);
    }

    {
      RegexpQuery a1 =
          new RegexpQuery(
              new Term(fieldName, "[aA]"),
              RegExp.NONE,
              0,
              _ -> null,
              Operations.DEFAULT_DETERMINIZE_WORK_LIMIT,
              new DocValuesRewriteMethod());
      RegexpQuery a2 =
          new RegexpQuery(
              new Term(fieldName, "[aA]"),
              RegExp.NONE,
              0,
              _ -> null,
              Operations.DEFAULT_DETERMINIZE_WORK_LIMIT,
              new DocValuesRewriteMethod());
      RegexpQuery b =
          new RegexpQuery(
              new Term(fieldName, "[bB]"),
              RegExp.NONE,
              0,
              _ -> null,
              Operations.DEFAULT_DETERMINIZE_WORK_LIMIT,
              new DocValuesRewriteMethod());
      assertEquals(a1, a2);
      assertFalse(a1.equals(b));
      QueryUtils.check(a1);
    }
  }
}
