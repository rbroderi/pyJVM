// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code operator.itemgetter}, {@code operator.attrgetter} and {@code operator.methodcaller} (the callable types that
 * {@code _operator} exports). Argument and error texts are CPython's (identical on 3.13 / 3.14 / 3.15).
 *
 * <p>Known difference: the classes are ordinary native classes, so (unlike CPython's final static types) they can be
 * subclassed and have attributes set on their instances' type.
 */
final class OperatorGetters {
    private OperatorGetters() {}

    private static final Class<?> C = OperatorGetters.class;
    private static final String ME = C.getName();

    private record Items(Object[] items) {}
    private record Attrs(Object[] names, String[][] paths) {}
    private record Caller(String name, List<Object> args, Map<Object, Object> kwargs) {}

    private static PyException err(String msg) { return new PyException("TypeError", msg); }

    static void install(PyModule m) {
        String[] names = {"itemgetter", "attrgetter", "methodcaller"};
        String[] prefixes = {"ig", "ag", "mc"};
        String[] docs = {OperatorDocs.DOC_ITEMGETTER, OperatorDocs.DOC_ATTRGETTER, OperatorDocs.DOC_METHODCALLER};
        String[] sigs = {OperatorDocs.SIG_ITEMGETTER, OperatorDocs.SIG_ATTRGETTER, OperatorDocs.SIG_METHODCALLER};
        for (int i = 0; i < names.length; i++) {
            PyClass cls = define("operator", names[i], null);
            PyRuntime.classAddMethodEx(cls, "__new__", C, prefixes[i] + "New", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
            PyRuntime.classAddAttr(cls, "__new__", cls.methods.get("__new__").function);
            methodVarKw(cls, C, "__call__", prefixes[i] + "Call", "self", "args", "kwargs", new PyDict());
            method(cls, C, "__repr__", prefixes[i] + "Repr", "self");
            method(cls, C, "__reduce__", prefixes[i] + "Reduce", "self");
            PyRuntime.classAddAttr(cls, "__doc__", docs[i]);
            PyRuntime.classAddAttr(cls, "__text_signature__", sigs[i]);
            finish(cls);
            m.attrs.put(names[i], cls);
        }
    }

    private static void noKeywords(String type, PyDict kw) {
        if (!kw.isEmpty()) throw err(type + "() takes no keyword arguments");
    }

    private static void oneArgument(String type, PyTuple args) {
        if (args.items.size() != 1) throw err(type + " expected 1 argument, got " + args.items.size());
    }

    private static PyTuple tupleOfArray(Object[] items) {
        PyTuple t = new PyTuple();
        for (Object o : items) t.items.add(o);
        return t;
    }

    // ------------------------------------------------------------------------------------------------ itemgetter

    private static Items items(Object self) { return state(self, Items.class, "operator.itemgetter"); }

    public static Object igNew(Object cls, Object args, Object kwargs) {
        noKeywords("itemgetter", (PyDict) kwargs);
        List<Object> a = ((PyTuple) args).items;
        if (a.isEmpty()) throw err("itemgetter expected 1 argument, got 0");
        return newInstance((PyClass) cls, new Items(a.toArray()));
    }

    public static Object igCall(Object self, Object args, Object kwargs) {
        Items st = items(self);
        noKeywords("itemgetter", (PyDict) kwargs);
        PyTuple a = (PyTuple) args;
        oneArgument("itemgetter", a);
        Object obj = a.items.get(0);
        if (st.items().length == 1) return PyRuntime.getitem(obj, st.items()[0]);
        PyTuple out = new PyTuple();
        for (Object item : st.items()) out.items.add(PyRuntime.getitem(obj, item));
        return out;
    }

    public static Object igRepr(Object self) {
        StringBuilder sb = new StringBuilder("operator.itemgetter(");
        Object[] its = items(self).items();
        for (int i = 0; i < its.length; i++) sb.append(i == 0 ? "" : ", ").append(PyRuntime.pyRepr(its[i]));
        return sb.append(')').toString();
    }

    public static Object igReduce(Object self) {
        return tuple(((PyInstance) self).cls, tupleOfArray(items(self).items()));
    }

    // ------------------------------------------------------------------------------------------------ attrgetter

    private static Attrs attrs(Object self) { return state(self, Attrs.class, "operator.attrgetter"); }

    public static Object agNew(Object cls, Object args, Object kwargs) {
        noKeywords("attrgetter", (PyDict) kwargs);
        List<Object> a = ((PyTuple) args).items;
        if (a.isEmpty()) throw err("attrgetter expected 1 argument, got 0");
        Object[] names = new Object[a.size()];
        String[][] paths = new String[a.size()][];
        for (int i = 0; i < names.length; i++) {
            Object name = NativeSubclass.unwrap(a.get(i));
            if (!(name instanceof String s)) throw err("attribute name must be a string");
            names[i] = name;
            paths[i] = s.split("\\.", -1);
        }
        return newInstance((PyClass) cls, new Attrs(names, paths));
    }

    private static Object resolve(Object obj, String[] path) {
        for (String p : path) obj = PyRuntime.getattr(obj, p);
        return obj;
    }

    public static Object agCall(Object self, Object args, Object kwargs) {
        Attrs st = attrs(self);
        noKeywords("attrgetter", (PyDict) kwargs);
        PyTuple a = (PyTuple) args;
        oneArgument("attrgetter", a);
        Object obj = a.items.get(0);
        if (st.paths().length == 1) return resolve(obj, st.paths()[0]);
        PyTuple out = new PyTuple();
        for (String[] path : st.paths()) out.items.add(resolve(obj, path));
        return out;
    }

    public static Object agRepr(Object self) {
        StringBuilder sb = new StringBuilder("operator.attrgetter(");
        Object[] names = attrs(self).names();
        for (int i = 0; i < names.length; i++) sb.append(i == 0 ? "" : ", ").append(PyRuntime.pyRepr(names[i]));
        return sb.append(')').toString();
    }

    public static Object agReduce(Object self) {
        return tuple(((PyInstance) self).cls, tupleOfArray(attrs(self).names()));
    }

    // ------------------------------------------------------------------------------------------------ methodcaller

    private static Caller caller(Object self) { return state(self, Caller.class, "operator.methodcaller"); }

    public static Object mcNew(Object cls, Object args, Object kwargs) {
        List<Object> a = ((PyTuple) args).items;
        if (a.isEmpty()) throw err("methodcaller needs at least one argument, the method name");
        Object name = NativeSubclass.unwrap(a.get(0));
        if (!(name instanceof String s)) throw err("method name must be a string");
        return newInstance((PyClass) cls, new Caller(s, new ArrayList<>(a.subList(1, a.size())), new LinkedHashMap<>((PyDict) kwargs)));
    }

    public static Object mcCall(Object self, Object args, Object kwargs) {
        Caller st = caller(self);
        PyTuple a = (PyTuple) args;
        oneArgument("methodcaller", a);
        Object method = PyRuntime.getattr(a.items.get(0), st.name());
        return PyRuntime.callFunction(method, new ArrayList<>(st.args()), new LinkedHashMap<>(st.kwargs()));
    }

    public static Object mcRepr(Object self) {
        Caller st = caller(self);
        StringBuilder sb = new StringBuilder("operator.methodcaller(").append(PyRuntime.pyRepr(st.name()));
        for (Object arg : st.args()) sb.append(", ").append(PyRuntime.pyRepr(arg));
        for (Map.Entry<Object, Object> e : st.kwargs().entrySet()) sb.append(", ").append(e.getKey()).append('=').append(PyRuntime.pyRepr(e.getValue()));
        return sb.append(')').toString();
    }

    public static Object mcReduce(Object self) {
        Caller st = caller(self);
        PyClass cls = ((PyInstance) self).cls;
        if (st.kwargs().isEmpty()) {
            PyTuple call = new PyTuple();
            call.items.add(st.name());
            call.items.addAll(st.args());
            return tuple(cls, call);
        }
        Object functools = ImportSupport.importBuiltin(new ArrayList<Object>(List.of("functools")), new LinkedHashMap<Object, Object>());
        Object partial = PyRuntime.callFunction(PyRuntime.getattr(functools, "partial"), new ArrayList<Object>(List.of(cls, st.name())), new LinkedHashMap<>(st.kwargs()));
        PyTuple rest = new PyTuple();
        rest.items.addAll(st.args());
        return tuple(partial, rest);
    }
}
