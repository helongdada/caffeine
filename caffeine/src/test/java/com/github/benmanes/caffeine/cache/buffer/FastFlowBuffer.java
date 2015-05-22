/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.buffer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.github.benmanes.caffeine.base.UnsafeAccess;
import com.github.benmanes.caffeine.cache.ReadBuffer;

/**
 * A ring buffer implemented using the FastFlow technique to reduce contention caused by the
 * drain invalidating the read counter.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class FastFlowBuffer<E> extends FastFlowHeader.ReadAndWriteCounterRef<E> {
  final AtomicReference<E>[] buffer;

  @SuppressWarnings({"unchecked", "cast", "rawtypes"})
  public FastFlowBuffer() {
    buffer = new AtomicReference[BUFFER_SIZE];
    for (int i = 0; i < BUFFER_SIZE; i++) {
      buffer[i] = new AtomicReference<>();
    }
  }

  @Override
  public int offer(E e) {
    long head = readCache;
    long tail = relaxedWriteCounter();

    long wrap = (tail - BUFFER_SIZE);
    if (head <= wrap) {
      head = readCounter;
      if (head <= wrap) {
        return FULL;
      }
      lazySetReadCache(head);
    }

    if (casWriteCounter(tail, tail + 1)) {
      int index = (int) (tail & BUFFER_MASK);
      buffer[index].lazySet(e);
      return SUCCESS;
    }
    return FAILED;
  }

  @Override
  public void drainTo(Consumer<E> consumer) {
    long head = readCounter;
    long tail = relaxedWriteCounter();
    long size = (tail - head);
    if (size == 0) {
      return;
    }
    do {
      int index = (int) (head & BUFFER_MASK);
      AtomicReference<E> slot = buffer[index];
      E e = slot.get();
      if (e == null) {
        // not published yet
        break;
      }
      slot.lazySet(null);
      consumer.accept(e);
      head++;
    } while (head != tail);
    lazySetReadCounter(head);
  }

  @Override
  public int reads() {
    return (int) readCounter;
  }

  @Override
  public int writes() {
    return (int) writeCounter;
  }
}

/** The namespace for field padding through inheritance. */
final class FastFlowHeader {

  static abstract class PadReadCache<E> extends ReadBuffer<E> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p10, p11, p12, p13, p14, p15, p16, p17;
  }

  /** Enforces a memory layout to avoid false sharing by padding the read count. */
  static abstract class ReadCacheRef<E> extends PadReadCache<E> {
    static final long READ_CACHE_OFFSET =
        UnsafeAccess.objectFieldOffset(ReadCacheRef.class, "readCache");

    volatile long readCache;

    void lazySetReadCache(long count) {
      UnsafeAccess.UNSAFE.putOrderedLong(this, READ_CACHE_OFFSET, count);
    }
  }

  static abstract class PadReadCounter<E> extends ReadCacheRef<E> {
    long p20, p21, p22, p23, p24, p25, p26, p27;
    long p30, p31, p32, p33, p34, p35, p36, p37;
  }

  /** Enforces a memory layout to avoid false sharing by padding the read count. */
  static abstract class ReadCounterRef<E> extends PadReadCounter<E> {
    static final long READ_OFFSET =
        UnsafeAccess.objectFieldOffset(ReadCounterRef.class, "readCounter");

    volatile long readCounter;

    void lazySetReadCounter(long count) {
      UnsafeAccess.UNSAFE.putOrderedLong(this, READ_OFFSET, count);
    }
  }

  static abstract class PadWriteCounter<E> extends ReadCounterRef<E> {
    long p40, p41, p42, p43, p44, p45, p46, p47;
    long p50, p51, p52, p53, p54, p55, p56, p57;
  }

  /** Enforces a memory layout to avoid false sharing by padding the write count. */
  static abstract class ReadAndWriteCounterRef<E> extends PadWriteCounter<E> {
    static final long WRITE_OFFSET =
        UnsafeAccess.objectFieldOffset(ReadAndWriteCounterRef.class, "writeCounter");

    volatile long writeCounter;

    long relaxedWriteCounter() {
      return UnsafeAccess.UNSAFE.getLong(this, WRITE_OFFSET);
    }

    boolean casWriteCounter(long expect, long update) {
      return UnsafeAccess.UNSAFE.compareAndSwapLong(this, WRITE_OFFSET, expect, update);
    }
  }
}