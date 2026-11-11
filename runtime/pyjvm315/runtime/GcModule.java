// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.fn;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code gc}. The JVM's collector is not generational in CPython's sense and cannot enumerate the heap, so:
 * {@code collect()} runs a full System.gc, waits for the reference machinery and runs this thread's pending weakref
 * callbacks and {@code __del__} finalizers, returning how many ran; the thresholds, counts and debug flags are kept
 * for compatibility but do not drive anything; {@code get_objects} and {@code get_referrers} raise
 * NotImplementedError; {@code garbage} stays empty because the JVM collects every cycle.
 */
final class GcModule {
    private GcModule() {}

    private static boolean enabled = true;
    private static long debug;
    private static long[] threshold = LangVersion.atLeast(3, 14) ? new long[]{2000, 10, 0} : new long[]{700, 10, 10};
    private static final long[] collections = new long[3], collected = new long[3];
    private static final ArrayList<Object> GARBAGE = new ArrayList<>(), CALLBACKS = new ArrayList<>();

    static PyModule create() {
        PyModule m = new PyModule("gc", "pyjvm315.runtime.GcModule");
        m.attrs.put("garbage", GARBAGE);
        m.attrs.put("callbacks", CALLBACKS);
        m.attrs.put("DEBUG_STATS", 1L);
        m.attrs.put("DEBUG_COLLECTABLE", 2L);
        m.attrs.put("DEBUG_UNCOLLECTABLE", 4L);
        m.attrs.put("DEBUG_SAVEALL", 32L);
        m.attrs.put("DEBUG_LEAK", 38L);
        fn(m, "collect", (a, k) -> collect(a, k));
        fn(m, "enable", (a, k) -> { enabled = true; return null; });
        fn(m, "disable", (a, k) -> { enabled = false; return null; });
        fn(m, "isenabled", (a, k) -> enabled);
        fn(m, "get_count", (a, k) -> NativeTypes.tuple(0L, 0L, 0L));
        fn(m, "get_threshold", (a, k) -> NativeTypes.tuple(threshold[0], threshold[1], threshold[2]));
        fn(m, "set_threshold", (a, k) -> setThreshold(a));
        fn(m, "get_debug", (a, k) -> debug);
        fn(m, "set_debug", (a, k) -> { debug = ((Number) a.get(0)).longValue(); return null; });
        fn(m, "get_stats", (a, k) -> stats());
        fn(m, "freeze", (a, k) -> null);
        fn(m, "unfreeze", (a, k) -> null);
        fn(m, "get_freeze_count", (a, k) -> 0L);
        fn(m, "is_tracked", (a, k) -> isTracked(one(a, "is_tracked")));
        fn(m, "is_finalized", (a, k) -> one(a, "is_finalized") instanceof GcFinalizable f && f.finalized);
        fn(m, "get_referents", (a, k) -> referents(a));
        fn(m, "get_objects", (a, k) -> { throw new PyException("NotImplementedError", "gc.get_objects() is not supported: the JVM heap cannot be enumerated"); });
        fn(m, "get_referrers", (a, k) -> { throw new PyException("NotImplementedError", "gc.get_referrers() is not supported: the JVM cannot find the referrers of an object"); });
        return m;
    }

    private static Object one(List<Object> a, String name) {
        if (a.size() != 1) throw new PyException("TypeError", name + "() takes exactly one argument (" + a.size() + " given)");
        return a.get(0);
    }

    private static Object collect(List<Object> a, Map<Object, Object> k) {
        long generation = 2;
        Object g = a.isEmpty() ? k.get("generation") : a.get(0);
        if (g != null) {
            if (!(g instanceof Number n) || g instanceof Double) throw new PyException("TypeError", "'" + PyRuntime.typeName(g) + "' object cannot be interpreted as an integer");
            generation = n.longValue();
            if (generation < 0 || generation > 2) throw new PyException("ValueError", "invalid generation");
        }
        notifyCallbacks("start", generation, 0, 0);
        long start = System.nanoTime();
        int ran = WeakrefDelivery.collect();
        collections[(int) generation]++;
        collected[(int) generation] += ran;
        notifyCallbacks("stop", generation, ran, System.nanoTime() - start);
        return (long) ran;
    }

    private static void notifyCallbacks(String phase, long generation, long found, long nanos) {
        if (CALLBACKS.isEmpty()) return;
        for (Object cb : new ArrayList<>(CALLBACKS)) {
            PyDict info = new PyDict();
            info.put("generation", generation);
            info.put("collected", found);
            info.put("uncollectable", 0L);
            if (LangVersion.atLeast(3, 14)) {
                info.put("candidates", found);
                info.put("duration", nanos / 1e9);
            }
            try {
                PyRuntime.callFunction(cb, new ArrayList<Object>(List.of(phase, info)), new LinkedHashMap<Object, Object>());
            } catch (PyException e) {
                WeakrefDelivery.unraisable("Exception ignored in garbage collection callback", cb, e);
            }
        }
    }

    private static Object setThreshold(List<Object> a) {
        if (a.isEmpty() || a.size() > 3) throw new PyException("TypeError", "set_threshold expected 1 to 3 arguments, got " + a.size());
        long[] t = threshold.clone();
        for (int i = 0; i < a.size(); i++) {
            if (!(a.get(i) instanceof Number n) || a.get(i) instanceof Double)
                throw new PyException("TypeError", "'" + PyRuntime.typeName(a.get(i)) + "' object cannot be interpreted as an integer");
            t[i] = n.longValue();
        }
        threshold = t;
        return null;
    }

    private static Object stats() {
        ArrayList<Object> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PyDict d = new PyDict();
            d.put("collections", collections[i]);
            d.put("collected", collected[i]);
            d.put("uncollectable", 0L);
            out.add(d);
        }
        return out;
    }

    private static boolean isTracked(Object o) {
        if (o == null || o instanceof Boolean || o instanceof Long || o instanceof BigInteger || o instanceof Double || o instanceof String
            || o instanceof PyBytes || o instanceof PyRange || o instanceof PyComplex) return false;
        if (o instanceof PyTuple t) { for (Object x : t.items) if (isTracked(x)) return true; return false; }
        if (o instanceof Map<?, ?> d && !(o instanceof PyInstance)) {
            for (Map.Entry<?, ?> e : d.entrySet()) if (isTracked(e.getKey()) || isTracked(e.getValue())) return true;
            return false;
        }
        return true;
    }

    private static Object referents(List<Object> a) {
        ArrayList<Object> out = new ArrayList<>();
        for (Object o : a) {
            if (o instanceof PyTuple t) out.addAll(t.items);
            else if (o instanceof List<?> l) out.addAll(l);
            else if (o instanceof Map<?, ?> d) { out.addAll(d.keySet()); out.addAll(d.values()); }
            else if (o instanceof Set<?> s) out.addAll(s);
            else if (o instanceof PyInstance i) {
                out.add(i.cls);
                out.addAll(i.fields.values());
                out.addAll(i.slotValues.values());
                if (i.payload != null) out.add(i.payload);
            } else if (o instanceof PyClass c) {
                out.addAll(c.bases);
                out.addAll(c.attrs.values());
            }
        }
        return out;
    }
}
