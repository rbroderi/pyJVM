// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.finish;
import static pyjvm315.runtime.NativeTypes.method;
import static pyjvm315.runtime.NativeTypes.methodVarKw;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The parts of the sys module beyond imports, streams and frames: structseq values (version_info, flags,
 * float_info, ...), the interpreter limits and hooks, audit hooks, sizeof and the CPython-version dependent stubs.
 */
final class SysExtras {
    private SysExtras() {}

    // ---------- structseq: a tuple subclass with named fields ----------

    private static final Map<String, PyClass> SEQ_CLASSES = new java.util.concurrent.ConcurrentHashMap<>();

    /** A structseq class: a tuple of the first {@code visible} names, the remaining names being attributes only. */
    private static PyClass seqClass(String name, String[] names, int visible) {
        return SEQ_CLASSES.computeIfAbsent(name, n -> {
            PyClass cls = (PyClass) PyRuntime.classCreate(n, "sys");
            PyRuntime.classAddBase(cls, PyRuntime.builtinType("tuple"));
            method(cls, SysExtras.class, "__repr__", "seqRepr", "self");
            PyTuple match = new PyTuple();
            for (int i = 0; i < visible; i++) match.items.add(names[i]);
            PyRuntime.classAddAttr(cls, "n_sequence_fields", (long) visible);
            PyRuntime.classAddAttr(cls, "n_fields", (long) names.length);
            PyRuntime.classAddAttr(cls, "n_unnamed_fields", 0L);
            PyRuntime.classAddAttr(cls, "__match_args__", match);
            finish(cls);
            return cls;
        });
    }

    static PyInstance seq(String name, String[] names, int visible, Object... values) {
        PyClass cls = seqClass(name, names, visible);
        PyInstance o = PyInstance.create(cls);
        PyTuple t = new PyTuple();
        for (int i = 0; i < visible; i++) t.items.add(values[i]);
        o.payload = t;
        for (int i = 0; i < names.length; i++) o.fields.put(names[i], values[i]);
        return o;
    }

    static Object seqRepr(Object self) {
        PyInstance o = (PyInstance) self;
        PyTuple t = (PyTuple) o.payload;
        PyTuple match = (PyTuple) o.cls.attrs.get("__match_args__");
        StringBuilder sb = new StringBuilder("sys.").append(o.cls.name).append('(');
        for (int i = 0; i < t.items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(match.items.get(i)).append('=').append(PyRuntime.pyRepr(t.items.get(i)));
        }
        return sb.append(')').toString();
    }

    private static final String[] VERSION_FIELDS = {"major", "minor", "micro", "releaselevel", "serial"};
    private static final String[] FLOAT_FIELDS = {"max", "max_exp", "max_10_exp", "min", "min_exp", "min_10_exp", "dig", "mant_dig", "epsilon", "radix", "rounds"};
    private static final String[] HASH_FIELDS = {"width", "modulus", "inf", "nan", "imag", "algorithm", "hash_bits", "seed_bits", "cutoff"};
    private static final String[] INT_FIELDS = {"bits_per_digit", "sizeof_digit", "default_max_str_digits", "str_digits_check_threshold"};
    private static final String[] THREAD_FIELDS = {"name", "lock", "version"};
    private static final String[] HOOK_FIELDS = {"firstiter", "finalizer"};

    static PyInstance versionInfo() {
        return seq("version_info", VERSION_FIELDS, 5, (long) LangVersion.MAJOR, (long) LangVersion.MINOR, 0L, "final", 0L);
    }

    static long hexversion() { return ((long) LangVersion.MAJOR << 24) | ((long) LangVersion.MINOR << 16) | 0xf0L; }

    static PyInstance flags(boolean noWrite) {
        List<String> names = new ArrayList<>(List.of("debug", "inspect", "interactive", "optimize", "dont_write_bytecode", "no_user_site", "no_site",
                "ignore_environment", "verbose", "bytes_warning", "quiet", "hash_randomization", "isolated", "dev_mode", "utf8_mode",
                "warn_default_encoding", "safe_path", "int_max_str_digits"));
        int visible = names.size();
        names.add("gil");
        if (LangVersion.atLeast(3, 14)) { names.add("thread_inherit_context"); names.add("context_aware_warnings"); }
        Object[] values = new Object[names.size()];
        for (int i = 0; i < values.length; i++) {
            String f = names.get(i);
            values[i] = switch (f) {
                case "dont_write_bytecode" -> noWrite ? 1L : 0L;
                case "hash_randomization", "utf8_mode", "gil" -> 1L;
                case "int_max_str_digits" -> 4300L;
                case "dev_mode", "safe_path" -> (Object) false;
                default -> 0L;
            };
        }
        return seq("flags", names.toArray(new String[0]), visible, values);
    }

    // ---------- types.SimpleNamespace ----------

    private static PyClass nsClass;

    /** The class behind types.SimpleNamespace (types.py takes it from type(sys.implementation)). */
    static synchronized PyClass namespaceClass() {
        if (nsClass == null) {
            PyClass cls = NativeTypes.define("types", "SimpleNamespace", null);
            methodVarKw(cls, SysExtras.class, "__init__", "nsInit", "self", "args", "kwargs", new PyDict());
            method(cls, SysExtras.class, "__repr__", "nsRepr", "self");
            method(cls, SysExtras.class, "__eq__", "nsEq", "self,other");
            finish(cls);
            nsClass = cls;
        }
        return nsClass;
    }

    static PyInstance namespace(Map<String, Object> fields) {
        PyInstance o = PyInstance.create(namespaceClass());
        o.fields.putAll(fields);
        return o;
    }

    static Object nsInit(Object self, Object args, Object kwargs) {
        if (!((PyTuple) args).items.isEmpty()) throw new PyException("TypeError", "no positional arguments expected");
        for (Map.Entry<?, ?> e : ((PyDict) kwargs).entrySet()) {
            if (!(e.getKey() instanceof String key)) throw new PyException("TypeError", "keywords must be strings");
            ((PyInstance) self).fields.put(key, e.getValue());
        }
        return null;
    }

    static Object nsRepr(Object self) {
        StringBuilder sb = new StringBuilder("namespace(");
        boolean first = true;
        for (Map.Entry<String, Object> e : ((PyInstance) self).fields.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=').append(PyRuntime.pyRepr(e.getValue()));
        }
        return sb.append(')').toString();
    }

    static Object nsEq(Object self, Object other) {
        if (!(other instanceof PyInstance o) || o.cls != ((PyInstance) self).cls) return PyRuntime.NOT_IMPLEMENTED;
        return ((PyInstance) self).fields.equals(o.fields);
    }

    // ---------- limits and settings ----------

    private static volatile long recursionLimit = 1000;
    private static volatile long dlopenFlags = 2;
    private static volatile long originDepth = 0;
    /** sys.get_int_max_str_digits(); 0 disables the limit. */
    static volatile long intMaxStrDigits = 4300;
    private static volatile Object asyncFirstiter, asyncFinalizer;
    private static final CopyOnWriteArrayList<Object> AUDIT_HOOKS = new CopyOnWriteArrayList<>();

    /** Enforce sys.set_int_max_str_digits for a string to int conversion of {@code digits} decimal digits. */
    static void checkIntStrDigits(int digits) {
        long limit = intMaxStrDigits;
        if (limit > 0 && digits > limit)
            throw new PyException("ValueError", "Exceeds the limit (" + limit + " digits) for integer string conversion: value has " + digits
                + " digits; use sys.set_int_max_str_digits() to increase the limit");
    }

    /** Raise an audit event (also usable from the runtime): calls every hook added with sys.addaudithook. */
    static void audit(String event, Object... args) {
        if (AUDIT_HOOKS.isEmpty()) return;
        PyTuple t = new PyTuple();
        for (Object a : args) t.items.add(a);
        for (Object hook : AUDIT_HOOKS)
            PyRuntime.callFunction(hook, new ArrayList<Object>(List.of(event, t)), new LinkedHashMap<Object, Object>());
    }

    private static long intArg(Object v) {
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof Long l) return l;
        if (v instanceof BigInteger) throw new PyException("OverflowError", "Python int too large to convert to C int");
        throw new PyException("TypeError", "'" + PyRuntime.typeName(v) + "' object cannot be interpreted as an integer");
    }

    private static void fn(PyModule m, String name, NativeFunction.Body body) { m.attrs.put(name, new NativeFunction(name, body)); }

    private static void noArgs(String name, List<Object> a) {
        if (!a.isEmpty()) throw new PyException("TypeError", name + "() takes no arguments (" + a.size() + " given)");
    }

    static Object sizeof(Object o) {
        if (o == null) return 16L;
        if (o instanceof Boolean || o instanceof Long) return 28L;
        if (o instanceof BigInteger b) return 24L + 4L * ((b.abs().bitLength() + 29) / 30);
        if (o instanceof Double) return 24L;
        if (o instanceof String s) return 49L + s.length();
        if (o instanceof PyTuple t) return 40L + 8L * t.items.size();
        if (o instanceof List<?> l) return 56L + 8L * l.size();
        if (o instanceof PyDict d) return 64L + 32L * d.size();
        if (o instanceof PyBytes b) return 33L + b.data.length;
        if (o instanceof java.util.Set<?> s) return 216L + 16L * s.size();
        return 16L;
    }

    private static final String STDLIB_NAMES =
        "__future__ _abc _aix_support _android_support _apple_support _ast _ast_unparse _asyncio _bisect _blake2 _bz2 " +
        "_codecs _codecs_cn _codecs_hk _codecs_iso2022 _codecs_jp _codecs_kr _codecs_tw _collections _collections_abc " +
        "_colorize _compat_pickle _contextvars _csv _ctypes _curses _curses_panel _datetime _dbm _decimal _elementtree " +
        "_frozen_importlib _frozen_importlib_external _functools _gdbm _hashlib _heapq _hmac _imp _interpchannels " +
        "_interpqueues _interpreters _io _ios_support _json _locale _lsprof _lzma _markupbase _math_integer _md5 " +
        "_multibytecodec _multiprocessing _opcode _opcode_metadata _operator _osx_support _overlapped _pickle " +
        "_posixshmem _posixsubprocess _py_abc _py_warnings _pydatetime _pydecimal _pyio _pylong _pyrepl _queue _random " +
        "_remote_debugging _scproxy _sha1 _sha2 _sha3 _signal _sitebuiltins _socket _sqlite3 _sre _ssl _stat " +
        "_statistics _string _strptime _struct _suggestions _symtable _sysconfig _thread _threading_local _tkinter " +
        "_tokenize _tracemalloc _types _typing _uuid _warnings _weakref _weakrefset _winapi _wmi _zoneinfo _zstd abc " +
        "annotationlib antigravity argparse array ast asyncio atexit base64 bdb binascii bisect builtins bz2 cProfile " +
        "calendar cmath cmd code codecs codeop collections colorsys compileall compression concurrent configparser " +
        "contextlib contextvars copy copyreg csv ctypes curses dataclasses datetime dbm decimal difflib dis doctest " +
        "email encodings ensurepip enum errno faulthandler fcntl filecmp fileinput fnmatch fractions ftplib functools " +
        "gc genericpath getopt getpass gettext glob graphlib grp gzip hashlib heapq hmac html http idlelib imaplib " +
        "importlib inspect io ipaddress itertools json keyword linecache locale logging lzma mailbox marshal math " +
        "mimetypes mmap modulefinder msvcrt multiprocessing netrc nt ntpath nturl2path numbers opcode operator optparse " +
        "os pathlib pdb pickle pickletools pkgutil platform plistlib poplib posix posixpath pprint profile profiling " +
        "pstats pty pwd py_compile pyclbr pydoc pydoc_data pyexpat queue quopri random re readline reprlib resource " +
        "rlcompleter runpy sched secrets select selectors shelve shlex shutil signal site smtplib socket socketserver " +
        "sqlite3 ssl stat statistics string stringprep struct subprocess symtable sys sysconfig syslog tabnanny tarfile " +
        "tempfile termios textwrap this threading time timeit tkinter token tokenize tomllib trace traceback " +
        "tracemalloc tty turtle turtledemo types typing unicodedata unittest urllib uuid venv warnings wave weakref " +
        "webbrowser winreg winsound wsgiref xml xmlrpc zipapp zipfile zipimport zlib zoneinfo ";

    static void install(PyModule m) {
        PyRuntime.importNative("builtins");
        Object stdlibDir = m.attrs.get("_stdlib_dir");
        String prefix = System.getProperty("pyjvm.prefix");
        if (prefix == null && stdlibDir instanceof String s && java.nio.file.Path.of(s).getParent() != null) prefix = java.nio.file.Path.of(s).getParent().toString();
        if (prefix == null) prefix = "/usr";
        for (String n : new String[]{"prefix", "exec_prefix", "base_prefix", "base_exec_prefix"}) m.attrs.put(n, prefix);
        m.attrs.put("platlibdir", "lib");
        m.attrs.put("abiflags", "");
        m.attrs.put("float_repr_style", "short");
        m.attrs.put("api_version", 1013L);
        m.attrs.put("hexversion", hexversion());
        m.attrs.put("copyright", "Copyright (c) 2001 Python Software Foundation.\nAll Rights Reserved.\n\nCopyright (c) pyJVM contributors.");
        m.attrs.put("version", LangVersion.MAJOR + "." + LangVersion.MINOR + ".0 (main, Jan  1 2026, 00:00:00) [pyJVM]");
        PyInstance vi = versionInfo();
        m.attrs.put("version_info", vi);
        Object base = m.attrs.get("executable");
        m.attrs.put("_base_executable", base == null ? "pyjvm" : base);
        m.attrs.put("_framework", "");
        m.attrs.put("_home", null);
        m.attrs.put("_git", tuple("pyJVM", "", ""));
        m.attrs.put("__name__", "sys");
        m.attrs.put("__package__", "");
        m.attrs.put("__doc__", "This module provides access to some objects used or maintained by the\ninterpreter and to functions that interact strongly with the interpreter.");
        m.attrs.put("__loader__", null);
        m.attrs.put("__spec__", null);
        ArrayList<Object> orig = new ArrayList<>();
        orig.add(System.getProperty("pyjvm.executable", "pyjvm"));
        for (String a : NativeModules.argv) orig.add(a);
        m.attrs.put("orig_argv", orig);
        boolean noWrite = Boolean.TRUE.equals(m.attrs.get("dont_write_bytecode"));
        m.attrs.put("flags", flags(noWrite));
        ArrayList<Object> names = new ArrayList<>(List.of(STDLIB_NAMES.trim().split(" ")));
        if (!LangVersion.atLeast(3, 15)) names.addAll(List.of("_compression", "sre_compile", "sre_constants", "sre_parse"));
        m.attrs.put("stdlib_module_names", new PyFrozenSet(names));

        m.attrs.put("float_info", seq("float_info", FLOAT_FIELDS, 11, Double.MAX_VALUE, 1024L, 308L, Double.MIN_NORMAL, -1021L, -307L, 15L, 53L, Math.ulp(1.0), 2L, 1L));
        m.attrs.put("hash_info", seq("hash_info", HASH_FIELDS, 9, 64L, 2305843009213693951L, 314159L, 0L, 1000003L, "siphash13", 64L, 128L, 0L));
        m.attrs.put("int_info", seq("int_info", INT_FIELDS, 4, 30L, 4L, 4300L, 640L));
        m.attrs.put("thread_info", seq("thread_info", THREAD_FIELDS, 3, "pthread", LangVersion.atLeast(3, 14) ? "pymutex" : "semaphore", "NPTL 2.39"));

        // implementation: version and hexversion follow the interpreter
        if (m.attrs.get("implementation") instanceof PyInstance old) {
            LinkedHashMap<String, Object> f = new LinkedHashMap<>(old.fields);
            PyInstance pi = namespace(f);
            m.attrs.put("implementation", pi);
            pi.fields.put("version", vi);
            pi.fields.put("hexversion", hexversion());
            if (LangVersion.atLeast(3, 14)) pi.fields.put("supports_isolated_interpreters", false);
        }

        fn(m, "getrecursionlimit", (a, k) -> { noArgs("getrecursionlimit", a); return recursionLimit; });
        fn(m, "setrecursionlimit", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", "setrecursionlimit() takes exactly one argument (" + a.size() + " given)");
            long n = intArg(a.get(0));
            if (n < 1) throw new PyException("ValueError", "recursion limit must be greater or equal than 1");
            recursionLimit = n;
            return null;
        });
        fn(m, "getrefcount", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", "getrefcount() takes exactly one argument (" + a.size() + " given)");
            return 2L;
        });
        fn(m, "is_finalizing", (a, k) -> false);
        fn(m, "getallocatedblocks", (a, k) -> Math.max(1L, (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 64));
        fn(m, "getunicodeinternedsize", (a, k) -> 0L);
        fn(m, "_is_interned", (a, k) -> {
            if (a.size() != 1 || !(a.get(0) instanceof String s)) throw new PyException("TypeError", "_is_interned() argument must be str");
            return s == s.intern();
        });
        fn(m, "intern", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", "intern() takes exactly one argument (" + a.size() + " given)");
            if (!(a.get(0) instanceof String s)) throw new PyException("TypeError", "intern() argument must be str, not " + PyRuntime.typeName(a.get(0)));
            return s.intern();
        });
        fn(m, "getsizeof", (a, k) -> {
            Object dflt = k.get("default");
            boolean hasDefault = k.containsKey("default");
            if (a.size() > 2 || a.isEmpty() && !k.containsKey("object")) throw new PyException("TypeError", "getsizeof expected at most 2 arguments, got " + a.size());
            if (a.size() == 2) { dflt = a.get(1); hasDefault = true; }
            Object o = a.isEmpty() ? k.get("object") : a.get(0);
            Object sz;
            try {
                Object meth = PyRuntime.getattr(o, "__sizeof__");
                sz = PyRuntime.callFunction(meth, new ArrayList<Object>(), new LinkedHashMap<Object, Object>());
                if (!(sz instanceof Long) && !(sz instanceof BigInteger) && !(sz instanceof Boolean))
                    throw new PyException("TypeError", "'" + PyRuntime.typeName(sz) + "' object cannot be interpreted as an integer");
                if (PyRuntime.bigInt(sz).signum() < 0) throw new PyException("ValueError", "__sizeof__() should return >= 0");
                if (o instanceof PyInstance) sz = PyRuntime.bigInt(sz).longValue() + 16L;
            } catch (PyException e) {
                if (e.typeName.equals("AttributeError")) sz = sizeof(o);
                else if (hasDefault && e.typeName.equals("TypeError")) return dflt;
                else throw e;
            }
            return sz;
        });
        fn(m, "call_tracing", (a, k) -> {
            if (a.size() != 2) throw new PyException("TypeError", "call_tracing expected 2 arguments, got " + a.size());
            if (!(a.get(1) instanceof PyTuple t)) throw new PyException("TypeError", "call_tracing(): argument 'args' must be tuple, not " + PyRuntime.typeName(a.get(1)));
            return PyRuntime.callFunction(a.get(0), new ArrayList<Object>(t.items), new LinkedHashMap<Object, Object>());
        });
        fn(m, "getdlopenflags", (a, k) -> dlopenFlags);
        fn(m, "setdlopenflags", (a, k) -> { dlopenFlags = intArg(a.get(0)); return null; });
        fn(m, "get_coroutine_origin_tracking_depth", (a, k) -> originDepth);
        fn(m, "set_coroutine_origin_tracking_depth", (a, k) -> {
            long d = intArg(a.isEmpty() ? k.get("depth") : a.get(0));
            if (d < 0) throw new PyException("ValueError", "depth must be >= 0");
            originDepth = d;
            return null;
        });
        fn(m, "get_int_max_str_digits", (a, k) -> intMaxStrDigits);
        fn(m, "set_int_max_str_digits", (a, k) -> {
            long d = intArg(a.isEmpty() ? k.get("maxdigits") : a.get(0));
            if (d != 0 && d < 640) throw new PyException("ValueError", "maxdigits must be >= 640 or 0 for unlimited");
            intMaxStrDigits = d;
            return null;
        });
        fn(m, "get_asyncgen_hooks", (a, k) -> seq("asyncgen_hooks", HOOK_FIELDS, 2, asyncFirstiter, asyncFinalizer));
        fn(m, "set_asyncgen_hooks", (a, k) -> {
            Object first = a.size() > 0 ? a.get(0) : k.containsKey("firstiter") ? k.get("firstiter") : asyncFirstiter;
            Object fin = a.size() > 1 ? a.get(1) : k.containsKey("finalizer") ? k.get("finalizer") : asyncFinalizer;
            if (first != null && !Boolean.TRUE.equals(PyRuntime.callable_(first))) throw new PyException("TypeError", "callable firstiter expected, got " + PyRuntime.typeName(first));
            if (fin != null && !Boolean.TRUE.equals(PyRuntime.callable_(fin))) throw new PyException("TypeError", "callable finalizer expected, got " + PyRuntime.typeName(fin));
            asyncFirstiter = first;
            asyncFinalizer = fin;
            return null;
        });
        fn(m, "audit", (a, k) -> {
            if (a.isEmpty()) throw new PyException("TypeError", "audit() missing 1 required positional argument: 'event'");
            if (!(a.get(0) instanceof String ev)) throw new PyException("TypeError", "audit() argument 'event' must be str, not " + PyRuntime.typeName(a.get(0)));
            audit(ev, a.subList(1, a.size()).toArray());
            return null;
        });
        fn(m, "addaudithook", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", "addaudithook() takes exactly one argument (" + a.size() + " given)");
            AUDIT_HOOKS.add(a.get(0));
            return null;
        });
        fn(m, "_clear_type_cache", (a, k) -> null);
        fn(m, "_clear_internal_caches", (a, k) -> null);
        fn(m, "_debugmallocstats", (a, k) -> null);
        fn(m, "_baserepl", (a, k) -> { throw new PyException("RuntimeError", "the interactive REPL is not available"); });
        fn(m, "_get_cpu_count_config", (a, k) -> -1L);
        fn(m, "_getframemodulename", (a, k) -> {
            Object depth = a.isEmpty() ? k.get("depth") : a.get(0);
            Object frame;
            try { frame = PyFrameSupport.getFrame(depth == null ? 0L : depth); } catch (PyException e) { return null; }
            Object globals = PyRuntime.getattr(frame, "f_globals");
            Object name = globals instanceof Map<?, ?> g ? g.get("__name__") : null;
            return name instanceof String ? name : null;
        });
        fn(m, "_current_exceptions", (a, k) -> {
            PyDict out = new PyDict();
            Object t = BuiltinsExtra.currentHandled();
            out.put(Thread.currentThread().threadId(), t == null ? null : PyRuntime.exceptionInstance(t));
            return out;
        });
        fn(m, "activate_stack_trampoline", (a, k) -> { throw new PyException("ValueError", "perf trampoline is not supported on this platform"); });
        fn(m, "deactivate_stack_trampoline", (a, k) -> null);
        fn(m, "is_stack_trampoline_active", (a, k) -> false);
        WeakrefDelivery.installHook(m);
        if (LangVersion.atLeast(3, 14)) {
            fn(m, "is_remote_debug_enabled", (a, k) -> false);
            fn(m, "remote_exec", (a, k) -> { throw new PyException("RuntimeError", "Remote debugging is not supported by pyJVM"); });
            fn(m, "_is_immortal", (a, k) -> a.get(0) == null || a.get(0) instanceof Boolean);
            PyModule jit = new PyModule("sys._jit", "pyjvm315.runtime.SysExtras");
            fn(jit, "is_available", (a, k) -> false);
            fn(jit, "is_enabled", (a, k) -> false);
            fn(jit, "is_active", (a, k) -> false);
            m.attrs.put("_jit", jit);
        }
        if (LangVersion.atLeast(3, 15)) {
            LinkedHashMap<String, Object> abi = new LinkedHashMap<>();
            abi.put("pointer_bits", 64L); abi.put("free_threaded", false); abi.put("debug", false); abi.put("byteorder", "little");
            m.attrs.put("abi_info", namespace(abi));
            fn(m, "_dump_tracelets", (a, k) -> null);
        }
    }

    private static PyTuple tuple(Object... items) {
        PyTuple t = new PyTuple();
        for (Object o : items) t.items.add(o);
        return t;
    }
}
