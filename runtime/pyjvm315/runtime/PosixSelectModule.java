// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static pyjvm315.runtime.NativeTypes.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code select} module over kernel descriptors: {@code select()} is {@code select(2)} and {@code poll()} is
 * {@code poll(2)} ({@link PosixSelect}), so readiness, EBADF and the 1024 limit are the kernel's. Reached only through
 * {@code SelectModule.create} behind {@code IoFd.KERNEL}. A wait on the main thread while {@code _signal} is active runs
 * in 50 ms slices with the Python handlers dispatched between slices; EINTR dispatches them too and continues with the
 * time that is left.
 */
final class PosixSelectModule {
    private PosixSelectModule() {}

    private static final String ME = PosixSelectModule.class.getName();
    private static final int POLLIN = 1, POLLPRI = 2, POLLOUT = 4;
    private static final int EINTR = 4;
    private static final long SLICE_MICROS = 50_000L;
    /** PyTime_t is a signed 64-bit count of nanoseconds. */
    private static final double MAX_NANOS = 9.223372036854775807E18;

    static synchronized PyModule create() {
        PyModule m = new PyModule("select", ME);
        String[] names = {"POLLIN", "POLLPRI", "POLLOUT", "POLLERR", "POLLHUP", "POLLNVAL", "POLLRDNORM", "POLLRDBAND", "POLLWRNORM",
            "POLLWRBAND", "POLLMSG", "POLLRDHUP", "PIPE_BUF"};
        long[] vals = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 8192, 4096};
        for (int i = 0; i < names.length; i++) m.attrs.put(names[i], vals[i]);
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        fn(m, "select", (a, k) -> select(a, k));
        PyClass pollClass = define("select", "poll", null);
        PyRuntime.classAddMethodEx(pollClass, "__new__", PosixSelectModule.class, "pollNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(pollClass, "__new__", pollClass.methods.get("__new__").function);
        method(pollClass, PosixSelectModule.class, "register", "pRegister", "self,fd,eventmask", defaults("eventmask", (long) (POLLIN | POLLPRI | POLLOUT)));
        method(pollClass, PosixSelectModule.class, "modify", "pModify", "self,fd,eventmask");
        method(pollClass, PosixSelectModule.class, "unregister", "pUnregister", "self,fd");
        method(pollClass, PosixSelectModule.class, "__repr__", "pRepr", "self");
        methodVarKw(pollClass, PosixSelectModule.class, "poll", "pPoll", "self", "args", "kwargs", new PyDict());
        finish(pollClass);
        m.attrs.put("poll", pollClass);
        return m;
    }

    // ---------------------------------------------------------------- shared

    /** A descriptor argument as a C int: an int or an object with {@code fileno()}; negative is a ValueError. */
    private static int cfd(Object o) {
        long fd;
        try { fd = SelectModule.fdOf(o); }
        catch (PyException e) {
            if (e.typeName.equals("OverflowError")) throw new PyException("OverflowError", "Python int too large to convert to C int");
            throw e;
        }
        if (fd > Integer.MAX_VALUE) throw new PyException("OverflowError", "Python int too large to convert to C int");
        return (int) fd;
    }

    /** A timeout number as a double; NaN and what overflows a PyTime_t ({@code nanosPerUnit} nanoseconds per unit) are errors, as in CPython. */
    private static double timeValue(Object t, double nanosPerUnit) {
        double v = t instanceof Double d ? d : t instanceof BigInteger b ? b.doubleValue() : IoUtil.asLong(t);
        if (Double.isNaN(v)) throw new PyException("ValueError", "Invalid value NaN (not a number)");
        if (Math.abs(v) * nanosPerUnit >= MAX_NANOS) throw new PyException("OverflowError", "timestamp out of range for C PyTime_t");
        return v;
    }

    private static boolean isNumber(Object t) {
        return t instanceof Double || t instanceof Long || t instanceof BigInteger || t instanceof Boolean;
    }

    /** One kernel call for a wait: the argument is the time to wait in microseconds (negative: for ever); returns the count or {@code -errno}. */
    private interface Call { int run(long micros); }

    /**
     * The wait loop shared by select and poll. {@code micros} is the caller's timeout (negative: none). On the main
     * thread with {@code _signal} active each call waits at most one 50 ms slice, and Python handlers run between slices
     * and after EINTR; otherwise one call covers the whole remaining time. Returns the positive ready count, or 0 when
     * the timeout passed.
     */
    private static int await(long micros, Call call) {
        boolean sliced = SignalModule.active && SignalModule.isMain();
        long deadline = micros < 0 ? 0 : System.nanoTime() + Math.min(micros * 1000L, Long.MAX_VALUE / 4);
        while (true) {
            long now;
            if (micros < 0) now = sliced ? SLICE_MICROS : -1;
            else {
                long left = Math.max(0L, (deadline - System.nanoTime() + 999L) / 1000L);
                now = sliced ? Math.min(left, SLICE_MICROS) : left;
            }
            int n = call.run(now);
            if (n > 0) return n;
            if (n < 0) {
                if (-n != EINTR) throw PosixNative.osError(-n, null);
            } else if (micros >= 0 && (!sliced || deadline - System.nanoTime() <= 0)) return 0;
            SignalModule.dispatchPending();
        }
    }

    // ---------------------------------------------------------------- select()

    private static long selectMicros(Object t) {
        if (t == null) return -1;
        if (!isNumber(t)) throw SelectModule.timeoutType(t, "a float or None");
        double secs = timeValue(t, 1e9);
        if (secs < 0) throw new PyException("ValueError", "timeout must be non-negative");
        return (long) Math.ceil(secs * 1e6);
    }

    private static Object select(List<Object> args, Map<Object, Object> kw) {
        if (!kw.isEmpty()) throw new PyException("TypeError", "select.select() takes no keyword arguments");
        if (args.size() < 3) throw new PyException("TypeError", "select expected at least 3 arguments, got " + args.size());
        if (args.size() > 4) throw new PyException("TypeError", "select expected at most 4 arguments, got " + args.size());
        long micros = selectMicros(args.size() == 4 ? args.get(3) : null);
        int sb = PosixSelect.SET_BYTES;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pristine = a.allocate(3L * sb, 8), work = a.allocate(3L * sb, 8);
            List<List<Object>> lists = new ArrayList<>();
            int[][] fds = new int[3][];
            int nfds = 0;
            for (int i = 0; i < 3; i++) {
                List<Object> items = SelectModule.seq(args.get(i));
                lists.add(items);
                fds[i] = new int[items.size()];
                for (int j = 0; j < fds[i].length; j++) {
                    int fd = cfd(items.get(j));
                    if (fd >= PosixSelect.FD_SETSIZE) throw new PyException("ValueError", "filedescriptor out of range in select()");   // before any bit is written
                    PosixSelect.setBit(pristine, (long) i * sb, fd);
                    fds[i][j] = fd;
                    nfds = Math.max(nfds, fd + 1);
                }
            }
            int count = nfds;
            int n = await(micros, now -> {
                MemorySegment.copy(pristine, 0, work, 0, work.byteSize());      // the kernel overwrites the sets
                return PosixSelect.select(count, work.asSlice(0, sb), work.asSlice(sb, sb), work.asSlice(2L * sb, sb), now);
            });
            List<Object> out0 = new ArrayList<>(), out1 = new ArrayList<>(), out2 = new ArrayList<>();
            List<List<Object>> outs = List.of(out0, out1, out2);
            if (n > 0) {
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < fds[i].length; j++) {
                        if (PosixSelect.isSet(work, (long) i * sb, fds[i][j])) outs.get(i).add(lists.get(i).get(j));
                    }
                }
            }
            return tuple(out0, out1, out2);
        }
    }

    // ---------------------------------------------------------------- poll()

    /** Registered descriptors and their event masks, in registration order. */
    private static final class Poll {
        final LinkedHashMap<Integer, Integer> fds = new LinkedHashMap<>();   // guarded by this
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    }

    public static Object pollNew(Object cls, Object args, Object kwargs) {
        if (!((PyDict) kwargs).isEmpty()) throw new PyException("TypeError", "select.poll() takes no keyword arguments");
        if (!((PyTuple) args).items.isEmpty()) throw new PyException("TypeError", "select.poll() takes no arguments (" + ((PyTuple) args).items.size() + " given)");
        return newInstance((PyClass) cls, new Poll());
    }

    private static Poll poll(Object self) { return state(self, Poll.class, "select.poll"); }

    /** The event mask as an unsigned short (the texts of 3.14 and later differ from 3.13: measured on 3.13, 3.14, 3.15), with the per-lane texts for what is not an integer and for a negative value. */
    private static int mask(Object o) {
        BigInteger v;
        try { v = BuiltinsExtra.rangeIndex(o); }
        catch (PyException e) {
            if (!e.typeName.equals("TypeError")) throw e;
            throw new PyException("TypeError", LangVersion.atLeast(3, 14) ? "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer" : "an integer is required");
        }
        if (v.signum() < 0) throw new PyException("ValueError", LangVersion.atLeast(3, 14) ? "Cannot convert negative int" : "value must be positive");
        if (v.bitLength() > 16) throw new PyException("OverflowError", "Python int too large for C unsigned short");
        return v.intValue();
    }

    public static Object pRegister(Object self, Object fd, Object eventmask) {
        int f = cfd(fd);
        int m = mask(eventmask);
        Poll p = poll(self);
        synchronized (p) { p.fds.put(f, m); }
        return null;
    }

    public static Object pModify(Object self, Object fd, Object eventmask) {
        int f = cfd(fd);
        int m = mask(eventmask);                      // the arguments are converted before the descriptor is looked up
        Poll p = poll(self);
        synchronized (p) {
            if (!p.fds.containsKey(f)) throw PosixNative.osError(2, null);
            p.fds.put(f, m);
        }
        return null;
    }

    public static Object pUnregister(Object self, Object fd) {
        int f = cfd(fd);
        Poll p = poll(self);
        Integer old;
        synchronized (p) { old = p.fds.remove(f); }
        if (old == null) throw new PyException("KeyError", (long) f);
        return null;
    }

    public static Object pRepr(Object self) {
        poll(self);
        return "<select.poll object at 0x" + Long.toHexString(0x7f0000000000L + System.identityHashCode(self) * 16L) + ">";
    }

    /** {@code poll(timeout)}: milliseconds as microseconds, negative for none; 3.15 clamps what exceeds INT_MAX, earlier lanes raise. */
    private static long pollMicros(Object t) {
        if (t == null) return -1;
        if (!isNumber(t)) throw SelectModule.timeoutType(t, "an integer or None");
        double v = timeValue(t, 1e6);
        if (v < 0) {                                   // rounded away from zero (_PyTime_ROUND_TIMEOUT): -0.5 waits for ever too
            if (!LangVersion.atLeast(3, 15) && Math.floor(v) < Integer.MIN_VALUE) throw new PyException("OverflowError", "timeout is too large");
            return -1;
        }
        double ms = Math.ceil(v);
        if (ms > Integer.MAX_VALUE) {
            if (!LangVersion.atLeast(3, 15)) throw new PyException("OverflowError", "timeout is too large");
            ms = Integer.MAX_VALUE;
        }
        return (long) ms * 1000L;
    }

    public static Object pPoll(Object self, Object args, Object kwargs) {
        List<Object> given = ((PyTuple) args).items;
        if (!((PyDict) kwargs).isEmpty()) throw new PyException("TypeError", "poll.poll() takes no keyword arguments");
        if (given.size() > 1) throw new PyException("TypeError", "poll expected at most 1 argument, got " + given.size());
        long micros = pollMicros(given.isEmpty() ? null : given.get(0));
        Poll p = poll(self);
        if (!p.running.compareAndSet(false, true)) throw new PyException("RuntimeError", "concurrent poll() invocation");
        try { return pollOnce(p, micros); } finally { p.running.set(false); }
    }

    private static Object pollOnce(Poll p, long micros) {
        int[] keys, masks;
        synchronized (p) {
            keys = new int[p.fds.size()];
            masks = new int[keys.length];
            int j = 0;
            for (Map.Entry<Integer, Integer> e : p.fds.entrySet()) { keys[j] = e.getKey(); masks[j++] = e.getValue(); }
        }
        int count = keys.length;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment fds = a.allocate(Math.max(1, count) * (long) PosixSelect.POLLFD_BYTES, 8);
            for (int i = 0; i < count; i++) {          // a handler may register more while we wait: results map to this snapshot
                long at = (long) i * PosixSelect.POLLFD_BYTES;
                fds.set(JAVA_INT, at, keys[i]);
                fds.set(JAVA_SHORT, at + 4, (short) masks[i]);
            }
            int n = await(micros, now -> PosixSelect.poll(fds, count, now < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, (now + 999L) / 1000L)));
            List<Object> out = new ArrayList<>();
            if (n > 0) {
                for (int k = 0; k < count; k++) {
                    int revents = fds.get(JAVA_SHORT, (long) k * PosixSelect.POLLFD_BYTES + 6) & 0xffff;
                    if (revents != 0) out.add(tuple((long) keys[k], (long) revents));
                }
            }
            return out;
        }
    }
}
