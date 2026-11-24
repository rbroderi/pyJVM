// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * libc calls for process-level functions (N1 plan 3.1). This ticket: {@code sysconf} and {@code confstr}, whose "no
 * value" result does not touch errno (plan 1.3). Only touched on POSIX with kernel descriptors; never loaded on Windows.
 */
final class PosixProc {
    private PosixProc() {}

    private static final MethodHandle SYSCONF = PosixNative.fn("sysconf", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
    private static final MethodHandle CONFSTR = PosixNative.fn("confstr", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle ERRNO_LOCATION = PosixNative.fnNoCapture("__errno_location", FunctionDescriptor.of(ADDRESS));

    private static final int EINVAL = 22;

    /** Writes 0 to this thread's errno, so a later captured 22 can only come from the call that follows. */
    private static void clearErrno() {
        try {
            MemorySegment p = (MemorySegment) ERRNO_LOCATION.invokeExact();
            p.reinterpret(4).set(JAVA_INT, 0L, 0);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    /**
     * {@code sysconf(name)}: the value, or -1 for "no value". An invalid name is EINVAL, but "no value" also returns -1
     * without touching errno, so errno is zeroed first and only a captured 22 on two consecutive attempts is an error
     * (the JVM may change errno between the two downcalls; it cannot do so to exactly 22 twice).
     */
    static long sysconf(int name) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                clearErrno();
                long r = (long) SYSCONF.invokeExact(cs, name);
                if (r != -1L) return r;
                if (PosixNative.errno(cs) != EINVAL) return -1L;
            }
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        throw PosixNative.osError(EINVAL, null);
    }

    /** One {@code confstr} call under the same two-attempt rule: the size needed (with NUL), 0 for "no value", or OSError(22). */
    private static long confstrCall(int name, MemorySegment buf, long len) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                clearErrno();
                long r = (long) CONFSTR.invokeExact(cs, name, buf, len);
                if (r != 0L) return r;
                if (PosixNative.errno(cs) != EINVAL) return 0L;
            }
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        throw PosixNative.osError(EINVAL, null);
    }

    /** {@code confstr(name)}: the text (UTF-8, surrogateescape, without the NUL), or null for "no value". */
    static String confstr(int name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(256);
            long need = confstrCall(name, buf, 256);
            if (need == 0) return null;
            if (need > 256) {
                buf = a.allocate(need);
                need = confstrCall(name, buf, need);
                if (need == 0) return null;
            }
            long n = Math.min(need, buf.byteSize()) - 1;     // the result includes the terminating NUL
            byte[] b = new byte[(int) n];
            MemorySegment.copy(buf, JAVA_BYTE, 0, b, 0, (int) n);
            return Codecs.decode(b, "utf-8", "surrogateescape");
        }
    }
}
