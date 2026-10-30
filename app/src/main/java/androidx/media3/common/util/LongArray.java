package androidx.media3.common.util;

import static java.lang.Math.max;

import java.util.Arrays;

public final class LongArray {

  private static final int DEFAULT_INITIAL_CAPACITY = 32;

   int size;
   long[] values;

  public LongArray() {
    this(DEFAULT_INITIAL_CAPACITY);
  }

  public LongArray(int initialCapacity) {
    values = new long[initialCapacity];
  }

  public void add(long value) {
    if (size == values.length) {
      values = Arrays.copyOf(values, size * 2);
    }
    values[size++] = value;
  }

  public void addAll(long[] values) {
    int newSize = size + values.length;
    if (newSize > this.values.length) {
      this.values = Arrays.copyOf(this.values, max(this.values.length * 2, newSize));
    }
    System.arraycopy(values, 0, this.values, size, values.length);
    size = newSize;
  }

  public long get(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("Invalid index " + index + ", size is " + size);
    }
    return values[index];
  }

  public int size() {
    return size;
  }

  public long[] toArray() {
    return Arrays.copyOf(values, size);
  }
}
