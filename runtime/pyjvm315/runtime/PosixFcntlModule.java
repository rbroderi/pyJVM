// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static pyjvm315.runtime.NativeTypes.fn;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * The {@code fcntl} module on kernel descriptors (n2_plan 1.4, 1.5): {@code fcntl}, {@code flock}, {@code lockf} and the
 * 79 integer constants. POSIX with {@code IoFd.KERNEL} only (under {@code PYJVM_POSIX_FDS=nio} the module is the
 * {@code StubModules} shell); never loaded on Windows.
 * <p>
 * Buffer arguments are copied into a zero-filled native buffer of {@code max(len, 1024)} bytes plus 8 slack bytes (the
 * kernel never sees heap memory and may write a whole struct), and exactly {@code len} bytes are read back. Descriptors
 * created by {@code F_DUPFD}/{@code F_DUPFD_CLOEXEC} are adopted ({@code PosixFds.adopt}). An interrupted call (EINTR)
 * runs pending handlers and is retried (PEP 475).
 * <p>
 * <b>Lock waits</b> ({@code flock} without {@code LOCK_NB}, {@code F_SETLKW} and {@code F_OFD_SETLKW} through
 * {@code fcntl.fcntl}, blocking {@code lockf}) follow {@link #lockWait}. Owner decision (2026-11-25, N2 Q3): on the main
 * thread with {@code _signal} imported a waiting lock is <i>polled</i> with the non-waiting command, backing off from 1 ms
 * to 50 ms with {@code PosixProc.backOff} (the back-off of {@code PosixProc.await}). Trade-off: signal handlers and
 * Ctrl-C ({@code _thread.interrupt_main}) run at most about 50 ms late and a handler that raises propagates without the
 * lock being taken; in exchange the kernel's FIFO queue of waiters and its {@code EDEADLK} deadlock detection do not apply
 * to a polled wait (a polled waiter can be overtaken by a later one, and a deadlock waits forever instead of failing), and
 * the lock can be acquired up to 50 ms after its release. Off the main thread, or before {@code _signal} is imported, the
 * wait is one blocking call as in CPython.
 * <p>
 * Known consequences, CPython semantics, no guard: Python can change flags of JVM-owned descriptors by number; POSIX
 * record locks ({@code lockf}, {@code F_SETLK}) belong to the process and are dropped when any descriptor of that file is
 * closed in the process, including opens and closes the JDK makes on its own. {@code flock} and OFD locks are per open
 * file description and unaffected. {@code ioctl} is added by N2 ticket 5.
 */
final class PosixFcntlModule {
    private PosixFcntlModule() {}

    private static final Linker.Option VARIADIC2 = Linker.Option.firstVariadicArg(2);
    /**
     * {@code fcntl(fd, cmd, long)}: the integer form. CPython passes a 32-bit C int; the x86-64 ABI leaves the upper 32
     * bits of that register undefined, so the value is passed zero-extended (as plan 1.4 does for the ioctl request): the
     * kernel's int commands read only the low 32 bits.
     */
    private static final MethodHandle FCNTL_INT = PosixNative.fn("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG), VARIADIC2);
    /** {@code fcntl(fd, cmd, void *)}: the buffer form. */
    private static final MethodHandle FCNTL_PTR = PosixNative.fn("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), VARIADIC2);
    private static final MethodHandle FLOCK = PosixNative.fn("flock", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

    private static final int EINTR = 4, EAGAIN = 11, EACCES = 13;
    private static final int F_DUPFD = 0, F_SETLK = 6, F_SETLKW = 7, F_DUPFD_CLOEXEC = 1030, F_OFD_SETLK = 37, F_OFD_SETLKW = 38;
    private static final short F_RDLCK = 0, F_WRLCK = 1, F_UNLCK = 2;
    private static final int LOCK_SH = 1, LOCK_EX = 2, LOCK_NB = 4, LOCK_UN = 8;
    private static final int BUF_MIN = 1024, SLACK = 8, FLOCK_SIZE = 32;
    private static final Object MISSING = new Object();

    static PyModule create() {
        PyModule m = new PyModule("fcntl", PosixFcntlModule.class.getName());
        PosixLibcConsts.install(m, PosixLibcConsts.FCNTL_NAMES, PosixLibcConsts.FCNTL_VALUES);
        fn(m, "fcntl", PosixFcntlModule::fcntl);
        fn(m, "flock", PosixFcntlModule::flock);
        fn(m, "lockf", PosixFcntlModule::lockf);
        return m;
    }

    /** Argument Clinic's checks for a positional-only function: keywords first, then {@code _PyArg_CheckPositional}. */
    private static void positional(String name, List<Object> a, Map<Object, Object> k, int min, int max) {
        if (!k.isEmpty()) throw new PyException("TypeError", "fcntl." + name + "() takes no keyword arguments");
        int n = a.size();
        if (n < min) throw new PyException("TypeError", name + " expected " + (min == max ? "" : "at least ") + min + " argument" + (min == 1 ? "" : "s") + ", got " + n);
        if (n > max) throw new PyException("TypeError", name + " expected " + (min == max ? "" : "at most ") + max + " argument" + (max == 1 ? "" : "s") + ", got " + n);
    }

    // ---------------------------------------------------------------- fcntl.fcntl

    /**
     * {@code fcntl(fd, cmd, arg=0, /)}: fd per {@link PosixArgs#fd}, cmd a C int, then the audit event
     * {@code (fd, cmd, arg or None)}, then the third argument: an integer ({@code I} format) gives the call's int result; a
     * str or bytes-like object (per lane, {@link PosixArgs#readBuffer}) is passed through a native buffer and returned as
     * bytes of the same length; anything else is the lane's TypeError.
     */
    private static Object fcntl(List<Object> a, Map<Object, Object> k) {
        positional("fcntl", a, k, 2, 3);
        int fd = PosixArgs.fd(a.get(0));
        int cmd = PosixOsProc.cInt(a.get(1));
        Object arg = a.size() > 2 ? a.get(2) : null;
        if (SysExtras.auditing()) SysExtras.audit("fcntl.fcntl", (long) fd, (long) cmd, arg);
        if (a.size() < 3) return intForm(fd, cmd, 0);
        boolean v314 = LangVersion.atLeast(3, 14);
        if (v314 && PosixArgs.isIndex(arg)) return intForm(fd, cmd, PosixArgs.uintMask(arg));
        byte[] b = PosixArgs.readBuffer(arg);
        if (b != null) return bufferForm(fd, cmd, b);
        if (v314) throw PosixArgs.badArg3("fcntl", arg);
        return intForm(fd, cmd, PosixArgs.uintMask(arg));    // 3.13: "s#" failed, so "I" (its TypeError for anything else)
    }

    private static Object intForm(int fd, int cmd, int v) {
        int r = isLockWait(cmd) ? lockWait(w -> fcntlInt(fd, w ? cmd : nonWaiting(cmd), v)) : retry(() -> fcntlInt(fd, cmd, v));
        if (r < 0) throw PosixNative.osError(-r, null);
        if (cmd == F_DUPFD || cmd == F_DUPFD_CLOEXEC) PosixFds.adopt(r);   // a new descriptor (plain F_DUPFD: inheritable, as asked)
        return (long) r;
    }

    private static Object bufferForm(int fd, int cmd, byte[] b) {
        PosixArgs.checkBufferLength(b.length, "fcntl");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(Math.max(b.length, BUF_MIN) + SLACK, 8);   // zero-filled
            MemorySegment.copy(b, 0, buf, JAVA_BYTE, 0, b.length);
            int r = isLockWait(cmd) ? lockWait(w -> fcntlPtr(fd, w ? cmd : nonWaiting(cmd), buf)) : retry(() -> fcntlPtr(fd, cmd, buf));
            if (r < 0) throw PosixNative.osError(-r, null);
            byte[] out = new byte[b.length];
            MemorySegment.copy(buf, JAVA_BYTE, 0, out, 0, b.length);
            return new PyBytes(out);
        }
    }

    private static boolean isLockWait(int cmd) { return cmd == F_SETLKW || cmd == F_OFD_SETLKW; }

    /** The non-waiting command a polled wait uses: {@code F_SETLK} for {@code F_SETLKW}, {@code F_OFD_SETLK} for {@code F_OFD_SETLKW}. */
    private static int nonWaiting(int cmd) { return cmd == F_SETLKW ? F_SETLK : F_OFD_SETLK; }

    // ---------------------------------------------------------------- fcntl.flock

    /** {@code flock(fd, operation, /)}: audit {@code (fd, operation)}; without {@code LOCK_NB} through {@link #lockWait}. */
    private static Object flock(List<Object> a, Map<Object, Object> k) {
        positional("flock", a, k, 2, 2);
        int fd = PosixArgs.fd(a.get(0));
        int op = PosixOsProc.cInt(a.get(1));
        if (SysExtras.auditing()) SysExtras.audit("fcntl.flock", (long) fd, (long) op);
        int r = (op & LOCK_NB) != 0 ? retry(() -> flockCall(fd, op)) : lockWait(w -> flockCall(fd, w ? op : op | LOCK_NB));
        if (r < 0) throw PosixNative.osError(-r, null);
        return null;
    }

    // ---------------------------------------------------------------- fcntl.lockf

    /**
     * {@code lockf(fd, cmd, len=0, start=0, whence=0, /)}, CPython's {@code fcntl_lockf_impl}: fd, cmd and whence (C ints)
     * are converted first; audit {@code (fd, cmd, len or None, start or None, whence)}; then cmd is mapped as CPython does
     * (measured on the three lanes, {@code work/n2/t4} scratch): exactly {@code LOCK_UN} is {@code F_UNLCK}, else any
     * {@code LOCK_SH} bit is {@code F_RDLCK}, else any {@code LOCK_EX} bit is {@code F_WRLCK}, else
     * {@code ValueError: unrecognized lockf argument} (so {@code LOCK_UN | LOCK_NB} is that ValueError); then start and len
     * as C longs (start first); {@code struct flock} per plan 3.2; {@code F_SETLK} if {@code LOCK_NB} is set, else
     * {@code F_SETLKW} through {@link #lockWait}. libc {@code lockf(3)} is not used (nor by CPython).
     */
    private static Object lockf(List<Object> a, Map<Object, Object> k) {
        positional("lockf", a, k, 2, 5);
        int fd = PosixArgs.fd(a.get(0));
        int code = PosixOsProc.cInt(a.get(1));
        Object lenObj = a.size() > 2 ? a.get(2) : MISSING;
        Object startObj = a.size() > 3 ? a.get(3) : MISSING;
        int whence = a.size() > 4 ? PosixOsProc.cInt(a.get(4)) : 0;
        if (SysExtras.auditing())
            SysExtras.audit("fcntl.lockf", (long) fd, (long) code, lenObj == MISSING ? null : lenObj, startObj == MISSING ? null : startObj, (long) whence);
        short type;
        if (code == LOCK_UN) type = F_UNLCK;
        else if ((code & LOCK_SH) != 0) type = F_RDLCK;
        else if ((code & LOCK_EX) != 0) type = F_WRLCK;
        else throw new PyException("ValueError", "unrecognized lockf argument");
        long start = startObj == MISSING ? 0 : cLong(startObj);
        long len = lenObj == MISSING ? 0 : cLong(lenObj);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment l = arena.allocate(FLOCK_SIZE, 8);    // zero-filled; l_pid stays 0
            l.set(JAVA_SHORT, 0L, type);
            l.set(JAVA_SHORT, 2L, (short) whence);              // C assigns the int to a short, as CPython does
            l.set(JAVA_LONG, 8L, start);
            l.set(JAVA_LONG, 16L, len);
            int r = (code & LOCK_NB) != 0 ? retry(() -> fcntlPtr(fd, F_SETLK, l)) : lockWait(w -> fcntlPtr(fd, w ? F_SETLKW : F_SETLK, l));
            if (r < 0) throw PosixNative.osError(-r, null);
        }
        return null;
    }

    /** {@code PyLong_AsLong}: {@code __index__} accepted, else the integer TypeError; beyond 64 bits the C long OverflowError. */
    private static long cLong(Object o) {
        java.math.BigInteger v = BuiltinsExtra.rangeIndex(o);
        if (v.bitLength() > 63) throw new PyException("OverflowError", "Python int too large to convert to C long");
        return v.longValue();
    }

    // ---------------------------------------------------------------- the lock-wait rule (plan 1.5)

    /** One lock call: {@code wait} true is the blocking command, false its non-waiting substitute; returns >= 0 or {@code -errno}. */
    @FunctionalInterface
    interface LockCall { int call(boolean wait); }

    /**
     * The lock-wait rule (n2_plan 1.5, owner decision Q3). Off the main thread, or before {@code _signal} is imported: one
     * blocking call, retried after EINTR once pending handlers have run. On the main thread with signals active the
     * kernel wait is never entered: the non-waiting command is tried; a conflict ({@code EAGAIN} or {@code EACCES}) or an
     * {@code EINTR} runs pending handlers (one that raises propagates, the lock not taken) and backs off with
     * {@link PosixProc#backOff} (1 ms doubling to 50 ms; a pending {@code interrupt_main} is KeyboardInterrupt); any other
     * result is returned at once, straight from the call that produced it.
     */
    static int lockWait(LockCall call) {
        if (!(SignalModule.active && SignalModule.isMain())) {
            while (true) {
                int r = call.call(true);
                if (r != -EINTR) return r;
                SignalModule.dispatchPending();
            }
        }
        long delay = 1;
        while (true) {
            int r = call.call(false);
            if (r != -EAGAIN && r != -EACCES && r != -EINTR) return r;
            SignalModule.dispatchPending();
            delay = PosixProc.backOff(delay);
        }
    }

    /** A call that is not a lock wait: retried after EINTR once pending handlers have run (PEP 475). */
    private static int retry(IntSupplier call) {
        while (true) {
            int r = call.getAsInt();
            if (r != -EINTR) return r;
            SignalModule.dispatchPending();
        }
    }

    // ---------------------------------------------------------------- raw calls: the result or -errno

    private static int fcntlInt(int fd, int cmd, int v) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) FCNTL_INT.invokeExact(cs, fd, cmd, Integer.toUnsignedLong(v));
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int fcntlPtr(int fd, int cmd, MemorySegment p) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) FCNTL_PTR.invokeExact(cs, fd, cmd, p);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int flockCall(int fd, int op) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) FLOCK.invokeExact(cs, fd, op);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }
}
