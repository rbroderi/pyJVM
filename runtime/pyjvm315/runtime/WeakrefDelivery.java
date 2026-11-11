// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Delivery of weak reference callbacks and {@code __del__} finalizers.
 *
 * <p>The JVM has no reference counting, so nothing here runs at the instant an object becomes unreachable.
 * A daemon watcher thread blocks on the reference queue and turns every cleared reference (and every object whose
 * Java finalizer fired, see {@link GcFinalizable}) into a {@link Task} addressed to the thread that created the
 * reference or the object. Python code is never run on the watcher or the JVM's finalizer thread: a task runs on
 * its own thread at the next poll point (every Python function entry, {@code gc.collect()}, creating or calling a
 * weak reference) and never concurrently with that thread's other Python code. Tasks whose thread has ended are run
 * by whichever thread polls next. Within one delivery, finalizers run first, then callbacks, most recently created
 * reference first (the order CPython documents).
 */
final class WeakrefDelivery {
    private WeakrefDelivery() {}

    static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final ConcurrentLinkedQueue<Task> TASKS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<ThreadInfo> INFO = ThreadLocal.withInitial(ThreadInfo::new);
    private static final ThreadLocal<boolean[]> DELIVERING = ThreadLocal.withInitial(() -> new boolean[1]);
    private static volatile Thread watcher;

    /** The creating thread of a reference or object, held weakly. */
    static final class ThreadInfo {
        final WeakReference<Thread> thread = new WeakReference<>(Thread.currentThread());
        boolean usable() {
            Thread t = thread.get();
            return t == null || !t.isAlive() || t == Thread.currentThread();
        }
    }

    static ThreadInfo currentThread() { return INFO.get(); }

    /** One piece of deferred Python code. */
    abstract static class Task {
        final ThreadInfo owner;
        final long seq;
        final boolean finalizer;
        Task(ThreadInfo owner, long seq, boolean finalizer) { this.owner = owner; this.seq = seq; this.finalizer = finalizer; }
        abstract void run();
        abstract String what();
        abstract Object subject();
    }

    /** A weak reference registered only to learn when the reference handler has caught up (see {@link #settle}). */
    static final class Sentinel extends WeakReference<Object> {
        volatile boolean done;
        Sentinel(Object o) { super(o, QUEUE); }
    }

    static void post(Task t) {
        TASKS.add(t);
        PENDING.incrementAndGet();
    }

    static void ensureWatcher() {
        if (watcher != null) return;
        synchronized (QUEUE) {
            if (watcher != null) return;
            Thread t = new Thread(WeakrefDelivery::watch, "pyjvm-weakref-watcher");
            t.setDaemon(true);
            t.start();
            watcher = t;
        }
    }

    private static void watch() {
        for (;;) {
            try {
                Reference<?> r = QUEUE.remove();
                if (r instanceof WeakrefModule.RefState s) WeakrefModule.referentDied(s);
                else if (r instanceof WeakrefModule.Key k) WeakrefModule.keyDied(k);
                else if (r instanceof Sentinel s) s.done = true;
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // a registry error must not kill the watcher
            }
        }
    }

    /** The cheap check made at every Python function entry. */
    static void poll() {
        if (PENDING.get() != 0) deliver();
    }

    /** Run the tasks that belong to the calling thread (or to a thread that has ended). Returns how many ran. */
    static int deliver() {
        boolean[] busy = DELIVERING.get();
        if (busy[0]) return 0;
        busy[0] = true;
        int ran = 0;
        try {
            for (int pass = 0; pass < 8; pass++) {
                ArrayList<Task> mine = new ArrayList<>();
                for (Task t : TASKS) if (t.owner.usable() && TASKS.remove(t)) mine.add(t);
                if (mine.isEmpty()) break;
                mine.sort(Comparator.<Task, Boolean>comparing(t -> !t.finalizer).thenComparing(t -> t.finalizer ? t.seq : -t.seq));
                for (Task t : mine) {
                    PENDING.decrementAndGet();
                    ran++;
                    try {
                        t.run();
                    } catch (PyException e) {
                        unraisable(t.what(), t.subject(), e);
                    } catch (StackOverflowError e) {
                        unraisable(t.what(), t.subject(), new PyException("RecursionError", "maximum recursion depth exceeded"));
                    }
                }
            }
        } finally {
            busy[0] = false;
        }
        return ran;
    }

    // ---- gc.collect ------------------------------------------------------------------------------

    private static Sentinel newSentinel() { return new Sentinel(new Object()); }

    private static Thread referenceHandler;

    private static Thread referenceHandler() {
        Thread h = referenceHandler;
        if (h != null) return h;
        ThreadGroup g = Thread.currentThread().getThreadGroup();
        while (g.getParent() != null) g = g.getParent();
        Thread[] all = new Thread[g.activeCount() * 2 + 8];
        int n = g.enumerate(all, true);
        for (int i = 0; i < n; i++) if (all[i].getName().equals("Reference Handler")) return referenceHandler = all[i];
        return null;
    }

    private static boolean idle(Thread t) {
        if (t == null) return true;
        Thread.State s = t.getState();
        return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING || s == Thread.State.BLOCKED || s == Thread.State.TERMINATED;
    }

    /** Wait until the collector's cleared references have been enqueued and the watcher has turned them into tasks. */
    private static void settle(Sentinel s) {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!s.done && System.nanoTime() < end) LockSupport.parkNanos(100_000);
        Thread h = referenceHandler(), w = watcher;
        int quiet = 0;
        end = System.nanoTime() + 200_000_000L;
        while (quiet < 3 && System.nanoTime() < end) {
            if (idle(h) && idle(w) ) quiet++; else quiet = 0;
            LockSupport.parkNanos(150_000);
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static void runFinalization() { System.runFinalization(); }

    /** A full collection: System.gc, wait for the reference machinery, then run this thread's pending tasks. */
    static int collect() {
        ensureWatcher();
        int ran = 0;
        for (int round = 0; round < 2; round++) {
            Sentinel s = newSentinel();
            System.gc();
            settle(s);
            runFinalization();
            ran += deliver();
        }
        return ran;
    }

    // ---- errors raised by callbacks and finalizers -----------------------------------------------

    private static PyClass hookArgsClass;

    /** Report an exception that cannot propagate, through sys.unraisablehook. */
    static void unraisable(String message, Object object, PyException e) {
        try {
            PyModule sys = (PyModule) PyRuntime.importNative("sys");
            Object hook = sys.attrs.get("unraisablehook");
            Object value = PyRuntime.exceptionInstance(e);
            PyInstance args = new PyInstance(hookArgs());
            args.fields.put("exc_type", PyRuntime.typeOf(value));
            args.fields.put("exc_value", value);
            args.fields.put("exc_traceback", PyRuntime.getattr(value, "__traceback__"));
            args.fields.put("err_msg", message);
            args.fields.put("object", object);
            if (hook == null) { defaultHook(args); return; }
            PyRuntime.callFunction(hook, new ArrayList<Object>(List.of(args)), new LinkedHashMap<Object, Object>());
        } catch (RuntimeException failure) {
            System.err.println(message + ": " + e);
        }
    }

    private static synchronized PyClass hookArgs() {
        if (hookArgsClass == null) {
            hookArgsClass = NativeTypes.define("builtins", "UnraisableHookArgs", null);
            hookArgsClass.qualname = "UnraisableHookArgs";
            NativeTypes.finish(hookArgsClass);
        }
        return hookArgsClass;
    }

    /** The default sys.unraisablehook: "Exception ignored in: ..." and the traceback on sys.stderr. */
    static Object defaultHook(Object args) {
        PyInstance a = (PyInstance) args;
        Object msg = a.fields.get("err_msg"), obj = a.fields.get("object"), value = a.fields.get("exc_value");
        StringBuilder sb = new StringBuilder();
        String text = msg == null ? "Exception ignored in" : PyRuntime.pyStr(msg);
        sb.append(text);
        if (obj != null) {
            String r;
            try { r = PyRuntime.pyRepr(obj); } catch (RuntimeException ex) { r = "<object repr() failed>"; }
            sb.append(msg == null ? ": " : ": ").append(r);
        }
        sb.append('\n');
        if (value != null) sb.append(TracebackFormat.format(value));
        Object err = BuiltinsExtra.currentStream("stderr");
        if (err == null) { System.err.print(sb); return null; }
        PyRuntime.callMethod(err, "write", new Object[]{sb.toString()});
        if (PyRuntime.truth(PyRuntime.hasattr(err, "flush"))) PyRuntime.callMethod(err, "flush", new Object[0]);
        return null;
    }

    /** Install sys.unraisablehook once. */
    static void installHook(PyModule sys) {
        if (sys.attrs.containsKey("unraisablehook")) return;
        NativeFunction hook = new NativeFunction("unraisablehook", (a, k) -> defaultHook(a.get(0)));
        sys.attrs.put("unraisablehook", hook);
        sys.attrs.put("__unraisablehook__", hook);
    }
}
