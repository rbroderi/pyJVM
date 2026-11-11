// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * An insertion-ordered map whose operations take the map's own monitor once a second thread exists
 * ({@link SyncSupport#MT}). Used for instance dicts, class namespaces and module namespaces, which Python code
 * may mutate from several threads (CPython 3.13t: every operation is atomic). Single-threaded programs pay one
 * volatile read per operation. While several threads run, iteration views are snapshots, so iterating while
 * another thread mutates never raises ConcurrentModificationException (it may skip or repeat, as in CPython).
 */
final class SyncMap<K,V> extends LinkedHashMap<K,V> {
    SyncMap() { super(); }
    SyncMap(Map<? extends K, ? extends V> m) { super(m); }

    @Override public V get(Object k) { if (!SyncSupport.MT) return super.get(k); synchronized (this) { return super.get(k); } }
    @Override public V getOrDefault(Object k, V d) { if (!SyncSupport.MT) return super.getOrDefault(k, d); synchronized (this) { return super.getOrDefault(k, d); } }
    @Override public boolean containsKey(Object k) { if (!SyncSupport.MT) return super.containsKey(k); synchronized (this) { return super.containsKey(k); } }
    @Override public V put(K k, V v) { if (!SyncSupport.MT) return super.put(k, v); synchronized (this) { return super.put(k, v); } }
    @Override public V putIfAbsent(K k, V v) { if (!SyncSupport.MT) return super.putIfAbsent(k, v); synchronized (this) { return super.putIfAbsent(k, v); } }
    @Override public V remove(Object k) { if (!SyncSupport.MT) return super.remove(k); synchronized (this) { return super.remove(k); } }
    @Override public void putAll(Map<? extends K, ? extends V> m) { if (!SyncSupport.MT) { super.putAll(m); return; } synchronized (this) { super.putAll(m); } }
    @Override public void clear() { if (!SyncSupport.MT) { super.clear(); return; } synchronized (this) { super.clear(); } }
    @Override public int size() { if (!SyncSupport.MT) return super.size(); synchronized (this) { return super.size(); } }
    @Override public boolean isEmpty() { return size() == 0; }
    @Override public V computeIfAbsent(K k, Function<? super K, ? extends V> f) { if (!SyncSupport.MT) return super.computeIfAbsent(k, f); synchronized (this) { return super.computeIfAbsent(k, f); } }
    @Override public void forEach(BiConsumer<? super K, ? super V> a) {
        if (!SyncSupport.MT) { super.forEach(a); return; }
        for (Map.Entry<K,V> e : new ArrayList<>(entrySet())) a.accept(e.getKey(), e.getValue());
    }
    @Override public Set<K> keySet() { if (!SyncSupport.MT) return super.keySet(); synchronized (this) { return new LinkedHashSet<>(super.keySet()); } }
    @Override public Collection<V> values() { if (!SyncSupport.MT) return super.values(); synchronized (this) { return new ArrayList<>(super.values()); } }
    @Override public Set<Map.Entry<K,V>> entrySet() {
        if (!SyncSupport.MT) return super.entrySet();
        synchronized (this) {
            LinkedHashSet<Map.Entry<K,V>> out = new LinkedHashSet<>();
            for (Map.Entry<K,V> e : super.entrySet()) out.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()));
            return out;
        }
    }
}
