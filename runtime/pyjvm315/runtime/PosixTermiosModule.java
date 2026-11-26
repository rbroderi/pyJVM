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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * The {@code termios} module on kernel descriptors (n2_plan 1.8, 1.3, 3.6, 3.8): {@code tcgetattr}, {@code tcsetattr},
 * {@code tcsendbreak}, {@code tcdrain}, {@code tcflush}, {@code tcflow}, {@code tcgetwinsize}, {@code tcsetwinsize}, the
 * 234 integer constants, and {@code termios.error}. POSIX with {@code IoFd.KERNEL} only: under
 * {@code PYJVM_POSIX_FDS=nio} and on Windows the module does not exist ({@code ModuleNotFoundError}); never loaded on
 * Windows.
 * <p>
 * {@code termios.error} is one class per module object, base {@code Exception} (not an {@code OSError}: no
 * {@code errno} attribute), raised with {@code args == (errno, strerror)}. The 60-byte glibc {@code struct termios} is
 * zero-filled native memory in a per-call arena. {@code tcsetattr} converts its arguments in CPython's order (measured on
 * the three lanes, {@code work/n2/measure/termios_order*.txt}): fd, {@code when}, the 7-element list check, then
 * {@code tcgetattr} (so its failure, 25 for a pipe, comes before any attribute is converted), the four flags, the two
 * speeds, the 32-element {@code cc} list and its items, then {@code cfsetispeed}, {@code cfsetospeed}, {@code tcsetattr}.
 * An interrupted call (EINTR) runs pending Python handlers and is retried (PEP 475); a handler does not run while the main
 * thread is blocked inside {@code tcdrain}, {@code tcsendbreak}, {@code tcflow} or a draining {@code tcsetattr} (plan 1.5).
 * <p>
 * Known gap: {@code bool} used as a descriptor does not give CPython's {@code RuntimeWarning: bool is used as a file
 * descriptor} ({@link PosixArgs#fd} is shared with {@code fcntl}).
 */
final class PosixTermiosModule {
    private static final Linker.Option VARIADIC2 = Linker.Option.firstVariadicArg(2);
    private static final MethodHandle TCGETATTR = PosixNative.fn("tcgetattr", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle TCSETATTR = PosixNative.fn("tcsetattr", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CFGETISPEED = PosixNative.fnNoCapture("cfgetispeed", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CFGETOSPEED = PosixNative.fnNoCapture("cfgetospeed", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CFSETISPEED = PosixNative.fn("cfsetispeed", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CFSETOSPEED = PosixNative.fn("cfsetospeed", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle TCSENDBREAK = PosixNative.fn("tcsendbreak", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle TCFLUSH = PosixNative.fn("tcflush", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle TCFLOW = PosixNative.fn("tcflow", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle TCDRAIN = PosixNative.fn("tcdrain", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    /** {@code ioctl(fd, request, void *)}: only {@code TIOCGWINSZ}/{@code TIOCSWINSZ} with a {@code struct winsize}. */
    private static final MethodHandle IOCTL_PTR = PosixNative.fn("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), VARIADIC2);

    private static final int EINTR = 4, ICANON = 2, VTIME = 5, VMIN = 6, NCCS = 32;
    private static final long TIOCGWINSZ = 0x5413L, TIOCSWINSZ = 0x5414L;
    /** glibc {@code struct termios}: iflag, oflag, cflag, lflag u32 at 0, 4, 8, 12; line u8 at 16; cc[32] at 17; ispeed, ospeed at 52, 56. */
    private static final int TERMIOS_SIZE = 60, TERMIOS_ALLOC = 64, CC_OFFSET = 17;

    private final PyClass error;

    private PosixTermiosModule(PyClass error) { this.error = error; }

    static PyModule create() {
        PyClass error = (PyClass) PyRuntime.classCreate("error", "termios");
        PyRuntime.classAddBase(error, PyRuntime.builtinType("Exception"));
        NativeTypes.finish(error);
        PosixTermiosModule t = new PosixTermiosModule(error);
        PyModule m = new PyModule("termios", PosixTermiosModule.class.getName());
        PosixLibcConsts.install(m, PosixLibcConsts.TERMIOS_NAMES, PosixLibcConsts.TERMIOS_VALUES);
        m.attrs.put("error", error);
        fn(m, "tcgetattr", t::tcgetattr);
        fn(m, "tcsetattr", t::tcsetattr);
        fn(m, "tcsendbreak", (a, k) -> t.fdInt("tcsendbreak", TCSENDBREAK, a, k));
        fn(m, "tcflush", (a, k) -> t.fdInt("tcflush", TCFLUSH, a, k));
        fn(m, "tcflow", (a, k) -> t.fdInt("tcflow", TCFLOW, a, k));
        fn(m, "tcdrain", t::tcdrain);
        fn(m, "tcgetwinsize", t::tcgetwinsize);
        fn(m, "tcsetwinsize", t::tcsetwinsize);
        return m;
    }

    // ---------------------------------------------------------------- argument checks (the texts of the generated clinic code)

    private static void noKeywords(String name, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "termios." + name + "() takes no keyword arguments");
    }

    /** A function taking exactly one positional argument ({@code METH_O}). */
    private static void one(String name, List<Object> a, Map<Object, Object> k) {
        noKeywords(name, k);
        if (a.size() != 1) throw new PyException("TypeError", "termios." + name + "() takes exactly one argument (" + a.size() + " given)");
    }

    /** A function taking exactly {@code n} positional arguments ({@code _PyArg_CheckPositional}). */
    private static void exactly(String name, List<Object> a, Map<Object, Object> k, int n) {
        noKeywords(name, k);
        if (a.size() != n) throw new PyException("TypeError", name + " expected " + n + " arguments, got " + a.size());
    }

    private PyException err(int errno) {
        return PyRuntime.userException(PyRuntime.instantiate(error, new Object[]{(long) errno, PosixNative.strerror(errno)}));
    }

    /** {@code PyLong_AsLong}: {@code __index__} accepted, else the integer TypeError; beyond 64 bits the C long OverflowError. */
    private static long cLong(Object o) {
        BigInteger v = BuiltinsExtra.rangeIndex(o);
        if (v.bitLength() > 63) throw new PyException("OverflowError", "Python int too large to convert to C long");
        return v.longValue();
    }

    private static boolean isInt(Object u) { return u instanceof Long || u instanceof BigInteger || u instanceof Boolean; }

    // ---------------------------------------------------------------- raw calls: the result or -errno

    /** Runs a call that can be interrupted: EINTR runs pending handlers (a raising handler propagates) and retries. */
    private static int run(IntSupplier call) {
        while (true) {
            int r = call.getAsInt();
            if (r != -EINTR) return r;
            SignalModule.dispatchPending();
        }
    }

    private static int callIntInt(MethodHandle h, int fd, int x) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) h.invokeExact(cs, fd, x);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int callInt(MethodHandle h, int fd) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) h.invokeExact(cs, fd);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int callPtr(MethodHandle h, int fd, MemorySegment p) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) h.invokeExact(cs, fd, p);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int callSpeed(MethodHandle h, MemorySegment p, int speed) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) h.invokeExact(cs, p, speed);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int speedOf(MethodHandle h, MemorySegment p) {
        try { return (int) h.invokeExact(p); } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int ioctl(int fd, long request, MemorySegment p) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) IOCTL_PTR.invokeExact(cs, fd, request, p);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    // ---------------------------------------------------------------- tcgetattr / tcsetattr

    /**
     * {@code tcgetattr(fd)}: {@code [iflag, oflag, cflag, lflag, ispeed, ospeed, cc]}; the flags are unsigned, {@code cc} has
     * 32 items, each {@code bytes} of length 1, except that with {@code ICANON} off in {@code lflag} the items {@code VMIN}
     * and {@code VTIME} are ints.
     */
    private Object tcgetattr(List<Object> a, Map<Object, Object> k) {
        one("tcgetattr", a, k);
        int fd = PosixArgs.fd(a.get(0));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment t = arena.allocate(TERMIOS_ALLOC, 8);
            int r = run(() -> callPtr(TCGETATTR, fd, t));
            if (r < 0) throw err(-r);
            int lflag = t.get(JAVA_INT, 12L);
            List<Object> cc = new ArrayList<>(NCCS);
            for (int i = 0; i < NCCS; i++) {
                byte c = t.get(JAVA_BYTE, CC_OFFSET + i);
                cc.add((lflag & ICANON) == 0 && (i == VMIN || i == VTIME) ? (Object) (long) (c & 0xff) : new PyBytes(new byte[]{c}));
            }
            List<Object> out = new ArrayList<>(7);
            for (int i = 0; i < 4; i++) out.add(Integer.toUnsignedLong(t.get(JAVA_INT, 4L * i)));
            out.add(Integer.toUnsignedLong(speedOf(CFGETISPEED, t)));
            out.add(Integer.toUnsignedLong(speedOf(CFGETOSPEED, t)));
            out.add(cc);
            return out;
        }
    }

    /**
     * {@code tcsetattr(fd, when, attributes)}: see the class comment for the order of the checks. Flags are C longs
     * truncated to 32 bits, speeds likewise (then {@code cfsetispeed}/{@code cfsetospeed}), each {@code cc} item is a
     * {@code bytes} of length 1 or an int truncated to a byte.
     */
    private Object tcsetattr(List<Object> a, Map<Object, Object> k) {
        exactly("tcsetattr", a, k, 3);
        int fd = PosixArgs.fd(a.get(0));
        int when = PosixOsProc.cInt(a.get(1));
        if (!(NativeSubclass.unwrap(a.get(2)) instanceof List<?> attrs) || attrs.size() != 7)
            throw new PyException("TypeError", "tcsetattr, arg 3: must be 7 element list");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment t = arena.allocate(TERMIOS_ALLOC, 8);   // zero-filled; c_line and hidden fields come from tcgetattr
            int r = run(() -> callPtr(TCGETATTR, fd, t));
            if (r < 0) throw err(-r);
            for (int i = 0; i < 4; i++) t.set(JAVA_INT, 4L * i, (int) cLong(attrs.get(i)));
            int ispeed = (int) cLong(attrs.get(4));
            int ospeed = (int) cLong(attrs.get(5));
            if (!(NativeSubclass.unwrap(attrs.get(6)) instanceof List<?> cc) || cc.size() != NCCS)
                throw new PyException("TypeError", "tcsetattr: attributes[6] must be " + NCCS + " element list");
            for (int i = 0; i < NCCS; i++) {
                Object u = NativeSubclass.unwrap(cc.get(i));
                byte c;
                if (u instanceof PyBytes b && b.byteSize() == 1) c = (byte) b.unsignedAt(0);
                else if (isInt(u)) c = (byte) cLong(u);
                else throw new PyException("TypeError", LangVersion.atLeast(3, 14)
                    ? "tcsetattr: elements of attributes must be bytes objects of length 1 or integers"
                    : "tcsetattr: elements of attributes must be characters or integers");
                t.set(JAVA_BYTE, CC_OFFSET + i, c);
            }
            r = callSpeed(CFSETISPEED, t, ispeed);
            if (r < 0) throw err(-r);
            r = callSpeed(CFSETOSPEED, t, ospeed);
            if (r < 0) throw err(-r);
            r = run(() -> callIntPtr(fd, when, t));
            if (r < 0) throw err(-r);
        }
        return null;
    }

    private static int callIntPtr(int fd, int when, MemorySegment t) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) TCSETATTR.invokeExact(cs, fd, when, t);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    // ---------------------------------------------------------------- the simple calls

    /** {@code tcsendbreak(fd, duration)}, {@code tcflush(fd, queue)}, {@code tcflow(fd, action)}: both arguments C ints. */
    private Object fdInt(String name, MethodHandle h, List<Object> a, Map<Object, Object> k) {
        exactly(name, a, k, 2);
        int fd = PosixArgs.fd(a.get(0));
        int x = PosixOsProc.cInt(a.get(1));
        int r = run(() -> callIntInt(h, fd, x));
        if (r < 0) throw err(-r);
        return null;
    }

    private Object tcdrain(List<Object> a, Map<Object, Object> k) {
        one("tcdrain", a, k);
        int fd = PosixArgs.fd(a.get(0));
        int r = run(() -> callInt(TCDRAIN, fd));
        if (r < 0) throw err(-r);
        return null;
    }

    // ---------------------------------------------------------------- window size

    /** {@code tcgetwinsize(fd)}: {@code ioctl(fd, TIOCGWINSZ)} as {@code (rows, cols)}. */
    private Object tcgetwinsize(List<Object> a, Map<Object, Object> k) {
        one("tcgetwinsize", a, k);
        int fd = PosixArgs.fd(a.get(0));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment w = arena.allocate(8, 8);
            int r = run(() -> ioctl(fd, TIOCGWINSZ, w));
            if (r < 0) throw err(-r);
            return NativeTypes.tuple((long) (w.get(JAVA_SHORT, 0L) & 0xffff), (long) (w.get(JAVA_SHORT, 2L) & 0xffff));
        }
    }

    /**
     * {@code tcsetwinsize(fd, winsize)}: a sequence of exactly two (CPython's {@code PySequence_Check} and size), both items
     * converted as C longs, then {@code TIOCGWINSZ} (so an unsuitable descriptor fails before the range check), each value
     * in {@code [0, 65535]} else {@code OverflowError: winsize value(s) out of range.}, rows and columns replaced (the
     * pixel fields kept), {@code TIOCSWINSZ}.
     */
    private Object tcsetwinsize(List<Object> a, Map<Object, Object> k) {
        exactly("tcsetwinsize", a, k, 2);
        int fd = PosixArgs.fd(a.get(0));
        Object ws = a.get(1);
        if (!isSequence(ws) || !(PyRuntime.len(ws) instanceof Long n) || n != 2) throw new PyException("TypeError", "tcsetwinsize, arg 2: must be a two-item sequence");
        long rows = cLong(PyRuntime.getitem(ws, 0L));
        long cols = cLong(PyRuntime.getitem(ws, 1L));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment w = arena.allocate(8, 8);
            int r = run(() -> ioctl(fd, TIOCGWINSZ, w));
            if (r < 0) throw err(-r);
            if (rows < 0 || rows > 0xffff || cols < 0 || cols > 0xffff) throw new PyException("OverflowError", "winsize value(s) out of range.");
            w.set(JAVA_SHORT, 0L, (short) rows);
            w.set(JAVA_SHORT, 2L, (short) cols);
            r = run(() -> ioctl(fd, TIOCSWINSZ, w));
            if (r < 0) throw err(-r);
        }
        return null;
    }

    /** {@code PySequence_Check}: str, bytes-like, list, tuple, range, array, or a class with {@code __getitem__} that is not a mapping. */
    private static boolean isSequence(Object o) {
        Object u = NativeSubclass.unwrap(o);
        if (u instanceof Map<?, ?> || u instanceof java.util.Set<?>) return false;
        if (u instanceof String || u instanceof PyByteSequence || u instanceof List<?> || u instanceof PyTuple || u instanceof PyRange
            || u instanceof PyMemoryView || ArrayModule.asArray(u) != null) return true;
        return o instanceof PyInstance p && p.payload == null && p.cls.lookupMethod("__getitem__") != null;
    }
}
