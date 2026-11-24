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
 * {@code select(2)} and {@code poll(2)} on the kernel (Linux x86-64 glibc, through FFM). Reached only from
 * {@link PosixSelectModule} when {@code IoFd.KERNEL} is true, so this class is never initialised on Windows. Neither call
 * runs Python signal handlers: the module layer does that between slices and on EINTR.
 */
final class PosixSelect {
    private PosixSelect() {}

    /** {@code FD_SETSIZE}: select(2) cannot watch a descriptor at or above this. */
    static final int FD_SETSIZE = 1024;
    /** sizeof(fd_set): descriptor {@code fd} is bit {@code fd % 8} of byte {@code fd / 8}. */
    static final int SET_BYTES = FD_SETSIZE / 8;
    /** sizeof(struct pollfd): fd i32 at 0, events i16 at 4, revents i16 at 6. */
    static final int POLLFD_BYTES = 8;

    private static final MethodHandle SELECT = PosixNative.fn("select",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle POLL = PosixNative.fn("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));

    /**
     * {@code select(nfds, r, w, x, timeout)} with the timeout in microseconds (negative: NULL, wait for ever). The
     * timeval is built for this call (Linux rewrites it). Returns the ready count, or {@code -errno}.
     */
    static int select(int nfds, MemorySegment r, MemorySegment w, MemorySegment x, long micros) {
        PosixNative.Tl t = PosixNative.tl();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment tv = MemorySegment.NULL;
            if (micros >= 0) {
                tv = a.allocate(16, 8);
                tv.set(JAVA_LONG, 0, micros / 1_000_000L);
                tv.set(JAVA_LONG, 8, micros % 1_000_000L);
            }
            int n = (int) SELECT.invokeExact(t.cs, nfds, r, w, x, tv);
            return n >= 0 ? n : -PosixNative.errno(t.cs);
        } catch (Throwable e) { throw PosixNative.rethrow(e); }
    }

    /** {@code poll(fds, n, ms)} ({@code ms} negative: wait for ever). Returns the ready count, or {@code -errno}. */
    static int poll(MemorySegment fds, int n, int ms) {
        PosixNative.Tl t = PosixNative.tl();
        try {
            int r = (int) POLL.invokeExact(t.cs, fds, (long) n, ms);
            return r >= 0 ? r : -PosixNative.errno(t.cs);
        } catch (Throwable e) { throw PosixNative.rethrow(e); }
    }

    /** Sets the bit of {@code fd} (0 <= fd < FD_SETSIZE: the caller checked) in the fd_set at byte {@code base} of {@code sets}. */
    static void setBit(MemorySegment sets, long base, int fd) {
        long at = base + (fd >> 3);
        sets.set(JAVA_BYTE, at, (byte) (sets.get(JAVA_BYTE, at) | (1 << (fd & 7))));
    }

    static boolean isSet(MemorySegment sets, long base, int fd) {
        return (sets.get(JAVA_BYTE, base + (fd >> 3)) & (1 << (fd & 7))) != 0;
    }
}
