// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static pyjvm315.runtime.NativeTypes.*;
import static pyjvm315.runtime.WindowsIocp.ERROR_IO_PENDING;
import static pyjvm315.runtime.WindowsIocp.ERROR_MORE_DATA;
import static pyjvm315.runtime.WindowsIocp.ERROR_NOT_FOUND;
import static pyjvm315.runtime.WindowsIocp.ERROR_PIPE_CONNECTED;
import static pyjvm315.runtime.WindowsIocp.STATUS_PENDING;

import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@code _overlapped}: I/O completion ports, events, thread-pool waits (RegisterWaitWithQueue) and the Overlapped
 * object with ReadFile, ReadFileInto, WriteFile, ConnectNamedPipe, WSARecv, WSARecvInto and WSASend, plus ConnectPipe, as
 * in CPython's Modules/overlapped.c. The rest of the socket parts (AcceptEx, ConnectEx, DisconnectEx, TransmitFile,
 * WSASendTo, WSARecvFrom, WSARecvFromInto, BindLocal, WSAConnect) are absent. This file also holds {@code _winapi.Overlapped} (the object
 * {@code _winapi.ConnectNamedPipe(overlapped=True)} returns), which shares the native OVERLAPPED machinery.
 *
 * <p>Lifetime. The kernel keeps the OVERLAPPED and the I/O buffer until an operation ends, and the JVM cannot run a
 * destructor at a defined point, so each Overlapped owns {@link Ov}: calloc'd memory for the OVERLAPPED and for the
 * buffer (a copy of the data to write; the bytes ReadFile fills, copied to a bytes object by getresult; for
 * ReadFileInto, copied into the caller's buffer by the first successful getresult, so the data is not visible in that
 * buffer earlier). A Cleaner frees the memory when the Python object is collected; while the operation is still pending
 * it cancels instead and parks the state as an orphan, freed once the operation has ended (and, for handles associated
 * with a completion port, once the packet was dequeued). Memory is never freed while the kernel owns it. CPython
 * raises a RuntimeError/unraisable in that situation; here there is no such report.
 */
final class WindowsOverlappedModule {
    private WindowsOverlappedModule() {}

    private static final String ME = WindowsOverlappedModule.class.getName();
    private static final long INVALID = -1L;
    private static PyClass overlappedClass, winapiClass;

    static synchronized PyModule create() {
        PyModule m = new PyModule("_overlapped", ME);
        m.attrs.put("INVALID_HANDLE_VALUE", WindowsWinApiModule.unsigned(INVALID));
        m.attrs.put("NULL", 0L);
        m.attrs.put("ERROR_IO_PENDING", 997L);
        m.attrs.put("ERROR_NETNAME_DELETED", 64L);
        m.attrs.put("ERROR_OPERATION_ABORTED", 995L);
        m.attrs.put("ERROR_PIPE_BUSY", 231L);
        m.attrs.put("ERROR_PORT_UNREACHABLE", 1234L);
        m.attrs.put("ERROR_SEM_TIMEOUT", 121L);
        m.attrs.put("INFINITE", 4294967295L);
        m.attrs.put("SO_UPDATE_ACCEPT_CONTEXT", 28683L);
        m.attrs.put("SO_UPDATE_CONNECT_CONTEXT", 28688L);
        m.attrs.put("TF_REUSE_SOCKET", 2L);

        fn(m, "CreateIoCompletionPort", (a, k) -> {
            List<Object> p = positional("CreateIoCompletionPort", a, k, 4, 4);
            long file = pointer(p.get(0)), port = pointer(p.get(1)), key = pointer(p.get(2));
            int threads = dword(p.get(3));
            long r = WindowsIocp.createIoCompletionPort(file, port, key, threads);
            if (file != INVALID && file != 0) ASSOCIATED.add(file);
            return WindowsWinApiModule.unsigned(r);
        });
        fn(m, "GetQueuedCompletionStatus", (a, k) -> {
            List<Object> p = positional("GetQueuedCompletionStatus", a, k, 2, 2);
            long port = pointer(p.get(0));
            int ms = dword(p.get(1));
            long[] r = WindowsIocp.getQueuedCompletionStatus(port, ms);
            if (r != null) {
                Ov o = LIVE.get(r[3]);
                if (o != null && o.orphaned) { ORPHANS.remove(o); o.release(); }
            }
            // a wait that the signal wakeup socket ended is a safe point: run the Python handlers of the signals that arrived
            if (SignalModule.active) SignalModule.dispatchPending();
            if (r == null) return null;
            return tuple(r[0], r[1], WindowsWinApiModule.unsigned(r[2]), WindowsWinApiModule.unsigned(r[3]));
        });
        fn(m, "PostQueuedCompletionStatus", (a, k) -> {
            List<Object> p = positional("PostQueuedCompletionStatus", a, k, 4, 4);
            long port = pointer(p.get(0));
            int n = dword(p.get(1));
            long key = pointer(p.get(2)), address = pointer(p.get(3));
            WindowsIocp.postQueuedCompletionStatus(port, n, key, address);
            return null;
        });
        fn(m, "RegisterWaitWithQueue", (a, k) -> {
            List<Object> p = positional("RegisterWaitWithQueue", a, k, 4, 4);
            long object = pointer(p.get(0)), port = pointer(p.get(1)), ov = pointer(p.get(2));
            int ms = dword(p.get(3));
            return WindowsWinApiModule.unsigned(WindowsIocp.registerWait(object, port, ov, ms));
        });
        fn(m, "UnregisterWait", (a, k) -> {
            WindowsIocp.unregisterWait(pointer(one("UnregisterWait", a, k)), 0, false);
            return null;
        });
        fn(m, "UnregisterWaitEx", (a, k) -> {
            List<Object> p = positional("UnregisterWaitEx", a, k, 2, 2);
            WindowsIocp.unregisterWait(pointer(p.get(0)), pointer(p.get(1)), true);
            return null;
        });
        fn(m, "CreateEvent", (a, k) -> {
            List<Object> p = positional("CreateEvent", a, k, 4, 4);
            boolean manual = intBool(p.get(1)), initial = intBool(p.get(2));
            String name = wideOrNone(p.get(3), "CreateEvent() argument 4");
            if (p.get(0) != null) throw new PyException("ValueError", "EventAttributes must be None");
            return WindowsWinApiModule.unsigned(WindowsIocp.createEvent(manual, initial, name));
        });
        fn(m, "SetEvent", (a, k) -> { WindowsIocp.setEvent(pointer(one("SetEvent", a, k))); return null; });
        fn(m, "ResetEvent", (a, k) -> { WindowsIocp.resetEvent(pointer(one("ResetEvent", a, k))); return null; });
        fn(m, "FormatMessage", (a, k) -> WindowsIocp.formatMessage(dword(one("FormatMessage", a, k))));
        fn(m, "ConnectPipe", (a, k) -> {
            Object addr = NativeSubclass.unwrap(one("ConnectPipe", a, k));
            if (!(addr instanceof String s)) throw new PyException("TypeError", "ConnectPipe() argument must be str, not " + PyRuntime.typeName(addr));
            noNul(s);
            // GENERIC_READ | GENERIC_WRITE, no sharing, OPEN_EXISTING, FILE_FLAG_OVERLAPPED
            return WindowsWinApiModule.unsigned(WindowsIocp.createFile(s, 0xC0000000, 0, 3, 0x40000000, 0, true));
        });

        overlappedClass = define("_overlapped", "Overlapped", null);
        newMethod(overlappedClass, "ovNew");
        method(overlappedClass, "getresult", "ovGetresult");
        method(overlappedClass, "cancel", "ovCancel");
        method(overlappedClass, "ReadFile", "ovReadFile");
        method(overlappedClass, "ReadFileInto", "ovReadFileInto");
        method(overlappedClass, "WriteFile", "ovWriteFile");
        method(overlappedClass, "ConnectNamedPipe", "ovConnectNamedPipe");
        method(overlappedClass, "WSARecv", "ovWSARecv");
        method(overlappedClass, "WSARecvInto", "ovWSARecvInto");
        method(overlappedClass, "WSASend", "ovWSASend");
        property(overlappedClass, "address", "ovAddress");
        property(overlappedClass, "pending", "ovPending");
        property(overlappedClass, "error", "ovError");
        property(overlappedClass, "event", "ovEvent");
        finish(overlappedClass);
        m.attrs.put("Overlapped", overlappedClass);
        return m;
    }

    /** {@code _winapi.Overlapped}, created with the {@code _winapi} module. */
    static synchronized PyClass winapiOverlapped() {
        if (winapiClass == null) {
            PyClass c = define("_winapi", "Overlapped", null);
            newMethod(c, "winNew");
            method(c, "GetOverlappedResult", "winGetOverlappedResult");
            method(c, "getbuffer", "winGetbuffer");
            method(c, "cancel", "winCancel");
            property(c, "event", "ovEvent");
            finish(c);
            winapiClass = c;
        }
        return winapiClass;
    }

    private static void method(PyClass cls, String pyName, String javaName) { methodVar(cls, WindowsOverlappedModule.class, pyName, javaName, "self", "args"); }

    private static void newMethod(PyClass cls, String javaName) {
        PyRuntime.classAddMethodEx(cls, "__new__", WindowsOverlappedModule.class, javaName, "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(cls, "__new__", cls.methods.get("__new__").function);
    }

    private static void property(PyClass cls, String name, String getter) { cls.properties.put(name, new PyProperty(ME, getter, null)); }

    // ---- argument conversion (what the Argument Clinic converters of overlapped.c / _winapi.c accept) ------------------

    /** Positional-only arguments, checked as _PyArg_CheckPositional does. */
    static List<Object> positional(String fn, List<Object> a, Map<Object, Object> k, int min, int max) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_overlapped." + fn + "() takes no keyword arguments");
        return count(fn, a, min, max);
    }

    private static List<Object> count(String fn, List<Object> a, int min, int max) {
        int n = a.size();
        if (n < min || n > max) {
            String what = min == max ? "expected " + min + " argument" + (min == 1 ? "" : "s")
                : n < min ? "expected at least " + min + " argument" + (min == 1 ? "" : "s") : "expected at most " + max + " argument" + (max == 1 ? "" : "s");
            throw new PyException("TypeError", fn + " " + what + ", got " + n);
        }
        return a;
    }

    private static Object integer(Object o, String modernMessage) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof Boolean b) return b ? 1L : 0L;
        if (o instanceof Long || o instanceof BigInteger) return o;
        throw new PyException("TypeError", LangVersion.atLeast(3, 14) ? modernMessage.replace("%s", PyRuntime.typeName(o)) : "an integer is required");
    }

    /** A DWORD ('k'): 0 .. 2**32-1. */
    static int dword(Object o) {
        Object v = integer(o, "'%s' object cannot be interpreted as an integer");
        BigInteger b = v instanceof BigInteger x ? x : BigInteger.valueOf((Long) v);
        if (b.signum() < 0) throw new PyException("ValueError", LangVersion.atLeast(3, 14) ? "Cannot convert negative int" : "value must be positive");
        if (b.bitLength() > 32) throw new PyException("OverflowError", LangVersion.atLeast(3, 14) ? "Python int too large for C unsigned long"
            : "Python int too large to convert to C unsigned long");
        return b.intValue();
    }

    /** A HANDLE or pointer-sized unsigned integer ('K': taken modulo 2**64, anything wider overflows). */
    static long pointer(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Long l) return l;
        if (o instanceof BigInteger b) {
            if (b.bitLength() > 64 || b.signum() < 0 && b.bitLength() > 63) throw new PyException("OverflowError", "int too big to convert");
            return b.longValue();
        }
        throw new PyException("TypeError", "an integer is required");
    }

    /** A C int used as a flag ('i'). */
    private static boolean intBool(Object o) {
        Object v = integer(o, "'%s' object cannot be interpreted as an integer");
        return v instanceof BigInteger b ? b.signum() != 0 : (Long) v != 0;
    }

    private static void noNul(String s) { if (s.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character"); }

    private static String wideOrNone(Object o, String where) {
        o = NativeSubclass.unwrap(o);
        if (o == null) return null;
        if (o instanceof String s) { noNul(s); return s; }
        throw new PyException("TypeError", where + " must be str or None, not " + PyRuntime.typeName(o));
    }

    // ---- the native state -----------------------------------------------------------------------------

    static final int NONE = 0, NOT_STARTED = 1, READ = 2, READINTO = 3, WRITE = 4, CONNECT_NAMED_PIPE = 5;
    private static final int OV_SIZE = 32, OFF_HEVENT = 24;
    private static final int ERROR_BROKEN_PIPE = 109, ERROR_OPERATION_ABORTED = 995, ERROR_IO_INCOMPLETE = 996;

    /** Every state whose OVERLAPPED may still be referenced by the kernel (or by a queued completion packet). */
    static final ConcurrentHashMap<Long, Ov> LIVE = new ConcurrentHashMap<>();
    /** States whose Python object is gone while the operation was pending. */
    static final ConcurrentLinkedQueue<Ov> ORPHANS = new ConcurrentLinkedQueue<>();
    /** Handles registered with a completion port: their completions queue a packet even after the object is gone. */
    static final java.util.Set<Long> ASSOCIATED = ConcurrentHashMap.newKeySet();
    private static final Cleaner CLEANER = Cleaner.create(r -> { Thread t = new Thread(r, "pyjvm-overlapped-cleaner"); t.setDaemon(true); return t; });

    static final class Ov {
        final long ov;
        final MemorySegment seg;
        final boolean winapi;
        long buf, bufLen;
        Object target;                       // ReadFileInto's destination
        long handle;
        int type = NONE, error;
        boolean orphaned, copied, freed;
        boolean wpending, wcompleted;        // _winapi.Overlapped flags

        Ov(long ov, boolean winapi) { this.ov = ov; this.seg = WindowsIocp.view(ov, OV_SIZE); this.winapi = winapi; }

        long internal() { return seg.get(JAVA_LONG, 0); }
        long event() { return seg.get(JAVA_LONG, OFF_HEVENT); }
        boolean completed() { return internal() != STATUS_PENDING; }
        boolean pending() { return !completed() && type != NOT_STARTED; }

        /** overlapped.c mark_as_completed. */
        void markCompleted() {
            seg.set(JAVA_LONG, 0, 0L);
            if (event() != 0) WindowsIocp.setEventQuiet(event());
        }

        /** Overlapped_clear: an operation that failed to start owns nothing. */
        void clear() {
            WindowsIocp.free(buf);
            buf = 0; bufLen = 0; target = null;
            type = NOT_STARTED;
        }

        synchronized void release() {
            if (freed) return;
            freed = true;
            long ev = event();
            if (ev != 0) WindowsIocp.closeQuiet(ev);
            LIVE.remove(ov);
            WindowsIocp.free(buf);
            WindowsIocp.free(ov);
            buf = 0; target = null;
        }
    }

    /** What runs when the Python object has been collected. Holds the state only, never the object. */
    private static final class Reaper implements Runnable {
        private final Ov s;
        Reaper(Ov s) { this.s = s; }

        @Override public void run() {
            try {
                synchronized (s) {
                    if (s.freed) return;
                    if (!s.pending()) { s.release(); return; }
                    s.orphaned = true;
                    s.target = null;
                }
                WindowsIocp.cancelIoEx(s.handle, s.ov);          // not waited for, the memory stays until the operation ends
                ORPHANS.add(s);
            } catch (Throwable ignored) {
                // a finalizer has nobody to report to
            }
        }
    }

    /** Frees the orphans whose operation has ended and that cannot produce a completion packet any more. */
    private static void sweep() {
        for (Ov s : ORPHANS) {
            synchronized (s) {
                if (s.freed) { ORPHANS.remove(s); continue; }
                if (s.completed() && !ASSOCIATED.contains(s.handle)) { ORPHANS.remove(s); s.release(); }
            }
        }
    }

    private static PyInstance instantiate(PyClass cls, Ov s) {
        PyInstance i = newInstance(cls, s);
        CLEANER.register(i, new Reaper(s));
        return i;
    }

    private static Ov state(Object self) { return NativeTypes.state(self, Ov.class, "_overlapped.Overlapped"); }
    private static Ov winState(Object self) { return NativeTypes.state(self, Ov.class, "_winapi.Overlapped"); }

    private static Ov newState(boolean winapi) {
        sweep();
        long ov = WindowsIocp.calloc(OV_SIZE);
        if (ov == 0) throw new PyException("MemoryError", null);
        Ov s = new Ov(ov, winapi);
        LIVE.put(ov, s);
        return s;
    }

    // ---- _overlapped.Overlapped ----------------------------------------------------------------------

    public static Object ovNew(Object cls, Object args, Object kwargs) {
        List<Object> pos = ((PyTuple) args).items;
        Map<?, ?> kw = (Map<?, ?>) kwargs;
        Object eventArg = null;
        boolean given = false;
        if (pos.isEmpty() && kw.size() > 1) throw new PyException("TypeError", "Overlapped() takes at most 1 keyword argument (" + kw.size() + " given)");
        for (Map.Entry<?, ?> e : kw.entrySet()) {
            if (!"event".equals(e.getKey())) throw new PyException("TypeError", "Overlapped() got an unexpected keyword argument '" + e.getKey() + "'");
            eventArg = e.getValue(); given = true;
        }
        if (pos.size() + kw.size() > 1) throw new PyException("TypeError", pos.size() > 1 ? "Overlapped() takes at most 1 argument (" + (pos.size() + kw.size()) + " given)"
            : "argument for Overlapped() given by name ('event') and position (1)");
        if (!pos.isEmpty()) { eventArg = pos.get(0); given = true; }
        long event = given ? pointer(eventArg) : INVALID;
        if (event == INVALID) event = WindowsIocp.createEvent(true, false, null);
        Ov s;
        try { s = newState(false); } catch (RuntimeException e) { if (event != 0) WindowsIocp.closeQuiet(event); throw e; }
        if (event != 0) s.seg.set(JAVA_LONG, OFF_HEVENT, event);
        return instantiate((PyClass) cls, s);
    }

    public static Object ovAddress(Object self) { return WindowsWinApiModule.unsigned(state(self).ov); }
    public static Object ovPending(Object self) { return state(self).pending(); }
    public static Object ovError(Object self) { return Integer.toUnsignedLong(state(self).error); }
    public static Object ovEvent(Object self) { return WindowsWinApiModule.unsigned(state(self).event()); }

    /** Method arguments; the single-argument and no-argument methods are METH_O / METH_NOARGS in CPython, with their own messages. */
    private static List<Object> margs(String fn, Object args, int min, int max) {
        List<Object> a = ((PyTuple) args).items;
        int n = a.size();
        if (max == 0 && n > 0) throw new PyException("TypeError", "Overlapped." + fn + "() takes no arguments (" + n + " given)");
        if (min == 1 && max == 1 && (fn.equals("ConnectNamedPipe") || fn.equals("GetOverlappedResult")) && n != 1)
            throw new PyException("TypeError", "Overlapped." + fn + "() takes exactly one argument (" + n + " given)");
        return count(fn, a, min, max);
    }

    /** A module function taking exactly one positional argument (METH_O). */
    private static Object one(String fn, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_overlapped." + fn + "() takes no keyword arguments");
        if (a.size() != 1) throw new PyException("TypeError", "_overlapped." + fn + "() takes exactly one argument (" + a.size() + " given)");
        return a.get(0);
    }

    public static Object ovCancel(Object self, Object args) {
        Ov s = state(self);
        margs("cancel", args, 0, 0);
        if (s.type == NOT_STARTED) return null;
        int err = 0;
        if (!s.completed()) err = WindowsIocp.cancelIoEx(s.handle, s.ov);
        if (err != 0 && err != ERROR_NOT_FOUND) throw WindowsIocp.OverlappedErrors.set(err);
        return null;
    }

    public static Object ovGetresult(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("getresult", args, 0, 1);
        boolean wait = p.isEmpty() ? false : intBool(p.get(0));
        int type;
        synchronized (s) {
            if (s.type == NONE) throw new PyException("ValueError", "operation not yet attempted");
            if (s.type == NOT_STARTED) throw new PyException("ValueError", "operation failed to start");
            type = s.type;
        }
        long[] r = WindowsIocp.getOverlappedResult(s.handle, s.ov, wait);     // may block: no lock held
        int err = (int) r[0];
        int n = (int) r[1];
        synchronized (s) {
            s.error = err;
            switch (err) {
                case 0, ERROR_MORE_DATA -> { }
                case ERROR_BROKEN_PIPE -> { if (type != READ && type != READINTO) throw WindowsIocp.OverlappedErrors.set(err); }
                default -> throw WindowsIocp.OverlappedErrors.set(err);
            }
            if (s.freed) throw new PyException("ValueError", "operation failed to start");
            switch (type) {
                case READ: {
                    byte[] data = new byte[(int) Math.min(n, s.bufLen)];
                    WindowsIocp.view(s.buf, Math.max(1, data.length)).asByteBuffer().get(data, 0, data.length);
                    return new PyBytes(data);
                }
                case READINTO: {
                    if (!s.copied) {
                        byte[] data = new byte[(int) Math.min(n, s.bufLen)];
                        WindowsIocp.view(s.buf, Math.max(1, data.length)).asByteBuffer().get(data, 0, data.length);
                        IoUtil.fill(s.target, data, 0, data.length);
                        s.copied = true;
                    }
                    return Integer.toUnsignedLong(n);
                }
                default:
                    return Integer.toUnsignedLong(n);
            }
        }
    }

    private static long bufferLength(Object buf) {
        try { return IoUtil.bufferSize(buf); }
        catch (PyException e) { throw new PyException("TypeError", "a bytes-like object is required, not '" + PyRuntime.typeName(NativeSubclass.unwrap(buf)) + "'"); }
    }

    private static void ensureNew(Ov s) {
        if (s.type != NONE) throw new PyException("ValueError", "operation already attempted");
    }

    /** do_ReadFile. */
    private static Object doReadFile(Ov s, long handle, int len) {
        int err = WindowsIocp.readFile(handle, s.buf, len, s.ov);
        s.error = err;
        switch (err) {
            case ERROR_BROKEN_PIPE:
                s.markCompleted();
                throw WindowsIocp.OverlappedErrors.set(err);
            case 0, ERROR_MORE_DATA, ERROR_IO_PENDING:
                return null;
            default:
                s.clear();
                throw WindowsIocp.OverlappedErrors.set(err);
        }
    }

    public static Object ovReadFile(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("ReadFile", args, 2, 2);
        long handle = pointer(p.get(0));
        int size = dword(p.get(1));
        synchronized (s) {
            ensureNew(s);
            long cap = Math.max(Integer.toUnsignedLong(size), 1);
            long buf = WindowsIocp.calloc(cap);
            if (buf == 0) throw new PyException("MemoryError", null);
            s.type = READ; s.handle = handle; s.buf = buf; s.bufLen = cap;
            return doReadFile(s, handle, size);
        }
    }

    public static Object ovReadFileInto(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("ReadFileInto", args, 2, 2);
        long handle = pointer(p.get(0));
        Object target = p.get(1);
        long len = bufferLength(target);
        synchronized (s) {
            ensureNew(s);
            if (len > 0xFFFFFFFFL) throw new PyException("ValueError", "buffer too large");
            long buf = WindowsIocp.calloc(Math.max(len, 1));
            if (buf == 0) throw new PyException("MemoryError", null);
            s.type = READINTO; s.handle = handle; s.buf = buf; s.bufLen = Math.max(len, 1); s.target = target;
            return doReadFile(s, handle, (int) len);
        }
    }

    public static Object ovWriteFile(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("WriteFile", args, 2, 2);
        long handle = pointer(p.get(0));
        byte[] data = IoUtil.bytesLike(p.get(1));
        synchronized (s) {
            ensureNew(s);
            long buf = WindowsIocp.calloc(Math.max(data.length, 1));
            if (buf == 0) throw new PyException("MemoryError", null);
            if (data.length > 0) WindowsIocp.view(buf, data.length).copyFrom(MemorySegment.ofArray(data));
            s.type = WRITE; s.handle = handle; s.buf = buf; s.bufLen = data.length;
            int err = WindowsIocp.writeFile(handle, buf, data.length, s.ov);
            s.error = err;
            switch (err) {
                case 0, ERROR_IO_PENDING: return null;
                default:
                    s.clear();
                    throw WindowsIocp.OverlappedErrors.set(err);
            }
        }
    }

    /** do_WSARecv. */
    private static Object doWsaRecv(Ov s, long handle, int len, int flags) {
        int err = WindowsSockets.wsaRecv(handle, s.buf, len, flags, s.ov);
        s.error = err;
        switch (err) {
            case ERROR_BROKEN_PIPE:
                s.markCompleted();
                throw WindowsIocp.OverlappedErrors.set(err);
            case 0, ERROR_MORE_DATA, ERROR_IO_PENDING:
                return null;
            default:
                s.clear();
                throw WindowsIocp.OverlappedErrors.set(err);
        }
    }

    public static Object ovWSARecv(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("WSARecv", args, 2, 3);
        long handle = pointer(p.get(0));
        int size = dword(p.get(1));
        int flags = p.size() > 2 ? dword(p.get(2)) : 0;
        synchronized (s) {
            ensureNew(s);
            long cap = Math.max(Integer.toUnsignedLong(size), 1);
            long buf = WindowsIocp.calloc(cap);
            if (buf == 0) throw new PyException("MemoryError", null);
            s.type = READ; s.handle = handle; s.buf = buf; s.bufLen = cap;
            return doWsaRecv(s, handle, size, flags);
        }
    }

    public static Object ovWSARecvInto(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("WSARecvInto", args, 3, 3);
        long handle = pointer(p.get(0));
        Object target = p.get(1);
        long len = bufferLength(target);
        int flags = dword(p.get(2));
        synchronized (s) {
            ensureNew(s);
            if (len > 0xFFFFFFFFL) throw new PyException("ValueError", "buffer too large");
            long buf = WindowsIocp.calloc(Math.max(len, 1));
            if (buf == 0) throw new PyException("MemoryError", null);
            s.type = READINTO; s.handle = handle; s.buf = buf; s.bufLen = Math.max(len, 1); s.target = target;
            return doWsaRecv(s, handle, (int) len, flags);
        }
    }

    public static Object ovWSASend(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("WSASend", args, 3, 3);
        long handle = pointer(p.get(0));
        byte[] data = IoUtil.bytesLike(p.get(1));
        int flags = dword(p.get(2));
        synchronized (s) {
            ensureNew(s);
            long buf = WindowsIocp.calloc(Math.max(data.length, 1));
            if (buf == 0) throw new PyException("MemoryError", null);
            if (data.length > 0) WindowsIocp.view(buf, data.length).copyFrom(MemorySegment.ofArray(data));
            s.type = WRITE; s.handle = handle; s.buf = buf; s.bufLen = data.length;
            int err = WindowsSockets.wsaSend(handle, buf, data.length, flags, s.ov);
            s.error = err;
            switch (err) {
                case 0, ERROR_IO_PENDING: return null;
                default:
                    s.clear();
                    throw WindowsIocp.OverlappedErrors.set(err);
            }
        }
    }

    public static Object ovConnectNamedPipe(Object self, Object args) {
        Ov s = state(self);
        List<Object> p = margs("ConnectNamedPipe", args, 1, 1);
        long pipe = pointer(p.get(0));
        synchronized (s) {
            ensureNew(s);
            s.type = CONNECT_NAMED_PIPE; s.handle = pipe;
            int err = WindowsIocp.connectNamedPipe(pipe, s.ov);
            s.error = err;
            switch (err) {
                case ERROR_PIPE_CONNECTED:
                    s.markCompleted();
                    return Boolean.TRUE;
                case 0, ERROR_IO_PENDING:
                    return Boolean.FALSE;
                default:
                    s.clear();
                    throw WindowsIocp.OverlappedErrors.set(err);
            }
        }
    }

    // ---- _winapi.Overlapped ---------------------------------------------------------------------------

    public static Object winNew(Object cls, Object args, Object kwargs) {
        throw new PyException("TypeError", "cannot create '_winapi.Overlapped' instances");
    }

    /** {@code _winapi.ConnectNamedPipe(handle, overlapped=True)}: starts the connect and returns the Overlapped. */
    static Object connectNamedPipeOverlapped(long handle) {
        Ov s = newState(true);
        s.handle = handle;
        s.type = CONNECT_NAMED_PIPE;
        long ev = WindowsIocp.createEvent(true, false, null);     // manual reset, initially non-signalled
        s.seg.set(JAVA_LONG, OFF_HEVENT, ev);
        int err = WindowsIocp.connectNamedPipe(handle, s.ov);
        if (err == ERROR_IO_PENDING) s.wpending = true;
        else if (err == ERROR_PIPE_CONNECTED) WindowsIocp.setEventQuiet(ev);
        else {
            s.release();
            throw WindowsNative.winError(err, null);
        }
        return instantiate(winapiOverlapped(), s);
    }

    public static Object winGetOverlappedResult(Object self, Object args) {
        Ov s = winState(self);
        List<Object> p = margs("GetOverlappedResult", args, 1, 1);
        boolean wait = PyRuntime.truth(p.get(0));
        long[] r = WindowsIocp.getOverlappedResult(s.handle, s.ov, wait);
        int err = (int) r[0];
        switch (err) {
            case 0, ERROR_MORE_DATA, ERROR_OPERATION_ABORTED -> { s.wcompleted = true; s.wpending = false; }
            case ERROR_IO_INCOMPLETE -> { }
            default -> { s.wpending = false; throw WindowsNative.winError(err, null); }
        }
        return tuple(r[1], Integer.toUnsignedLong(err));
    }

    public static Object winGetbuffer(Object self, Object args) {
        Ov s = winState(self);
        margs("getbuffer", args, 0, 0);
        if (!s.wcompleted) throw new PyException("ValueError", "can't get read buffer before GetOverlappedResult() signals the operation completed");
        return null;
    }

    public static Object winCancel(Object self, Object args) {
        Ov s = winState(self);
        margs("cancel", args, 0, 0);
        int err = 0;
        if (s.wpending) err = WindowsIocp.cancelIoEx(s.handle, s.ov);
        if (err != 0 && err != ERROR_NOT_FOUND) throw WindowsNative.winError(err, null);
        s.wpending = false;
        return null;
    }
}
