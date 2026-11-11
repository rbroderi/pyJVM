// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The run-time import system. {@code sys.modules} is one {@link PyDict} shared by statically compiled modules
 * (registered when their class is first imported), Java-implemented modules and modules created at run time.
 * Imports that the compiler could not resolve statically go through {@code __import__}, which runs CPython's own
 * unmodified {@code importlib._bootstrap} and {@code importlib._bootstrap_external} (read from the directory named
 * by the PYJVM_STDLIB environment variable) on top of the native {@code _imp}, {@code _thread}, {@code _warnings},
 * {@code _weakref}, {@code posix}, {@code _io} and {@code marshal} modules. Source files are compiled by the
 * in-process compiler through {@code compile()}; there are no .pyc files (sys.implementation.cache_tag is None).
 */
public final class ImportSupport {
    private ImportSupport() {}

    static final PyDict MODULES = new PyDict();
    /** Modules implemented in Java: importlib's BuiltinImporter serves exactly these. */
    static final Set<String> NATIVE_NAMES = Set.of("_string", "ast","_ast", "sys", "builtins", "jvm_io", "_imp", "_thread", "_warnings",
        "_weakref", "posix", "_io", "marshal", "itertools", "_collections", "errno", "math", "time", "_contextvars", "_codecs", "_locale", "gc", "atexit", "_struct", "binascii");
    private static final String[] BOOT_ORDER = {"sys", "builtins", "_imp", "_thread", "_warnings", "_weakref", "posix", "_io", "marshal"};

    private static boolean bootstrapping, bootstrapped;
    private static PyModule bootstrapModule;

    // ---- sys.modules ---------------------------------------------------------------------------

    static PyModule cachedModule(String name) {
        synchronized (MODULES) { return MODULES.get(name) instanceof PyModule m ? m : null; }
    }

    static void register(String name, PyModule module) { synchronized (MODULES) { MODULES.put(name, module); } }

    static void unregister(String name, PyModule module) { synchronized (MODULES) { if (MODULES.get(name) == module) MODULES.remove(name); } }

    static PyModule createNative(String name) {
        return switch (name) {
            case "_imp" -> ImpModule.create();
            case "_thread" -> ThreadModule.create();
            case "_warnings" -> WarningsModule.create();
            case "_weakref" -> WeakrefModule.create();
            case "gc" -> GcModule.create();
            case "atexit" -> AtexitModule.create();
            case "posix" -> PosixModule.create();
            case "_io" -> IoModule.create();
            case "marshal" -> MarshalModule.create();
            case "itertools" -> ItertoolsModule.create();
            case "_collections" -> CollectionsModule.create();
            case "errno" -> ErrnoModule.create();
            case "math" -> MathModule.create();
            case "time" -> TimeModule.create();
            case "_contextvars" -> ContextvarsModule.create();
            case "_codecs" -> CodecsModule.create();
            case "_locale" -> LocaleModule.create();
            case "_struct" -> StructModule.create();
            case "binascii" -> BinasciiModule.create();
            default -> throw new PyException("ModuleNotFoundError", new PyExcArgs(tuple("No module named '" + name + "'"), null, attrs("name", name)));
        };
    }

    private static Map<String,Object> attrs(String k, Object v) { LinkedHashMap<String,Object> m = new LinkedHashMap<>(); m.put(k, v); return m; }

    // ---- module objects created at run time ------------------------------------------------------

    /** {@code types.ModuleType(name, doc=None)}. */
    static Object moduleCall(List<Object> args, Map<Object,Object> kwargs) {
        for (Object k : kwargs.keySet()) if (!k.equals("name") && !k.equals("doc"))
            throw new PyException("TypeError", "module() got an unexpected keyword argument '" + k + "'");
        Object name = args.size() > 0 ? args.get(0) : kwargs.get("name");
        Object doc = args.size() > 1 ? args.get(1) : kwargs.get("doc");
        if (args.size() > 2) throw new PyException("TypeError", "module() takes at most 2 arguments (" + args.size() + " given)");
        if (!(name instanceof String s)) {
            if (name == null && args.isEmpty() && !kwargs.containsKey("name")) throw new PyException("TypeError", "module() missing required argument 'name' (pos 1)");
            throw new PyException("TypeError", "module() argument 'name' must be str, not " + PyRuntime.typeName(name));
        }
        PyModule m = new PyModule(s, "pyjvm315.runtime.ImportSupport");
        m.dict = new PyDict();
        m.dict.put("__name__", s); m.dict.put("__doc__", doc); m.dict.put("__package__", null);
        m.dict.put("__loader__", null); m.dict.put("__spec__", null);
        return m;
    }

    static String moduleRepr(PyModule m) {
        try {
            Map<Object,Object> d = m.dict != null ? m.dict : null;
            Object spec = d != null ? d.get("__spec__") : m.attrs.get("__spec__");
            Object file = d != null ? d.get("__file__") : m.attrs.get("__file__");
            Object name = d != null && d.get("__name__") != null ? d.get("__name__") : m.name;
            if (spec instanceof PyInstance && m.dict != null || spec instanceof PyInstance && m.attrs.containsKey("__spec__") && !m.className.startsWith("pyjvm315.imports")) {
                Object origin = PyRuntime.getattr(spec, "origin");
                Object loc = PyRuntime.getattr(spec, "has_location");
                String sname = PyRuntime.pyStr(PyRuntime.getattr(spec, "name"));
                if (origin == null) {
                    Object loader = PyRuntime.getattr(spec, "loader");
                    return loader == null ? "<module " + PyRuntime.pyRepr(sname) + ">" : "<module " + PyRuntime.pyRepr(sname) + " (" + PyRuntime.pyRepr(loader) + ")>";
                }
                return PyRuntime.truth(loc) ? "<module " + PyRuntime.pyRepr(sname) + " from " + PyRuntime.pyRepr(origin) + ">"
                    : "<module " + PyRuntime.pyRepr(sname) + " (" + PyRuntime.pyStr(origin) + ")>";
            }
            if (file instanceof String f) return "<module " + PyRuntime.pyRepr(name) + " from " + PyRuntime.pyRepr(f) + ">";
            if (d == null && NATIVE_NAMES.contains(m.name)) return "<module " + PyRuntime.pyRepr(m.name) + " (built-in)>";
            return "<module " + PyRuntime.pyRepr(name) + ">";
        } catch (RuntimeException e) {
            return "<module '" + m.name + "'>";
        }
    }

    static Object dictGetattr(PyModule m, String name) {
        PyDict d = m.dict;
        if (name.equals("__dict__")) return d;
        if (d.containsKey(name)) return d.get(name);
        Object hook = d.get("__getattr__");
        if (hook != null) return PyRuntime.callFunction(hook, new ArrayList<Object>(List.of(name)), new LinkedHashMap<Object,Object>());
        Object modName = d.get("__name__") == null ? m.name : d.get("__name__");
        Object spec = d.get("__spec__");
        boolean initializing = false;
        if (spec != null) {
            try { initializing = PyRuntime.truth(PyRuntime.getattr(spec, "_initializing")); } catch (PyException e) { initializing = false; }
        }
        throw new PyException("AttributeError", initializing
            ? "partially initialized module '" + modName + "' has no attribute '" + name + "' (most likely due to a circular import)"
            : "module '" + modName + "' has no attribute '" + name + "'");
    }

    static void dictDelattr(PyModule m, String name) {
        if (!m.dict.containsKey(name)) throw new PyException("AttributeError", "module '" + m.name + "' has no attribute '" + name + "'");
        m.dict.remove(name);
    }

    // ---- ImportError / ModuleNotFoundError -------------------------------------------------------

    static boolean isImportErrorType(String type) { return type.equals("ImportError") || type.equals("ModuleNotFoundError"); }

    static Object newImportError(String type, List<Object> args, Map<Object,Object> kwargs) {
        LinkedHashMap<String,Object> a = new LinkedHashMap<>();
        for (Map.Entry<Object,Object> e : kwargs.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!k.equals("name") && !k.equals("path") && !k.equals("name_from"))
                throw new PyException("TypeError", "'" + k + "' is an invalid keyword argument for " + type + "()");
            a.put(k, e.getValue());
        }
        PyTuple all = new PyTuple(); all.items.addAll(args);
        return new PyExceptionValue(type, new PyExcArgs(all, null, a));
    }

    private static final Set<String> OS_ERRORS = Set.of("OSError", "FileNotFoundError", "PermissionError", "FileExistsError",
        "IsADirectoryError", "NotADirectoryError", "BlockingIOError", "ChildProcessError", "ConnectionError", "BrokenPipeError",
        "ConnectionAbortedError", "ConnectionRefusedError", "ConnectionResetError", "InterruptedError", "ProcessLookupError", "TimeoutError");

    /** Attributes of ImportError and OSError values; {@code PyRuntime.MISSING} when {@code name} is not one of them. */
    static Object exceptionAttr(PyExceptionValue exc, String name) {
        PyExcArgs payload = exc.value instanceof PyExcArgs p ? p : null;
        if (payload != null && payload.attrs != null && payload.attrs.containsKey(name)) return payload.attrs.get(name);
        if (isImportErrorType(exc.typeName)) {
            if (name.equals("name") || name.equals("path") || name.equals("name_from")) return null;
            if (name.equals("msg")) {
                if (payload != null) return payload.args.items.size() == 1 ? payload.args.items.get(0) : null;
                return exc.value;
            }
        }
        if (OS_ERRORS.contains(exc.typeName)) {
            List<Object> items = payload != null ? payload.args.items : null;
            switch (name) {
                case "errno": return items != null && items.size() >= 2 ? items.get(0) : null;
                case "strerror": return items != null && items.size() >= 2 ? items.get(1) : null;
                case "filename": return items != null && items.size() >= 3 ? items.get(2) : null;
                case "filename2": return items != null && items.size() >= 5 ? items.get(4) : null;
                default: break;
            }
        }
        return PyRuntime.MISSING;
    }

    // ---- __import__ ------------------------------------------------------------------------------

    static Object importBuiltin(List<Object> args, Map<Object,Object> kwargs) {
        String[] names = {"name", "globals", "locals", "fromlist", "level"};
        for (Object k : kwargs.keySet()) if (!List.of(names).contains(k))
            throw new PyException("TypeError", "__import__() got an unexpected keyword argument '" + k + "'");
        if (args.size() > 5) throw new PyException("TypeError", "__import__() takes at most 5 arguments (" + args.size() + " given)");
        Object[] v = new Object[5];
        for (int i = 0; i < 5; i++) {
            if (i < args.size()) {
                if (kwargs.containsKey(names[i])) throw new PyException("TypeError", "argument for __import__() given by name ('" + names[i] + "') and position (" + (i + 1) + ")");
                v[i] = args.get(i);
            } else v[i] = kwargs.get(names[i]);
        }
        if (v[0] == null && args.isEmpty() && !kwargs.containsKey("name")) throw new PyException("TypeError", "__import__() missing required argument 'name' (pos 1)");
        if (!(v[0] instanceof String name)) throw new PyException("TypeError", "module name must be a string");
        long level = v[4] == null ? 0 : v[4] instanceof Number n ? n.longValue() : -1;
        if (v[4] != null && !(v[4] instanceof Number)) throw new PyException("TypeError", "'" + PyRuntime.typeName(v[4]) + "' object cannot be interpreted as an integer");
        if (level < 0) throw new PyException("ValueError", "level must be >= 0");
        if (name.isEmpty() && level == 0) throw new PyException("ValueError", "Empty module name");
        return doImport(name, v[1], v[2], v[3], level);
    }

    /** Entry point of compiled {@code import} statements that the compiler could not resolve statically. */
    public static Object importName(Object name, Object globals, Object moduleName, Object pkg, Object fromlist, Object level) {
        Object g = globals;
        if (g == null) {
            PyDict d = new PyDict();
            d.put("__name__", moduleName); d.put("__package__", pkg == null || "".equals(pkg) ? null : pkg);
            g = d;
        }
        Object from = null;
        if (fromlist != null) {
            PyTuple t = new PyTuple();
            for (String s : ((String) fromlist).split(",")) t.items.add(s);
            from = t;
        }
        return doImport((String) name, g, null, from, Long.parseLong((String) level));
    }

    private static boolean emptyFromlist(Object fromlist) {
        return fromlist == null || !PyRuntime.truth(fromlist);
    }

    private static Object doImport(String name, Object globals, Object locals, Object fromlist, long level) {
        if (level == 0 && emptyFromlist(fromlist)) {
            Object cached = fastLookup(name);
            if (cached != null) return cached;
        }
        if (!bootstrapped) {
            if (bootstrapping) {
                if (level == 0 && NATIVE_NAMES.contains(name)) return PyRuntime.importNative(name);
                throw new PyException("ImportError", "import of '" + name + "' during the importlib bootstrap is not possible");
            }
            ensureBootstrap();
        }
        ArrayList<Object> a = new ArrayList<>();
        a.add(name); a.add(globals); a.add(locals); a.add(fromlist == null ? new PyTuple() : fromlist); a.add(level);
        return PyRuntime.callFunction(PyRuntime.moduleGetattr(bootstrapModule, "__import__"), a, new LinkedHashMap<Object,Object>());
    }

    /** {@code import a.b.c} with the whole chain already in sys.modules: the top-level package. */
    private static Object fastLookup(String name) {
        int dot = name.indexOf('.');
        synchronized (MODULES) {
            Object m = MODULES.get(name);
            if (!(m instanceof PyModule)) return null;
            if (dot < 0) return m;
            Object top = MODULES.get(name.substring(0, dot));
            return top instanceof PyModule ? top : null;
        }
    }

    /** {@code __module__} of classes and functions defined by exec'd code: the {@code __name__} of the globals it runs in. */
    public static Object dynamicModuleName(Object globals, Object fallback) {
        if (globals instanceof Map<?,?> g && g.get("__name__") instanceof String n) return n;
        return fallback;
    }

    /** CPython's IMPORT_FROM: attribute first, then a submodule already present in sys.modules. */
    public static Object importFrom(Object module, Object nameObj) {
        String name = (String) nameObj;
        try {
            return PyRuntime.getattr(module, name);
        } catch (PyException e) {
            if (!e.typeName.equals("AttributeError")) throw e;
        }
        Object pkgName = null;
        try { pkgName = PyRuntime.getattr(module, "__name__"); } catch (PyException e) { if (!e.typeName.equals("AttributeError")) throw e; }
        if (pkgName instanceof String pn) {
            Object sub = MODULES.get(pn + "." + name);
            if (sub != null) return sub;
        }
        Object pkgPath = null; boolean initializing = false;
        if (pkgName instanceof String) {
            try { Object f = PyRuntime.getattr(module, "__file__"); if (f instanceof String) pkgPath = f; } catch (PyException e) { if (!e.typeName.equals("AttributeError")) throw e; }
            try {
                Object spec = PyRuntime.getattr(module, "__spec__");
                if (spec != null) initializing = PyRuntime.truth(PyRuntime.getattr(spec, "_initializing"));
            } catch (PyException e) { if (!e.typeName.equals("AttributeError")) throw e; }
        }
        String message;
        if (!(pkgName instanceof String)) message = "cannot import name " + PyRuntime.pyRepr(name);
        else if (initializing) message = "cannot import name " + PyRuntime.pyRepr(name) + " from partially initialized module " + PyRuntime.pyRepr(pkgName)
            + " (most likely due to a circular import) (" + (pkgPath == null ? "unknown location" : pkgPath) + ")";
        else message = "cannot import name " + PyRuntime.pyRepr(name) + " from " + PyRuntime.pyRepr(pkgName) + " (" + (pkgPath == null ? "unknown location" : pkgPath) + ")";
        LinkedHashMap<String,Object> a = new LinkedHashMap<>();
        a.put("name", pkgName); a.put("path", pkgPath); a.put("name_from", name);
        throw new PyException("ImportError", new PyExcArgs(tuple(message), null, a));
    }

    /** {@code from m import *} in code whose names are looked up dynamically (exec'd modules). */
    @SuppressWarnings("unchecked")
    public static Object importStar(Object module, Object namespace) {
        Map<Object,Object> ns = (Map<Object,Object>) namespace;
        Object all = null;
        try { all = PyRuntime.getattr(module, "__all__"); } catch (PyException e) { if (!e.typeName.equals("AttributeError")) throw e; }
        if (all != null) {
            for (Object n : PyRuntime.iterable(all)) ns.put(n, PyRuntime.getattr(module, n));
            return null;
        }
        Object dict = PyRuntime.getattr(module, "__dict__");
        for (Object k : new ArrayList<>(((Map<Object,Object>) dict).keySet()))
            if (k instanceof String s && !s.startsWith("_")) ns.put(s, ((Map<Object,Object>) dict).get(s));
        return null;
    }

    // ---- bootstrap -------------------------------------------------------------------------------

    static String stdlibDir() {
        String dir = System.getenv("PYJVM_STDLIB");
        if (dir == null || dir.isEmpty()) dir = System.getProperty("pyjvm.stdlib");
        return dir == null || dir.isEmpty() ? null : dir;
    }

    private static synchronized void ensureBootstrap() {
        if (bootstrapped || bootstrapping) return;
        String stdlib = stdlibDir();
        if (stdlib == null)
            throw new PyException("ImportError", "run-time imports run CPython's importlib: set PYJVM_STDLIB to a CPython 3.13+ Lib directory "
                + "(the directory containing importlib/_bootstrap.py); the sources are read, not copied");
        bootstrapping = true;
        try {
            for (String n : BOOT_ORDER) PyRuntime.importNative(n);
            PyModule sys = (PyModule) PyRuntime.importNative("sys");
            PyModule imp = (PyModule) PyRuntime.importNative("_imp");
            PyModule boot = execSource("_frozen_importlib", Path.of(stdlib, "importlib", "_bootstrap.py"), "<frozen importlib._bootstrap>");
            execSource("_frozen_importlib_external", Path.of(stdlib, "importlib", "_bootstrap_external.py"), "<frozen importlib._bootstrap_external>");
            call(boot, "_install", sys, imp);
            call(boot, "_install_external_importers");
            bootstrapModule = boot;
            bootstrapped = true;
        } finally {
            bootstrapping = false;
            if (!bootstrapped) {
                MODULES.remove("_frozen_importlib"); MODULES.remove("_frozen_importlib_external");
            }
        }
    }

    private static Object call(PyModule m, String fn, Object... args) {
        ArrayList<Object> a = new ArrayList<>(List.of(args));
        return PyRuntime.callFunction(PyRuntime.moduleGetattr(m, fn), a, new LinkedHashMap<Object,Object>());
    }

    private static PyModule execSource(String name, Path file, String filename) {
        String text;
        try { text = Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new PyException("ImportError", "cannot read " + file + ": " + e.getMessage()); }
        PyModule m = (PyModule) moduleCall(new ArrayList<Object>(List.of(name)), new LinkedHashMap<Object,Object>());
        register(name, m);
        PyCode code = (PyCode) ExecSupport.compileBuiltin(new ArrayList<Object>(List.of(text, filename, "exec")), new LinkedHashMap<Object,Object>());
        ExecSupport.run("exec", code, m.dict, m.dict, "exec");
        return m;
    }

    // ---- sys -------------------------------------------------------------------------------------

    private static PyInstance namespaceObject(String type, LinkedHashMap<String,Object> fields) {
        PyClass cls = define("sys", type, null);
        finish(cls);
        PyInstance o = new PyInstance(cls);
        o.fields.putAll(fields);
        return o;
    }

    /** The import-related parts of the sys module. Called once when sys is created. */
    static void installSys(PyModule sys) {
        sys.attrs.put("modules", MODULES);
        ArrayList<Object> path = new ArrayList<>();
        String script = NativeModules.argv.length > 0 ? NativeModules.argv[0] : "";
        Path dir = script.contains("/") ? Path.of(script).toAbsolutePath().getParent() : Path.of("").toAbsolutePath();
        path.add(dir == null ? Path.of("").toAbsolutePath().toString() : dir.toString());
        String pp = System.getenv("PYTHONPATH");
        if (pp != null) for (String p : pp.split(java.io.File.pathSeparator)) if (!p.isEmpty()) path.add(p);
        String stdlib = stdlibDir();
        if (stdlib != null) path.add(stdlib);
        sys.attrs.put("path", path);
        sys.attrs.put("meta_path", new ArrayList<Object>());
        sys.attrs.put("path_hooks", new ArrayList<Object>());
        sys.attrs.put("path_importer_cache", new PyDict());
        sys.attrs.put("dont_write_bytecode", true);
        sys.attrs.put("pycache_prefix", null);
        sys.attrs.put("warnoptions", new ArrayList<Object>());
        sys.attrs.put("_xoptions", new PyDict());
        sys.attrs.put("executable", System.getProperty("pyjvm.executable", "pyjvm"));
        if (stdlib != null) sys.attrs.put("_stdlib_dir", stdlib);
        String os = System.getProperty("os.name", "").toLowerCase();
        sys.attrs.put("platform", os.contains("win") ? "win32" : os.contains("mac") ? "darwin" : "linux");
        TreeSet<String> builtin = new TreeSet<>(NATIVE_NAMES);
        sys.attrs.put("builtin_module_names", tupleOf(new ArrayList<Object>(builtin)));
        LinkedHashMap<String,Object> flags = new LinkedHashMap<>();
        for (String f : new String[]{"debug", "inspect", "interactive", "optimize", "dont_write_bytecode", "no_user_site", "no_site",
                "ignore_environment", "verbose", "bytes_warning", "quiet", "hash_randomization", "isolated", "dev_mode", "safe_path", "int_max_str_digits", "warn_default_encoding", "gil", "thread_inherit_context", "context_aware_warnings"})
            flags.put(f, f.equals("dont_write_bytecode") ? 1L : f.equals("int_max_str_digits") ? 4300L : 0L);
        sys.attrs.put("flags", namespaceObject("flags", flags));
        LinkedHashMap<String,Object> impl = new LinkedHashMap<>();
        impl.put("name", "pyjvm"); impl.put("cache_tag", null);
        impl.put("version", sys.attrs.get("version_info"));
        impl.put("hexversion", ((long) LangVersion.MAJOR << 24) | ((long) LangVersion.MINOR << 16) | 0xf0L);
        sys.attrs.put("implementation", namespaceObject("implementation", impl));
        fn(sys, "audit", (a, k) -> null);
        fn(sys, "getfilesystemencoding", (a, k) -> "utf-8");
        fn(sys, "getfilesystemencodeerrors", (a, k) -> "surrogateescape");
        if (!sys.attrs.containsKey("getdefaultencoding")) fn(sys, "getdefaultencoding", (a, k) -> "utf-8");
        fn(sys, "intern", (a, k) -> a.get(0));
        sys.lazyAttrs = attr -> {
            if (!bootstrapped && !bootstrapping && stdlibDir() != null && (attr.equals("meta_path") || attr.equals("path_hooks") || attr.equals("path_importer_cache"))) {
                sys.lazyAttrs = null;
                ensureBootstrap();
            }
        };
    }
}
