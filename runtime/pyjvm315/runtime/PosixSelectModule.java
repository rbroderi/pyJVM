// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static pyjvm315.runtime.NativeTypes.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code select} module over kernel descriptors: {@code select()} is {@code select(2)}, {@code poll()} is
 * {@code poll(2)} and {@code epoll} is {@code epoll_create1/epoll_ctl/epoll_wait} ({@link PosixSelect}), so readiness,
 * EBADF and the 1024 limit are the kernel's. Reached only through
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
        NativeFunction pollFn = new NativeFunction("poll", (a, k) -> makePoll(pollClass, a, k));
        pollFn.attrs.put("__module__", "select");
        m.attrs.put("poll", pollFn);
        addEpoll(m);
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
                // a list is read live, as CPython's seq2set over PySequence_Fast: its size is re-read after every
                // fileno() call, so a fileno() that shrinks the list ends the scan (test_select_mutated). Only an exact
                // list is a bare ArrayList here; a list subclass is a PyInstance (payload ArrayList) and is snapshotted
                // by seq(), which matches CPython: PySequence_Fast returns the object itself only for exact lists and
                // tuples, and copies a subclass through iteration.
                Object arg = args.get(i);
                List<Object> items;
                int[] got;
                if (arg instanceof ArrayList<?> live) {
                    items = new ArrayList<>();
                    got = new int[live.size()];
                    for (int j = 0; j < live.size(); j++) {
                        Object x = live.get(j);
                        int fd = cfd(x);
                        if (fd >= PosixSelect.FD_SETSIZE) throw new PyException("ValueError", "filedescriptor out of range in select()");
                        if (items.size() == got.length) got = java.util.Arrays.copyOf(got, got.length * 2 + 1);
                        got[items.size()] = fd;
                        items.add(x);
                    }
                    got = java.util.Arrays.copyOf(got, items.size());
                } else {
                    items = SelectModule.seq(arg);
                    got = null;
                }
                lists.add(items);
                fds[i] = new int[items.size()];
                for (int j = 0; j < fds[i].length; j++) {
                    int fd = got != null ? got[j] : cfd(items.get(j));
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

    /** {@code type(select.poll())()}: the poll type cannot be instantiated, only {@code select.poll()} makes one (as CPython). */
    public static Object pollNew(Object cls, Object args, Object kwargs) {
        throw new PyException("TypeError", "cannot create 'select.poll' instances");
    }

    /** The module function {@code select.poll()}. */
    private static Object makePoll(PyClass cls, List<Object> args, Map<Object, Object> kwargs) {
        if (!kwargs.isEmpty()) throw new PyException("TypeError", "select.poll() takes no keyword arguments");
        if (!args.isEmpty()) throw new PyException("TypeError", "select.poll() takes no arguments (" + args.size() + " given)");
        return newInstance(cls, new Poll());
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

    // ---------------------------------------------------------------- epoll

    private static final int EPOLL_CTL_ADD = 1, EPOLL_CTL_DEL = 2, EPOLL_CTL_MOD = 3;
    private static final String CLOSED = "I/O operation on closed epoll object";
    /** The native event buffer holds at most this many events whatever maxevents says (a shorter answer is indistinguishable but for a pathological count). */
    private static final int MAX_BUFFER_EVENTS = 65536;
    /** {@code EP_MAX_EVENTS} of the kernel (INT_MAX / sizeof(struct epoll_event)): epoll_wait fails with EINVAL above it. */
    private static final int KERNEL_MAX_EVENTS = Integer.MAX_VALUE / PosixSelect.EPOLL_EVENT_BYTES;

    private static final String[] EPOLL_NAMES = {"EPOLLIN", "EPOLLOUT", "EPOLLPRI", "EPOLLERR", "EPOLLHUP", "EPOLLET", "EPOLLONESHOT",
        "EPOLLRDNORM", "EPOLLRDBAND", "EPOLLWRNORM", "EPOLLWRBAND", "EPOLLMSG", "EPOLLRDHUP", "EPOLL_CLOEXEC", "EPOLLWAKEUP"};
    private static final long[] EPOLL_VALUES = {1, 4, 2, 8, 16, 0x80000000L, 0x40000000L, 64, 128, 256, 512, 1024, 8192, 524288, 0x20000000L};

    /** The cleaner that closes an epoll descriptor whose object was collected without close() (one daemon thread, started on first use). */
    private static final class EpollCleaner {
        static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create(r -> {
            Thread t = new Thread(r, "pyjvm-epoll-cleaner");
            t.setDaemon(true);
            return t;
        });
    }

    /** The Java state of one epoll object. Never refers to the Python object (the Cleaner holds it). */
    private static final class Ep {
        final AtomicInteger fd;
        final long gen;
        Ep(int fd, long gen) { this.fd = new AtomicInteger(fd); this.gen = gen; }
    }

    private static final class Reaper implements Runnable {
        private final Ep ep;
        Reaper(Ep ep) { this.ep = ep; }
        @Override public void run() {
            try {
                int f = ep.fd.getAndSet(-1);
                if (f >= 0 && PosixFds.gen(f) == ep.gen) PosixFds.close(f);     // the number may have been closed and reused meanwhile (plan 1.7)
            } catch (Throwable ignored) { /* a finalizer has nobody to report to */ }
        }
    }

    private static void addEpoll(PyModule m) {
        for (int i = 0; i < EPOLL_NAMES.length; i++) {
            if (EPOLL_NAMES[i].equals("EPOLLWAKEUP") && !LangVersion.atLeast(3, 14)) continue;      // measured on 3.13, 3.14, 3.15
            m.attrs.put(EPOLL_NAMES[i], EPOLL_VALUES[i]);
        }
        PyClass c = define("select", "epoll", null);
        PyRuntime.classAddMethodEx(c, "__new__", PosixSelectModule.class, "epNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(c, "__new__", c.methods.get("__new__").function);
        PyRuntime.classAddMethodEx(c, "fromfd", PosixSelectModule.class, "epFromfd", "class", "", "cls,fd", "", null, null, new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(c, "fromfd", PyRuntime.methodDescriptor(c.methods.get("fromfd").function, "class"));
        method(c, PosixSelectModule.class, "close", "epClose", "self");
        method(c, PosixSelectModule.class, "fileno", "epFileno", "self");
        method(c, PosixSelectModule.class, "register", "epRegister", "self,fd,eventmask", defaults("eventmask", (long) (POLLIN | POLLPRI | POLLOUT)));
        method(c, PosixSelectModule.class, "modify", "epModify", "self,fd,eventmask");
        method(c, PosixSelectModule.class, "unregister", "epUnregister", "self,fd");
        method(c, PosixSelectModule.class, "poll", "epPoll", "self,timeout,maxevents", defaults("timeout", null, "maxevents", -1L));
        method(c, PosixSelectModule.class, "__repr__", "epRepr", "self");
        method(c, PosixSelectModule.class, "__enter__", "epEnter", "self");
        methodVar(c, PosixSelectModule.class, "__exit__", "epExit", "self", "args");
        c.properties.put("closed", new PyProperty(ME, "epClosed", null));
        finish(c);
        NativeTypes.seal(c);
        NativeTypes.noPickle(c, false);
        m.attrs.put("epoll", c);
    }

    /** A Python int argument as a C int. */
    private static int cint(Object o) {
        BigInteger v = BuiltinsExtra.rangeIndex(o);
        if (v.bitLength() > 31) throw new PyException("OverflowError", "Python int too large to convert to C int");
        return v.intValue();
    }

    private static Ep ep(Object self) { return state(self, Ep.class, "select.epoll"); }

    /** The open descriptor of {@code e}, or the closed-object ValueError. */
    private static int open(Ep e) {
        int f = e.fd.get();
        if (f < 0) throw new PyException("ValueError", CLOSED);
        return f;
    }

    private static Object newEpoll(Object cls, int fd, boolean create) {
        Ep e;
        if (create) {
            int r = PosixSelect.epollCreate();
            if (r < 0) throw PosixNative.osError(-r, null);
            PosixFds.adopt(r);
            e = new Ep(r, PosixFds.gen(r));
        } else e = new Ep(fd, PosixFds.gen(fd));              // fromfd wraps the number as it is: nothing adopted
        PyInstance inst = newInstance((PyClass) cls, e);
        EpollCleaner.CLEANER.register(inst, new Reaper(e));
        return inst;
    }

    /** {@code epoll(sizehint=-1, flags=0)}; the argument errors are the ones of CPython's generated parser. */
    public static Object epNew(Object cls, Object args, Object kwargs) {
        List<Object> given = ((PyTuple) args).items;
        if (given.size() > 2) throw new PyException("TypeError", "epoll() takes at most 2 arguments (" + given.size() + " given)");
        String[] names = {"sizehint", "flags"};
        Object[] v = new Object[2];
        for (int i = 0; i < given.size(); i++) v[i] = given.get(i);
        for (Map.Entry<Object, Object> kv : ((PyDict) kwargs).entrySet()) {
            int i = kv.getKey() instanceof String k ? java.util.Arrays.asList(names).indexOf(k) : -1;
            if (i < 0) throw new PyException("TypeError", "epoll() got an unexpected keyword argument '" + kv.getKey() + "'");
            if (i < given.size()) throw new PyException("TypeError", "argument for epoll() given by name ('" + names[i] + "') and position (" + (i + 1) + ")");
            v[i] = kv.getValue();
        }
        int sizehint = v[0] == null ? -1 : cint(v[0]);
        int flags = v[1] == null ? 0 : cint(v[1]);
        if (sizehint != -1 && sizehint <= 0) throw new PyException("ValueError", "negative sizehint");
        if (flags != 0 && flags != PosixSelect.EPOLL_CLOEXEC) throw new PyException("OSError", "invalid flags");
        return newEpoll(cls, -1, true);
    }

    /** {@code epoll.fromfd(fd)}: wraps the number without looking at it; -1 makes a new epoll descriptor, as in CPython. */
    public static Object epFromfd(Object cls, Object fd) {
        int f = cint(fd);
        if (f < -1) throw PosixModule.osError(ErrnoModule.osErrorType(0), 0, "Error", null);     // CPython: epoll_create1 failed with a leftover errno (host-dependent), 3.15 shows 0
        return newEpoll(cls, f, f == -1);
    }

    public static Object epClose(Object self) {
        int f = ep(self).fd.getAndSet(-1);
        if (f < 0) return null;
        try { PosixFds.close(f); }
        catch (PyException e) { if (LangVersion.atLeast(3, 15)) throw e; }     // 3.13 and 3.14 ignore a failing close (os.close(e.fileno()) first)
        return null;
    }

    public static Object epRepr(Object self) {
        ep(self);
        return "<select.epoll object at 0x" + Long.toHexString(0x7f0000000000L + System.identityHashCode(self) * 16L) + ">";
    }

    public static Object epClosed(Object self) { return ep(self).fd.get() < 0; }

    public static Object epFileno(Object self) { return (long) open(ep(self)); }

    public static Object epEnter(Object self) {
        open(ep(self));
        return self;
    }

    public static Object epExit(Object self, Object args) { return epClose(self); }

    /** The event mask: the low 32 bits of the Python int ({@code bitwise} unsigned int in CPython), no range error. */
    private static int epMask(Object o) {
        BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (LangVersion.atLeast(3, 15) && (b.bitLength() > 32 || b.signum() < 0 && b.bitLength() > 31))     // outside [-2**31, 2**32)
            PyRuntime.callMethod(ImportSupport.importBuiltin(new ArrayList<Object>(List.of("warnings")), new java.util.LinkedHashMap<Object, Object>()), "warn",
                new Object[]{"integer value out of range", PyRuntime.builtinType("DeprecationWarning"), 2L});      // through the Python module so catch_warnings sees it (the native method frame counts)
        return b.intValue();
    }

    /** The descriptor and the mask are converted before the closed check (measured on 3.13, 3.14, 3.15). */
    private static Object ctl(Object self, int op, int fd, int events) {
        try {
            int r = PosixSelect.epollCtl(open(ep(self)), op, fd, events);
            if (r < 0) throw PosixNative.osError(-r, null);
            return null;
        } finally { java.lang.ref.Reference.reachabilityFence(self); }     // the Cleaner must not close the descriptor mid-call
    }

    public static Object epRegister(Object self, Object fd, Object eventmask) {
        int f = cfd(fd);
        return ctl(self, EPOLL_CTL_ADD, f, epMask(eventmask));
    }

    public static Object epModify(Object self, Object fd, Object eventmask) {
        int f = cfd(fd);
        return ctl(self, EPOLL_CTL_MOD, f, epMask(eventmask));
    }

    public static Object epUnregister(Object self, Object fd) {
        return ctl(self, EPOLL_CTL_DEL, cfd(fd), 0);
    }

    /** {@code poll(timeout)}: seconds to microseconds (whole milliseconds, rounded up), negative for no timeout. */
    private static long epollMicros(Object t) {
        if (t == null) return -1;
        if (!isNumber(t)) throw SelectModule.timeoutType(t, "an integer or None");
        double v = timeValue(t, 1e9);
        double ns = v < 0 ? Math.floor(v * 1e9) : Math.ceil(v * 1e9);          // _PyTime_ROUND_TIMEOUT: away from zero
        long ms = (long) Math.ceil(ns / 1e6);
        if (ms > Integer.MAX_VALUE || ms < Integer.MIN_VALUE) throw new PyException("OverflowError", "timeout is too large");
        return ms < 0 ? -1 : ms * 1000L;                                      // a negative number of milliseconds waits for ever; -0.0005 s is 0 ms
    }

    /** {@code epoll.poll(timeout=None, maxevents=-1)}: maxevents is converted, then the closed check, then the timeout, then maxevents' range. */
    public static Object epPoll(Object self, Object timeout, Object maxevents) {
        int max = cint(maxevents);
        try {
            int epfd = open(ep(self));
            long micros = epollMicros(timeout);
            if (max == -1) max = 1023;
            else if (max < 1) throw new PyException("ValueError", "maxevents must be greater than 0, got " + max);
            if (max > KERNEL_MAX_EVENTS) throw PosixNative.osError(22, null);
            return waitEvents(epfd, micros, Math.min(max, MAX_BUFFER_EVENTS));
        } finally { java.lang.ref.Reference.reachabilityFence(self); }     // the Cleaner must not close the descriptor mid-wait
    }

    private static Object waitEvents(int epfd, long micros, int count) {
        int eb = PosixSelect.EPOLL_EVENT_BYTES;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ev = a.allocate((long) count * eb, 4);
            int n = await(micros, now -> PosixSelect.epollWait(epfd, ev, count, now < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, (now + 999L) / 1000L)));
            List<Object> out = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                long at = (long) k * eb;
                long events = Integer.toUnsignedLong(ev.get(JAVA_INT_UNALIGNED, at));
                out.add(tuple((long) ev.get(JAVA_INT_UNALIGNED, at + 4), events));      // data: the descriptor, low 32 bits signed
            }
            return out;
        }
    }
}
