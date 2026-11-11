// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Python dict: CPython's compact layout (an open-addressing index table over insertion-ordered
 * entry arrays) with keys hashed by Python {@code hash()} and compared by Python {@code ==}
 * (identity first), so {@code 1}, {@code 1.0} and {@code True} are one key and user
 * {@code __hash__}/{@code __eq__} take part. Implements {@link Map} so existing runtime code
 * keeps working. Iterators raise Python's "changed size/keys during iteration" errors.
 */
final class PyDict extends AbstractMap<Object,Object> {
    private static final Object DELETED = new Object();
    private static final int EMPTY = 0, TOMBSTONE = -1;

    private int[] table;          // 0 empty, -1 tombstone, else entry index + 1
    private Object[] keys;
    private Object[] vals;
    private int[] hashes;
    private int used;             // entries appended (including deleted)
    private int live;             // current size
    private int version;          // bumped on every key insertion/removal (not on value updates)
    private boolean frozen;       // frozendict: immutable once built

    /** Make this dict immutable (turns it into a frozendict). Returns this. */
    PyDict freeze() { frozen = true; return this; }
    boolean isFrozen() { return frozen; }
    private void mutable(String what) {
        if (frozen) throw new PyException("TypeError", "'frozendict' object does not support item " + what);
    }

    PyDict() { this(8); }
    PyDict(int capacity) { alloc(Math.max(8, capacity)); }
    PyDict(Map<?,?> other) {
        this(other.size());
        for (Map.Entry<?,?> e : other.entrySet()) put(e.getKey(), e.getValue());
    }

    private void alloc(int entryCapacity) {
        int tableSize = Integer.highestOneBit(Math.max(8, entryCapacity * 3 / 2 + 1) - 1) << 1;
        table = new int[tableSize];
        keys = new Object[entryCapacity];
        vals = new Object[entryCapacity];
        hashes = new int[entryCapacity];
        used = 0;
    }

    private static int hashOf(Object key) {
        long h = ((Number) PyRuntime.hash(key)).longValue();
        return (int) (h ^ (h >>> 32));
    }

    private static boolean sameKey(Object stored, Object probe) {
        if (stored == probe) return true;
        return PyRuntime.truth(PyRuntime.eq(stored, probe));
    }

    /** Entry index of {@code key}, or -1. Restarts if a user __eq__ mutated the dict mid-probe. */
    private int find(Object key, int hash) {
        restart:
        while (true) {
            int mask = table.length - 1, i = hash & mask;
            long perturb = hash & 0xffffffffL;
            while (true) {
                int slot = table[i];
                if (slot == EMPTY) return -1;
                if (slot > 0) {
                    int idx = slot - 1;
                    Object stored = keys[idx];
                    if (stored == key) return idx;
                    if (hashes[idx] == hash) {
                        int startVersion = version;
                        Object[] startKeys = keys;
                        boolean same = sameKey(stored, key);
                        if (version != startVersion || startKeys != keys) continue restart;
                        if (same) return idx;
                    }
                }
                perturb >>>= 5;
                i = (int) ((5L * i + 1 + perturb) & mask);
            }
        }
    }

    private void insertNew(Object key, int hash, Object value) {
        if (used == keys.length) resize();
        int mask = table.length - 1, i = hash & mask;
        long perturb = hash & 0xffffffffL;
        while (table[i] > 0) { perturb >>>= 5; i = (int) ((5L * i + 1 + perturb) & mask); }
        // a tombstone slot can be reused, an EMPTY slot ends the probe: either way the key is absent
        table[i] = used + 1;
        keys[used] = key; vals[used] = value; hashes[used] = hash;
        used++; live++; version++;
    }

    private void resize() {
        Object[] oldKeys = keys, oldVals = vals; int[] oldHashes = hashes; int oldUsed = used;
        alloc(Math.max(8, live * 3));
        int mask = table.length - 1;
        for (int n = 0; n < oldUsed; n++) {
            if (oldKeys[n] == DELETED) continue;
            int h = oldHashes[n], i = h & mask;
            long perturb = h & 0xffffffffL;
            while (table[i] != EMPTY) { perturb >>>= 5; i = (int) ((5L * i + 1 + perturb) & mask); }
            table[i] = used + 1;
            keys[used] = oldKeys[n]; vals[used] = oldVals[n]; hashes[used] = h;
            used++;
        }
    }

    @Override public int size() { return live; }
    @Override public boolean isEmpty() { return live == 0; }

    // Free-threaded model: once a second thread exists every operation runs under this dict's monitor, which keeps
    // each operation atomic (CPython 3.13t semantics). The hash is computed outside the lock where possible.
    @Override public boolean containsKey(Object key) {
        int h = hashOf(key);
        if (!SyncSupport.MT) return find(key, h) >= 0;
        synchronized (this) { return find(key, h) >= 0; }
    }

    @Override public Object get(Object key) {
        int h = hashOf(key);
        if (!SyncSupport.MT) { int idx = find(key, h); return idx < 0 ? null : vals[idx]; }
        synchronized (this) { int idx = find(key, h); return idx < 0 ? null : vals[idx]; }
    }

    @Override public Object getOrDefault(Object key, Object fallback) {
        int h = hashOf(key);
        if (!SyncSupport.MT) { int idx = find(key, h); return idx < 0 ? fallback : vals[idx]; }
        synchronized (this) { int idx = find(key, h); return idx < 0 ? fallback : vals[idx]; }
    }

    @Override public Object put(Object key, Object value) {
        mutable("assignment");
        int hash = hashOf(key);
        if (!SyncSupport.MT) return put0(key, hash, value);
        synchronized (this) { return put0(key, hash, value); }
    }

    private Object put0(Object key, int hash, Object value) {
        int idx = find(key, hash);
        if (idx >= 0) { Object old = vals[idx]; vals[idx] = value; return old; }
        insertNew(key, hash, value);
        return null;
    }

    @Override public Object remove(Object key) {
        mutable("deletion");
        int hash = hashOf(key);
        if (!SyncSupport.MT) return remove0(key, hash);
        synchronized (this) { return remove0(key, hash); }
    }

    private Object remove0(Object key, int hash) {
        int idx = find(key, hash);
        if (idx < 0) return null;
        // locate the table slot that points at idx
        int mask = table.length - 1, i = hash & mask;
        long perturb = hash & 0xffffffffL;
        while (table[i] != idx + 1) { perturb >>>= 5; i = (int) ((5L * i + 1 + perturb) & mask); }
        table[i] = TOMBSTONE;
        Object old = vals[idx];
        keys[idx] = DELETED; vals[idx] = null;
        live--; version++;
        return old;
    }

    @Override public void clear() {
        mutable("deletion");
        if (!SyncSupport.MT) { clear0(); return; }
        synchronized (this) { clear0(); }
    }

    private void clear0() {
        Arrays.fill(table, EMPTY);
        Arrays.fill(keys, 0, used, null); Arrays.fill(vals, 0, used, null);
        if (live != 0) version++;
        used = 0; live = 0;
    }

    /** The most recently inserted live entry removed and returned (dict.popitem), or null if empty. */
    Map.Entry<Object,Object> popLast() {
        if (!SyncSupport.MT) return popLast0();
        synchronized (this) { return popLast0(); }
    }

    private Map.Entry<Object,Object> popLast0() {
        for (int n = used - 1; n >= 0; n--) {
            if (keys[n] == DELETED) continue;
            Object k = keys[n], v = vals[n];
            remove0(k, hashes[n]);
            return new SimpleEntry<>(k, v);
        }
        return null;
    }

    @Override public Set<Map.Entry<Object,Object>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return live; }
            @Override public boolean contains(Object o) {
                return o instanceof Map.Entry<?,?> e && containsKey(e.getKey()) && PyRuntime.truth(PyRuntime.eq(get(e.getKey()), e.getValue()));
            }
            @Override public Iterator<Map.Entry<Object,Object>> iterator() {
                return new Iterator<>() {
                    final int startSize = live, startVersion = version;
                    int next = 0, last = -1;
                    private void check() {
                        if (live != startSize) throw new PyException("RuntimeError", "dictionary changed size during iteration");
                        if (version != startVersion) throw new PyException("RuntimeError", "dictionary keys changed during iteration");
                    }
                    @Override public boolean hasNext() {
                        if (SyncSupport.MT) synchronized (PyDict.this) { return hasNext0(); }
                        return hasNext0();
                    }
                    private boolean hasNext0() {
                        while (next < used && keys[next] == DELETED) next++;
                        return next < used;
                    }
                    @Override public Map.Entry<Object,Object> next() {
                        if (SyncSupport.MT) synchronized (PyDict.this) { return next0(); }
                        return next0();
                    }
                    private Map.Entry<Object,Object> next0() {
                        check();
                        if (!hasNext0()) throw new NoSuchElementException();
                        last = next++;
                        return new SimpleEntry<>(keys[last], vals[last]) {
                            @Override public Object setValue(Object value) { PyDict.this.put(getKey(), value); return super.setValue(value); }
                        };
                    }
                    @Override public void remove() {
                        if (last < 0 || keys[last] == DELETED) throw new IllegalStateException();
                        PyDict.this.remove(keys[last]);
                    }
                };
            }
        };
    }

    /** Dict equality uses Python == on values and key lookup by Python hash/eq. */
    @Override public boolean equals(Object other) {
        if (other == this) return true;
        if (SyncSupport.MT) synchronized (this) { return equals0(other); }
        return equals0(other);
    }

    private boolean equals0(Object other) {
        if (!(other instanceof Map<?,?> m) || m.size() != live) return false;
        for (int n = 0; n < used; n++) {
            if (keys[n] == DELETED) continue;
            if (!m.containsKey(keys[n])) return false;
            if (!PyRuntime.truth(PyRuntime.eq(vals[n], m.get(keys[n])))) return false;
        }
        return true;
    }

    @Override public int hashCode() {
        if (SyncSupport.MT) synchronized (this) { return hashCode0(); }
        return hashCode0();
    }

    private int hashCode0() {
        if (!frozen) throw new PyException("TypeError", "unhashable type: 'dict'");
        long h = 0x345678L;
        for (int n = 0; n < used; n++) {
            if (keys[n] == DELETED) continue;
            long pair = ((Number) PyRuntime.hash(keys[n])).longValue() * 1000003L ^ ((Number) PyRuntime.hash(vals[n])).longValue();
            h += pair * 0x9E3779B97F4A7C15L ^ (pair >>> 29);   // commutative: order must not matter
        }
        return (int) (h ^ (h >>> 32));
    }
}
