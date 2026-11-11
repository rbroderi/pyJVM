// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime half of annotation lowering (pyjvm315/annot.py): the __pyjvm_* helpers the lowered code calls, and the
 * __annotations__ / __annotate__ attribute rules for functions and classes (PEP 649 / PEP 749, CPython 3.14+;
 * eager PEP 526 behaviour below 3.14).
 */
final class AnnotationSupport {
    private AnnotationSupport() {}

    static final String ANNOTATIONS = "__annotations__", ANNOTATE = "__annotate__";
    static final String ANNOTATE_FUNC = "__annotate_func__", CACHE = "__annotations_cache__";

    static boolean deferred() { return LangVersion.atLeast(3, 14); }
    static boolean isAnnotationName(String name) { return name.startsWith("__annot") && (name.equals(ANNOTATIONS) || name.equals(ANNOTATE)); }

    // ---- helpers called by lowered code ----------------------------------------------------------

    static boolean isHelper(String name) { return name.startsWith("__pyjvm_"); }

    static Object callHelper(String name, List<Object> args) {
        if (TypeParamsSupport.isHelper(name)) return TypeParamsSupport.callHelper(name, args);
        switch (name) {
            case "__pyjvm_annotate__": return decorator(name, args, true);
            case "__pyjvm_annotations__": return decorator(name, args, false);
            case "__pyjvm_class_annotate__": return nameAnnotate(args);
            case "__pyjvm_cond_add__": return condAdd(args);
            case "__pyjvm_global__": return PyRuntime.globalGet(PyRuntime.UNBOUND, args.get(0));
            case "__pyjvm_not_implemented__": throw new PyException("NotImplementedError", "");
            default: throw new PyException("NameError", "name '" + name + "' is not defined");
        }
    }

    private static Object decorator(String name, List<Object> args, boolean annotate) {
        if (args.size() != 1) throw new PyException("TypeError", name + "() takes exactly one argument");
        Object payload = args.get(0);
        return new NativeFunction("annotation decorator", (a, kw) -> {
            PyFunction function = (PyFunction) a.get(0);
            if (annotate) {
                if (payload instanceof PyFunction lambda) nameAnnotate(lambda, function.metadata.get("__qualname__"));
                function.metadata.put(ANNOTATE, payload);
            } else function.metadata.put(ANNOTATIONS, payload);
            return function;
        });
    }

    private static void nameAnnotate(PyFunction annotate, Object ownerQualname) {
        annotate.metadata.put("__name__", ANNOTATE);
        annotate.metadata.put("__qualname__", ownerQualname == null ? ANNOTATE : ownerQualname + "." + ANNOTATE);
    }

    private static Object nameAnnotate(List<Object> args) {
        if (args.size() != 2) throw new PyException("TypeError", "__pyjvm_class_annotate__() takes exactly 2 arguments");
        if (args.get(0) instanceof PyFunction annotate) nameAnnotate(annotate, args.get(1));
        return args.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Object condAdd(List<Object> args) {
        ((Set<Object>) args.get(0)).add(args.get(1));
        return null;
    }

    // ---- functions -------------------------------------------------------------------------------

    private static Object callAnnotate(Object annotate) {
        ArrayList<Object> arguments = new ArrayList<>();
        arguments.add(PyRuntime.pyInt("1"));
        Object result = PyRuntime.callFunction(annotate, arguments, PyRuntime.dict0());
        if (!(result instanceof Map<?, ?>))
            throw new PyException("TypeError", "__annotate__() must return a dict, not " + PyRuntime.typeName(result));
        return result;
    }

    private static boolean isCallable(Object value) { return Boolean.TRUE.equals(PyRuntime.callable_(value)); }

    static Object functionGet(PyFunction function, String name) {
        if (!deferred()) {
            if (name.equals(ANNOTATIONS)) return function.metadata.computeIfAbsent(name, key -> PyRuntime.dict0());
            if (function.metadata.containsKey(name)) return function.metadata.get(name);
            throw new PyException("AttributeError", "function has no attribute '" + name + "'");
        }
        if (name.equals(ANNOTATE)) return function.metadata.get(ANNOTATE);
        Object cached = function.metadata.get(ANNOTATIONS);
        if (cached != null) return cached;
        Object annotate = function.metadata.get(ANNOTATE);
        Object result = annotate != null && isCallable(annotate) ? callAnnotate(annotate) : PyRuntime.dict0();
        function.metadata.put(ANNOTATIONS, result);
        return result;
    }

    static void functionSet(PyFunction function, String name, Object value) {
        if (name.equals(ANNOTATIONS)) {
            if (value != null && !(value instanceof Map<?, ?>))
                throw new PyException("TypeError", "__annotations__ must be set to a dict object");
            if (value == null) function.metadata.remove(ANNOTATIONS); else function.metadata.put(ANNOTATIONS, value);
            if (deferred()) function.metadata.put(ANNOTATE, null);
            return;
        }
        if (!deferred()) { function.metadata.put(name, value); return; }
        if (value != null && !isCallable(value)) throw new PyException("TypeError", "__annotate__ must be callable or None");
        function.metadata.put(ANNOTATE, value);
        if (value != null) function.metadata.remove(ANNOTATIONS);
    }

    static void functionDelete(PyFunction function, String name) {
        if (name.equals(ANNOTATIONS)) {
            function.metadata.remove(ANNOTATIONS);
            if (deferred()) function.metadata.put(ANNOTATE, null);
            return;
        }
        if (deferred()) throw new PyException("TypeError", "__annotate__ cannot be deleted");
        if (!function.metadata.containsKey(name)) throw new PyException("AttributeError", "attribute not found");
        function.metadata.remove(name);
    }

    // ---- classes (3.14+) -------------------------------------------------------------------------

    private static Object classAnnotate(PyClass cls) {
        Object annotate = cls.attrs.get(ANNOTATE);   // a user-defined __annotate__ wins over the compiler's __annotate_func__
        return annotate != null ? annotate : cls.attrs.get(ANNOTATE_FUNC);
    }

    static Object classGet(PyClass cls, String name) {
        if (name.equals(ANNOTATE)) {
            Object annotate = classAnnotate(cls);
            return annotate != null && isCallable(annotate) ? annotate : null;
        }
        if (cls.attrs.containsKey(ANNOTATIONS)) return cls.attrs.get(ANNOTATIONS);
        Object cached = cls.attrs.get(CACHE);
        if (cached != null) return cached;
        Object annotate = classAnnotate(cls);
        Object result = annotate != null && isCallable(annotate) ? callAnnotate(annotate) : PyRuntime.dict0();
        cls.attrs.put(CACHE, result);
        return result;
    }

    static void classSet(PyClass cls, String name, Object value) {
        if (name.equals(ANNOTATIONS)) {
            cls.attrs.remove(ANNOTATIONS); cls.attrs.remove(ANNOTATE); cls.attrs.remove(ANNOTATE_FUNC);
            if (value == null) cls.attrs.remove(CACHE); else cls.attrs.put(CACHE, value);
            cls.attrs.put(ANNOTATE_FUNC, null);
            return;
        }
        if (value != null && !isCallable(value)) throw new PyException("TypeError", "__annotate__ must be callable or None");
        cls.attrs.remove(ANNOTATE);
        cls.attrs.put(ANNOTATE_FUNC, value);
        if (value != null) { cls.attrs.remove(CACHE); cls.attrs.remove(ANNOTATIONS); }
    }

    static void classDelete(PyClass cls, String name) {
        if (name.equals(ANNOTATE)) throw new PyException("TypeError", "cannot delete __annotate__ attribute");
        boolean had = cls.attrs.remove(CACHE) != null || cls.attrs.containsKey(ANNOTATIONS);
        if (!had) throw new PyException("AttributeError", ANNOTATIONS);
        cls.attrs.remove(ANNOTATIONS); cls.attrs.remove(ANNOTATE); cls.attrs.remove(ANNOTATE_FUNC);
        cls.methods.remove(ANNOTATE); cls.methods.remove(ANNOTATE_FUNC);
        cls.attrs.put(ANNOTATE_FUNC, null);
    }
}
