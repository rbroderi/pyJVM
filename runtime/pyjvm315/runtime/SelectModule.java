// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal {@code select} module: {@code select()} and {@code poll()} over the descriptors this runtime has (regular
 * files and directories, which POSIX always reports ready, and the standard streams, where stdin is readable when the
 * JVM reports buffered input). There are no pipes or sockets here, so there is no {@code epoll}/{@code kqueue}. A wait
 * with nothing ready runs signal handlers as signals arrive. On Windows only {@code select()} exists, over real Winsock
 * sockets ({@link WindowsSockets#select}); anything that is not a socket fails with the system's error, as in CPython.
 */
final class SelectModule {
    private SelectModule() {}

    private static final String ME = SelectModule.class.getName();
    private static final int POLLIN = 1, POLLPRI = 2, POLLOUT = 4, POLLERR = 8, POLLHUP = 16, POLLNVAL = 32;
    static PyClass pollClass;
    /** Windows: whether WSAStartup has run, which in CPython means {@code _socket} was imported. */
    static volatile boolean wsaStarted;

    static synchronized PyModule create() {
        PyModule m = new PyModule("select", ME);
        if (ImportSupport.WINDOWS) {
            // CPython on Windows: only select() (sockets only) and its error alias; no poll, epoll, PIPE_BUF or POLL* constants
            m.attrs.put("error", PyRuntime.builtinType("OSError"));
            fn(m, "select", (a, k) -> select(a, k));
            return m;
        }
        if (IoFd.KERNEL) return PosixSelectModule.create();
        String[] names = {"POLLIN", "POLLPRI", "POLLOUT", "POLLERR", "POLLHUP", "POLLNVAL", "POLLRDNORM", "POLLRDBAND", "POLLWRNORM",
            "POLLWRBAND", "POLLMSG", "POLLRDHUP", "PIPE_BUF"};
        long[] vals = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 8192, 4096};
        for (int i = 0; i < names.length; i++) m.attrs.put(names[i], vals[i]);
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        fn(m, "select", (a, k) -> select(a, k));
        pollClass = define("select", "poll", null);
        PyRuntime.classAddMethodEx(pollClass, "__new__", SelectModule.class, "pollNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(pollClass, "__new__", pollClass.methods.get("__new__").function);
        method(pollClass, SelectModule.class, "register", "pRegister", "self,fd,eventmask", defaults("eventmask", (long) (POLLIN | POLLPRI | POLLOUT)));
        method(pollClass, SelectModule.class, "modify", "pModify", "self,fd,eventmask");
        method(pollClass, SelectModule.class, "unregister", "pUnregister", "self,fd");
        method(pollClass, SelectModule.class, "poll", "pPoll", "self,timeout", defaults("timeout", null));
        finish(pollClass);
        m.attrs.put("poll", pollClass);
        return m;
    }

    // ---------------------------------------------------------------- readiness

    private static boolean stdinReady() {
        try { return System.in.available() > 0; } catch (java.io.IOException e) { return false; }
    }

    /** Bit 1: readable, bit 2: writable, or -1 for a descriptor that is not open. */
    private static int ready(long fd) {
        if (!IoFd.valid(fd)) return -1;
        if (IoFd.KERNEL) return fd == 0 ? (stdinReady() ? 1 : 0) : fd <= 2 ? 2 : 3;
        IoFd.Fd f = IoFd.get(fd);
        if (f.std == 0) return stdinReady() ? 1 : 0;
        if (f.std > 0) return 2;
        return 3;
    }

    static long fdOf(Object o) {
        long fd;
        if (o instanceof Long || o instanceof java.math.BigInteger || o instanceof Boolean) fd = IoUtil.asLong(o);
        else {
            Object r;
            try { r = PyRuntime.callMethod(o, "fileno", new Object[0]); }
            catch (PyException e) {
                if (e.typeName.equals("AttributeError")) throw new PyException("TypeError", "argument must be an int, or have a fileno() method.");
                throw e;
            }
            if (!(r instanceof Long || r instanceof java.math.BigInteger)) throw new PyException("TypeError", "fileno() returned a non-integer");
            fd = IoUtil.asLong(r);
        }
        if (fd < 0) throw new PyException("ValueError", "file descriptor cannot be a negative integer (" + fd + ")");
        return fd;
    }

    /** Waits until {@code check} reports something or the timeout (seconds, negative for none) passes. */
    private static void waitUntil(java.util.function.BooleanSupplier check, double timeout) {
        long end = timeout < 0 ? Long.MAX_VALUE : System.nanoTime() + (long) (timeout * 1e9);
        while (!check.getAsBoolean()) {
            long left = end == Long.MAX_VALUE ? 5_000_000L : Math.min(5_000_000L, end - System.nanoTime());
            if (left <= 0) return;
            if (SignalModule.active) SignalModule.sleep(left);
            else try { Thread.sleep(left / 1_000_000, (int) (left % 1_000_000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ---------------------------------------------------------------- select()

    static List<Object> seq(Object o) {
        try {
            List<Object> out = new ArrayList<>();
            for (Object x : PyRuntime.iterable(o)) out.add(x);
            return out;
        } catch (PyException e) { throw new PyException("TypeError", "arguments 1-3 must be sequences"); }
    }

    private static Object select(List<Object> args, Map<Object, Object> kw) {
        if (!kw.isEmpty()) throw new PyException("TypeError", "select() takes no keyword arguments");
        if (args.size() < 3) throw new PyException("TypeError", "select expected at least 3 arguments, got " + args.size());
        if (args.size() > 4) throw new PyException("TypeError", "select expected at most 4 arguments, got " + args.size());
        List<Object> rl = seq(args.get(0)), wl = seq(args.get(1)), xl = seq(args.get(2));
        double timeout = -1;
        Object t = args.size() == 4 ? args.get(3) : null;
        if (t != null) {
            if (!(t instanceof Double || t instanceof Long || t instanceof java.math.BigInteger || t instanceof Boolean)) throw timeoutType(t, "a float or None");
            timeout = t instanceof Double d ? d : IoUtil.asLong(t);
            if (timeout < 0) throw new PyException("ValueError", "timeout must be non-negative");
        }
        if (ImportSupport.WINDOWS) {
            // Winsock select() takes sockets only; anything else (and three empty sets) is the system's error, as in CPython
            long[][] fds = new long[3][];
            List<List<Object>> lists = List.of(rl, wl, xl);
            for (int i = 0; i < 3; i++) {
                fds[i] = new long[lists.get(i).size()];
                for (int j = 0; j < fds[i].length; j++) fds[i][j] = fdOf(lists.get(i).get(j));
            }
            if (rl.size() > WindowsSockets.FD_SETSIZE || wl.size() > WindowsSockets.FD_SETSIZE || xl.size() > WindowsSockets.FD_SETSIZE)
                throw new PyException("ValueError", "too many file descriptors in select()");
            if (!wsaStarted) throw WindowsNative.winError(10093, null);
            return selectSockets(lists, fds, timeout);
        }
        long[] rf = fds(rl), wf = fds(wl);
        fds(xl);
        List<Object> ro = new ArrayList<>(), wo = new ArrayList<>();
        waitUntil(() -> collect(rl, rf, wl, wf, ro, wo), timeout);
        return tuple(ro, wo, new ArrayList<Object>());
    }

    /**
     * Windows: Winsock select(). A long wait runs in slices so that Python signal handlers get their turn (a Winsock
     * select cannot be interrupted); {@code timeout} is in seconds, negative for none.
     */
    private static Object selectSockets(List<List<Object>> lists, long[][] fds, double timeout) {
        long end = timeout < 0 ? Long.MAX_VALUE : System.nanoTime() + (long) (timeout * 1e9);
        int[] err = new int[1];
        while (true) {
            long micros = 50_000;
            if (end != Long.MAX_VALUE) micros = Math.max(0, Math.min(micros, (end - System.nanoTime()) / 1000));
            long[][] ready = WindowsSockets.select(fds[0], fds[1], fds[2], micros, err);
            if (ready == null) throw WindowsNative.winError(err[0], null);
            boolean any = ready[0].length + ready[1].length + ready[2].length > 0;
            if (any || (end != Long.MAX_VALUE && System.nanoTime() >= end)) {
                List<Object> out0 = new ArrayList<>(), out1 = new ArrayList<>(), out2 = new ArrayList<>();
                List<List<Object>> outs = List.of(out0, out1, out2);
                for (int i = 0; i < 3; i++) {
                    java.util.Set<Long> hit = new java.util.HashSet<>();
                    for (long s : ready[i]) hit.add(s);
                    for (int j = 0; j < fds[i].length; j++) if (hit.contains(fds[i][j])) outs.get(i).add(lists.get(i).get(j));
                }
                return tuple(out0, out1, out2);
            }
            if (SignalModule.active) SignalModule.dispatchPending();
        }
    }

    private static long[] fds(List<Object> items) {
        long[] out = new long[items.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = fdOf(items.get(i));
            if (ready(out[i]) < 0) throw IoFd.badFd();
        }
        return out;
    }

    private static boolean collect(List<Object> rl, long[] rf, List<Object> wl, long[] wf, List<Object> ro, List<Object> wo) {
        ro.clear(); wo.clear();
        for (int i = 0; i < rf.length; i++) if ((ready(rf[i]) & 1) != 0) ro.add(rl.get(i));
        for (int i = 0; i < wf.length; i++) if ((ready(wf[i]) & 2) != 0) wo.add(wl.get(i));
        return !ro.isEmpty() || !wo.isEmpty();
    }

    // ---------------------------------------------------------------- poll()

    private static final class Poll { final LinkedHashMap<Long, Long> fds = new LinkedHashMap<>(); }

    public static Object pollNew(Object cls, Object args, Object kwargs) {
        if (!((PyTuple) args).items.isEmpty()) throw new PyException("TypeError", "select.poll() takes no arguments (" + ((PyTuple) args).items.size() + " given)");
        return newInstance((PyClass) cls, new Poll());
    }

    private static Poll poll(Object self) { return state(self, Poll.class, "select.poll"); }

    /** 3.15 names the offending type and says "real number". */
    static PyException timeoutType(Object got, String old) {
        if (LangVersion.atLeast(3, 15)) return new PyException("TypeError", "timeout must be a real number or None, not " + PyRuntime.typeName(got));
        return new PyException("TypeError", "timeout must be " + old);
    }

    private static long mask(Object o) {
        long v = IoUtil.asLong(o);
        if (v < 0 || v > 0xffff) throw new PyException("OverflowError", "Python int too large to convert to C unsigned short");
        return v;
    }

    public static Object pRegister(Object self, Object fd, Object eventmask) {
        poll(self).fds.put(fdOf(fd), mask(eventmask));
        return null;
    }

    public static Object pModify(Object self, Object fd, Object eventmask) {
        long f = fdOf(fd);
        Poll p = poll(self);
        if (!p.fds.containsKey(f)) throw PosixModule.osError("FileNotFoundError", 2, "No such file or directory", null);
        p.fds.put(f, mask(eventmask));
        return null;
    }

    public static Object pUnregister(Object self, Object fd) {
        long f = fdOf(fd);
        if (poll(self).fds.remove(f) == null) throw new PyException("KeyError", f);
        return null;
    }

    private static boolean pollOnce(Poll p, List<Object> out) {
        out.clear();
        for (Map.Entry<Long, Long> e : p.fds.entrySet()) {
            int r = ready(e.getKey());
            long want = e.getValue();
            long got = r < 0 ? POLLNVAL : ((r & 1) != 0 ? (want & POLLIN) : 0) | ((r & 2) != 0 ? (want & POLLOUT) : 0);
            if (got != 0) out.add(tuple(e.getKey(), got));
        }
        return !out.isEmpty();
    }

    public static Object pPoll(Object self, Object timeout) {
        double secs = -1;
        if (timeout != null) {
            if (!(timeout instanceof Long || timeout instanceof java.math.BigInteger || timeout instanceof Boolean || timeout instanceof Double))
                throw timeoutType(timeout, "an integer or None");
            double ms = timeout instanceof Double d ? d : IoUtil.asLong(timeout);
            secs = ms < 0 ? -1 : ms / 1000.0;
        }
        Poll p = poll(self);
        List<Object> out = new ArrayList<>();
        waitUntil(() -> pollOnce(p, out), secs);
        return out;
    }
}
