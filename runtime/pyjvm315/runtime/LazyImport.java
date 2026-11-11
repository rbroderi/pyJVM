// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PEP 810 lazy imports (Python 3.15). The compiler lowers {@code lazy import} / {@code lazy from ... import} (and, in a
 * module that lists the name in {@code __lazy_modules__} or while {@code sys.set_lazy_imports("all")} is in effect, a
 * plain module-level import) to calls of the helpers below; the helper either binds a {@code lazy_import} object or
 * imports eagerly (a filter said no). Loading a name the compiler knows to be lazy-bound, or reading it as an attribute
 * of its module, resolves the import and rebinds the name to the real object; {@code globals()} and module dictionaries
 * show the lazy object until then. Failures keep the binding lazy and raise the real error with an ImportError saying
 * that the lazy import failed as its {@code __cause__}.
 */
public final class LazyImport {
    private LazyImport() {}

    /** What one lazy binding stands for. */
    static final class Lazy {
        final String name, attr, importer, pkg;
        /** {@code import a.b.c} without {@code as}: the binding is the top-level package, not the module. */
        final boolean top;
        Lazy(String name, String attr, boolean top, String importer, String pkg) { this.name = name; this.attr = attr; this.top = top; this.importer = importer; this.pkg = pkg; }
        String display() { return attr == null ? name : name + "." + attr; }
    }

    private static final String DOC = "Represents a lazy import that will be resolved on first use.\n\n"
        + "Instances of this object accessed from the global scope will be\n"
        + "automatically imported based upon their name and then replaced with\n"
        + "the imported value.";

    /** Namespaces of the code being run by exec/import, innermost last: where a lazy import in dynamic code finds its importer. */
    private static final ThreadLocal<java.util.ArrayDeque<Object>> RUNNING = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    static void enter(Object globals) { RUNNING.get().push(globals); }

    static void exit() { java.util.ArrayDeque<Object> d = RUNNING.get(); if (!d.isEmpty()) d.pop(); }

    static volatile String mode = "normal";
    static volatile Object filter;
    static final PySet LAZY_MODULES = new PySet();
    private static PyClass cls;

    static synchronized PyClass cls() {
        if (cls == null) {
            PyClass c = define("builtins", "lazy_import", null);
            PyRuntime.classAddMethodEx(c, "__new__", LazyImport.class, "newFn", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
            PyRuntime.classAddAttr(c, "__new__", c.methods.get("__new__").function);
            method(c, LazyImport.class, "resolve", "resolveMethod", "self");
            method(c, LazyImport.class, "__repr__", "reprMethod", "self");
            PyRuntime.classAddAttr(c, "__doc__", DOC);
            finish(c);
            cls = c;
        }
        return cls;
    }

    public static Object newFn(Object c, Object args, Object kwargs) { throw new PyException("TypeError", "cannot create 'lazy_import' instances"); }

    private static Lazy lazy(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof Lazy z) return z;
        throw new PyException("TypeError", "descriptor 'resolve' for 'lazy_import' objects doesn't apply to a '" + PyRuntime.typeName(self) + "' object");
    }

    public static Object resolveMethod(Object self) { return resolve(lazy(self)); }

    public static Object reprMethod(Object self) { return "<lazy_import " + PyRuntime.pyRepr(lazy(self).display()) + ">"; }

    // ------------------------------------------------------------------ sys

    static void installSys(PyModule sys) {
        sys.attrs.put("lazy_modules", LAZY_MODULES);
        fn(sys, "set_lazy_imports", (a, k) -> {
            Object[] p = NativeArgs.parse("set_lazy_imports", a, k, new String[]{"mode"}, 1, 0);
            if (!(p[0] instanceof String s)) throw new PyException("TypeError", "mode must be a string: 'normal' or 'all'");
            if (!s.equals("normal") && !s.equals("all")) throw new PyException("ValueError", "mode must be 'normal' or 'all'");
            mode = s;
            return null;
        });
        fn(sys, "get_lazy_imports", (a, k) -> {
            if (!a.isEmpty() || !k.isEmpty()) throw new PyException("TypeError", "sys.get_lazy_imports() takes no arguments (" + (a.size() + k.size()) + " given)");
            return mode;
        });
        fn(sys, "set_lazy_imports_filter", (a, k) -> {
            Object[] p = NativeArgs.parse("set_lazy_imports_filter", a, k, new String[]{"filter"}, 1, 0);
            if (p[0] != null && !Boolean.TRUE.equals(PyRuntime.callable_(p[0]))) throw new PyException("ValueError", "filter provided but is not callable");
            filter = p[0];
            return null;
        });
        fn(sys, "get_lazy_imports_filter", (a, k) -> {
            if (!a.isEmpty() || !k.isEmpty()) throw new PyException("TypeError", "sys.get_lazy_imports_filter() takes no arguments (" + (a.size() + k.size()) + " given)");
            return filter;
        });
    }

    /** {@code types.LazyImportType}: types.py has no pure-Python fallback for it, so the module body's end adds it. */
    static void afterExec(Object globals) {
        if (LangVersion.atLeast(3, 15) && globals instanceof Map<?, ?> g && "types".equals(g.get("__name__")) && !g.containsKey("LazyImportType")) {
            @SuppressWarnings("unchecked") Map<Object, Object> m = (Map<Object, Object>) g;
            m.put("LazyImportType", cls());
        }
    }

    // ------------------------------------------------------------------ helpers called by lowered code

    static boolean isHelper(String name) { return name.equals("__pyjvm_lazy_import__") || name.equals("__pyjvm_lazy_ok__"); }

    /** Arguments: module name, from-name or None, level, binds-top flag, importing module, its package. */
    static Object callHelper(String helper, List<Object> args) {
        String name = (String) args.get(0), attr = (String) args.get(1), importer = (String) args.get(4), pkg = (String) args.get(5);
        long level = (Long) args.get(2);
        if (importer == null) {   // dynamic code: the namespace being executed says who is importing
            Object g = RUNNING.get().peek();
            Map<?, ?> ns = g instanceof Map<?, ?> m ? m : Map.of();
            importer = ns.get("__name__") instanceof String n ? n : "__main__";
            pkg = ns.get("__package__") instanceof String p ? p : null;
            if (pkg == null && ns.get("__spec__") != null) {
                try { pkg = PyRuntime.getattr(ns.get("__spec__"), "parent") instanceof String p ? p : null; } catch (PyException e) { pkg = null; }
            }
        }
        boolean top = Boolean.TRUE.equals(args.get(3));
        String abs = absolute(name, level, pkg);
        if (helper.equals("__pyjvm_lazy_ok__")) return implicit(abs, attr, importer);
        Lazy z = new Lazy(abs, attr, top, importer, pkg);
        if (!allowed(importer, abs, attr)) return resolveDirect(z);
        track(z);
        return NativeTypes.newInstance(cls(), z);
    }

    /** The decision for a plain import: lazy in "all" mode or when the module lists the name in {@code __lazy_modules__}. */
    private static boolean implicit(String abs, String attr, String importer) {
        if (abs == null) return false;
        boolean on = mode.equals("all");
        if (!on) {
            try {
                Object mod = ImportSupport.MODULES.get(importer);
                Object listed = mod instanceof PyModule m ? PyRuntime.moduleGetattr(m, "__lazy_modules__") : null;
                if (listed != null) for (Object o : PyRuntime.iterable(listed)) if (abs.equals(o)) { on = true; break; }
            } catch (PyException e) {
                if (!e.typeName.equals("AttributeError")) throw e;
            }
        }
        return on && allowed(importer, abs, attr);
    }

    private static boolean allowed(String importer, String abs, String attr) {
        Object f = filter;
        if (f == null) return true;
        ArrayList<Object> call = new ArrayList<>();
        call.add(importer); call.add(abs); call.add(attr == null ? null : tuple(attr));
        return PyRuntime.truth(PyRuntime.callFunction(f, call, new LinkedHashMap<>()));
    }

    /** The absolute module name, or an ImportError for a relative import that cannot work (reported when the statement runs). */
    private static String absolute(String name, long level, String pkg) {
        if (level == 0) return name;
        if (pkg == null || pkg.isEmpty()) throw new PyException("ImportError", "attempted relative import with no known parent package");
        String[] parts = pkg.split("\\.");
        long keep = parts.length - (level - 1);
        if (keep < 1) throw new PyException("ImportError", "attempted relative import beyond top-level package");
        StringBuilder sb = new StringBuilder(String.join(".", java.util.Arrays.copyOf(parts, (int) keep)));
        if (name != null && !name.isEmpty()) sb.append('.').append(name);
        return sb.toString();
    }

    private static void track(Lazy z) {
        LAZY_MODULES.add(z.name);
        if (z.attr != null) LAZY_MODULES.add(z.name + "." + z.attr);
    }

    // ------------------------------------------------------------------ resolution

    /** Loading a lazily bound name: the real object, or the value itself when it is not a lazy import. */
    public static Object reify(Object value) {
        if (value instanceof PyInstance i && i.slotValues.get(STATE) instanceof Lazy z && i.cls == cls) return resolve(z);
        return value;
    }

    /** Same for a name in a dict namespace (exec'd code): the resolved object replaces the binding. */
    @SuppressWarnings("unchecked")
    public static Object reifyNs(Object value, Object ns, Object name) {
        Object r = reify(value);
        if (r != value && ns instanceof Map<?, ?>) ((Map<Object, Object>) ns).put(name, r);
        return r;
    }

    /** Attribute of a statically compiled module: the resolved object replaces the static field. */
    static Object reifyField(Object value, java.lang.reflect.Field f) throws IllegalAccessException {
        Object r = reify(value);
        if (r != value) f.set(null, r);
        return r;
    }

    private static Object resolve(Lazy z) {
        boolean[] before = {isLoaded(z.name), z.attr != null && isLoaded(z.name + "." + z.attr)};
        Object result;
        try {
            result = resolveDirect(z);
        } catch (PyException e) {
            PyRuntime.raiseObjectFrom(e, new PyException("ImportError", "lazy import of " + PyRuntime.pyRepr(z.display()) + " raised an exception during resolution"));
            throw e;
        }
        if (!before[0] && isLoaded(z.name)) LAZY_MODULES.remove(z.name);
        if (z.attr != null && !before[1] && isLoaded(z.name + "." + z.attr)) LAZY_MODULES.remove(z.name + "." + z.attr);
        return result;
    }

    private static boolean isLoaded(String n) { return ImportSupport.MODULES.containsKey(n); }

    /** The import the statement stands for, done now: the same value an eager statement would bind. */
    private static Object resolveDirect(Lazy z) {
        PyDict g = new PyDict();
        g.put("__name__", z.importer);
        g.put("__package__", z.pkg);
        PyTuple fromlist = new PyTuple();
        if (z.attr != null) fromlist.items.add(z.attr);
        Object imported;
        try {
            imported = ImportSupport.doImport(z.name, g, null, fromlist.items.isEmpty() ? null : fromlist, 0);
        } catch (PyException e) {
            // a missing submodule of an `as` import reads like a missing name of its package
            if (z.attr != null || z.top || !e.typeName.equals("ModuleNotFoundError") || z.name.indexOf('.') < 0) throw e;
            imported = ImportSupport.doImport(z.name.substring(0, z.name.indexOf('.')), g, null, null, 0);
        }
        if (z.attr != null) return ImportSupport.importFrom(imported, z.attr);
        if (z.top) return imported;
        String[] parts = z.name.split("\\.");   // `import a.b as c` is IMPORT_NAME a.b, then IMPORT_FROM b
        Object cur = imported;
        for (int i = 1; i < parts.length; i++) cur = ImportSupport.importFrom(cur, parts[i]);
        return cur;
    }
}
