// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * libc calls through the Foreign Function and Memory API for what java.nio does not expose ({@code setenv},
 * {@code unsetenv}, {@code fchmod} on the process streams, and the POSIX descriptor layer). Only touched on non-Windows
 * systems; errno is captured per call ({@code Linker.Option.captureCallState("errno")}) so the JVM's own libc calls
 * cannot overwrite it. errno is meaningful only after a call whose return value says it failed.
 */
final class PosixNative {
    private PosixNative() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("errno");

    /** Downcall handle for a libc function; captures errno, so the first {@code invokeExact} argument is the call state. */
    static MethodHandle fn(String name, FunctionDescriptor d, Linker.Option... extra) {
        Linker.Option[] opts = Arrays.copyOf(extra, extra.length + 1);
        opts[extra.length] = CAPTURE;
        return LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow(name), d, opts);
    }

    /** Handle without errno capture, for functions that cannot fail (malloc/free are checked by value, not errno). */
    private static MethodHandle fnNoCapture(String name, FunctionDescriptor d) {
        return LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow(name), d);
    }

    private static final MethodHandle SETENV = fn("setenv", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle UNSETENV = fn("unsetenv", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FCHMOD = fn("fchmod", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle MALLOC = fnNoCapture("malloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    private static final MethodHandle FREE = fnNoCapture("free", FunctionDescriptor.ofVoid(ADDRESS));

    /** Per-thread native state: the call-state segment and a reusable I/O buffer (both {@code Arena.ofAuto()}, GC-freed). */
    static final class Tl {
        final MemorySegment cs = Arena.ofAuto().allocate(CALL_STATE);
        MemorySegment buf = Arena.ofAuto().allocate(65536);
        long cap = 65536;
    }

    private static final ThreadLocal<Tl> TL = ThreadLocal.withInitial(Tl::new);

    static Tl tl() { return TL.get(); }

    /** The errno captured by the last downcall on {@code cs}; read it only after that call reported failure. */
    static int errno(MemorySegment cs) { return (int) ERRNO.get(cs, 0L); }

    static PyException osError(int errno, String filename) {
        return PosixModule.osError(ErrnoModule.osErrorType(errno), errno, strerror(errno), filename);
    }

    static String strerror(int errno) { return PosixErrnoText.strerror(errno); }

    /** {@code malloc(n)} as an n-byte segment (not zero-filled); NULL raises MemoryError. Release with {@link #nativeFree}. */
    static MemorySegment nativeAlloc(long n) {
        try {
            MemorySegment p = (MemorySegment) MALLOC.invokeExact(Math.max(n, 1L));   // malloc(0) may return NULL
            if (p.equals(MemorySegment.NULL)) throw new PyException("MemoryError", "");
            return p.reinterpret(n);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void nativeFree(MemorySegment p) {
        try {
            FREE.invokeExact(p);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** Own copy, not WindowsNative's: touching WindowsNative on POSIX runs its static init, which opens kernel32. */
    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    /** The POSIX runtime is Linux x86-64 glibc only; anything else is an ImportError. Called when the kernel layer is enabled. */
    static void requireSupportedHost() {
        String arch = System.getProperty("os.arch", "");
        if (!arch.equals("amd64") || LINKER.defaultLookup().find("gnu_get_libc_version").isEmpty()) {
            throw new PyException("ImportError", "pyJVM's POSIX runtime supports Linux x86-64 glibc only (found "
                + System.getProperty("os.name", "") + "/" + arch + ")");
        }
    }

    private static MemorySegment cString(Arena a, byte[] b) {
        MemorySegment seg = a.allocate(b.length + 1L);
        MemorySegment.copy(b, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, b.length);
        return seg;
    }

    /** {@code setenv(name, value, 1)}; raises OSError with the captured errno on failure. */
    static void setenv(byte[] name, byte[] value) {
        MemorySegment cs = tl().cs;
        try (Arena a = Arena.ofConfined()) {
            if ((int) SETENV.invokeExact(cs, cString(a, name), cString(a, value), 1) != 0) throw osError(errno(cs), null);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void unsetenv(byte[] name) {
        MemorySegment cs = tl().cs;
        try (Arena a = Arena.ofConfined()) {
            if ((int) UNSETENV.invokeExact(cs, cString(a, name)) != 0) throw osError(errno(cs), null);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void fchmod(int fd, int mode) {
        MemorySegment cs = tl().cs;
        try {
            if ((int) FCHMOD.invokeExact(cs, fd, mode) != 0) throw osError(errno(cs), null);
        } catch (Throwable t) { throw rethrow(t); }
    }
}
