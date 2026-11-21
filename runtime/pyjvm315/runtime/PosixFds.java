// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * POSIX descriptors as kernel descriptors (Linux x86-64 glibc, through FFM). The Python-visible number is the kernel's;
 * there is no Java-side table: access mode, append mode, offsets and close-on-exec live in the kernel. Reached only
 * through {@code IoFd} when {@code IoFd.KERNEL} is true, so this class is never initialised on Windows.
 *
 * <p>Signals (plan N0, 1.6): on the main thread, while {@code _signal} is active, a blocking read or write first waits
 * in 50 ms {@code poll} slices, running Python handlers between slices; EINTR is retried after the handlers ran
 * (PEP 475), except for {@code close}. Handlers may re-enter os.read/os.write on this thread and reuse the thread-local
 * buffer, so no handler runs between a syscall and the copy-out of its result, and a write copies its input again
 * after handlers ran.
 */
final class PosixFds {
    private PosixFds() {}

    static final int O_NONBLOCK = 04000, O_CLOEXEC = 02000000;
    static final int F_GETFD = 1, F_GETFL = 3, F_DUPFD_CLOEXEC = 1030;
    static final int SEEK_CUR = 1;
    static final int EINTR = 4;
    static final short POLLIN = 1, POLLOUT = 4;
    static final int S_IFMT = 0170000, S_IFDIR = 0040000;
    /** sizeof(struct stat) on Linux x86-64; st_mode is a u32 at 24, st_size an i64 at 48. */
    static final long STAT_SIZE = 144;
    /** I/O above this goes through malloc instead of the thread buffer (which grows up to this size). */
    private static final long BUF_MAX = 1L << 20;
    private static final int SLICE_MS = 50;

    private static final Linker.Option VARIADIC2 = Linker.Option.firstVariadicArg(2);
    private static final MethodHandle OPEN = PosixNative.fn("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), VARIADIC2);
    private static final MethodHandle CLOSE = PosixNative.fn("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle READ = PosixNative.fn("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle WRITE = PosixNative.fn("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle LSEEK = PosixNative.fn("lseek", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle FSTAT = PosixNative.fn("fstat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle FTRUNCATE = PosixNative.fn("ftruncate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
    private static final MethodHandle FSYNC = PosixNative.fn("fsync", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle ISATTY = PosixNative.fn("isatty", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle FCNTL = PosixNative.fn("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), VARIADIC2);
    private static final MethodHandle POLL = PosixNative.fn("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));

    // ---- descriptor generations (plan 1.7): not a descriptor table, nothing reads it for I/O ----------------

    private static final ConcurrentHashMap<Integer, Long> GEN = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_GEN = new AtomicLong();

    /** Called after every successful descriptor-creating call: the number gets a fresh generation. */
    static void adopt(int fd) { GEN.put(fd, NEXT_GEN.incrementAndGet()); }

    /** Called before {@code close(2)}. */
    static void forget(int fd) { GEN.remove(fd); }

    /** The generation of {@code fd}, 0 for a number this runtime did not create (or has closed). */
    static long gen(long fd) {
        Long g = fd == (int) fd ? GEN.get((int) fd) : null;
        return g == null ? 0 : g;
    }

    // ---- arguments ----------------------------------------------------------------------------------------

    /** A descriptor as a C int; negative values go to the kernel (EBADF), values beyond an int are OverflowError. */
    static int cfd(long fd) {
        if (fd != (int) fd) throw new PyException("OverflowError", "Python int too large to convert to C int");
        return (int) fd;
    }

    /**
     * The NUL-terminated path for a syscall, in {@code a}: a relative path is joined to the emulated cwd by string
     * (no normalisation: the kernel resolves {@code ..} through symlinks), encoded UTF-8 with surrogateescape.
     */
    static MemorySegment pathBytes(Arena a, String path) {
        if (path.indexOf('\0') >= 0) throw new PyException("ValueError", "open: embedded null character in path");
        Path cwd = PosixModule.cwd;
        String full = path.isEmpty() || path.startsWith("/") || cwd == null ? path : cwd + "/" + path;
        byte[] b = Codecs.encode(full, "utf-8", "surrogateescape");
        MemorySegment seg = a.allocate(b.length + 1L);      // zero-filled: the last byte is the NUL
        MemorySegment.copy(b, 0, seg, JAVA_BYTE, 0, b.length);
        return seg;
    }

    /** The thread buffer, grown (a new segment replaces the old one) to hold {@code n <= BUF_MAX} bytes. */
    private static MemorySegment ioBuf(PosixNative.Tl t, long n) {
        if (n > t.cap) {
            long c = Math.min(BUF_MAX, Long.highestOneBit(n - 1) << 1);
            t.buf = Arena.ofAuto().allocate(c);
            t.cap = c;
        }
        return t.buf;
    }

    // ---- signals (1.6) ------------------------------------------------------------------------------------

    /**
     * Pre-wait before a read or write on the main thread while signal handling is active: 50 ms {@code poll} slices
     * with pending Python handlers run between them. Returns when the descriptor is ready (or in error: the syscall
     * reports it), or at once for a non-blocking descriptor (the syscall then returns EAGAIN).
     */
    static void awaitReady(int fd, short events) {
        if (!SignalModule.active || !SignalModule.isMain()) return;
        PosixNative.Tl t = PosixNative.tl();
        boolean first = true;
        while (true) {
            MemorySegment p = t.buf;                            // re-read: a handler may have replaced it
            p.set(JAVA_INT, 0, fd);
            p.set(JAVA_SHORT, 4, events);
            p.set(JAVA_SHORT, 6, (short) 0);
            int n;
            try { n = (int) POLL.invokeExact(t.cs, p, 1L, SLICE_MS); } catch (Throwable x) { throw PosixNative.rethrow(x); }
            if (n > 0) return;
            if (n < 0) {
                int e = PosixNative.errno(t.cs);
                if (e != EINTR) throw PosixNative.osError(e, null);
            } else if (first) {
                first = false;
                if (nonBlocking(t, fd)) return;
            }
            SignalModule.dispatchPending();
        }
    }

    /** True when {@code fd} has O_NONBLOCK; false when it has not or F_GETFL fails (the syscall reports that). */
    private static boolean nonBlocking(PosixNative.Tl t, int fd) {
        int fl;
        try { fl = (int) FCNTL.invokeExact(t.cs, fd, F_GETFL, 0); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        return fl != -1 && (fl & O_NONBLOCK) != 0;
    }

    // ---- descriptor calls ---------------------------------------------------------------------------------

    /** {@code open(2)}, always close-on-exec; errors carry the caller's path. */
    static long open(String path, long flags, long mode) {
        int fl = (int) (flags & 0xffffffffL & ~IoFd.DELETE_ON_CLOSE) | O_CLOEXEC;
        PosixNative.Tl t = PosixNative.tl();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = pathBytes(a, path);
            while (true) {
                int fd = (int) OPEN.invokeExact(t.cs, p, fl, (int) mode);
                if (fd >= 0) { adopt(fd); return fd; }
                int e = PosixNative.errno(t.cs);
                if (e != EINTR) throw PosixNative.osError(e, path);
                SignalModule.dispatchPending();
            }
        } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    /** {@code close(2)}: the generation is forgotten first; never retried (Linux frees the number even on EINTR). */
    static void close(long fd) {
        int f = cfd(fd);
        forget(f);
        MemorySegment cs = PosixNative.tl().cs;
        int r;
        try { r = (int) CLOSE.invokeExact(cs, f); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r != 0) throw PosixNative.osError(PosixNative.errno(cs), null);
    }

    /** {@code fcntl(fd, F_DUPFD_CLOEXEC, 0)}: the lowest free number, close-on-exec. */
    static long dup(long fd) {
        int f = cfd(fd);
        MemorySegment cs = PosixNative.tl().cs;
        int r;
        try { r = (int) FCNTL.invokeExact(cs, f, F_DUPFD_CLOEXEC, 0); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r < 0) throw PosixNative.osError(PosixNative.errno(cs), null);
        adopt(r);
        return r;
    }

    /** One {@code read(2)} of up to {@code n} bytes; a short read is returned as is, an empty array at end of file. */
    static byte[] read(long fd, int n) {
        int f = cfd(fd);
        if (n < 0) return new byte[0];
        if (n > 0) awaitReady(f, POLLIN);       // n == 0 still reaches read(2), so a bad descriptor raises EBADF as in CPython
        PosixNative.Tl t = PosixNative.tl();
        boolean big = n > BUF_MAX;
        MemorySegment own = big ? PosixNative.nativeAlloc(n) : null;
        try {
            while (true) {
                MemorySegment buf = big ? own : ioBuf(t, n);
                long r;
                try { r = (long) READ.invokeExact(t.cs, f, buf, (long) n); } catch (Throwable x) { throw PosixNative.rethrow(x); }
                if (r >= 0) {
                    byte[] out = new byte[(int) r];
                    MemorySegment.copy(buf, JAVA_BYTE, 0, out, 0, (int) r);
                    return out;
                }
                int e = PosixNative.errno(t.cs);
                if (e != EINTR) throw PosixNative.osError(e, null);
                SignalModule.dispatchPending();                 // nothing was read: no result is pending in buf
            }
        } finally {
            if (big) PosixNative.nativeFree(own);
        }
    }

    /** One {@code write(2)} of {@code b[off, off+len)}; returns the count written (may be short). */
    static int write(long fd, byte[] b, int off, int len) {
        int f = cfd(fd);
        if (len > 0) awaitReady(f, POLLOUT);
        PosixNative.Tl t = PosixNative.tl();
        boolean big = len > BUF_MAX;
        MemorySegment own = big ? PosixNative.nativeAlloc(len) : null;
        try {
            while (true) {
                MemorySegment buf = big ? own : ioBuf(t, len);
                MemorySegment.copy(b, off, buf, JAVA_BYTE, 0, len);     // (again) after handlers ran: they may reuse buf
                long r;
                try { r = (long) WRITE.invokeExact(t.cs, f, buf, (long) len); } catch (Throwable x) { throw PosixNative.rethrow(x); }
                if (r >= 0) return (int) r;
                int e = PosixNative.errno(t.cs);
                if (e != EINTR) throw PosixNative.osError(e, null);
                SignalModule.dispatchPending();
            }
        } finally {
            if (big) PosixNative.nativeFree(own);
        }
    }

    /** {@code lseek(2)}; {@code how} is passed through (the kernel rejects bad values with EINVAL). */
    static long lseek(long fd, long pos, int how) {
        int f = cfd(fd);
        MemorySegment cs = PosixNative.tl().cs;
        long r;
        try { r = (long) LSEEK.invokeExact(cs, f, pos, how); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r < 0) throw PosixNative.osError(PosixNative.errno(cs), null);
        return r;
    }

    /** Whether {@code lseek(fd, 0, SEEK_CUR)} succeeds; never raises for a descriptor that fits an int. */
    static boolean seekable(long fd) {
        int f = cfd(fd);
        long r;
        try { r = (long) LSEEK.invokeExact(PosixNative.tl().cs, f, 0L, SEEK_CUR); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        return r >= 0;
    }

    /** {@code fstat(2)} into {@code st} (at least {@link #STAT_SIZE} bytes); EINTR retried; errors raise OSError. */
    static void fstatRaw(int fd, MemorySegment st) {
        MemorySegment cs = PosixNative.tl().cs;
        while (true) {
            int r;
            try { r = (int) FSTAT.invokeExact(cs, fd, st); } catch (Throwable x) { throw PosixNative.rethrow(x); }
            if (r == 0) return;
            int e = PosixNative.errno(cs);
            if (e != EINTR) throw PosixNative.osError(e, null);
            SignalModule.dispatchPending();
        }
    }

    /** {@code st_mode} of {@code fd}; the stat buffer is the thread buffer, read before anything else can reuse it. */
    private static int statMode(int fd) {
        MemorySegment st = PosixNative.tl().buf;
        fstatRaw(fd, st);
        return st.get(JAVA_INT, 24);
    }

    static long size(long fd) {
        int f = cfd(fd);
        MemorySegment st = PosixNative.tl().buf;
        fstatRaw(f, st);
        return st.get(JAVA_LONG, 48);
    }

    static boolean isDir(long fd) { return (statMode(cfd(fd)) & S_IFMT) == S_IFDIR; }

    static void ftruncate(long fd, long size) {
        int f = cfd(fd);
        MemorySegment cs = PosixNative.tl().cs;
        while (true) {
            int r;
            try { r = (int) FTRUNCATE.invokeExact(cs, f, size); } catch (Throwable x) { throw PosixNative.rethrow(x); }
            if (r == 0) return;
            int e = PosixNative.errno(cs);
            if (e != EINTR) throw PosixNative.osError(e, null);
            SignalModule.dispatchPending();
        }
    }

    static void fsync(long fd) {
        int f = cfd(fd);
        MemorySegment cs = PosixNative.tl().cs;
        while (true) {
            int r;
            try { r = (int) FSYNC.invokeExact(cs, f); } catch (Throwable x) { throw PosixNative.rethrow(x); }
            if (r == 0) return;
            int e = PosixNative.errno(cs);
            if (e != EINTR) throw PosixNative.osError(e, null);
            SignalModule.dispatchPending();
        }
    }

    /** {@code isatty(3) == 1}; never raises (a number beyond an int is not a terminal). */
    static boolean isatty(long fd) {
        if (fd != (int) fd) return false;
        int r;
        try { r = (int) ISATTY.invokeExact(PosixNative.tl().cs, (int) fd); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        return r == 1;
    }

    /** Whether {@code fd} is an open descriptor ({@code fcntl(fd, F_GETFD)} succeeds); never raises. */
    static boolean valid(long fd) {
        if (fd != (int) fd) return false;
        int r;
        try { r = (int) FCNTL.invokeExact(PosixNative.tl().cs, (int) fd, F_GETFD, 0); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        return r != -1;
    }
}
