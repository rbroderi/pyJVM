// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

/** {@code os.terminal_size}: the (columns, lines) struct sequence that shutil.get_terminal_size and argparse construct. */
final class TerminalSize {
    private TerminalSize() {}

    static PyClass define() {
        PyClass cls = NativeTypes.define("os", "terminal_size", null);
        method(cls, TerminalSize.class, "__new__", "make", "cls,sequence");
        method(cls, TerminalSize.class, "__len__", "len", "self");
        method(cls, TerminalSize.class, "__getitem__", "get", "self,index");
        method(cls, TerminalSize.class, "__iter__", "iter", "self");
        method(cls, TerminalSize.class, "__repr__", "repr", "self");
        method(cls, TerminalSize.class, "__eq__", "eq", "self,other");
        method(cls, TerminalSize.class, "__hash__", "hash", "self");
        PyRuntime.classAddProperty(cls, "columns", TerminalSize.class.getName(), "columns", null);
        PyRuntime.classAddProperty(cls, "lines", TerminalSize.class.getName(), "lines", null);
        finish(cls);
        return cls;
    }

    private static PyTuple items(Object self) { return state(self, PyTuple.class, "terminal_size"); }

    public static Object make(Object cls, Object sequence) {
        PyTuple t = new PyTuple();
        for (Object o : PyRuntime.iterable(sequence)) t.items.add(o);
        if (t.items.size() != 2) throw new PyException("TypeError", "os.terminal_size() takes a 2-sequence (" + t.items.size() + "-sequence given)");
        return newInstance((PyClass) cls, t);
    }
    public static Object len(Object self) { return 2L; }
    public static Object get(Object self, Object index) { return PyRuntime.getitem(items(self), index); }
    public static Object iter(Object self) { return PyRuntime.iter(items(self)); }
    public static Object columns(Object self) { return items(self).items.get(0); }
    public static Object lines(Object self) { return items(self).items.get(1); }
    public static Object repr(Object self) {
        PyTuple t = items(self);
        return "os.terminal_size(columns=" + PyRuntime.pyRepr(t.items.get(0)) + ", lines=" + PyRuntime.pyRepr(t.items.get(1)) + ")";
    }
    public static Object eq(Object self, Object other) {
        Object o = other instanceof PyInstance i && i.slotValues.get(STATE) instanceof PyTuple t ? t : other;
        return PyRuntime.eq(items(self), o);
    }
    public static Object hash(Object self) { return PyRuntime.hash(items(self)); }
}
