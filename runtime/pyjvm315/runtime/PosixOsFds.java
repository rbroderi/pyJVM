// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static pyjvm315.runtime.NativeTypes.*;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.util.List;

/**
 * The {@code posix} functions that work on kernel descriptors ({@code IoFd.KERNEL}): registered over the shared
 * java.nio-era lambdas by {@code PosixFiles.install}, before {@code AuditEvents.install} wraps whatever is registered.
 * Never loaded on Windows.
 */
final class PosixOsFds {
    private PosixOsFds() {}

    // These cannot fail, so no errno capture.
    private static final MethodHandle UMASK = PosixNative.fnNoCapture("umask", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GETUID = id("getuid"), GETEUID = id("geteuid"), GETGID = id("getgid"), GETEGID = id("getegid"), GETPPID = id("getppid");

    private static MethodHandle id(String name) { return PosixNative.fnNoCapture(name, FunctionDescriptor.of(JAVA_INT)); }

    static void install(PyModule m) {
        fn(m, "close", (a, k) -> { PosixFds.close(PosixFiles.fd(a.get(0))); return null; });
        fn(m, "read", (a, k) -> {
            long fd = PosixFds.cfd(PosixFiles.fd(a.get(0)));       // fd converted first, as CPython (OverflowError before EINVAL)
            long len = IoUtil.asLong(a.get(1));
            if (len < 0) throw PosixNative.osError(22, null);
            return new PyBytes(PosixFds.read(fd, (int) Math.min(len, 1 << 30)));
        });
        fn(m, "write", (a, k) -> {
            long fd = PosixFiles.fd(a.get(0));
            byte[] b = IoUtil.bytesLike(a.get(1));
            return (long) PosixFds.write(fd, b, 0, b.length);
        });
        fn(m, "lseek", (a, k) -> PosixFds.lseek(PosixFiles.fd(a.get(0)), IoUtil.asLong(a.get(1)), (int) IoUtil.asLong(a.get(2))));
        fn(m, "ftruncate", (a, k) -> { PosixFds.ftruncate(PosixFiles.fd(a.get(0)), IoUtil.asLong(a.get(1))); return null; });
        fn(m, "fsync", (a, k) -> { PosixFds.fsync(PosixFiles.fd(a.get(0))); return null; });
        fn(m, "fdatasync", (a, k) -> { PosixFds.fdatasync(PosixFiles.fd(a.get(0))); return null; });
        fn(m, "dup", (a, k) -> PosixFds.dup(PosixFiles.fd(a.get(0))));
        fn(m, "isatty", (a, k) -> PosixFds.isatty(PosixFds.cfd(PosixFiles.fd(a.get(0)))));   // os.isatty(2**40): OverflowError as in CPython
        fn(m, "fstat", (a, k) -> PosixFds.fstat(PosixFiles.fd(a.get(0))));
        fn(m, "fchmod", (a, k) -> {
            PosixFiles.checkFchmodArgs(a, k);
            long fd = PosixFiles.fd(a.get(0)), mode = IoUtil.asLong(a.get(1));
            PosixNative.fchmod(PosixFds.cfd(fd), PosixFds.cfd(mode));   // mode is a C int: OverflowError rather than truncation
            return null;
        });
        fn(m, "get_inheritable", (a, k) -> PosixFds.getInheritable(PosixFiles.fd(a.get(0))));
        fn(m, "set_inheritable", (a, k) -> { PosixFds.setInheritable(PosixFiles.fd(a.get(0)), IoUtil.asLong(a.get(1)) != 0); return null; });   // C int argument
        fn(m, "get_blocking", (a, k) -> PosixFds.getBlocking(PosixFiles.fd(a.get(0))));
        fn(m, "set_blocking", (a, k) -> { PosixFds.setBlocking(PosixFiles.fd(a.get(0)), PyRuntime.truth(a.get(1))); return null; });
        fn(m, "umask", (a, k) -> umask(PosixFds.cfd(PosixFiles.fd(a.get(0)))));
        fn(m, "getuid", (a, k) -> ids(GETUID));
        fn(m, "geteuid", (a, k) -> ids(GETEUID));
        fn(m, "getgid", (a, k) -> ids(GETGID));
        fn(m, "getegid", (a, k) -> ids(GETEGID));
        fn(m, "getppid", (a, k) -> ids(GETPPID));
        fn(m, "get_terminal_size", (a, k) -> terminalSize(m, a));
    }

    /** {@code umask(2)} stores {@code mask & 0777} and returns the previous mask; nothing is cached on the Java side. */
    private static Object umask(int mask) {
        try { return Integer.toUnsignedLong((int) UMASK.invokeExact(mask)); } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    /** uid_t, gid_t and pid_t results as unsigned 32-bit values. */
    private static Object ids(MethodHandle h) {
        try { return Integer.toUnsignedLong((int) h.invokeExact()); } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    /** {@code os.get_terminal_size(fd=1)}, built like nt's: an {@code os.terminal_size((columns, lines))}. */
    private static Object terminalSize(PyModule m, List<Object> a) {
        long[] size = PosixFds.winsize(a.isEmpty() ? 1L : PosixFiles.fd(a.get(0)));
        return PyRuntime.instantiate((PyClass) m.attrs.get("terminal_size"), new Object[]{tuple(size[0], size[1])});
    }
}
