import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.UnicodeSet
import com.ibm.icu.text.UnicodeSetIterator
import com.ibm.icu.util.VersionInfo
import java.nio.file.Paths
import java.util.Locale
import java.util.ArrayList

def icuVersion = VersionInfo.ICU_VERSION.toString()
def unicodeVersion = UCharacter.getUnicodeVersion().toString()

def outputFile = Paths.get(args[0])

def generateSwitch() {
  StringBuilder sb = new StringBuilder()
  sb.append("switch(c) {\n")
  for (int c = UCharacter.MIN_CODE_POINT; c <= UCharacter.MAX_CODE_POINT; c++) {
    UnicodeSet set = new UnicodeSet(c, c).closeOver(UnicodeSet.SIMPLE_CASE_INSENSITIVE);
    ArrayList scf = new ArrayList()
    for (UnicodeSetIterator it = new UnicodeSetIterator(set); it.next(); ) {
      if (it.codepoint != c && it.codepoint != UCharacter.toUpperCase(c) && it.codepoint != UCharacter.toLowerCase(c)) {
        scf.add(it.codepoint)
      }
    }
    if (!scf.isEmpty()) {
      sb.append(String.format(Locale.ROOT, "      case 0x%04X: // %s\n", c, UCharacter.getName(c)))
      for (int alt : scf) {
        sb.append(String.format(Locale.ROOT, "        fn.accept(0x%04X); // %s\n", alt, UCharacter.getName(alt)))
      }
      sb.append("        break;\n")
    }
  }
  sb.append("    }")
  return sb.toString();
}

def code = """
// DO NOT EDIT THIS FILE! Use "gradlew generateUnicodeProps tidy" to recreate.

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

package org.apache.lucene.util.automaton;

import java.util.function.IntConsumer;

/**
 * This file contains unicode properties used by {@code RegExp}.
 * The data was generated using ICU4J v${icuVersion}, unicode version: ${unicodeVersion}.
 */
final class CaseFolding {
  private CaseFolding() {}

  /**
   * Calls {@code fn} consumer with {@code c} itself and its {@code scf} mappings. 
   */
  static void expand(int c, IntConsumer fn) {
    // add codepoint
    fn.accept(c);
    // add uppercase from tables
    int upper = Character.toUpperCase(c);
    if (upper != c) {
      fn.accept(upper);
    }
    // add lowercase from tables
    int lower = Character.toLowerCase(c);
    if (lower != c) {
      fn.accept(lower);
    }
    // add special casing variants
    ${generateSwitch()}
  }
}
"""
outputFile.setText(code, "UTF-8")
