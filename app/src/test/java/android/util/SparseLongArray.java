package android.util;

import java.util.HashMap;

/**
 * Stupid (but working) implementation of Android-specific SparseLongArray to be used in unit tests running using JUnit in
 * classic JVM, where Android-specific code is not available
 */
public class SparseLongArray {
    private final SparseArray<Long> impl;

    public SparseLongArray() {
        impl = new SparseArray<>();
    }

    public SparseLongArray(int initialCapacity) {
        impl = new SparseArray<>(initialCapacity);
    }

    public void put(int key, long value) {
        impl.put(key, value);
    }

    public long get(int key) {
        return impl.get(key, 0L);
    }

    public long get(int key, long valueIfNotFound) {
        return impl.get(key, valueIfNotFound);
    }

    public void append(int key, long value) {
        impl.append(key, value);
    }

    public void clear() {
        impl.clear();
    }

    public int size() {
        return impl.size();
    }

    public int keyAt(int index) {
        return impl.keyAt(index);
    }

    public long valueAt(int index) {
        return impl.valueAt(index);
    }

    public void delete(int key) {
        impl.delete(key);
    }

    public void remove(int key) {
        impl.remove(key);
    }

    public void removeAt(int index) {
        impl.removeAt(index);
    }

    public void removeAtRange(int index, int size) {
        impl.removeAtRange(index, size);
    }

    public void setValueAt(int index, long value) {
        impl.setValueAt(index, value);
    }

    public int indexOfKey(int key) {
        return impl.indexOfKey(key);
    }

    public int indexOfValue(long value) {
        return impl.indexOfValue(value);
    }
}
