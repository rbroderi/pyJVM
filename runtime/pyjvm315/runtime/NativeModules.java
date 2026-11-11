// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small native modules: the beginnings of {@code sys} and the file access the self-hosted compiler needs. */
public final class NativeModules {
    private NativeModules() {}

    /** Program arguments, set by {@link Launcher}; sys.argv[0] is the program name. */
    static volatile String[] argv = new String[]{"pyjvm"};

    private static void fn(PyModule m, String name, NativeFunction.Body body) { m.attrs.put(name, new NativeFunction(name, body)); }

    static PyModule sys() {
        PyModule m = new PyModule("sys", "pyjvm315.runtime.NativeModules");
        PyTuple version = new PyTuple();
        version.items.add((long) LangVersion.MAJOR); version.items.add((long) LangVersion.MINOR); version.items.add(0L);
        version.items.add("final"); version.items.add(0L);
        m.attrs.put("version_info", version);
        m.attrs.put("version", LangVersion.MAJOR + "." + LangVersion.MINOR + ".0 (pyjvm)");
        m.attrs.put("maxsize", Long.MAX_VALUE);
        m.attrs.put("byteorder", "big");
        m.attrs.put("platform", "java");
        ArrayList<Object> args = new ArrayList<>();
        for (String a : argv) args.add(a);
        m.attrs.put("argv", args);
        BuiltinsExtra.installSys(m);
        ImportSupport.installSys(m);
        fn(m, "_getframe", (a, k) -> PyFrameSupport.getFrame(a.isEmpty() ? null : a.get(0)));
        fn(m, "settrace", (a, k) -> Tracing.settrace(a.get(0)));
        fn(m, "gettrace", (a, k) -> Tracing.traceFn);
        fn(m, "setprofile", (a, k) -> Tracing.setprofile(a.get(0)));
        fn(m, "getprofile", (a, k) -> Tracing.profileFn);
        PyModule monitoring = new PyModule("sys.monitoring", "pyjvm315.runtime.Monitoring");
        Monitoring.install(monitoring);
        m.attrs.put("monitoring", monitoring);
        NativeFunction hook = new NativeFunction("displayhook", Namespaces::defaultDisplayhook);
        m.attrs.put("displayhook", hook);
        m.attrs.put("__displayhook__", hook);
        return m;
    }

    /** Whole-file text and byte access (the self-hosted compiler reads sources and writes class files). */
    static PyModule jvmIo() {
        PyModule m = new PyModule("jvm_io", "pyjvm315.runtime.NativeModules");
        fn(m, "read_text", (a, k) -> {
            try { return Files.readString(Path.of((String) a.get(0)), StandardCharsets.UTF_8); }
            catch (IOException e) { throw new PyException("OSError", "cannot read " + a.get(0) + ": " + e.getMessage()); }
        });
        fn(m, "write_bytes", (a, k) -> {
            try {
                Path p = Path.of((String) a.get(0));
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.write(p, ((PyBytes) a.get(1)).data);
                return null;
            } catch (IOException e) { throw new PyException("OSError", "cannot write " + a.get(0) + ": " + e.getMessage()); }
        });
        fn(m, "exists", (a, k) -> Files.exists(Path.of((String) a.get(0))));
        return m;
    }
}
