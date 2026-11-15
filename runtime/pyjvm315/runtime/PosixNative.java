// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * libc calls through the Foreign Function and Memory API for what java.nio does not expose ({@code setenv},
 * {@code unsetenv}, {@code fchmod} on the process streams). Only touched on non-Windows systems; errno is captured per
 * call ({@code Linker.Option.captureCallState("errno")}) so the JVM's own libc calls cannot overwrite it.
 */
final class PosixNative {
    private PosixNative() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("errno");

    private static MethodHandle libc(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow(name), fd, CAPTURE);
    }

    private static final MethodHandle SETENV = libc("setenv", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle UNSETENV = libc("unsetenv", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FCHMOD = libc("fchmod", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

    /** Own copy, not WindowsNative's: touching WindowsNative on POSIX runs its static init, which opens kernel32. */
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    private static MemorySegment cString(Arena a, byte[] b) {
        MemorySegment seg = a.allocate(b.length + 1L);
        MemorySegment.copy(b, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, b.length);
        return seg;
    }

    /** {@code setenv(name, value, 1)}; raises OSError with the captured errno on failure. */
    static void setenv(byte[] name, byte[] value) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            if ((int) SETENV.invokeExact(state, cString(a, name), cString(a, value), 1) != 0) throw error(state);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void unsetenv(byte[] name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            if ((int) UNSETENV.invokeExact(state, cString(a, name)) != 0) throw error(state);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void fchmod(int fd, int mode) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            if ((int) FCHMOD.invokeExact(state, fd, mode) != 0) throw error(state);
        } catch (Throwable t) { throw rethrow(t); }
    }

    private static PyException error(MemorySegment state) {
        int errno = (int) ERRNO.get(state, 0L);
        String type = switch (errno) {
            case 1, 13 -> "PermissionError";
            case 2 -> "FileNotFoundError";
            case 17 -> "FileExistsError";
            case 20 -> "NotADirectoryError";
            case 21 -> "IsADirectoryError";
            default -> "OSError";
        };
        return PosixModule.osError(type, errno, PosixModule.strerror(errno), null);
    }
}
