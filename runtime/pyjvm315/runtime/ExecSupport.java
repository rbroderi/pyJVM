// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * exec(), eval() and compile(): source is compiled to a class whose module-level names resolve
 * through the supplied globals/locals mappings, defined as a hidden class (unloadable when the
 * code and everything it created become unreachable) and run via {@code run(globals, locals)}.
 */
final class ExecSupport {
    private ExecSupport() {}

    /** Where class files for dynamic code come from. */
    interface CompilerBackend {
        /**
         * @param source text, or (compile of an AST) the Python-level ast tree
         * @param optimize 0 keeps asserts, __debug__ and docstrings; 1 drops asserts; 2 also docstrings
         * @param featureMinor grammar level for parsing (Integer.MAX_VALUE: current)
         * @return class bytes for module code in {@code mode} ("exec", "eval" or "single").
         */
        byte[] compile(Object source, String filename, String mode, int optimize, int featureMinor);
    }

    private static volatile CompilerBackend backend;
    static void setBackend(CompilerBackend replacement) { backend = replacement; }
    /** The self-hosted compiler when its classes are on the classpath (PYJVM_COMPILER=host forces the host interpreter). */
    private static CompilerBackend backend() {
        CompilerBackend b = backend;
        if (b == null) {
            boolean forceHost = "host".equals(System.getenv("PYJVM_COMPILER"));
            backend = b = !forceHost && SelfHostedBackend.available() ? new SelfHostedBackend() : new HostPythonBackend();
        }
        return b;
    }

    /**
     * The compiler running in-process: pyJVM's own compiler, compiled by itself (roadmap P1-07), is loaded from
     * the classpath like any other module and asked for the class file. No host Python is involved.
     */
    static final class SelfHostedBackend implements CompilerBackend {
        static final String MODULE_CLASS = "pyjvm315.imports.pyjvm315.compiler";
        private final ConcurrentHashMap<String,byte[]> cache = new ConcurrentHashMap<>();

        static boolean available() {
            try { Class.forName(MODULE_CLASS); return true; }
            catch (ClassNotFoundException | LinkageError e) { return false; }
        }

        @Override public byte[] compile(Object source, String filename, String mode, int optimize, int featureMinor) {
            String key = source instanceof String ? mode + '\0' + filename + '\0' + optimize + '\0' + featureMinor + '\0' + source : null;
            byte[] cached = key == null ? null : cache.get(key);
            if (cached != null) return cached;
            Object[] result = new Object[1]; Throwable[] failure = new Throwable[1];
            // the compiler recurses deeply on nested source, so it gets a thread with a large stack
            Thread worker = new Thread(null, () -> {
                try { result[0] = compileInProcess(source, filename, mode, optimize, featureMinor); } catch (Throwable t) { failure[0] = t; }
            }, "pyjvm-compiler", 1L << 30);
            worker.start();
            try { worker.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PyException("RuntimeError", "interrupted while compiling"); }
            if (failure[0] != null) throw translate(failure[0], source instanceof String s ? s : null, filename);
            byte[] data = ((PyBytes) result[0]).data;
            if (key != null) cache.put(key, data);
            return data;
        }

        private static Object compileInProcess(Object source, String filename, String mode, int optimize, int featureMinor) {
            PyModule compiler = (PyModule) PyRuntime.importModule("pyjvm315.compiler", MODULE_CLASS);
            Object compileSource = PyRuntime.moduleGetattr(compiler, "compile_source");
            java.util.LinkedHashMap<Object,Object> kwargs = new java.util.LinkedHashMap<>();
            kwargs.put("class_name", "pyjvm315.runtime.PyExec"); kwargs.put("filename", filename);
            kwargs.put("dynamic", true); kwargs.put("mode", mode); kwargs.put("optimize", (long) optimize);
            if (featureMinor != Integer.MAX_VALUE) {
                PyTuple level = new PyTuple(); level.items.add(3L); level.items.add((long) featureMinor);
                kwargs.put("feature_version_override", level);
            }
            Object text = source;
            if (!(source instanceof String)) { kwargs.put("tree", source); text = ""; }
            return PyRuntime.callFunction(compileSource, new java.util.ArrayList<Object>(java.util.List.of(text)), kwargs);
        }

        /** The compiler's own exceptions become the errors Python code expects from compile(). */
        private static RuntimeException translate(Throwable t, String source, String filename) {
            if (t instanceof PyException p) {
                String message = PyRuntime.pyStr(p.value instanceof PyInstance ? p.value : p.value == null ? "" : p.value);
                switch (p.typeName) {
                    case "CompileError": return new PyException("NotImplementedError", message);
                    case "SyntaxError", "IndentationError", "TabError":
                        if (p.syntax != null) return p;
                        return SyntaxInfo.error(p.typeName, message, filename, source, 0, 0);
                    case "FrameError":
                        return message.contains("1..65535 bytes")
                            ? new PyException("NotImplementedError", "compiled code exceeds the JVM 64KB method limit (method splitting is not implemented)")
                            : new PyException("RuntimeError", "FrameError: " + message);
                    default: return new PyException("RuntimeError", "compiler failure: " + p.typeName + ": " + message);
                }
            }
            if (t instanceof RuntimeException r) return r;
            return new PyException("RuntimeError", "compiler failure: " + t);
        }
    }

    /**
     * Interim backend: runs the Python compiler in a host interpreter. Needs PYJVM_HOME (the directory
     * holding the pyjvm315 package) and optionally PYJVM_PYTHON. Replaced by the in-process,
     * self-hosted compiler (roadmap P1-07).
     */
    static final class HostPythonBackend implements CompilerBackend {
        private static final String BOOT =
            "import sys;sys.path.insert(0,sys.argv[1]);from pyjvm315.exec_compile import main;raise SystemExit(main(sys.argv[2:]))";
        private final ConcurrentHashMap<String,byte[]> cache = new ConcurrentHashMap<>();

        @Override public byte[] compile(Object tree, String filename, String mode, int optimize, int featureMinor) {
            if (!(tree instanceof String source))
                throw new PyException("NotImplementedError", "compile() of an AST object needs the self-hosted compiler on the classpath");
            String home = System.getenv("PYJVM_HOME");
            if (home == null || home.isEmpty())
                throw new PyException("NotImplementedError",
                    "exec/eval/compile need a compiler: put the self-hosted compiler's class files on the classpath "
                    + "(tools/selfhost.py stage1 <dir>, then add <dir>/stage1), or set PYJVM_HOME to the directory "
                    + "containing the pyjvm315 package to use a host Python instead");
            String python = System.getenv().getOrDefault("PYJVM_PYTHON", "python3");
            String key = mode + '\0' + filename + '\0' + optimize + '\0' + featureMinor + '\0' + source;
            byte[] cached = cache.get(key);
            if (cached != null) return cached;
            try {
                String level = featureMinor == Integer.MAX_VALUE ? "-1" : Integer.toString(featureMinor);
                Process p = new ProcessBuilder(python, "-I", "-c", BOOT, home, mode, filename, Integer.toString(optimize), level).start();
                p.getOutputStream().write(source.getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().close();
                byte[][] streams = new byte[2][];
                Thread err = new Thread(() -> streams[1] = drain(p.getErrorStream()));
                err.start();
                streams[0] = drain(p.getInputStream());
                int status = p.waitFor();
                err.join();
                if (status != 0) throw failure(new String(streams[1], StandardCharsets.UTF_8), source, filename);
                cache.put(key, streams[0]);
                return streams[0];
            } catch (IOException e) {
                throw new PyException("NotImplementedError", "cannot start compiler backend '" + python + "': " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PyException("RuntimeError", "interrupted while compiling");
            }
        }

        private static byte[] drain(InputStream in) {
            try { ByteArrayOutputStream out = new ByteArrayOutputStream(); in.transferTo(out); return out.toByteArray(); }
            catch (IOException e) { return new byte[0]; }
        }

        private static PyException failure(String json, String source, String filename) {
            String kind = jsonField(json, "kind"), message = jsonField(json, "message");
            if ("SyntaxError".equals(kind)) {
                String lineno = jsonNumber(json, "lineno"), offset = jsonNumber(json, "offset");
                return SyntaxInfo.error(jsonField(json, "type").isEmpty() ? "SyntaxError" : jsonField(json, "type"), message, filename, source,
                    lineno.isEmpty() ? 0 : Integer.parseInt(lineno), offset.isEmpty() ? 0 : Integer.parseInt(offset));
            }
            if ("Unsupported".equals(kind)) return new PyException("NotImplementedError", message);
            return new PyException("RuntimeError", json.isEmpty() ? "compiler backend failed" : message.isEmpty() ? json : message);
        }

        private static String jsonNumber(String json, String field) {
            int i = json.indexOf("\"" + field + "\"");
            if (i < 0) return "";
            i = json.indexOf(':', i) + 1;
            while (i < json.length() && json.charAt(i) == ' ') i++;
            int j = i;
            while (j < json.length() && Character.isDigit(json.charAt(j))) j++;
            return json.substring(i, j);
        }

        /** Minimal extraction of a string field from the flat JSON object the service writes. */
        private static String jsonField(String json, String field) {
            int i = json.indexOf("\"" + field + "\"");
            if (i < 0) return "";
            i = json.indexOf('"', json.indexOf(':', i) + 1);
            if (i < 0) return "";
            StringBuilder out = new StringBuilder();
            for (int j = i + 1; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '"') break;
                if (c == '\\' && j + 1 < json.length()) {
                    char n = json.charAt(++j);
                    switch (n) {
                        case 'n' -> out.append('\n'); case 't' -> out.append('\t'); case 'r' -> out.append('\r');
                        case 'u' -> { out.append((char) Integer.parseInt(json.substring(j + 1, j + 5), 16)); j += 4; }
                        default -> out.append(n);
                    }
                } else out.append(c);
            }
            return out.toString();
        }
    }

    // ---- code objects -------------------------------------------------------------------------

    private static final long PYCF_ONLY_AST = 0x400L, PYCF_OPTIMIZED_AST = 0x8400L, PYCF_TOP_LEVEL_AWAIT = 0x2000L,
        PYCF_FUTURE_ANNOTATIONS = 0x1000000L,
        // CO_FUTURE_* bits (division .. annotations), CO_NESTED, DONT_IMPLY_DEDENT, TYPE_COMMENTS, ALLOW_INCOMPLETE_INPUT: accepted
        PYCF_KNOWN = 0x20000L | 0x40000L | 0x80000L | 0x100000L | 0x200000L | 0x400000L | 0x800000L | PYCF_FUTURE_ANNOTATIONS
            | 0x10L | 0x200L | 0x1000L | 0x4000L | PYCF_TOP_LEVEL_AWAIT | PYCF_OPTIMIZED_AST;

    private static final java.util.Set<String> COMPILE_PARAMS = java.util.Set.of("source", "filename", "mode", "flags", "dont_inherit", "optimize", "_feature_version", "module");

    /**
     * The compile() builtin: source text, bytes or an AST, to a code object (or, with PyCF_ONLY_AST, an AST).
     * flags: the __future__ bits are accepted (they change nothing here except annotations, which is refused);
     * dont_inherit is moot because compiled code never inherits future flags from its caller.
     */
    static Object compileBuiltin(java.util.List<Object> args, Map<Object,Object> kwargs) {
        for (Object key : kwargs.keySet())
            if (!COMPILE_PARAMS.contains(key)) throw new PyException("TypeError", "compile() got an unexpected keyword argument '" + key + "'");
        if (args.size() > 6) throw new PyException("TypeError", "compile() takes at most 6 arguments (" + args.size() + " given)");
        String[] names = {"source", "filename", "mode", "flags", "dont_inherit", "optimize"};
        Object[] values = new Object[6];
        for (int k = 0; k < 6; k++) {
            boolean positional = k < args.size();
            if (positional && kwargs.containsKey(names[k]))
                throw new PyException("TypeError", "argument for compile() given by name ('" + names[k] + "') and position (" + (k + 1) + ")");
            values[k] = positional ? args.get(k) : kwargs.get(names[k]);
        }
        for (int k = 0; k < 3; k++)
            if (values[k] == null && !(k == 0 && args.isEmpty() && kwargs.containsKey("source")))
                throw new PyException("TypeError", "compile() missing required argument '" + names[k] + "' (pos " + (k + 1) + ")");
        Object source = values[0], filename = values[1], mode = values[2];
        long flags = values[3] == null ? 0 : integer(values[3]);
        long optimize = values[5] == null ? -1 : integer(values[5]);
        int featureMinor = AstModule.featureMinor(kwargs.get("_feature_version"));
        if (kwargs.containsKey("_feature_version") && kwargs.get("_feature_version") instanceof Long n && n < 0) featureMinor = Integer.MAX_VALUE;

        if (filename instanceof PyBytes fb) filename = new String(fb.data, StandardCharsets.UTF_8);
        if (!(filename instanceof String file)) throw new PyException("TypeError", "compile() argument 'filename' must be str, bytes or os.PathLike, not " + PyRuntime.typeName(filename));
        if (!(mode instanceof String m)) throw new PyException("TypeError", "compile() argument 'mode' must be str, not " + PyRuntime.typeName(mode));
        if ((flags & ~PYCF_KNOWN & ~PYCF_ONLY_AST) != 0) throw new PyException("ValueError", "compile(): unrecognised flags");
        if (optimize < -1 || optimize > 2) throw new PyException("ValueError", "compile(): invalid optimize value");
        if (!(m.equals("exec") || m.equals("eval") || m.equals("single") || (m.equals("func_type") && (flags & PYCF_ONLY_AST) != 0)))
            throw new PyException("ValueError", "compile() mode must be " + ((flags & PYCF_ONLY_AST) != 0
                ? "'exec', 'eval', 'single' or 'func_type'" : "'exec', 'eval' or 'single'"));
        if (m.equals("func_type")) throw new PyException("NotImplementedError", "compile() mode 'func_type' is not implemented");
        if ((flags & PYCF_TOP_LEVEL_AWAIT) != 0)
            throw new PyException("NotImplementedError", "compile() flag PyCF_ALLOW_TOP_LEVEL_AWAIT is not implemented (it needs coroutine-returning module code)");
        if ((flags & PYCF_FUTURE_ANNOTATIONS) != 0)
            throw new PyException("NotImplementedError", "compile() flag CO_FUTURE_ANNOTATIONS is not implemented; start the source with 'from __future__ import annotations'");
        boolean astOnly = (flags & PYCF_ONLY_AST) != 0;
        if (astOnly && (flags & PYCF_OPTIMIZED_AST) == PYCF_OPTIMIZED_AST)
            throw new PyException("NotImplementedError", "compile() flag PyCF_OPTIMIZED_AST needs constant folding of the AST, which is not implemented");

        if (AstModule.isNode(source)) {
            if (astOnly) {
                AstModule.validateForCompile(source, m);
                return source;
            }
            AstModule.validateForCompile(source, m);
            PyCode code = new PyCode("<module>", file, 1);
            code.classBytes = backend().compile(source, file, m, (int) Math.max(optimize, 0), featureMinor);
            code.mode = m;
            return code;
        }
        String text = sourceText(source, "compile");
        if (text.indexOf('\0') >= 0) throw SyntaxInfo.error("SyntaxError", "source code string cannot contain null bytes", file, text, 0, 0);
        if (astOnly) return AstModule.parseSource(text, file, m, featureMinor);
        PyCode code = new PyCode("<module>", file, 1);
        code.classBytes = backend().compile(text, file, m, (int) Math.max(optimize, 0), featureMinor);
        code.mode = m;
        return code;
    }

    private static long integer(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Long l) return l;
        throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer");
    }

    static PyCode compileCode(Object source, Object filename, Object mode) {
        return (PyCode) compileBuiltin(new java.util.ArrayList<>(java.util.List.of(source, filename, mode)), new java.util.LinkedHashMap<>());
    }

    private static String sourceText(Object source, String who) {
        if (source instanceof String s) return s;
        if (source instanceof PyBytes b) return new String(b.data, StandardCharsets.UTF_8);
        if (source instanceof PyByteArray b) return new String(b.toByteArray(), StandardCharsets.UTF_8);
        throw new PyException("TypeError", who + "() arg 1 must be a string, bytes or AST object");
    }

    // ---- exec / eval --------------------------------------------------------------------------

    /**
     * The namespaces exec/eval run in. Missing globals/locals mean the caller's own: its module's globals and
     * (module level) the same mapping, or (inside a function) a snapshot of its locals.
     */
    private static Object[] namespaces(String who, Object globals, Object locals) {
        boolean implicit = globals == null;
        if (implicit) {
            globals = Namespaces.globalsCall();
            if (locals == null) locals = Namespaces.localsCall();
        } else if (!(globals instanceof PyDict g && !g.isFrozen()) && !(globals instanceof NamespacesModuleView))
            throw new PyException("TypeError", who + "() globals must be a dict, not " + PyRuntime.typeName(globals));
        if (locals == null) locals = globals;
        else if (!(locals instanceof Map<?,?>))
            throw new PyException("NotImplementedError", who + "() locals must be a dict (other mappings are not supported yet)");
        @SuppressWarnings("unchecked") Map<Object,Object> g = (Map<Object,Object>) globals;
        if (!g.containsKey("__builtins__")) g.put("__builtins__", Namespaces.builtinsDict());
        return new Object[]{globals, locals};
    }

    static Object run(String who, Object source, Object globals, Object locals, String mode) {
        PyCode code;
        if (source instanceof PyCode c) {
            code = c;
        } else {
            if (!(source instanceof String || source instanceof PyBytes || source instanceof PyByteArray))
                throw new PyException("TypeError", who + "() arg 1 must be a string, bytes or code object");
            Object text = mode.equals("eval") ? sourceText(source, who).stripLeading() : source;
            code = (PyCode) compileBuiltin(new java.util.ArrayList<>(java.util.List.of(text, "<string>", mode)), new java.util.LinkedHashMap<>());
        }
        if (code.classBytes == null) throw new PyException("TypeError", who + "() cannot run this code object");
        Object[] ns = namespaces(who, globals, locals);
        int depth = PyRuntime.logicalFrameDepth();
        try {
            return invoke(code.classBytes, ns[0], ns[1]);
        } finally {
            PyRuntime.truncateLogicalFrames(depth);
        }
    }

    private static Object invoke(byte[] classBytes, Object globals, Object locals) {
        try {
            MethodHandles.Lookup hidden = MethodHandles.lookup().defineHiddenClass(classBytes, true);
            MethodHandle run = hidden.findStatic(hidden.lookupClass(), "run",
                MethodType.methodType(Object.class, Object.class, Object.class));
            return run.invoke(globals, locals);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new PyException("RuntimeError", "cannot run compiled code: " + t);
        }
    }
}
