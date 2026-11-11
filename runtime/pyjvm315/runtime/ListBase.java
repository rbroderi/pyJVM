// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;

/**
 * Interim stand-in for {@code class X(list)}: until builtin types are subclassable (ticket P2-04), a class that
 * names {@code list} as a base gets this Java-implemented base class instead. Instances behave as mutable
 * sequences with the usual list methods (the ones importlib's bootstrap uses and the common rest), but they are
 * not {@code isinstance(x, list)} and cannot be passed where a real list is required; the operations that need
 * a real list (sort, slicing assignment, comparison, arithmetic) are absent and raise AttributeError/TypeError.
 */
final class ListBase {
    private ListBase() {}

    private static PyClass base;

    static synchronized PyClass get() {
        if (base != null) return base;
        PyClass c = define("builtins", "list", null);
        method(c, ListBase.class, "append", "append", "self,x");
        method(c, ListBase.class, "extend", "extend", "self,items");
        method(c, ListBase.class, "insert", "insert", "self,index,x");
        method(c, ListBase.class, "remove", "remove", "self,x");
        method(c, ListBase.class, "pop", "pop", "self,index", defaults("index", -1L));
        method(c, ListBase.class, "clear", "clear", "self");
        method(c, ListBase.class, "index", "index", "self,x");
        method(c, ListBase.class, "count", "count", "self,x");
        method(c, ListBase.class, "reverse", "reverse", "self");
        method(c, ListBase.class, "__len__", "len", "self");
        method(c, ListBase.class, "__iter__", "iter", "self");
        method(c, ListBase.class, "__contains__", "contains", "self,x");
        method(c, ListBase.class, "__getitem__", "getitem", "self,index");
        method(c, ListBase.class, "__setitem__", "setitem", "self,index,x");
        method(c, ListBase.class, "__delitem__", "delitem", "self,index");
        method(c, ListBase.class, "__repr__", "repr", "self");
        finish(c);
        return base = c;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Object> items(Object self) {
        PyInstance i = (PyInstance) self;
        Object s = i.slotValues.get(STATE);
        if (s == null) { s = new ArrayList<Object>(); i.slotValues.put(STATE, s); }
        return (ArrayList<Object>) s;
    }

    private static int at(Object self, Object index, boolean insert) {
        if (!(index instanceof Long) && !(index instanceof Boolean)) throw new PyException("TypeError", "list indices must be integers, not " + PyRuntime.typeName(index));
        long n = index instanceof Boolean b ? (b ? 1 : 0) : (Long) index;
        int size = items(self).size();
        if (n < 0) n += size;
        if (insert) return (int) Math.max(0, Math.min(size, n));
        if (n < 0 || n >= size) throw new PyException("IndexError", "list index out of range");
        return (int) n;
    }

    private static int find(Object self, Object x) {
        ArrayList<Object> l = items(self);
        for (int k = 0; k < l.size(); k++) if (l.get(k) == x || PyRuntime.truth(PyRuntime.eq(l.get(k), x))) return k;
        return -1;
    }

    public static Object append(Object self, Object x) { items(self).add(x); return null; }
    public static Object extend(Object self, Object more) { for (Object o : PyRuntime.iterable(more)) items(self).add(o); return null; }
    public static Object insert(Object self, Object index, Object x) { items(self).add(at(self, index, true), x); return null; }
    public static Object remove(Object self, Object x) {
        int k = find(self, x);
        if (k < 0) throw new PyException("ValueError", "list.remove(x): x not in list");
        items(self).remove(k);
        return null;
    }
    public static Object pop(Object self, Object index) {
        if (items(self).isEmpty()) throw new PyException("IndexError", "pop from empty list");
        return items(self).remove(at(self, index, false));
    }
    public static Object clear(Object self) { items(self).clear(); return null; }
    public static Object index(Object self, Object x) {
        int k = find(self, x);
        if (k < 0) throw new PyException("ValueError", PyRuntime.pyRepr(x) + " is not in list");
        return (long) k;
    }
    public static Object count(Object self, Object x) {
        long n = 0;
        for (Object o : items(self)) if (o == x || PyRuntime.truth(PyRuntime.eq(o, x))) n++;
        return n;
    }
    public static Object reverse(Object self) { java.util.Collections.reverse(items(self)); return null; }
    public static Object len(Object self) { return (long) items(self).size(); }
    public static Object iter(Object self) { return PyRuntime.iter(new ArrayList<>(items(self))); }
    public static Object contains(Object self, Object x) { return find(self, x) >= 0; }
    public static Object getitem(Object self, Object index) { return items(self).get(at(self, index, false)); }
    public static Object setitem(Object self, Object index, Object x) { items(self).set(at(self, index, false), x); return null; }
    public static Object delitem(Object self, Object index) { items(self).remove(at(self, index, false)); return null; }
    public static Object repr(Object self) { return PyRuntime.pyRepr(items(self)); }
}
