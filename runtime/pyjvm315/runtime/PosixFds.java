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
import java.math.BigInteger;
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
    static final int F_GETFD = 1, F_SETFD = 2, F_GETFL = 3, F_SETFL = 4, F_DUPFD_CLOEXEC = 1030, FD_CLOEXEC = 1;
    static final long TIOCGWINSZ = 0x5413;
    static final int SEEK_CUR = 1;
    static final int EINTR = 4, EAGAIN = 11;
    static final short POLLIN = 1, POLLOUT = 4;
    static final int S_IFMT = 0170000, S_IFDIR = 0040000;
    static final int AT_FDCWD = -100, AT_SYMLINK_NOFOLLOW = 256;
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
    private static final MethodHandle PREAD = PosixNative.fn("pread", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle PWRITE = PosixNative.fn("pwrite", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle PIPE2 = PosixNative.fn("pipe2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle DUP2 = PosixNative.fn("dup2", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle DUP3 = PosixNative.fn("dup3", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle LSEEK = PosixNative.fn("lseek", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle FSTAT = PosixNative.fn("fstat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle FSTATAT = PosixNative.fn("fstatat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle FTRUNCATE = PosixNative.fn("ftruncate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
    private static final MethodHandle FSYNC = PosixNative.fn("fsync", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle ISATTY = PosixNative.fn("isatty", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle FCNTL = PosixNative.fn("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), VARIADIC2);
    private static final MethodHandle POLL = PosixNative.fn("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle FDATASYNC = PosixNative.fn("fdatasync", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle IOCTL = PosixNative.fn("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), VARIADIC2);

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
    static MemorySegment pathBytes(Arena a, String func, String path) {
        if (path.indexOf('\0') >= 0) throw new PyException("ValueError", func + ": embedded null character in path");
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
     * Pre-wait before a read or write on the main thread while signal handling is active: {@code poll} slices with
     * pending Python handlers run between them. Returns when the descriptor is ready (or in error: the syscall reports
     * it), or after the first empty slice for a non-blocking descriptor (the syscall then returns EAGAIN). The first
     * slice is a zero-timeout probe, so a non-blocking descriptor never waits; the following slices are 50 ms.
     * Handlers of signals that arrived before the call run first: pyJVM has no eval-loop signal check, so without this
     * a loop of reads and writes that never wait would never run them (CPython runs them at the next bytecode).
     */
    static void awaitReady(int fd, short events) {
        if (!SignalModule.active || !SignalModule.isMain()) return;
        SignalModule.dispatchPending();
        PosixNative.Tl t = PosixNative.tl();
        boolean first = true;
        while (true) {
            MemorySegment p = t.buf;                            // re-read: a handler may have replaced it
            p.set(JAVA_INT, 0, fd);
            p.set(JAVA_SHORT, 4, events);
            p.set(JAVA_SHORT, 6, (short) 0);
            int n;
            try { n = (int) POLL.invokeExact(t.cs, p, 1L, first ? 0 : SLICE_MS); } catch (Throwable x) { throw PosixNative.rethrow(x); }
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

    /**
     * {@code signal.set_wakeup_fd(fd)}'s checks, as CPython's: the descriptor must exist ({@code fstat}; errors raise
     * OSError from errno) and be in non-blocking mode (else ValueError). A regular file passes.
     */
    static void checkWakeupFd(long fd) {
        int f = cfd(fd);
        try (Arena a = Arena.ofConfined()) {
            fstatRaw(f, a.allocate(STAT_SIZE, 8));
        }
        if ((fcntlGet(f, F_GETFL) & O_NONBLOCK) == 0) throw new PyException("ValueError", "the fd " + f + " must be in non-blocking mode");
    }

    /**
     * The signal wakeup write: one {@code write(2)} of the byte {@code value} to {@code fd}, retried only on EINTR (as
     * CPython's {@code _Py_write_noraise}; no handlers run, no pre-wait). Returns 0, or the errno of the failure. Runs on
     * whatever thread delivers the signal, so it uses that thread's own call state and a buffer of its own (never the
     * thread I/O buffer).
     */
    static int writeByteNoThrow(int fd, int value) {
        try (Arena a = Arena.ofConfined()) {   // no Tl: the JDK runs each signal handler on a fresh thread
            MemorySegment cs = PosixNative.callState(a);
            MemorySegment b = a.allocate(1);
            b.set(JAVA_BYTE, 0, (byte) value);
            while (true) {
                long r = (long) WRITE.invokeExact(cs, fd, b, 1L);
                if (r >= 0) return 0;
                int e = PosixNative.errno(cs);
                if (e != EINTR) return e;
            }
        } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    // ---- descriptor calls ---------------------------------------------------------------------------------

    /** {@code open(2)}, always close-on-exec; errors carry the caller's path. */
    static long open(String path, long flags, long mode) {
        int fl = (int) (flags & 0xffffffffL & ~IoFd.DELETE_ON_CLOSE) | O_CLOEXEC;
        PosixNative.Tl t = PosixNative.tl();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = pathBytes(a, "open", path);
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

    /** {@code pipe2(2)} with the caller's flags verbatim (CPython's {@code os.pipe} passes O_CLOEXEC); both ends are adopted. */
    static long[] pipe(int flags) {
        PosixNative.Tl t = PosixNative.tl();
        MemorySegment p = t.buf;
        int r;
        try { r = (int) PIPE2.invokeExact(t.cs, p, flags); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r != 0) throw PosixNative.osError(PosixNative.errno(t.cs), null);
        int rd = p.get(JAVA_INT, 0), wr = p.get(JAVA_INT, 4);     // read before anything can reuse the buffer
        adopt(rd);
        adopt(wr);
        return new long[]{rd, wr};
    }

    /**
     * {@code os.dup2}: {@code dup2(2)} when inheritable, else {@code dup3(2)} with O_CLOEXEC (atomic). Never retried on
     * EINTR. Returns {@code fd2}; a new generation is recorded for it unless {@code fd == fd2} (nothing changed then).
     */
    static long dup2(long fd, long fd2, boolean inheritable) {
        int f = cfd(fd), g = cfd(fd2);
        MemorySegment cs = PosixNative.tl().cs;
        int r;
        try {
            r = inheritable ? (int) DUP2.invokeExact(cs, f, g) : (int) DUP3.invokeExact(cs, f, g, O_CLOEXEC);
        } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r < 0) throw PosixNative.osError(PosixNative.errno(cs), null);
        if (f != g) adopt(r);
        return r;
    }

    /**
     * {@code os.closerange(low, high)}: closes descriptors in {@code [max(low, 0), high)}, errors ignored, but only
     * stdio (0, 1, 2) and the numbers this runtime adopted (generation != 0: {@code os.open}, {@code dup},
     * {@code pipe}, {@code dup2} targets, {@code select.epoll}). A number above 2 that pyJVM never adopted is skipped:
     * it belongs to the JVM (class-path jars, its own epoll/pipes) or was inherited from the parent, and closing it
     * would break class loading or let a later JVM close hit a reused number. This deliberately diverges from CPython
     * for inherited descriptors (they survive the daemonise/pre-exec idiom {@code closerange(3, MAXFD)}); an explicit
     * {@code os.close(fd)} still closes any number. Only the generation table is visited, never the int range.
     * Above 2 a number is closed only if this call removed it from the table ({@code GEN.remove(fd) != null}, atomic),
     * so a number closed by another thread after the snapshot, and reopened unadopted by the JVM, is left alone. 0, 1
     * and 2 are closed unconditionally, as CPython does, even if the JVM reused one of those numbers after
     * {@code os.close(1)}.
     */
    static void closeRange(int low, int high) {
        int from = Math.max(low, 0);
        if (from >= high) return;
        java.util.TreeSet<Integer> fds = new java.util.TreeSet<>();
        for (int fd = from; fd <= 2 && fd < high; fd++) fds.add(fd);
        for (Integer fd : GEN.keySet()) if (fd >= from && fd < high) fds.add(fd);
        MemorySegment cs = PosixNative.tl().cs;
        for (int fd : fds) {
            if (fd > 2) { if (GEN.remove(fd) == null) continue; }
            else forget(fd);
            try { int ignored = (int) CLOSE.invokeExact(cs, fd); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        }
    }

    /** One {@code read(2)} of up to {@code n} bytes; a short read is returned as is, an empty array at end of file. */
    static byte[] read(long fd, int n) {
        int f = cfd(fd);
        if (n < 0) return new byte[0];
        if (n > 0) awaitReady(f, POLLIN);       // n == 0 still reaches read(2), so a bad descriptor raises EBADF as in CPython
        return readCore(f, n, false, 0, false);
    }

    /** As {@link #read}, but a read that would block (EAGAIN: a non-blocking descriptor) returns null instead of raising. */
    static byte[] readOrNull(long fd, int n) {
        int f = cfd(fd);
        if (n < 0) return new byte[0];
        if (n > 0) awaitReady(f, POLLIN);
        return readCore(f, n, false, 0, true);
    }

    /** {@code pread(2)}: as {@link #read} without the signal pre-wait (positional reads are for seekable files); the offset is not moved. */
    static byte[] pread(long fd, int n, long offset) {
        int f = cfd(fd);
        if (n < 0) return new byte[0];
        return readCore(f, n, true, offset, false);
    }

    /** The read loop; with {@code eagainNull} an EAGAIN returns null (nothing was read) instead of raising. */
    private static byte[] readCore(int f, int n, boolean at, long offset, boolean eagainNull) {
        PosixNative.Tl t = PosixNative.tl();
        boolean big = n > BUF_MAX;
        MemorySegment own = big ? PosixNative.nativeAlloc(n) : null;
        try {
            while (true) {
                MemorySegment buf = big ? own : ioBuf(t, n);
                long r;
                try {
                    if (at) r = (long) PREAD.invokeExact(t.cs, f, buf, (long) n, offset);
                    else r = (long) READ.invokeExact(t.cs, f, buf, (long) n);
                } catch (Throwable x) { throw PosixNative.rethrow(x); }
                if (r >= 0) {
                    byte[] out = new byte[(int) r];
                    MemorySegment.copy(buf, JAVA_BYTE, 0, out, 0, (int) r);
                    return out;
                }
                int e = PosixNative.errno(t.cs);
                if (e == EAGAIN && eagainNull) return null;
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
        return writeCore(f, b, off, len, false, 0, false);
    }

    /** As {@link #write}, but a write that would block (EAGAIN: a non-blocking descriptor) returns -1 instead of raising. */
    static int writeOrNeg(long fd, byte[] b, int off, int len) {
        int f = cfd(fd);
        if (len > 0) awaitReady(f, POLLOUT);
        return writeCore(f, b, off, len, false, 0, true);
    }

    /** {@code pwrite(2)}: as {@link #write} without the signal pre-wait; the offset is not moved. */
    static int pwrite(long fd, byte[] b, int off, int len, long offset) { return writeCore(cfd(fd), b, off, len, true, offset, false); }

    /** The write loop; with {@code eagainNeg} an EAGAIN returns -1 (nothing was written) instead of raising. */
    private static int writeCore(int f, byte[] b, int off, int len, boolean at, long offset, boolean eagainNeg) {
        PosixNative.Tl t = PosixNative.tl();
        boolean big = len > BUF_MAX;
        MemorySegment own = big ? PosixNative.nativeAlloc(len) : null;
        try {
            while (true) {
                MemorySegment buf = big ? own : ioBuf(t, len);
                MemorySegment.copy(b, off, buf, JAVA_BYTE, 0, len);     // (again) after handlers ran: they may reuse buf
                long r;
                try {
                    if (at) r = (long) PWRITE.invokeExact(t.cs, f, buf, (long) len, offset);
                    else r = (long) WRITE.invokeExact(t.cs, f, buf, (long) len);
                } catch (Throwable x) { throw PosixNative.rethrow(x); }
                if (r >= 0) return (int) r;
                int e = PosixNative.errno(t.cs);
                if (e == EAGAIN && eagainNeg) return -1;
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
    static void fstatRaw(int fd, MemorySegment st) { fstatRaw(fd, st, null); }

    /** As {@link #fstatRaw(int, MemorySegment)}; a non-null {@code filename} (the int of {@code os.stat(fd)}) is put on the error. */
    static void fstatRaw(int fd, MemorySegment st, Object filename) {
        MemorySegment cs = PosixNative.tl().cs;
        while (true) {
            int r;
            try { r = (int) FSTAT.invokeExact(cs, fd, st); } catch (Throwable x) { throw PosixNative.rethrow(x); }
            if (r == 0) return;
            int e = PosixNative.errno(cs);
            if (e != EINTR) throw filename == null ? PosixNative.osError(e, null) : withFilename(PosixNative.osError(e, null), filename);
            SignalModule.dispatchPending();
        }
    }

    /** The same OSError with {@code filename} replaced (any object: {@code os.stat(9999)} names the int). */
    static PyException withFilename(PyException base, Object filename) {
        PyExcArgs a = (PyExcArgs) base.value;
        java.util.LinkedHashMap<String,Object> attrs = new java.util.LinkedHashMap<>(a.attrs);
        attrs.put("filename", filename);
        return new PyException(base.typeName, new PyExcArgs(a.args, null, attrs));
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

    // ---- os-level descriptor functions (PosixOsFds) -----------------------------------------------------------

    /** A u64 as a Python int: values above 2^63 must not print negative. */
    static Object unsigned(long v) { return v >= 0 ? (Object) v : new BigInteger(Long.toUnsignedString(v)); }

    private static long timeNs(MemorySegment st, long off) { return st.get(JAVA_LONG, off) * 1_000_000_000L + st.get(JAVA_LONG, off + 8); }

    /**
     * The one place that turns a {@code struct stat} (plan 3.2) into an {@code os.stat_result}. Every field is read
     * before anything else can run, so the buffer is never read across a {@code dispatchPending()}.
     */
    static PyInstance statResult(MemorySegment st) {
        long dev = st.get(JAVA_LONG, 0), ino = st.get(JAVA_LONG, 8), nlink = st.get(JAVA_LONG, 16);
        long mode = Integer.toUnsignedLong(st.get(JAVA_INT, 24)), uid = Integer.toUnsignedLong(st.get(JAVA_INT, 28)), gid = Integer.toUnsignedLong(st.get(JAVA_INT, 32));
        long rdev = st.get(JAVA_LONG, 40), size = st.get(JAVA_LONG, 48), blksize = st.get(JAVA_LONG, 56), blocks = st.get(JAVA_LONG, 64);
        long[] at = {timeNs(st, 72), timeNs(st, 88), timeNs(st, 104)};
        PyInstance r = PosixModule.newStatResult();
        PosixModule.fill(r, mode, ino, dev, nlink, uid, gid, size, at);
        if (ino < 0 || dev < 0) {                       // PosixModule.fill takes longs: patch the unsigned values in
            PyTuple t = (PyTuple) r.slotValues.get(NativeTypes.STATE);
            if (ino < 0) { t.items.set(1, unsigned(ino)); PosixModule.put(r, "st_ino", unsigned(ino)); }
            if (dev < 0) { t.items.set(2, unsigned(dev)); PosixModule.put(r, "st_dev", unsigned(dev)); }
        }
        PosixModule.put(r, "st_rdev", unsigned(rdev));
        PosixModule.put(r, "st_blksize", blksize);
        PosixModule.put(r, "st_blocks", blocks);
        return r;
    }

    /** {@code os.fstat}: the kernel's answer for the descriptor, into a fresh 144-byte buffer. */
    static Object fstat(long fd) { return fstat(fd, null); }

    /** {@code os.stat(fd)}: as {@code fstat}, an error names the descriptor int as its filename. */
    static Object fstat(long fd, Object filename) {
        int f = cfd(fd);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(STAT_SIZE, 8);
            fstatRaw(f, st, filename);
            return statResult(st);
        }
    }

    /**
     * {@code os.stat}/{@code os.lstat} of a path: one {@code fstatat(AT_FDCWD, ...)} (no existence pre-check), the
     * path and a fresh 144-byte buffer in a confined arena that outlives any {@code dispatchPending()} on EINTR.
     * Errors come from errno and carry the caller's string.
     */
    static Object statPath(String func, String path, boolean follow) {
        PosixNative.Tl t = PosixNative.tl();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = pathBytes(a, func, path);
            MemorySegment st = a.allocate(STAT_SIZE, 8);
            int flags = follow ? 0 : AT_SYMLINK_NOFOLLOW;
            while (true) {
                int r = (int) FSTATAT.invokeExact(t.cs, AT_FDCWD, p, st, flags);
                if (r == 0) return statResult(st);
                int e = PosixNative.errno(t.cs);
                if (e != EINTR) throw PosixNative.osError(e, path);
                SignalModule.dispatchPending();
            }
        } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    /** {@code fcntl(fd, cmd, 0)} for a getter; -1 is an error (never a flag set). */
    private static int fcntlGet(int fd, int cmd) {
        MemorySegment cs = PosixNative.tl().cs;
        int r;
        try { r = (int) FCNTL.invokeExact(cs, fd, cmd, 0); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r == -1) throw PosixNative.osError(PosixNative.errno(cs), null);
        return r;
    }

    private static void fcntlSet(int fd, int cmd, int value) {
        MemorySegment cs = PosixNative.tl().cs;
        int r;
        try { r = (int) FCNTL.invokeExact(cs, fd, cmd, value); } catch (Throwable x) { throw PosixNative.rethrow(x); }
        if (r == -1) throw PosixNative.osError(PosixNative.errno(cs), null);
    }

    /** Read-modify-write of one bit of F_GETFD/F_GETFL; the setter runs only if the value changes. */
    private static void setBit(int fd, int getCmd, int setCmd, int bit, boolean on) {
        int old = fcntlGet(fd, getCmd);
        int nw = on ? old | bit : old & ~bit;
        if (nw != old) fcntlSet(fd, setCmd, nw);
    }

    /** {@code fcntl(fd, F_GETFD)}: the descriptor flags, or -1 when {@code fd} is not open (no exception). */
    static int fdFlagsOrNeg(int fd) {
        try { return (int) FCNTL.invokeExact(PosixNative.tl().cs, fd, F_GETFD, 0); } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    /**
     * The descriptors open in this process, ascending, read from {@code /proc/self/fd} (for building a child's
     * {@code CLOSE} actions only, plan 1.8; nothing else iterates "all descriptors"). The directory stream is closed
     * before this returns, so its own descriptor (which the JDK holds without close-on-exec while open) is listed but
     * is no longer open when the caller looks at it.
     */
    static int[] listFds() {
        int[] out = new int[64];
        int n = 0;
        try (java.nio.file.DirectoryStream<Path> ds = java.nio.file.Files.newDirectoryStream(Path.of("/proc/self/fd"))) {
            for (Path p : ds) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                out[n++] = Integer.parseInt(p.getFileName().toString());
            }
        } catch (java.io.IOException e) {
            throw PosixModule.osError(e, "/proc/self/fd");
        }
        out = java.util.Arrays.copyOf(out, n);
        java.util.Arrays.sort(out);
        return out;
    }

    static boolean getInheritable(long fd) { return (fcntlGet(cfd(fd), F_GETFD) & FD_CLOEXEC) == 0; }

    static void setInheritable(long fd, boolean inheritable) { setBit(cfd(fd), F_GETFD, F_SETFD, FD_CLOEXEC, !inheritable); }

    static boolean getBlocking(long fd) { return (fcntlGet(cfd(fd), F_GETFL) & O_NONBLOCK) == 0; }

    static void setBlocking(long fd, boolean blocking) { setBit(cfd(fd), F_GETFL, F_SETFL, O_NONBLOCK, !blocking); }

    static void fdatasync(long fd) {
        int f = cfd(fd);
        MemorySegment cs = PosixNative.tl().cs;
        while (true) {
            int r;
            try { r = (int) FDATASYNC.invokeExact(cs, f); } catch (Throwable x) { throw PosixNative.rethrow(x); }
            if (r == 0) return;
            int e = PosixNative.errno(cs);
            if (e != EINTR) throw PosixNative.osError(e, null);
            SignalModule.dispatchPending();
        }
    }

    /** {@code ioctl(fd, TIOCGWINSZ)}: {columns, lines}; errors (a pipe: ENOTTY) raise OSError. Not retried, as in CPython. */
    static long[] winsize(long fd) {
        int f = cfd(fd);
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ws = a.allocate(8, 8);
            int r;
            try { r = (int) IOCTL.invokeExact(cs, f, TIOCGWINSZ, ws); } catch (Throwable x) { throw PosixNative.rethrow(x); }
            if (r == -1) throw PosixNative.osError(PosixNative.errno(cs), null);
            return new long[]{ws.get(JAVA_SHORT, 2) & 0xffffL, ws.get(JAVA_SHORT, 0) & 0xffffL};
        }
    }
}
