// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static pyjvm315.runtime.NativeTypes.fn;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;

/**
 * The {@code os} pseudo-terminal functions: {@code openpty}, {@code ttyname}, {@code posix_openpt}, {@code grantpt},
 * {@code unlockpt}, {@code ptsname} (n2_plan 1.8). {@code openpty} is deliberately not libc's: it is
 * {@code posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC)}, {@code grantpt}, {@code unlockpt}, {@code ptsname_r} and an
 * {@code open} of the slave with {@code O_RDWR | O_NOCTTY | O_CLOEXEC}, so a JVM that happens to be a session leader never
 * acquires the pty as its controlling terminal. Every descriptor created here is {@link PosixFds#adopt adopted}.
 * Never loaded on Windows.
 */
final class PosixOsTty {
    private PosixOsTty() {}

    private static final int O_RDWR = 2, O_NOCTTY = 0x100, O_CLOEXEC = 0x80000, ERANGE = 34, EINTR = 4;
    private static final long NAME_LIMIT = 1L << 20;
    /** Test hook (n2 ticket 6): when positive, the first name buffer of {@code ptsname_r}/{@code ttyname_r} has this size. Read once. */
    private static final long TEST_BUF = Math.min(Long.getLong("pyjvm.test.ptsbuf", 0L), NAME_LIMIT);

    private static final MethodHandle POSIX_OPENPT = PosixNative.fn("posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GRANTPT = PosixNative.fn("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle UNLOCKPT = PosixNative.fn("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle PTSNAME_R = PosixNative.fnNoCapture("ptsname_r", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle TTYNAME_R = PosixNative.fnNoCapture("ttyname_r", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));

    static void install(PyModule m) {
        fn(m, "openpty", (a, k) -> {
            if (!k.isEmpty()) throw new PyException("TypeError", "posix.openpty() takes no keyword arguments");
            if (!a.isEmpty()) throw new PyException("TypeError", "posix.openpty() takes no arguments (" + a.size() + " given)");
            return openpty();
        });
        fn(m, "ttyname", (a, k) -> {
            one("ttyname", a, k);
            return name(TTYNAME_R, PosixOsProc.cInt(a.get(0)));
        });
        fn(m, "posix_openpt", (a, k) -> {
            one("posix_openpt", a, k);
            return (long) posixOpenpt(PosixOsProc.cInt(a.get(0)) | O_CLOEXEC);
        });
        fn(m, "grantpt", (a, k) -> {
            one("grantpt", a, k);
            ptCall(GRANTPT, PosixArgs.fd(a.get(0)));
            return null;
        });
        fn(m, "unlockpt", (a, k) -> {
            one("unlockpt", a, k);
            ptCall(UNLOCKPT, PosixArgs.fd(a.get(0)));
            return null;
        });
        fn(m, "ptsname", (a, k) -> {
            one("ptsname", a, k);
            return name(PTSNAME_R, PosixArgs.fd(a.get(0)));
        });
    }

    private static void one(String name, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "posix." + name + "() takes no keyword arguments");
        if (a.size() != 1) throw new PyException("TypeError", "posix." + name + "() takes exactly one argument (" + a.size() + " given)");
    }

    /** {@code posix_openpt(flags)}; the result is adopted. */
    private static int posixOpenpt(int flags) {
        PosixNative.Tl t = PosixNative.tl();
        int fd;
        try { fd = (int) POSIX_OPENPT.invokeExact(t.cs, flags); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (fd < 0) throw PosixNative.osError(PosixNative.errno(t.cs), null);
        PosixFds.adopt(fd);
        return fd;
    }

    /** {@code grantpt}/{@code unlockpt}: 0 or an errno. */
    private static void ptCall(MethodHandle h, int fd) {
        PosixNative.Tl t = PosixNative.tl();
        int r;
        try { r = (int) h.invokeExact(t.cs, fd); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r != 0) throw PosixNative.osError(PosixNative.errno(t.cs), null);
    }

    /**
     * {@code ttyname_r}/{@code ptsname_r}: the name as a str (UTF-8, surrogateescape). Both return the error number (no
     * errno is read); the buffer starts at 64 bytes and doubles while the answer is {@code ERANGE}.
     */
    private static String name(MethodHandle h, int fd) {
        long size = TEST_BUF > 0 ? TEST_BUF : 64;
        while (true) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment buf = a.allocate(size);
                int r;
                try { r = (int) h.invokeExact(fd, buf, size); } catch (Throwable x) { throw PosixNative.rethrow(x); }
                if (r == 0) {
                    long n = 0;
                    while (n < size && buf.get(JAVA_BYTE, n) != 0) n++;
                    byte[] b = new byte[(int) n];
                    MemorySegment.copy(buf, JAVA_BYTE, 0, b, 0, (int) n);
                    return Codecs.decode(b, "utf-8", "surrogateescape");
                }
                if (r != ERANGE) throw PosixNative.osError(r, null);
            }
            if (size >= NAME_LIMIT) throw PosixNative.osError(ERANGE, null);
            size *= 2;
        }
    }

    /**
     * {@code os.openpty()}: see the class comment. A failure at any step (any Throwable) closes what was opened. Known
     * difference: a failing slave open raises an OSError that carries the slave path as its filename, where CPython's
     * libc {@code openpty} gives {@code None}.
     */
    private static Object openpty() {
        int master = posixOpenpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        boolean done = false;
        try {
            ptCall(GRANTPT, master);
            ptCall(UNLOCKPT, master);
            String slaveName = name(PTSNAME_R, master);
            long slave = PosixFds.open(slaveName, O_RDWR | O_NOCTTY, 0);   // adds O_CLOEXEC, adopts
            Object r = NativeTypes.tuple((long) master, slave);
            done = true;
            return r;
        } finally {
            if (!done) {
                try { PosixFds.close(master); } catch (RuntimeException ignored) { }
            }
        }
    }
}
