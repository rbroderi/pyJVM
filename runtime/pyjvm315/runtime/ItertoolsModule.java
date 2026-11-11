// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * {@code itertools}: lazy iterators over Python iterables. Each function returns a {@link PyLazyIterator}; the
 * objects are iterators (not instances of per-function classes, so {@code isinstance(x, itertools.chain)} and
 * subclassing are not supported). Argument errors follow CPython's messages for the common cases.
 */
public final class ItertoolsModule {
    private ItertoolsModule() {}

    private static final Map<Object,Object> NO_KW = new LinkedHashMap<>();

    /** A lazily computed iterator named after its CPython type. */
    private abstract static class Lazy extends PyLazyIterator {
        private final String type;
        Lazy(String type) { this.type = type; }
        @Override String pyTypeName() { return type; }
    }

    private static Object call(Object f, Object... args) {
        return PyRuntime.callFunction(f, new ArrayList<Object>(List.of(args)), new LinkedHashMap<Object,Object>());
    }

    private static Object it(Object iterable) { return PyRuntime.iter(iterable); }

    private static long toLong(Object o, String what) {
        if (o instanceof Long l) return l;
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n && !(o instanceof Double)) return n.longValue();
        throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer");
    }

    private static void arity(String name, List<Object> a, int min, int max) {
        if (a.size() < min || a.size() > max)
            throw new PyException("TypeError", name + (min == max ? " expected " + min : " expected " + min + " to " + max) + " argument" + (max == 1 ? "" : "s") + ", got " + a.size());
    }

    private static List<Object> pool(Object iterable) {
        ArrayList<Object> out = new ArrayList<>();
        Object source = it(iterable);
        while (true) {
            try { out.add(PyLazyIterator.pull(source)); } catch (NoSuchElementException end) { return out; }
        }
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("itertools", "pyjvm315.runtime.ItertoolsModule");
        NativeTypes.fn(m, "count", (a, k) -> count(a, k));
        NativeTypes.fn(m, "cycle", (a, k) -> { arity("cycle", a, 1, 1); return cycle(a.get(0)); });
        NativeTypes.fn(m, "repeat", (a, k) -> repeat(a, k));
        NativeTypes.fn(m, "accumulate", (a, k) -> accumulate(a, k));
        NativeTypes.fn(m, "chain", (a, k) -> chain(a.iterator()));
        NativeTypes.fn(m, "compress", (a, k) -> { arity("compress", a, 2, 2); return compress(a.get(0), a.get(1)); });
        NativeTypes.fn(m, "dropwhile", (a, k) -> { arity("dropwhile", a, 2, 2); return dropwhile(a.get(0), a.get(1)); });
        NativeTypes.fn(m, "takewhile", (a, k) -> { arity("takewhile", a, 2, 2); return takewhile(a.get(0), a.get(1)); });
        NativeTypes.fn(m, "filterfalse", (a, k) -> { arity("filterfalse", a, 2, 2); return filterfalse(a.get(0), a.get(1)); });
        NativeTypes.fn(m, "starmap", (a, k) -> { arity("starmap", a, 2, 2); return starmap(a.get(0), a.get(1)); });
        NativeTypes.fn(m, "islice", (a, k) -> islice(a));
        NativeTypes.fn(m, "groupby", (a, k) -> groupby(a, k));
        NativeTypes.fn(m, "tee", (a, k) -> tee(a));
        NativeTypes.fn(m, "zip_longest", (a, k) -> zipLongest(a, k));
        NativeTypes.fn(m, "product", (a, k) -> product(a, k));
        NativeTypes.fn(m, "permutations", (a, k) -> permutations(a));
        NativeTypes.fn(m, "combinations", (a, k) -> combinations(a, false));
        NativeTypes.fn(m, "combinations_with_replacement", (a, k) -> combinations(a, true));
        NativeTypes.fn(m, "pairwise", (a, k) -> { arity("pairwise", a, 1, 1); return pairwise(a.get(0)); });
        NativeTypes.fn(m, "batched", (a, k) -> batched(a, k));
        NativeFunction chain = (NativeFunction) m.attrs.get("chain");
        chain.attrs.put("from_iterable", NativeTypes.fn("from_iterable", (a, k) -> { arity("from_iterable", a, 1, 1); return chain(PyRuntime.iterable(a.get(0)).iterator()); }));
        return m;
    }

    private static Object count(List<Object> a, Map<Object,Object> k) {
        Object start = a.size() > 0 ? a.get(0) : k.getOrDefault("start", 0L);
        Object step = a.size() > 1 ? a.get(1) : k.getOrDefault("step", 1L);
        if (!(start instanceof Number) && !(start instanceof Boolean)) throw new PyException("TypeError", "a number is required");
        Object[] cur = {start};
        return new Lazy("itertools.count") {
            @Override Object compute() { Object out = cur[0]; cur[0] = PyRuntime.add(out, step); return out; }
        };
    }

    private static Object cycle(Object iterable) {
        Object source = it(iterable);
        ArrayList<Object> saved = new ArrayList<>();
        int[] at = {0};
        boolean[] drained = {false};
        return new Lazy("itertools.cycle") {
            @Override Object compute() {
                if (!drained[0]) {
                    try { Object v = PyLazyIterator.pull(source); saved.add(v); return v; }
                    catch (NoSuchElementException end) { drained[0] = true; }
                }
                if (saved.isEmpty()) throw new NoSuchElementException();
                Object out = saved.get(at[0]);
                at[0] = (at[0] + 1) % saved.size();
                return out;
            }
        };
    }

    private static Object repeat(List<Object> a, Map<Object,Object> k) {
        if (a.isEmpty() && !k.containsKey("object")) throw new PyException("TypeError", "repeat() missing required argument 'object' (pos 1)");
        Object value = a.isEmpty() ? k.get("object") : a.get(0);
        Object times = a.size() > 1 ? a.get(1) : k.get("times");
        long[] left = {times == null ? -1 : Math.max(0, toLong(times, "times"))};
        return new Lazy("itertools.repeat") {
            @Override Object compute() {
                if (left[0] == 0) throw new NoSuchElementException();
                if (left[0] > 0) left[0]--;
                return value;
            }
        };
    }

    private static Object accumulate(List<Object> a, Map<Object,Object> k) {
        if (a.isEmpty()) throw new PyException("TypeError", "accumulate() missing required argument 'iterable' (pos 1)");
        Object source = it(a.get(0));
        Object func = a.size() > 1 ? a.get(1) : k.get("func");
        Object initial = k.get("initial");
        Object[] total = {null};
        boolean[] started = {false}, gaveInitial = {initial == null};
        return new Lazy("itertools.accumulate") {
            @Override Object compute() {
                if (!gaveInitial[0]) { gaveInitial[0] = true; started[0] = true; total[0] = initial; return initial; }
                Object v = PyLazyIterator.pull(source);
                if (!started[0]) { started[0] = true; total[0] = v; return v; }
                total[0] = func == null ? PyRuntime.add(total[0], v) : call(func, total[0], v);
                return total[0];
            }
        };
    }

    private static Object chain(Iterator<?> sources) {
        Object[] current = {null};
        return new Lazy("itertools.chain") {
            @Override Object compute() {
                while (true) {
                    if (current[0] == null) {
                        if (!sources.hasNext()) throw new NoSuchElementException();
                        current[0] = it(sources.next());
                    }
                    try { return PyLazyIterator.pull(current[0]); } catch (NoSuchElementException end) { current[0] = null; }
                }
            }
        };
    }

    private static Object compress(Object data, Object selectors) {
        Object d = it(data), s = it(selectors);
        return new Lazy("itertools.compress") {
            @Override Object compute() {
                while (true) {
                    Object v = PyLazyIterator.pull(d), sel = PyLazyIterator.pull(s);
                    if (PyRuntime.truth(sel)) return v;
                }
            }
        };
    }

    private static Object dropwhile(Object pred, Object iterable) {
        Object source = it(iterable);
        boolean[] dropping = {true};
        return new Lazy("itertools.dropwhile") {
            @Override Object compute() {
                while (true) {
                    Object v = PyLazyIterator.pull(source);
                    if (!dropping[0]) return v;
                    if (!PyRuntime.truth(call(pred, v))) { dropping[0] = false; return v; }
                }
            }
        };
    }

    private static Object takewhile(Object pred, Object iterable) {
        Object source = it(iterable);
        boolean[] stopped = {false};
        return new Lazy("itertools.takewhile") {
            @Override Object compute() {
                if (stopped[0]) throw new NoSuchElementException();
                Object v = PyLazyIterator.pull(source);
                if (!PyRuntime.truth(call(pred, v))) { stopped[0] = true; throw new NoSuchElementException(); }
                return v;
            }
        };
    }

    private static Object filterfalse(Object pred, Object iterable) {
        Object source = it(iterable);
        return new Lazy("itertools.filterfalse") {
            @Override Object compute() {
                while (true) {
                    Object v = PyLazyIterator.pull(source);
                    if (!PyRuntime.truth(pred == null ? v : call(pred, v))) return v;
                }
            }
        };
    }

    private static Object starmap(Object func, Object iterable) {
        Object source = it(iterable);
        return new Lazy("itertools.starmap") {
            @Override Object compute() {
                Object v = PyLazyIterator.pull(source);
                return PyRuntime.callFunction(func, new ArrayList<Object>(pool(v)), new LinkedHashMap<Object,Object>());
            }
        };
    }

    private static Object islice(List<Object> a) {
        if (a.size() < 2 || a.size() > 4) throw new PyException("TypeError", "islice expected at most 4 arguments, got " + a.size());
        Object source = it(a.get(0));
        long start = 0, step = 1;
        Long stop = null;
        if (a.size() == 2) { if (a.get(1) != null) stop = toLong(a.get(1), "stop"); }
        else {
            if (a.get(1) != null) start = toLong(a.get(1), "start");
            if (a.get(2) != null) stop = toLong(a.get(2), "stop");
            if (a.size() == 4 && a.get(3) != null) step = toLong(a.get(3), "step");
        }
        if (start < 0 || (stop != null && stop < 0) || step <= 0)
            throw new PyException("ValueError", step <= 0 ? "Step for islice() must be a positive integer or None." : "Indices for islice() must be None or an integer: 0 <= x <= sys.maxsize.");
        long[] index = {0};
        long first = start, stride = step;
        Long end = stop;
        long[] nextWanted = {first};
        return new Lazy("itertools.islice") {
            @Override Object compute() {
                if (end != null && nextWanted[0] >= end) {
                    // consume up to the stop index like CPython so the source is advanced consistently
                    while (index[0] < end) { PyLazyIterator.pull(source); index[0]++; }
                    throw new NoSuchElementException();
                }
                Object v = null;
                while (index[0] <= nextWanted[0]) { v = PyLazyIterator.pull(source); index[0]++; }
                nextWanted[0] += stride;
                return v;
            }
        };
    }

    private static Object groupby(List<Object> a, Map<Object,Object> k) {
        if (a.isEmpty()) throw new PyException("TypeError", "groupby() missing required argument 'iterable' (pos 1)");
        Object source = it(a.get(0));
        Object keyfunc = a.size() > 1 ? a.get(1) : k.get("key");
        Object[] pending = {null, null};   // lookahead value and its key
        boolean[] hasPending = {false}, finished = {false};
        Object[] currentKey = {null};
        boolean[] haveKey = {false};
        int[] generation = {0};
        return new Lazy("itertools.groupby") {
            @Override Object compute() {
                generation[0]++;
                int mine = generation[0];
                // skip the rest of the previous group
                if (!hasPending[0]) {
                    if (finished[0]) throw new NoSuchElementException();
                    try { fetch(); } catch (NoSuchElementException end) { finished[0] = true; throw end; }
                }
                while (haveKey[0] && PyRuntime.truth(PyRuntime.eq(pending[1], currentKey[0]))) {
                    hasPending[0] = false;
                    try { fetch(); } catch (NoSuchElementException end) { finished[0] = true; throw end; }
                }
                currentKey[0] = pending[1];
                haveKey[0] = true;
                Object groupKey = currentKey[0];
                Iterator<Object> group = new Lazy("itertools._grouper") {
                    @Override Object compute() {
                        if (generation[0] != mine) throw new NoSuchElementException();
                        if (!hasPending[0]) {
                            if (finished[0]) throw new NoSuchElementException();
                            try { fetch(); } catch (NoSuchElementException end) { finished[0] = true; throw end; }
                        }
                        if (!PyRuntime.truth(PyRuntime.eq(pending[1], groupKey))) throw new NoSuchElementException();
                        hasPending[0] = false;
                        return pending[0];
                    }
                };
                return NativeTypes.tuple(groupKey, group);
            }
            private void fetch() {
                Object v = PyLazyIterator.pull(source);
                pending[0] = v;
                pending[1] = keyfunc == null ? v : call(keyfunc, v);
                hasPending[0] = true;
            }
        };
    }

    private static Object tee(List<Object> a) {
        arity("tee", a, 1, 2);
        long n = a.size() == 2 ? toLong(a.get(1), "n") : 2;
        if (n < 0) throw new PyException("ValueError", "n must be >= 0");
        Object source = it(a.get(0));
        ArrayList<Object> buffer = new ArrayList<>();
        PyTuple out = new PyTuple();
        for (long i = 0; i < n; i++) {
            int[] at = {0};
            out.items.add(new Lazy("itertools._tee") {
                @Override Object compute() {
                    if (at[0] == buffer.size()) buffer.add(PyLazyIterator.pull(source));
                    return buffer.get(at[0]++);
                }
            });
        }
        return out;
    }

    private static Object zipLongest(List<Object> a, Map<Object,Object> k) {
        Object fill = k.get("fillvalue");
        for (Object key : k.keySet()) if (!"fillvalue".equals(key)) throw new PyException("TypeError", "zip_longest() got an unexpected keyword argument '" + key + "'");
        ArrayList<Object> sources = new ArrayList<>();
        for (Object o : a) sources.add(it(o));
        boolean[] done = new boolean[sources.size()];
        int[] active = {sources.size()};
        return new Lazy("itertools.zip_longest") {
            @Override Object compute() {
                if (active[0] == 0) throw new NoSuchElementException();
                PyTuple row = new PyTuple();
                for (int i = 0; i < sources.size(); i++) {
                    Object v = fill;
                    if (!done[i]) {
                        try { v = PyLazyIterator.pull(sources.get(i)); }
                        catch (NoSuchElementException end) { done[i] = true; active[0]--; }
                    }
                    row.items.add(v);
                }
                if (active[0] == 0) throw new NoSuchElementException();
                return row;
            }
        };
    }

    private static Object product(List<Object> a, Map<Object,Object> k) {
        long repeat = 1;
        for (Map.Entry<Object,Object> e : k.entrySet()) {
            if (!"repeat".equals(e.getKey())) throw new PyException("TypeError", "product() got an unexpected keyword argument '" + e.getKey() + "'");
            repeat = toLong(e.getValue(), "repeat");
        }
        if (repeat < 0) throw new PyException("ValueError", "repeat argument cannot be negative");
        ArrayList<List<Object>> pools = new ArrayList<>();
        for (Object o : a) pools.add(pool(o));
        int base = pools.size();
        for (long r = 1; r < repeat; r++) for (int i = 0; i < base; i++) pools.add(pools.get(i));
        if (repeat == 0) pools.clear();
        int n = pools.size();
        int[] idx = new int[n];
        boolean[] done = {false};
        for (List<Object> p : pools) if (p.isEmpty()) done[0] = true;
        boolean[] first = {true};
        return new Lazy("itertools.product") {
            @Override Object compute() {
                if (done[0]) throw new NoSuchElementException();
                if (!first[0]) {
                    int i = n - 1;
                    while (i >= 0 && ++idx[i] == pools.get(i).size()) { idx[i] = 0; i--; }
                    if (i < 0) { done[0] = true; throw new NoSuchElementException(); }
                }
                first[0] = false;
                PyTuple row = new PyTuple();
                for (int i = 0; i < n; i++) row.items.add(pools.get(i).get(idx[i]));
                return row;
            }
        };
    }

    private static Object permutations(List<Object> a) {
        arity("permutations", a, 1, 2);
        List<Object> items = pool(a.get(0));
        int n = items.size();
        long rArg = a.size() == 2 && a.get(1) != null ? toLong(a.get(1), "r") : n;
        if (rArg < 0) throw new PyException("ValueError", "r must be non-negative");
        int r = (int) rArg;
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        int[] cycles = new int[r];
        for (int i = 0; i < r; i++) cycles[i] = n - i;
        boolean[] first = {true}, done = {r > n};
        return new Lazy("itertools.permutations") {
            @Override Object compute() {
                if (done[0]) throw new NoSuchElementException();
                if (!first[0]) {
                    boolean advanced = false;
                    for (int i = r - 1; i >= 0; i--) {
                        if (--cycles[i] == 0) {
                            int saved = indices[i];
                            System.arraycopy(indices, i + 1, indices, i, n - i - 1);
                            indices[n - 1] = saved;
                            cycles[i] = n - i;
                        } else {
                            int j = n - cycles[i];
                            int tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp;
                            advanced = true;
                            break;
                        }
                    }
                    if (!advanced) { done[0] = true; throw new NoSuchElementException(); }
                }
                first[0] = false;
                PyTuple row = new PyTuple();
                for (int i = 0; i < r; i++) row.items.add(items.get(indices[i]));
                return row;
            }
        };
    }

    private static Object combinations(List<Object> a, boolean replacement) {
        String name = replacement ? "combinations_with_replacement" : "combinations";
        if (a.size() != 2) throw new PyException("TypeError", name + " expected 2 arguments, got " + a.size());
        List<Object> items = pool(a.get(0));
        int n = items.size();
        long rArg = toLong(a.get(1), "r");
        if (rArg < 0) throw new PyException("ValueError", "r must be non-negative");
        int r = (int) rArg;
        int[] idx = new int[r];
        boolean[] first = {true}, done = {!replacement && r > n || replacement && n == 0 && r > 0};
        if (!replacement) for (int i = 0; i < r; i++) idx[i] = i;
        return new Lazy("itertools." + name) {
            @Override Object compute() {
                if (done[0]) throw new NoSuchElementException();
                if (!first[0]) {
                    int i = r - 1;
                    if (replacement) { while (i >= 0 && idx[i] == n - 1) i--; }
                    else { while (i >= 0 && idx[i] == i + n - r) i--; }
                    if (i < 0) { done[0] = true; throw new NoSuchElementException(); }
                    idx[i]++;
                    for (int j = i + 1; j < r; j++) idx[j] = replacement ? idx[i] : idx[j - 1] + 1;
                }
                first[0] = false;
                PyTuple row = new PyTuple();
                for (int i = 0; i < r; i++) row.items.add(items.get(idx[i]));
                return row;
            }
        };
    }

    private static Object pairwise(Object iterable) {
        Object source = it(iterable);
        Object[] prev = {null};
        boolean[] started = {false};
        return new Lazy("itertools.pairwise") {
            @Override Object compute() {
                if (!started[0]) { started[0] = true; prev[0] = PyLazyIterator.pull(source); }
                Object next = PyLazyIterator.pull(source);
                Object out = NativeTypes.tuple(prev[0], next);
                prev[0] = next;
                return out;
            }
        };
    }

    private static Object batched(List<Object> a, Map<Object,Object> k) {
        if (a.isEmpty()) throw new PyException("TypeError", "batched() missing required argument 'iterable' (pos 1)");
        Object nObj = a.size() > 1 ? a.get(1) : k.get("n");
        long n = toLong(nObj, "n");
        if (n < 1) throw new PyException("ValueError", "n must be at least one");
        boolean strict = PyRuntime.truth(k.getOrDefault("strict", false));
        Object source = it(a.get(0));
        return new Lazy("itertools.batched") {
            @Override Object compute() {
                PyTuple batch = new PyTuple();
                while (batch.items.size() < n) {
                    try { batch.items.add(PyLazyIterator.pull(source)); } catch (NoSuchElementException end) { break; }
                }
                if (batch.items.isEmpty()) throw new NoSuchElementException();
                if (strict && batch.items.size() < n) throw new PyException("ValueError", "batched(): incomplete batch");
                return batch;
            }
        };
    }
}
