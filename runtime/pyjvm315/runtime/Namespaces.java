// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * globals(), locals(), vars(), the {@code builtins} module and {@code __builtins__} handling, and
 * the interactive display hook. Compiled code reaches these through the public statics below.
 *
 * <p>Semantics follow CPython 3.13+ (PEP 667): at module and class level {@code locals()} is the live
 * namespace mapping; in a function it is a fresh snapshot dict on every call (writes never reach the
 * function). A statically compiled module has no dictionary of its own, so {@code globals()} returns a
 * {@link NamespacesModuleView} that reads and writes the module's static fields.
 */
public final class Namespaces {
    private Namespaces() {}

    // ---- frames -------------------------------------------------------------------------------

    /** Called by a module's entry code: records the module class and (exec/eval code) its locals mapping. */
    public static void frameSetModule(Object owner, Object locals) {
        ActiveFrame frame = PyRuntime.currentLogicalFrame();
        if (frame == null) return;
        frame.owner = owner;
        if (locals != null) frame.namespace = castMap(locals);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object,Object> castMap(Object o) { return (Map<Object,Object>) o; }

    /** The globals mapping of the code owning {@code owner}: a hidden exec class or a statically compiled module. */
    static Map<Object,Object> globalsOfOwner(Object owner) {
        if (owner instanceof Class<?> c) {
            try { return castMap(c.getField("__py_globals").get(null)); }
            catch (ReflectiveOperationException e) { throw new PyException("RuntimeError", "globals(): code has no globals mapping"); }
        }
        if (owner instanceof String name) return NamespacesModuleView.of(name);
        throw new PyException("NotImplementedError", "globals() cannot find the namespace of this frame");
    }

    private static Object ownerOf(ActiveFrame frame) {
        for (ActiveFrame f : PyRuntime.LOGICAL_FRAMES.get()) {   // iterates from the innermost frame outwards
            if (f.owner != null) return f.owner;
        }
        return frame.owner;
    }

    /** The builtin {@code globals()} called with no frame information at compile time. */
    public static Object globalsCall() {
        ActiveFrame frame = PyRuntime.currentLogicalFrame();
        if (frame == null) throw new PyException("RuntimeError", "globals(): no active frame");
        return globalsOfOwner(ownerOf(frame));
    }

    /** The builtin {@code locals()}: the live namespace of a module or class, a fresh snapshot in a function. */
    public static Object localsCall() {
        ActiveFrame frame = PyRuntime.currentLogicalFrame();
        if (frame == null) throw new PyException("RuntimeError", "locals(): no active frame");
        if (frame.namespace != null) return frame.namespace;
        if (frame.name.equals("<module>")) {
            Object owner = frame.owner;
            if (owner == null) throw new PyException("NotImplementedError", "locals() at module level needs the module's namespace");
            return frame.namespace = globalsOfOwner(owner);
        }
        if (!frame.inspectSupported)
            throw new PyException("NotImplementedError", "locals() inside a generator or coroutine is not implemented");
        LinkedHashMap<Object,Object> snapshot = PyRuntime.frameLocalSnapshot(frame);
        snapshot.values().removeIf(value -> value == PyRuntime.UNBOUND);
        return new PyDict(snapshot);
    }

    /** {@code globals()} compiled into a statically compiled module. */
    public static Object moduleNamespace(Object className) { return NamespacesModuleView.of((String) className); }

    // ---- builtins -----------------------------------------------------------------------------

    /** The shared builtins mapping every exec'd namespace gets as {@code __builtins__} (identity matters). */
    private static final PyDict BUILTINS = new PyDict();
    private static volatile boolean populated;
    private static PyModule builtinsModule;

    static final String[] FUNCTIONS = {"exec", "eval", "compile", "aiter", "anext", "ord", "chr", "repr", "print", "hash", "id", "len",
        "iter", "next", "reversed", "getattr", "hasattr", "setattr", "delattr", "callable", "isinstance", "issubclass", "pow", "abs",
        "any", "all", "sum", "min", "max", "sorted", "hex", "oct", "bin", "format", "dir", "vars", "round", "globals", "locals"};
    static final String[] TYPES = {"object", "int", "bool", "float", "complex", "classmethod", "staticmethod", "property", "str", "bytes",
        "bytearray", "memoryview", "list", "tuple", "dict", "set", "frozenset", "frozendict", "range", "type", "map", "zip",
        "enumerate", "filter", "slice", "super"};

    static synchronized PyDict builtinsDict() {
        if (!populated) {
            for (String name : FUNCTIONS) BUILTINS.put(name, PyRuntime.builtinFunction(name));
            for (String name : TYPES) BUILTINS.put(name, PyRuntime.builtinType(name));
            BUILTINS.put("BaseException", PyRuntime.builtinType("BaseException"));
            List<String> names = new ArrayList<>(PyRuntime.EXCEPTION_PARENTS.keySet());
            java.util.Collections.sort(names);
            for (String name : names) BUILTINS.put(name, PyRuntime.builtinType(name));
            BUILTINS.put("NotImplemented", PyRuntime.notImplemented());
            BUILTINS.put("Ellipsis", PyRuntime.ellipsis());
            BUILTINS.put("None", null);
            BUILTINS.put("True", Boolean.TRUE);
            BUILTINS.put("False", Boolean.FALSE);
            BUILTINS.put("__debug__", Boolean.TRUE);
            BUILTINS.put("__name__", "builtins");
            populated = true;
        }
        return BUILTINS;
    }

    /** {@code import builtins}: its {@code __dict__} is the shared mapping. */
    static synchronized PyModule builtinsModule() {
        if (builtinsModule == null) {
            PyModule m = new PyModule("builtins", "pyjvm315.runtime.Namespaces");
            for (Map.Entry<Object,Object> e : builtinsDict().entrySet()) m.attrs.put((String) e.getKey(), e.getValue());
            builtinsModule = m;
        }
        return builtinsModule;
    }

    /** {@code __builtins__} as seen by a statically compiled module: the module in {@code __main__}, the dict elsewhere. */
    public static Object builtinsFor(Object moduleName) {
        return "__main__".equals(moduleName) ? builtinsModule() : builtinsDict();
    }

    /**
     * A name missing from both namespaces of exec'd code: resolved through globals["__builtins__"]
     * (a dict, a module, or absent), so exec'd code can run with replaced or empty builtins.
     */
    public static Object builtinOrError(Object globals, Object nameObj) {
        Object source = ((Map<?,?>) globals).get("__builtins__");
        if (source == null || source == BUILTINS || source == builtinsModule) {
            if (populated && BUILTINS.containsKey(nameObj)) return BUILTINS.get(nameObj);
            return PyRuntime.globalGet(PyRuntime.UNBOUND, nameObj);
        }
        if (source instanceof PyModule module) source = PyRuntime.moduleGetattr(module, "__dict__");
        if (source instanceof Map<?,?> map) {
            if (map.containsKey(nameObj)) return map.get(nameObj);
            throw new PyException("NameError", "name '" + nameObj + "' is not defined");
        }
        throw new PyException("TypeError", "__builtins__ must be a dict or a module, not " + PyRuntime.typeName(source));
    }

    // ---- interactive mode ---------------------------------------------------------------------

    /** What compile(..., "single") code does with an expression statement: sys.displayhook(value). */
    public static void display(Object value) {
        PyModule sys = (PyModule) PyRuntime.importNative("sys");
        Object hook = sys.attrs.get("displayhook");
        if (hook == null) throw new PyException("RuntimeError", "lost sys.displayhook");
        List<Object> args = new ArrayList<>();
        args.add(value);
        PyRuntime.callFunction(hook, args, new LinkedHashMap<Object,Object>());
    }

    /** sys.displayhook: print repr(value) and remember it as builtins._ (None is ignored). */
    static Object defaultDisplayhook(List<Object> args, Map<Object,Object> kwargs) {
        if (args.size() != 1) throw new PyException("TypeError", "displayhook() takes exactly one argument (" + args.size() + " given)");
        Object value = args.get(0);
        if (value == null) return null;
        builtinsDict().put("_", null);
        List<Object> out = new ArrayList<>();
        out.add(PyRuntime.pyRepr(value));
        PyRuntime.printArgs(out, null, null);
        builtinsDict().put("_", value);
        if (builtinsModule != null) builtinsModule.attrs.put("_", value);
        return null;
    }

}
