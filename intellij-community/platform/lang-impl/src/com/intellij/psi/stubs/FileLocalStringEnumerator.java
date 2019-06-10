// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.stubs;

import com.intellij.util.io.AbstractStringEnumerator;
import com.intellij.util.io.DataInputOutputUtil;
import com.intellij.util.io.IOUtil;
import gnu.trove.TObjectIntHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;

class FileLocalStringEnumerator implements AbstractStringEnumerator {
  private final TObjectIntHashMap<String> myEnumerates;
  final ArrayList<String> myStrings = new ArrayList<>();

  FileLocalStringEnumerator(boolean forSavingStub) {
    myEnumerates = forSavingStub ? new TObjectIntHashMap<>() : null;
  }

  @Override
  public int enumerate(@Nullable String value) {
    if (value == null) return 0;
    assert myEnumerates != null; // enumerate possible only when writing stub
    int i = myEnumerates.get(value);
    if (i == 0) {
      myEnumerates.put(value, i = myStrings.size() + 1);
      myStrings.add(value);
    }
    return i;
  }

  @Override
  public String valueOf(int idx) {
    if (idx == 0) return null;
    return myStrings.get(idx - 1);
  }

  void write(@NotNull DataOutputStream stream) throws IOException {
    assert myEnumerates != null;
    DataInputOutputUtil.writeINT(stream, myStrings.size());
    byte[] buffer = IOUtil.allocReadWriteUTFBuffer();
    for(String s: myStrings) {
      IOUtil.writeUTFFast(buffer, stream, s);
    }
  }

  @Override
  public void markCorrupted() {
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public boolean isDirty() {
    return false;
  }

  @Override
  public void force() {
  }
}
