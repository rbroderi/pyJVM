// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.fn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code atexit}. The registered callbacks run, last registered first, from a JVM shutdown hook: after the main
 * program ends normally, after an uncaught exception has been reported, after {@code sys.exit()} and after the last
 * non-daemon thread has finished, but not after {@code os._exit()} (which halts the JVM). The hook is a separate
 * Java thread, so the callbacks do not run on the main thread. As in CPython, callbacks registered while the
 * callbacks run are discarded without running, and an exception in a callback is reported and does not stop the rest.
 */
final class AtexitModule {
    private AtexitModule() {}

    private record Callback(Object func, List<Object> args, Map<Object, Object> kwargs) {}

    private static final ArrayList<Callback> CALLBACKS = new ArrayList<>();
    private static boolean hookInstalled;

    static PyModule create() {
        PyModule m = new PyModule("atexit", "pyjvm315.runtime.AtexitModule");
        fn(m, "register", (a, k) -> register(a, k));
        fn(m, "unregister", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", "unregister() takes exactly one argument (" + a.size() + " given)");
            unregister(a.get(0));
            return null;
        });
        fn(m, "_run_exitfuncs", (a, k) -> { runExitFuncs(); return null; });
        fn(m, "_clear", (a, k) -> { synchronized (CALLBACKS) { CALLBACKS.clear(); } return null; });
        fn(m, "_ncallbacks", (a, k) -> { synchronized (CALLBACKS) { return (long) CALLBACKS.size(); } });
        return m;
    }

    private static Object register(List<Object> a, Map<Object, Object> k) {
        if (a.isEmpty()) throw new PyException("TypeError", "register() takes at least 1 argument (0 given)");
        Object func = a.get(0);
        if (!PyRuntime.truth(PyRuntime.callBuiltin("callable", new ArrayList<Object>(List.of(func)), new LinkedHashMap<Object, Object>())))
            throw new PyException("TypeError", "the first argument must be callable");
        synchronized (CALLBACKS) {
            CALLBACKS.add(new Callback(func, new ArrayList<>(a.subList(1, a.size())), new LinkedHashMap<>(k)));
            if (!hookInstalled) {
                hookInstalled = true;
                Runtime.getRuntime().addShutdownHook(new Thread(AtexitModule::orderedShutdown, "pyjvm-atexit"));
            }
        }
        return func;
    }

    private static void unregister(Object func) {
        ArrayList<Callback> snapshot;
        synchronized (CALLBACKS) { snapshot = new ArrayList<>(CALLBACKS); }
        for (Callback c : snapshot) {
            if (c.func() == func || PyRuntime.truth(PyRuntime.eq(c.func(), func))) synchronized (CALLBACKS) { CALLBACKS.remove(c); }
        }
    }

    static synchronized void runExitFuncs() {
        int n;
        synchronized (CALLBACKS) { n = CALLBACKS.size(); }
        for (int i = n - 1; i >= 0; i--) {
            Callback c;
            synchronized (CALLBACKS) { if (i >= CALLBACKS.size()) continue; c = CALLBACKS.get(i); }
            try {
                PyRuntime.callFunction(c.func(), new ArrayList<Object>(c.args()), new LinkedHashMap<Object, Object>(c.kwargs()));
            } catch (PyException e) {
                if (!e.typeName.equals("SystemExit")) WeakrefDelivery.unraisable("Exception ignored in atexit callback", c.func(), e);
            }
        }
        synchronized (CALLBACKS) { CALLBACKS.clear(); }
    }

    private static void shutdown() {
        try {
            runExitFuncs();
        } catch (Throwable t) {
            System.err.println("Error in atexit._run_exitfuncs: " + t);
        } finally {
            IoStd.flushAtExit();   // after the callbacks: their output must reach the real streams
            System.out.flush();
            System.err.flush();
        }
    }

    /** Ordered shutdown shared by every JVM shutdown hook: exit callbacks first, then the stream flush (serialized). */
    static synchronized void orderedShutdown() { shutdown(); }
}
