// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The {@code _signal} module (Linux numbering). Python handlers run in the main thread at <em>safe points</em>:
 * every {@code _signal} call, {@code time.sleep}, {@code select}, {@code pause} and {@code sigwait} (all of which also
 * wake early when a signal arrives). The JVM offers no way to interrupt compiled Python code at an arbitrary
 * instruction, so a pure busy loop is not interrupted. Signals sent to this process by {@code os.kill},
 * {@code raise_signal}, {@code alarm}/{@code setitimer} and {@code pthread_kill} are delivered in-process; real OS
 * signals reach Python only for signals the JVM lets a program handle ({@code sun.misc.Signal}).
 */
final class SignalModule {
    private SignalModule() {}

    static final int NSIG = 65;
    private static final long SIG_DFL = 0, SIG_IGN = 1;
    private static final String[] NAMES = new String[NSIG];
    private static final String[] TEXT = new String[NSIG];
    private static final Object[] handlers = new Object[NSIG];
    private static final boolean[] pending = new boolean[NSIG];
    private static final boolean[] blocked = new boolean[NSIG];
    private static final boolean[] osHooked = new boolean[NSIG];
    private static final Object LOCK = new Object();
    private static volatile boolean anyPending;
    private static Thread MAIN;
    /** Set once the module has been imported: only then do sleeps and waits need to watch for signals. */
    static volatile boolean active;
    private static ScheduledExecutorService timer;
    private static ScheduledFuture<?> timerTask;
    private static long timerDeadline, timerInterval;
    private static NativeFunction defaultInt;
    static PyClass itimerError;

    private static Thread findMain() {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("main")) return t;
        return Thread.currentThread();
    }

    static {
        String[] n = {"HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE", "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM",
            "STKFLT", "CHLD", "CONT", "STOP", "TSTP", "TTIN", "TTOU", "URG", "XCPU", "XFSZ", "VTALRM", "PROF", "WINCH", "IO", "PWR", "SYS"};
        String[] t = {"Hangup", "Interrupt", "Quit", "Illegal instruction", "Trace/breakpoint trap", "Aborted", "Bus error",
            "Floating point exception", "Killed", "User defined signal 1", "Segmentation fault", "User defined signal 2", "Broken pipe",
            "Alarm clock", "Terminated", "Stack fault", "Child exited", "Continued", "Stopped (signal)", "Stopped", "Stopped (tty input)",
            "Stopped (tty output)", "Urgent I/O condition", "CPU time limit exceeded", "File size limit exceeded", "Virtual timer expired",
            "Profiling timer expired", "Window changed", "I/O possible", "Power failure", "Bad system call"};
        for (int i = 0; i < n.length; i++) { NAMES[i + 1] = "SIG" + n[i]; TEXT[i + 1] = t[i]; }
        for (int i = 34; i < NSIG; i++) TEXT[i] = "Real-time signal " + (i - 34);
        for (int i = 1; i < NSIG; i++) handlers[i] = SIG_DFL;
        handlers[13] = SIG_IGN;
        handlers[25] = SIG_IGN;
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_signal", SignalModule.class.getName());
        if (MAIN == null) MAIN = findMain();
        active = true;
        m.attrs.put("SIG_DFL", SIG_DFL);
        m.attrs.put("SIG_IGN", SIG_IGN);
        m.attrs.put("NSIG", (long) NSIG);
        for (int i = 1; i < 32; i++) m.attrs.put(NAMES[i], (long) i);
        m.attrs.put("SIGIOT", 6L);
        m.attrs.put("SIGCLD", 17L);
        m.attrs.put("SIGPOLL", 29L);
        m.attrs.put("SIGRTMIN", 34L);
        m.attrs.put("SIGRTMAX", 64L);
        m.attrs.put("SIG_BLOCK", 0L);
        m.attrs.put("SIG_UNBLOCK", 1L);
        m.attrs.put("SIG_SETMASK", 2L);
        m.attrs.put("ITIMER_REAL", 0L);
        m.attrs.put("ITIMER_VIRTUAL", 1L);
        m.attrs.put("ITIMER_PROF", 2L);
        itimerError = (PyClass) PyRuntime.classCreate("ItimerError", "signal");
        PyRuntime.classAddBase(itimerError, PyRuntime.builtinType("OSError"));
        finish(itimerError);
        m.attrs.put("ItimerError", itimerError);
        defaultInt = fn("default_int_handler", (a, k) -> { throw new PyException("KeyboardInterrupt", null); });
        handlers[2] = defaultInt;
        m.attrs.put("default_int_handler", defaultInt);
        fn(m, "signal", (a, k) -> signal(a, k));
        fn(m, "getsignal", (a, k) -> getsignal(NativeArgs.parse("getsignal", a, k, new String[]{"signalnum"}, 1, 1)[0]));
        fn(m, "raise_signal", (a, k) -> { int s = sigArg("raise_signal", a, k); deliver(s); dispatchPending(); return null; });
        fn(m, "strsignal", (a, k) -> strsignal(sigArg("strsignal", a, k)));
        fn(m, "valid_signals", (a, k) -> validSignals());
        fn(m, "alarm", (a, k) -> alarm(a, k));
        fn(m, "pause", (a, k) -> pause());
        fn(m, "setitimer", (a, k) -> setitimer(a, k));
        fn(m, "getitimer", (a, k) -> getitimer(NativeArgs.parse("getitimer", a, k, new String[]{"which"}, 1, 1)[0]));
        fn(m, "set_wakeup_fd", (a, k) -> setWakeupFd(a, k));
        fn(m, "siginterrupt", (a, k) -> siginterrupt(a, k));
        fn(m, "pthread_sigmask", (a, k) -> pthreadSigmask(a, k));
        fn(m, "sigpending", (a, k) -> sigpending());
        fn(m, "sigwait", (a, k) -> sigwait(a, k));
        fn(m, "pthread_kill", (a, k) -> pthreadKill(a, k));
        return m;
    }

    // ---------------------------------------------------------------- argument helpers

    private static int signum(Object o) {
        long s = IoUtil.asLong(o);
        if (s < 1 || s >= NSIG) throw new PyException("ValueError", "signal number out of range");
        return (int) s;
    }

    private static int sigArg(String fname, List<Object> a, Map<Object, Object> k) {
        return signum(NativeArgs.parse(fname, a, k, new String[]{"signalnum"}, 1, 1)[0]);
    }

    private static boolean isMain() { return Thread.currentThread() == MAIN; }

    private static PyException osErr(String type, int errno, String msg) { return PosixModule.osError(type, errno, msg, null); }

    // ---------------------------------------------------------------- handlers

    private static Object signal(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("signal", a, k, new String[]{"signalnum", "handler"}, 2, 2);
        int s = signum(p[0]);
        Object h = p[1];
        boolean ok = h instanceof Long l && (l == SIG_DFL || l == SIG_IGN) || PyRuntime.truth(PyRuntime.callBuiltin("callable", new ArrayList<Object>(List.of(h)), new LinkedHashMap<Object, Object>()));
        if (!ok) throw new PyException("TypeError", "signal handler must be signal.SIG_IGN, signal.SIG_DFL, or a callable object");
        if (!isMain()) throw new PyException("ValueError", "signal only works in main thread of the main interpreter");
        if (s == 9 || s == 19) throw osErr("OSError", 22, "Invalid argument");
        Object old;
        synchronized (LOCK) { old = handlers[s]; handlers[s] = h; }
        hookOs(s, h);
        dispatchPending();
        return old;
    }

    private static Object getsignal(Object num) { synchronized (LOCK) { return handlers[signum(num)]; } }

    /** Mirrors the handler choice onto a real OS signal where the JVM allows it. */
    private static void hookOs(int s, Object h) {
        if (NAMES[s] == null || s > 31) return;
        boolean dflt = h instanceof Long l && l == SIG_DFL;
        final int num = s;
        try {
            Class<?> sigClass = Class.forName("sun.misc.Signal"), handlerClass = Class.forName("sun.misc.SignalHandler");
            Object sig = sigClass.getConstructor(String.class).newInstance(NAMES[s].substring(3));
            Object handler = dflt ? handlerClass.getField("SIG_DFL").get(null)
                : java.lang.reflect.Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[]{handlerClass},
                    (proxy, method, args) -> { if (method.getName().equals("handle")) deliver(num); return method.getReturnType() == int.class ? (Object) 0 : null; });
            sigClass.getMethod("handle", sigClass, handlerClass).invoke(null, sig, handler);
            osHooked[s] = !dflt;
        } catch (ReflectiveOperationException | RuntimeException e) { /* reserved by the JVM or unavailable: stays in-process only */ }
    }

    private static Object strsignal(int s) { return TEXT[s]; }

    private static Object validSignals() {
        PySet set = new PySet();
        for (int i = 1; i < NSIG; i++) if (i != 32 && i != 33) set.add((long) i);
        return set;
    }

    // ---------------------------------------------------------------- delivery and safe points

    /** Delivers {@code sig} to this process (any thread): records it for the main thread, or applies the default action. */
    static void deliver(int sig) {
        Object h;
        synchronized (LOCK) { h = handlers[sig]; }
        if (h instanceof Long l) {
            if (l == SIG_IGN) return;
            defaultAction(sig);
            return;
        }
        synchronized (LOCK) { pending[sig] = true; anyPending = true; LOCK.notifyAll(); }
    }

    private static void defaultAction(int sig) {
        switch (sig) {
            case 17, 18, 23, 28, 19, 20, 21, 22 -> { }
            default -> { System.out.flush(); System.err.flush(); Runtime.getRuntime().halt(128 + sig); }
        }
    }

    /** Runs the Python handlers of signals that arrived (main thread only; blocked signals stay pending). */
    static void dispatchPending() {
        if (!anyPending || !isMain()) return;
        while (true) {
            int sig = -1;
            Object h = null;
            synchronized (LOCK) {
                boolean left = false;
                for (int i = 1; i < NSIG; i++) {
                    if (!pending[i]) continue;
                    if (blocked[i]) { left = true; continue; }
                    if (sig < 0) sig = i; else left = true;
                }
                anyPending = left;
                if (sig < 0) return;
                pending[sig] = false;
                h = handlers[sig];
            }
            if (h instanceof Long) continue;
            PyRuntime.callFunction(h, new ArrayList<Object>(java.util.Arrays.asList((long) sig, null)), new LinkedHashMap<Object, Object>());
        }
    }

    /** Sleeps up to {@code nanos}, running signal handlers as they arrive (a handler that returns does not end the sleep). */
    static void sleep(long nanos) {
        long end = System.nanoTime() + nanos;
        while (true) {
            dispatchPending();
            long left = end - System.nanoTime();
            if (left <= 0) return;
            try {
                synchronized (LOCK) {
                    if (isMain() && anyPending) continue;
                    LOCK.wait(left / 1_000_000, (int) (left % 1_000_000));
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static Object pause() {
        if (!isMain()) throw new PyException("ValueError", "signal only works in main thread of the main interpreter");
        try {
            synchronized (LOCK) { while (!hasDeliverable()) LOCK.wait(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        dispatchPending();
        return null;
    }

    private static boolean hasDeliverable() {
        for (int i = 1; i < NSIG; i++) if (pending[i] && !blocked[i]) return true;
        return false;
    }

    // ---------------------------------------------------------------- alarm / itimer

    private static Object alarm(List<Object> a, Map<Object, Object> k) {
        long secs = IoUtil.asLong(NativeArgs.parse("alarm", a, k, new String[]{"seconds"}, 1, 1)[0]);
        if (secs < 0) secs &= 0xffffffffL; // C takes an unsigned int
        double[] old = arm(secs, 0);
        return (long) Math.ceil(old[0]);
    }

    private static synchronized double[] arm(double seconds, double interval) {
        double[] old = remaining();
        if (timerTask != null) { timerTask.cancel(false); timerTask = null; }
        timerDeadline = 0; timerInterval = 0;
        if (seconds > 0) {
            if (timer == null) {
                timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "pyjvm-itimer"); t.setDaemon(true); return t; });
            }
            long first = (long) (seconds * 1e9), every = (long) (interval * 1e9);
            timerDeadline = System.nanoTime() + first; timerInterval = every;
            Runnable fire = () -> {
                deliver(14);
                synchronized (SignalModule.class) { if (timerInterval > 0) timerDeadline = System.nanoTime() + timerInterval; else timerDeadline = 0; }
            };
            timerTask = every > 0 ? timer.scheduleAtFixedRate(fire, first, every, TimeUnit.NANOSECONDS) : timer.schedule(fire, first, TimeUnit.NANOSECONDS);
        }
        return old;
    }

    private static synchronized double[] remaining() {
        if (timerDeadline == 0) return new double[]{0, 0};
        return new double[]{Math.max(0, (timerDeadline - System.nanoTime()) / 1e9), timerInterval / 1e9};
    }

    private static void requireReal(Object which) {
        long w = IoUtil.asLong(which);
        if (w == 0) return;
        if (w == 1 || w == 2) throw new PyException("NotImplementedError", "ITIMER_VIRTUAL and ITIMER_PROF need CPU-time timers, which the JVM does not provide");
        throw PyRuntime.userException(PyRuntime.instantiate(itimerError, new Object[]{22L, "Invalid argument"}));
    }

    private static double secs(Object o) {
        double d = o instanceof Double x ? x : IoUtil.asLong(o);
        if (d < 0) throw PyRuntime.userException(PyRuntime.instantiate(itimerError, new Object[]{22L, "Invalid argument"}));
        return d;
    }

    private static Object setitimer(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("setitimer", a, k, new String[]{"which", "seconds", "interval"}, 2, 3);
        requireReal(p[0]);
        double interval = p[2] == NativeArgs.MISSING ? 0 : secs(p[2]);
        double[] old = arm(secs(p[1]), interval);
        return tuple(old[0], old[1]);
    }

    private static Object getitimer(Object which) {
        requireReal(which);
        double[] r = remaining();
        return tuple(r[0], r[1]);
    }

    // ---------------------------------------------------------------- masks and waiting

    private static Object setWakeupFd(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("set_wakeup_fd", a, k, new String[]{"fd", "warn_on_full_buffer"}, 1, 1);
        long fd = IoUtil.asLong(p[0]);
        if (!isMain()) throw new PyException("ValueError", "set_wakeup_fd only works in main thread of the main interpreter");
        if (fd != -1) throw new PyException("NotImplementedError", "set_wakeup_fd needs a pipe or socket descriptor, which this runtime does not provide");
        return -1L;
    }

    private static Object siginterrupt(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("siginterrupt", a, k, new String[]{"signalnum", "flag"}, 2, 2);
        signum(p[0]);
        return null;
    }

    private static List<Integer> maskArg(Object iterable) {
        ArrayList<Integer> out = new ArrayList<>();
        for (Object o : PyRuntime.iterable(iterable)) {
            long s = IoUtil.asLong(o);
            if (s < 1 || s >= NSIG) throw new PyException("ValueError", "signal number " + s + " out of range [1; " + (NSIG - 1) + "]");
            out.add((int) s);
        }
        return out;
    }

    private static PySet maskSet(boolean[] bits) {
        PySet set = new PySet();
        for (int i = 1; i < NSIG; i++) if (bits[i]) set.add((long) i);
        return set;
    }

    private static Object pthreadSigmask(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("pthread_sigmask", a, k, new String[]{"how", "mask"}, 2, 2);
        long how = IoUtil.asLong(p[0]);
        List<Integer> mask = maskArg(p[1]);
        if (how < 0 || how > 2) throw osErr("OSError", 22, "Invalid argument");
        PySet old;
        synchronized (LOCK) {
            old = maskSet(blocked);
            if (how == 2) java.util.Arrays.fill(blocked, false);
            for (int s : mask) if (s != 9 && s != 19) blocked[s] = how != 1;
            anyPending = hasDeliverable() || anyPendingBlocked();
            LOCK.notifyAll();
        }
        dispatchPending();
        return old;
    }

    private static boolean anyPendingBlocked() {
        for (int i = 1; i < NSIG; i++) if (pending[i]) return true;
        return false;
    }

    private static Object sigpending() {
        boolean[] bits = new boolean[NSIG];
        synchronized (LOCK) { for (int i = 1; i < NSIG; i++) bits[i] = pending[i] && blocked[i]; }
        return maskSet(bits);
    }

    private static Object sigwait(List<Object> a, Map<Object, Object> k) {
        List<Integer> wanted = maskArg(NativeArgs.parse("sigwait", a, k, new String[]{"sigset"}, 1, 1)[0]);
        try {
            synchronized (LOCK) {
                while (true) {
                    for (int s : wanted) if (pending[s]) { pending[s] = false; anyPending = anyPendingBlocked(); return (long) s; }
                    LOCK.wait();
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    private static Object pthreadKill(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("pthread_kill", a, k, new String[]{"thread_id", "signalnum"}, 2, 2);
        long tid = IoUtil.asLong(p[0]);
        long s = IoUtil.asLong(p[1]);
        if (s < 0 || s >= NSIG) throw new PyException("ValueError", "signal number " + s + " out of range [0; " + (NSIG - 1) + "]");
        boolean found = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.threadId() == tid) found = true;
        if (!found) throw osErr("ProcessLookupError", 3, "No such process");
        if (s != 0) { deliver((int) s); dispatchPending(); }
        return null;
    }

    // ---------------------------------------------------------------- os.kill

    /** {@code os.kill(pid, sig)}: this process is signalled in-process; other processes through the system {@code kill}. */
    static void kill(long pid, long sig) {
        if (sig < 0 || sig >= NSIG) throw osErr("OSError", 22, "Invalid argument");
        if (pid == ProcessHandle.current().pid()) {
            if (sig != 0) { deliver((int) sig); dispatchPending(); }
            return;
        }
        if (ProcessHandle.of(pid).isEmpty()) throw osErr("ProcessLookupError", 3, "No such process");
        if (sig == 0) return;
        try {
            Process pr = new ProcessBuilder("kill", "-" + sig, Long.toString(pid)).redirectErrorStream(true).start();
            pr.getInputStream().readAllBytes();
            if (pr.waitFor() != 0) throw osErr("PermissionError", 1, "Operation not permitted");
        } catch (java.io.IOException e) {
            throw osErr("OSError", 38, "Function not implemented");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
