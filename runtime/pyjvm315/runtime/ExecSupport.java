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
        /** @return class bytes for module code in {@code mode} ("exec" or "eval"). */
        byte[] compile(String source, String filename, String mode);
    }

    private static volatile CompilerBackend backend;
    static void setBackend(CompilerBackend replacement) { backend = replacement; }
    private static CompilerBackend backend() {
        CompilerBackend b = backend;
        if (b == null) backend = b = new HostPythonBackend();
        return b;
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

        @Override public byte[] compile(String source, String filename, String mode) {
            String home = System.getenv("PYJVM_HOME");
            if (home == null || home.isEmpty())
                throw new PyException("NotImplementedError",
                    "exec/eval/compile need a compiler backend: set PYJVM_HOME to the directory containing the pyjvm315 "
                    + "package (the in-process self-hosted compiler is not implemented yet)");
            String python = System.getenv().getOrDefault("PYJVM_PYTHON", "python3");
            String key = mode + '\0' + filename + '\0' + source;
            byte[] cached = cache.get(key);
            if (cached != null) return cached;
            try {
                Process p = new ProcessBuilder(python, "-I", "-c", BOOT, home, mode, filename).start();
                p.getOutputStream().write(source.getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().close();
                byte[][] streams = new byte[2][];
                Thread err = new Thread(() -> streams[1] = drain(p.getErrorStream()));
                err.start();
                streams[0] = drain(p.getInputStream());
                int status = p.waitFor();
                err.join();
                if (status != 0) throw failure(new String(streams[1], StandardCharsets.UTF_8));
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

        private static PyException failure(String json) {
            String kind = jsonField(json, "kind"), message = jsonField(json, "message");
            if ("SyntaxError".equals(kind)) return new PyException("SyntaxError", message);
            if ("Unsupported".equals(kind)) return new PyException("NotImplementedError", message);
            return new PyException("RuntimeError", json.isEmpty() ? "compiler backend failed" : message.isEmpty() ? json : message);
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

    static PyCode compileCode(Object source, Object filename, Object mode) {
        String text = sourceText(source, "compile");
        if (!(filename instanceof String file)) throw new PyException("TypeError", "compile() arg 2 must be str, not " + PyRuntime.typeName(filename));
        if (!(mode instanceof String m) || !(m.equals("exec") || m.equals("eval") || m.equals("single")))
            throw new PyException("ValueError", "compile() mode must be 'exec', 'eval' or 'single'");
        if (m.equals("single")) throw new PyException("NotImplementedError", "compile() mode 'single' is not implemented");
        PyCode code = new PyCode("<module>", file, 1);
        code.classBytes = backend().compile(text, file, m);
        code.mode = m;
        return code;
    }

    private static String sourceText(Object source, String who) {
        if (source instanceof String s) return s;
        if (source instanceof PyBytes b) return new String(b.data, StandardCharsets.UTF_8);
        throw new PyException("TypeError", who + "() arg 1 must be a string, bytes or code object");
    }

    // ---- exec / eval --------------------------------------------------------------------------

    private static final ThreadLocal<ArrayDeque<Object[]>> NONE = ThreadLocal.withInitial(ArrayDeque::new);

    @SuppressWarnings("unchecked")
    private static Object[] namespaces(String who, Object globals, Object locals) {
        if (globals == null)
            throw new PyException("NotImplementedError", who + "() without explicit globals needs the caller's namespace, which only "
                + "code compiled by exec/eval has; pass globals (and locals) explicitly");
        if (!(globals instanceof PyDict g) || g.isFrozen())
            throw new PyException("TypeError", who + "() globals must be a dict, not " + PyRuntime.typeName(globals));
        if (locals == null) locals = globals;
        else if (!(locals instanceof Map<?,?>))
            throw new PyException("NotImplementedError", who + "() locals must be a dict (other mappings are not supported yet)");
        if (!g.containsKey("__builtins__")) g.put("__builtins__", PyRuntime.builtinModuleStandIn());
        return new Object[]{globals, locals};
    }

    static Object run(String who, Object source, Object globals, Object locals, String mode) {
        PyCode code;
        if (source instanceof PyCode c) {
            code = c;
        } else {
            code = compileCode(mode.equals("eval") ? sourceText(source, who).strip() : source, "<string>", mode);
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
