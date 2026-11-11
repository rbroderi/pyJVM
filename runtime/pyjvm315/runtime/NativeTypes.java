// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for defining Python classes whose methods are Java static methods (the native modules of the import
 * system: _thread locks, _weakref.ref, _io.FileIO, ...). The classes are ordinary {@link PyClass} objects, so
 * Python code can subclass them (importlib does: {@code class KeyedRef(_weakref.ref)}). Java state lives in
 * {@link PyInstance#slotValues} under a key that is not a legal identifier.
 */
final class NativeTypes {
    private NativeTypes() {}

    static final String STATE = "\u0000state";

    static PyClass define(String module, String name, PyClass base) {
        PyClass cls = (PyClass) PyRuntime.classCreate(name, module);
        PyRuntime.classAddBase(cls, base != null ? base : PyRuntime.builtinType("object"));
        return cls;
    }

    /** Defaults for trailing parameters: {@code defaults("blocking", true, "timeout", -1L)}. */
    static PyDict defaults(Object... pairs) {
        PyDict d = new PyDict();
        for (int i = 0; i < pairs.length; i += 2) d.put(pairs[i], pairs[i + 1]);
        return d;
    }

    /** Compiled classes register each method both as a method and as a class attribute (super() and getattr look at attributes). */
    private static void add(PyClass cls, String pyName, Class<?> owner, String javaName, String kind, String params, String vararg, PyDict defaults) {
        PyRuntime.classAddMethodEx(cls, pyName, owner, javaName, kind, "", params, "", vararg, null, defaults, "<native>", 0L);
        PyFunction f = cls.methods.get(pyName).function;
        PyRuntime.classAddAttr(cls, pyName, kind.equals("static") && !pyName.equals("__new__") ? PyRuntime.methodDescriptor(f, "static") : f);
    }

    /** {@code def pyName(params)}: a static Java method {@code javaName} of {@code owner} taking one Object per parameter. */
    static void method(PyClass cls, Class<?> owner, String pyName, String javaName, String params, PyDict defaults) {
        add(cls, pyName, owner, javaName, "instance", params, null, defaults);
    }

    static void method(PyClass cls, Class<?> owner, String pyName, String javaName, String params) {
        method(cls, owner, pyName, javaName, params, new PyDict());
    }

    /** {@code def pyName(self, *args)}: the Java method receives (self, PyTuple args). */
    static void methodVar(PyClass cls, Class<?> owner, String pyName, String javaName, String params, String vararg) {
        add(cls, pyName, owner, javaName, "instance", params, vararg, new PyDict());
    }

    static void staticMethod(PyClass cls, Class<?> owner, String pyName, String javaName, String params, PyDict defaults) {
        add(cls, pyName, owner, javaName, "static", params, null, defaults);
    }

    /** {@code @staticmethod def pyName(params, *vararg)}: the Java method receives the fixed parameters then the PyTuple of extras. */
    static void staticMethodVar(PyClass cls, Class<?> owner, String pyName, String javaName, String params, String vararg) {
        add(cls, pyName, owner, javaName, "static", params, vararg, new PyDict());
    }

    static void finish(PyClass cls) { PyRuntime.classFinalizeWithKeywords(cls, new PyDict()); }

    static PyInstance newInstance(PyClass cls, Object state) {
        PyInstance i = new PyInstance(cls);
        i.slotValues.put(STATE, state);
        return i;
    }

    static <T> T state(Object self, Class<T> type, String owner) {
        if (self instanceof PyInstance i && type.isInstance(i.slotValues.get(STATE))) return type.cast(i.slotValues.get(STATE));
        throw new PyException("TypeError", "descriptor requires a '" + owner + "' object but received '" + PyRuntime.typeName(self) + "'");
    }

    static void setState(Object self, Object state) { ((PyInstance) self).slotValues.put(STATE, state); }

    static NativeFunction fn(String name, NativeFunction.Body body) { return new NativeFunction(name, body); }

    static void fn(PyModule m, String name, NativeFunction.Body body) { m.attrs.put(name, new NativeFunction(name, body)); }

    static PyTuple tuple(Object... items) {
        PyTuple t = new PyTuple();
        for (Object o : items) t.items.add(o);
        return t;
    }

    static PyTuple tupleOf(List<?> items) { PyTuple t = new PyTuple(); t.items.addAll(items); return t; }

    static List<Object> list(Object... items) {
        ArrayList<Object> l = new ArrayList<>();
        for (Object o : items) l.add(o);
        return l;
    }

    static Object kw(Map<Object,Object> kwargs, String name, Object fallback) { return kwargs.containsKey(name) ? kwargs.get(name) : fallback; }

    static Map<Object,Object> noKwargs() { return new LinkedHashMap<>(); }
}
