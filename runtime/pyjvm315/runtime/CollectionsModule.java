// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * {@code _collections}: {@code deque} (array-list backed; indexed access is O(1), operations at the left end are
 * O(n)) and {@code defaultdict} (a dict subclass whose {@code __missing__} calls the factory). The pure-Python
 * fallbacks in collections/__init__.py supply OrderedDict, _count_elements and _tuplegetter.
 */
public final class CollectionsModule {
    private CollectionsModule() {}

    private static PyClass dequeClass, defaultdictClass;

    /** Deque state: the items and the maximum length (-1 for unbounded). */
    static final class Deque {
        final ArrayList<Object> items = new ArrayList<>();
        long maxlen = -1;
        long mutations;
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_collections", "pyjvm315.runtime.CollectionsModule");
        Class<?> c = CollectionsModule.class;
        dequeClass = define("collections", "deque", null);
        method(dequeClass, c, "__init__", "dqInit", "self,iterable,maxlen", defaults("iterable", null, "maxlen", null));
        method(dequeClass, c, "append", "dqAppend", "self,x");
        method(dequeClass, c, "appendleft", "dqAppendLeft", "self,x");
        method(dequeClass, c, "pop", "dqPop", "self");
        method(dequeClass, c, "popleft", "dqPopLeft", "self");
        method(dequeClass, c, "extend", "dqExtend", "self,iterable");
        method(dequeClass, c, "extendleft", "dqExtendLeft", "self,iterable");
        method(dequeClass, c, "clear", "dqClear", "self");
        method(dequeClass, c, "rotate", "dqRotate", "self,n", defaults("n", 1L));
        method(dequeClass, c, "count", "dqCount", "self,x");
        method(dequeClass, c, "index", "dqIndex", "self,x,start,stop", defaults("start", 0L, "stop", Long.MAX_VALUE));
        method(dequeClass, c, "insert", "dqInsert", "self,i,x");
        method(dequeClass, c, "remove", "dqRemove", "self,x");
        method(dequeClass, c, "reverse", "dqReverse", "self");
        method(dequeClass, c, "copy", "dqCopy", "self");
        method(dequeClass, c, "__copy__", "dqCopy", "self");
        method(dequeClass, c, "__len__", "dqLen", "self");
        method(dequeClass, c, "__iter__", "dqIter", "self");
        method(dequeClass, c, "__reversed__", "dqReversed", "self");
        method(dequeClass, c, "__contains__", "dqContains", "self,x");
        method(dequeClass, c, "__getitem__", "dqGet", "self,i");
        method(dequeClass, c, "__setitem__", "dqSet", "self,i,x");
        method(dequeClass, c, "__delitem__", "dqDel", "self,i");
        method(dequeClass, c, "__repr__", "dqRepr", "self");
        method(dequeClass, c, "__add__", "dqAdd", "self,other");
        method(dequeClass, c, "__iadd__", "dqIAdd", "self,other");
        method(dequeClass, c, "__mul__", "dqMul", "self,n");
        method(dequeClass, c, "__rmul__", "dqMul", "self,n");
        method(dequeClass, c, "__imul__", "dqIMul", "self,n");
        method(dequeClass, c, "__eq__", "dqEq", "self,other");
        method(dequeClass, c, "__ne__", "dqNe", "self,other");
        method(dequeClass, c, "__lt__", "dqLt", "self,other");
        method(dequeClass, c, "__le__", "dqLe", "self,other");
        method(dequeClass, c, "__gt__", "dqGt", "self,other");
        method(dequeClass, c, "__ge__", "dqGe", "self,other");
        method(dequeClass, c, "__reduce__", "dqReduce", "self");
        PyRuntime.classAddProperty(dequeClass, "maxlen", c.getName(), "dqMaxlen", null);
        finish(dequeClass);
        m.attrs.put("deque", dequeClass);
        for (String name : new String[]{"_deque_iterator", "_deque_reverse_iterator"}) {
            PyClass iterClass = define("_collections", name, null);
            finish(iterClass);
            m.attrs.put(name, iterClass);
        }

        return finishDefaultdict(m, c);
    }

    private static PyModule finishDefaultdict(PyModule m, Class<?> c) {
        PyClass cls = (PyClass) PyRuntime.classCreate("defaultdict", "collections");
        PyRuntime.classAddBase(cls, PyRuntime.builtinType("dict"));
        methodVarKw(cls, c, "__init__", "ddInit", "self,default_factory", "args", "kwargs", defaults("default_factory", null));
        method(cls, c, "__missing__", "ddMissing", "self,key");
        method(cls, c, "__repr__", "ddRepr", "self");
        method(cls, c, "copy", "ddCopy", "self");
        method(cls, c, "__copy__", "ddCopy", "self");
        method(cls, c, "__reduce__", "ddReduce", "self");
        PyRuntime.classAddProperty(cls, "default_factory", c.getName(), "ddFactory", "ddSetFactory");
        finish(cls);
        defaultdictClass = cls;
        m.attrs.put("defaultdict", cls);
        return m;
    }

    // ---- deque -----------------------------------------------------------------------------------

    private static Deque dq(Object self) {
        if (!(self instanceof PyInstance i)) throw new PyException("TypeError", "descriptor requires a 'collections.deque' object but received '" + PyRuntime.typeName(self) + "'");
        Object s = i.slotValues.get(STATE);
        if (s instanceof Deque d) return d;
        synchronized (i) {
            if (i.slotValues.get(STATE) instanceof Deque made) return made;
            Deque d = new Deque();
            i.slotValues.put(STATE, d);
            return d;
        }
    }

    private static Object it(Object iterable) { return PyRuntime.iter(iterable); }

    private static List<Object> drain(Object iterable) {
        ArrayList<Object> out = new ArrayList<>();
        Object source = it(iterable);
        while (true) {
            try { out.add(PyLazyIterator.pull(source)); } catch (NoSuchElementException end) { return out; }
        }
    }

    private static long index(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof java.math.BigInteger) throw new PyException("OverflowError", "Python int too large to convert to C ssize_t");
        throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer");
    }

    public static Object dqInit(Object self, Object iterable, Object maxlen) { if (!SyncSupport.MT) return dqInit0(self, iterable, maxlen); synchronized (dq(self)) { return dqInit0(self, iterable, maxlen); } }
    private static Object dqInit0(Object self, Object iterable, Object maxlen) {
        Deque d = dq(self);
        if (maxlen != null) {
            long n = index(maxlen);
            if (n < 0) throw new PyException("ValueError", "maxlen must be non-negative");
            d.maxlen = n;
        } else d.maxlen = -1;
        d.items.clear();
        d.mutations++;
        if (iterable != null) dqExtend(self, iterable);
        return null;
    }

    private static void push(Deque d, Object x, boolean left) {
        if (d.maxlen == 0) return;
        if (d.maxlen > 0 && d.items.size() >= d.maxlen) d.items.remove(left ? d.items.size() - 1 : 0);
        if (left) d.items.add(0, x); else d.items.add(x);
        d.mutations++;
    }

    public static Object dqAppend(Object self, Object x) { if (!SyncSupport.MT) return dqAppend0(self, x); synchronized (dq(self)) { return dqAppend0(self, x); } }
    private static Object dqAppend0(Object self, Object x) { push(dq(self), x, false); return null; }
    public static Object dqAppendLeft(Object self, Object x) { if (!SyncSupport.MT) return dqAppendLeft0(self, x); synchronized (dq(self)) { return dqAppendLeft0(self, x); } }
    private static Object dqAppendLeft0(Object self, Object x) { push(dq(self), x, true); return null; }

    public static Object dqPop(Object self) { if (!SyncSupport.MT) return dqPop0(self); synchronized (dq(self)) { return dqPop0(self); } }
    private static Object dqPop0(Object self) {
        Deque d = dq(self);
        if (d.items.isEmpty()) throw new PyException("IndexError", "pop from an empty deque");
        d.mutations++;
        return d.items.remove(d.items.size() - 1);
    }

    public static Object dqPopLeft(Object self) { if (!SyncSupport.MT) return dqPopLeft0(self); synchronized (dq(self)) { return dqPopLeft0(self); } }
    private static Object dqPopLeft0(Object self) {
        Deque d = dq(self);
        if (d.items.isEmpty()) throw new PyException("IndexError", "pop from an empty deque");
        d.mutations++;
        return d.items.remove(0);
    }

    public static Object dqExtend(Object self, Object iterable) { if (!SyncSupport.MT) return dqExtend0(self, iterable); synchronized (dq(self)) { return dqExtend0(self, iterable); } }
    private static Object dqExtend0(Object self, Object iterable) {
        Deque d = dq(self);
        for (Object x : drain(iterable == self ? dqCopyList(d) : iterable)) push(d, x, false);
        return null;
    }

    public static Object dqExtendLeft(Object self, Object iterable) { if (!SyncSupport.MT) return dqExtendLeft0(self, iterable); synchronized (dq(self)) { return dqExtendLeft0(self, iterable); } }
    private static Object dqExtendLeft0(Object self, Object iterable) {
        Deque d = dq(self);
        for (Object x : drain(iterable == self ? dqCopyList(d) : iterable)) push(d, x, true);
        return null;
    }

    private static ArrayList<Object> dqCopyList(Deque d) { return new ArrayList<>(d.items); }

    private static ArrayList<Object> pyList(List<Object> items) { return new ArrayList<>(items); }

    public static Object dqClear(Object self) { if (!SyncSupport.MT) return dqClear0(self); synchronized (dq(self)) { return dqClear0(self); } }
    private static Object dqClear0(Object self) { Deque d = dq(self); d.items.clear(); d.mutations++; return null; }

    public static Object dqRotate(Object self, Object nObj) { if (!SyncSupport.MT) return dqRotate0(self, nObj); synchronized (dq(self)) { return dqRotate0(self, nObj); } }
    private static Object dqRotate0(Object self, Object nObj) {
        Deque d = dq(self);
        int size = d.items.size();
        if (size <= 1) return null;
        long n = index(nObj) % size;
        if (n < 0) n += size;
        if (n == 0) return null;
        java.util.Collections.rotate(d.items, (int) n);
        d.mutations++;
        return null;
    }

    public static Object dqCount(Object self, Object x) { if (!SyncSupport.MT) return dqCount0(self, x); synchronized (dq(self)) { return dqCount0(self, x); } }
    private static Object dqCount0(Object self, Object x) {
        Deque d = dq(self);
        long mutations = d.mutations;
        long n = 0;
        for (int i = 0; i < d.items.size(); i++) {
            if (PyRuntime.truth(PyRuntime.eq(d.items.get(i), x))) n++;
            if (d.mutations != mutations) throw new PyException("RuntimeError", "deque mutated during iteration");
        }
        return n;
    }

    public static Object dqIndex(Object self, Object x, Object startObj, Object stopObj) { if (!SyncSupport.MT) return dqIndex0(self, x, startObj, stopObj); synchronized (dq(self)) { return dqIndex0(self, x, startObj, stopObj); } }
    private static Object dqIndex0(Object self, Object x, Object startObj, Object stopObj) {
        Deque d = dq(self);
        int size = d.items.size();
        long start = index(startObj), stop = stopObj instanceof Long l ? l : index(stopObj);
        if (start < 0) start = Math.max(0, start + size);
        if (stop < 0) stop += size;
        stop = Math.min(stop, size);
        long mutations = d.mutations;
        for (long i = start; i < stop; i++) {
            if (PyRuntime.truth(PyRuntime.eq(d.items.get((int) i), x))) return i;
            if (d.mutations != mutations) throw new PyException("RuntimeError", "deque mutated during iteration");
        }
        throw new PyException("ValueError", PyRuntime.pyRepr(x) + " is not in deque");
    }

    public static Object dqInsert(Object self, Object iObj, Object x) { if (!SyncSupport.MT) return dqInsert0(self, iObj, x); synchronized (dq(self)) { return dqInsert0(self, iObj, x); } }
    private static Object dqInsert0(Object self, Object iObj, Object x) {
        Deque d = dq(self);
        if (d.maxlen >= 0 && d.items.size() >= d.maxlen) throw new PyException("IndexError", "deque already at its maximum size");
        long i = index(iObj);
        int size = d.items.size();
        if (i < 0) i = Math.max(0, i + size);
        d.items.add((int) Math.min(i, size), x);
        d.mutations++;
        return null;
    }

    public static Object dqRemove(Object self, Object x) { if (!SyncSupport.MT) return dqRemove0(self, x); synchronized (dq(self)) { return dqRemove0(self, x); } }
    private static Object dqRemove0(Object self, Object x) {
        Deque d = dq(self);
        long mutations = d.mutations;
        for (int i = 0; i < d.items.size(); i++) {
            boolean same = PyRuntime.truth(PyRuntime.eq(d.items.get(i), x));
            if (d.mutations != mutations) throw new PyException("IndexError", "deque mutated during remove().");
            if (same) { d.items.remove(i); d.mutations++; return null; }
        }
        throw new PyException("ValueError", "deque.remove(x): x not in deque");
    }

    public static Object dqReverse(Object self) { if (!SyncSupport.MT) return dqReverse0(self); synchronized (dq(self)) { return dqReverse0(self); } }
    private static Object dqReverse0(Object self) { Deque d = dq(self); java.util.Collections.reverse(d.items); d.mutations++; return null; }

    public static Object dqCopy(Object self) { if (!SyncSupport.MT) return dqCopy0(self); synchronized (dq(self)) { return dqCopy0(self); } }
    private static Object dqCopy0(Object self) {
        Deque d = dq(self);
        PyClass cls = ((PyInstance) self).cls;
        PyInstance out = new PyInstance(cls);
        Deque nd = new Deque();
        nd.items.addAll(d.items);
        nd.maxlen = d.maxlen;
        out.slotValues.put(STATE, nd);
        return out;
    }

    public static Object dqLen(Object self) { if (!SyncSupport.MT) return dqLen0(self); synchronized (dq(self)) { return dqLen0(self); } }
    private static Object dqLen0(Object self) { return (long) dq(self).items.size(); }

    private static Object iterate(Deque d, boolean reversed) {
        long mutations = d.mutations;
        int[] at = {0};
        return new PyLazyIterator() {
            @Override String pyTypeName() { return reversed ? "_collections._deque_reverse_iterator" : "_collections._deque_iterator"; }
            @Override Object compute() {
                if (SyncSupport.MT) synchronized (d) { return step(); }
                return step();
            }
            private Object step() {
                if (d.mutations != mutations) throw new PyException("RuntimeError", "deque mutated during iteration");
                if (at[0] >= d.items.size()) throw new NoSuchElementException();
                int i = at[0]++;
                return d.items.get(reversed ? d.items.size() - 1 - i : i);
            }
        };
    }

    public static Object dqIter(Object self) { if (!SyncSupport.MT) return dqIter0(self); synchronized (dq(self)) { return dqIter0(self); } }
    private static Object dqIter0(Object self) { return iterate(dq(self), false); }
    public static Object dqReversed(Object self) { if (!SyncSupport.MT) return dqReversed0(self); synchronized (dq(self)) { return dqReversed0(self); } }
    private static Object dqReversed0(Object self) { return iterate(dq(self), true); }

    public static Object dqContains(Object self, Object x) { if (!SyncSupport.MT) return dqContains0(self, x); synchronized (dq(self)) { return dqContains0(self, x); } }
    private static Object dqContains0(Object self, Object x) {
        Deque d = dq(self);
        long mutations = d.mutations;
        for (int i = 0; i < d.items.size(); i++) {
            if (PyRuntime.truth(PyRuntime.eq(d.items.get(i), x))) return true;
            if (d.mutations != mutations) throw new PyException("RuntimeError", "deque mutated during iteration");
        }
        return false;
    }

    private static int slot(Deque d, Object iObj) {
        if (iObj instanceof PySlice) throw new PyException("TypeError", "sequence index must be integer, not 'slice'");
        long i = index(iObj);
        int size = d.items.size();
        if (i < 0) i += size;
        if (i < 0 || i >= size) throw new PyException("IndexError", "deque index out of range");
        return (int) i;
    }

    public static Object dqGet(Object self, Object i) { if (!SyncSupport.MT) return dqGet0(self, i); synchronized (dq(self)) { return dqGet0(self, i); } }
    private static Object dqGet0(Object self, Object i) { Deque d = dq(self); return d.items.get(slot(d, i)); }
    public static Object dqSet(Object self, Object i, Object x) { if (!SyncSupport.MT) return dqSet0(self, i, x); synchronized (dq(self)) { return dqSet0(self, i, x); } }
    private static Object dqSet0(Object self, Object i, Object x) { Deque d = dq(self); d.items.set(slot(d, i), x); return null; }
    public static Object dqDel(Object self, Object i) { if (!SyncSupport.MT) return dqDel0(self, i); synchronized (dq(self)) { return dqDel0(self, i); } }
    private static Object dqDel0(Object self, Object i) { Deque d = dq(self); d.items.remove(slot(d, i)); d.mutations++; return null; }

    public static Object dqRepr(Object self) { if (!SyncSupport.MT) return dqRepr0(self); synchronized (dq(self)) { return dqRepr0(self); } }
    private static Object dqRepr0(Object self) {
        Deque d = dq(self);
        StringBuilder out = new StringBuilder(((PyInstance) self).cls.name).append("([");
        for (int i = 0; i < d.items.size(); i++) {
            Object x = d.items.get(i);
            out.append(i == 0 ? "" : ", ").append(x == self ? "[...]" : PyRuntime.pyRepr(x));
        }
        out.append("]");
        if (d.maxlen >= 0) out.append(", maxlen=").append(d.maxlen);
        return out.append(")").toString();
    }

    public static Object dqAdd(Object self, Object other) { if (!SyncSupport.MT) return dqAdd0(self, other); synchronized (dq(self)) { return dqAdd0(self, other); } }
    private static Object dqAdd0(Object self, Object other) {
        if (!(other instanceof PyInstance o) || !(o.slotValues.get(STATE) instanceof Deque))
            throw new PyException("TypeError", "can only concatenate deque (not \"" + PyRuntime.typeName(other) + "\") to deque");
        Object out = dqCopy(self);
        dqExtend(out, other);
        return out;
    }

    public static Object dqIAdd(Object self, Object other) { if (!SyncSupport.MT) return dqIAdd0(self, other); synchronized (dq(self)) { return dqIAdd0(self, other); } }
    private static Object dqIAdd0(Object self, Object other) { dqExtend(self, other); return self; }

    public static Object dqMul(Object self, Object nObj) { if (!SyncSupport.MT) return dqMul0(self, nObj); synchronized (dq(self)) { return dqMul0(self, nObj); } }
    private static Object dqMul0(Object self, Object nObj) {
        Deque d = dq(self);
        long n = index(nObj);
        Object out = dqCopy(self);
        Deque nd = dq(out);
        nd.items.clear();
        if (n > 0) for (long k = 0; k < n; k++) for (Object x : d.items) push(nd, x, false);
        return out;
    }

    public static Object dqIMul(Object self, Object nObj) { if (!SyncSupport.MT) return dqIMul0(self, nObj); synchronized (dq(self)) { return dqIMul0(self, nObj); } }
    private static Object dqIMul0(Object self, Object nObj) {
        Deque d = dq(self);
        long n = index(nObj);
        ArrayList<Object> base = new ArrayList<>(d.items);
        d.items.clear();
        d.mutations++;
        if (n > 0) for (long k = 0; k < n; k++) for (Object x : base) push(d, x, false);
        return self;
    }

    private static Deque other(Object o) { return o instanceof PyInstance i && i.slotValues.get(STATE) instanceof Deque d ? d : null; }

    private static Object compare(Object self, Object other, String op) {
        Deque a = dq(self), b = other(other);
        if (b == null) return PyRuntime.NOT_IMPLEMENTED;
        ArrayList<Object> la = pyList(a.items), lb = pyList(b.items);
        return switch (op) {
            case "eq" -> PyRuntime.eq(la, lb);
            case "lt" -> PyRuntime.lt(la, lb);
            case "le" -> PyRuntime.le(la, lb);
            case "gt" -> PyRuntime.lt(lb, la);
            default -> PyRuntime.le(lb, la);
        };
    }

    public static Object dqEq(Object self, Object other) { if (!SyncSupport.MT) return dqEq0(self, other); synchronized (dq(self)) { return dqEq0(self, other); } }
    private static Object dqEq0(Object self, Object other) { return compare(self, other, "eq"); }
    public static Object dqNe(Object self, Object other) { if (!SyncSupport.MT) return dqNe0(self, other); synchronized (dq(self)) { return dqNe0(self, other); } }
    private static Object dqNe0(Object self, Object other) {
        Object r = compare(self, other, "eq");
        return r == PyRuntime.NOT_IMPLEMENTED ? r : (Object) !PyRuntime.truth(r);
    }
    public static Object dqLt(Object self, Object other) { if (!SyncSupport.MT) return dqLt0(self, other); synchronized (dq(self)) { return dqLt0(self, other); } }
    private static Object dqLt0(Object self, Object other) { return compare(self, other, "lt"); }
    public static Object dqLe(Object self, Object other) { if (!SyncSupport.MT) return dqLe0(self, other); synchronized (dq(self)) { return dqLe0(self, other); } }
    private static Object dqLe0(Object self, Object other) { return compare(self, other, "le"); }
    public static Object dqGt(Object self, Object other) { if (!SyncSupport.MT) return dqGt0(self, other); synchronized (dq(self)) { return dqGt0(self, other); } }
    private static Object dqGt0(Object self, Object other) { return compare(self, other, "gt"); }
    public static Object dqGe(Object self, Object other) { if (!SyncSupport.MT) return dqGe0(self, other); synchronized (dq(self)) { return dqGe0(self, other); } }
    private static Object dqGe0(Object self, Object other) { return compare(self, other, "ge"); }

    public static Object dqMaxlen(Object self) { if (!SyncSupport.MT) return dqMaxlen0(self); synchronized (dq(self)) { return dqMaxlen0(self); } }
    private static Object dqMaxlen0(Object self) { Deque d = dq(self); return d.maxlen < 0 ? null : (Object) d.maxlen; }

    public static Object dqReduce(Object self) { if (!SyncSupport.MT) return dqReduce0(self); synchronized (dq(self)) { return dqReduce0(self); } }
    private static Object dqReduce0(Object self) {
        Deque d = dq(self);
        PyTuple args = d.maxlen < 0 ? new PyTuple() : tuple(new PyTuple(), d.maxlen);
        return tuple(((PyInstance) self).cls, args, null, iterate(d, false));
    }

    // ---- defaultdict -----------------------------------------------------------------------------

    private static PyInstance dd(Object self) {
        if (self instanceof PyInstance i && i.payload instanceof PyDict) return i;
        throw new PyException("TypeError", "descriptor requires a 'collections.defaultdict' object but received '" + PyRuntime.typeName(self) + "'");
    }

    public static Object ddInit(Object self, Object factory, Object args, Object kwargs) {
        PyInstance inst = dd(self);
        if (factory != null && !PyRuntime.truth(PyRuntime.callable_(factory))) throw new PyException("TypeError", "first argument must be callable or None");
        inst.fields.put("default_factory", factory);
        PyTuple rest = (PyTuple) args;
        @SuppressWarnings("unchecked") Map<Object,Object> kw = (Map<Object,Object>) kwargs;
        NativeSubclass.initPayload("dict", inst.payload, rest.items.toArray(), kw);
        return null;
    }

    public static Object ddMissing(Object self, Object key) {
        PyInstance inst = dd(self);
        Object factory = inst.fields.get("default_factory");
        if (factory == null) throw new PyException("KeyError", key);
        Object value = PyRuntime.callFunction(factory, new ArrayList<Object>(), new LinkedHashMap<Object,Object>());
        PyRuntime.setitem(inst.payload, key, value);
        return value;
    }

    public static Object ddFactory(Object self) { return dd(self).fields.get("default_factory"); }

    public static Object ddSetFactory(Object self, Object value) { dd(self).fields.put("default_factory", value); return null; }

    public static Object ddRepr(Object self) {
        PyInstance inst = dd(self);
        Object factory = inst.fields.get("default_factory");
        String f = factory == null ? "None" : factory == self ? "..." : PyRuntime.pyRepr(factory);
        return inst.cls.name + "(" + f + ", " + PyRuntime.pyRepr(inst.payload) + ")";
    }

    public static Object ddCopy(Object self) {
        PyInstance inst = dd(self);
        ArrayList<Object> args = new ArrayList<>();
        args.add(inst.fields.get("default_factory"));
        args.add(inst.payload);
        return PyRuntime.callFunction(inst.cls, args, new LinkedHashMap<Object,Object>());
    }

    public static Object ddReduce(Object self) {
        PyInstance inst = dd(self);
        Object factory = inst.fields.get("default_factory");
        PyTuple args = factory == null ? new PyTuple() : tuple(factory);
        return tuple(inst.cls, args, null, null, PyRuntime.callMethod(inst.payload, "items", new Object[0]));
    }
}
