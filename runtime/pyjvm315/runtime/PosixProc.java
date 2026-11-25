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

/**
 * libc calls for process-level functions (N1 plan 3.1): {@code sysconf}/{@code confstr} (whose "no value" result does not
 * touch errno, plan 1.3), the wait family with the blocking-wait rule (plan 1.5), {@code pidfd_open}, {@code kill}/
 * {@code killpg} and the process-group calls. The raw wrappers return the result or {@code -errno}. Only touched on POSIX
 * with kernel descriptors; never loaded on Windows.
 */
final class PosixProc {
    private PosixProc() {}

    private static final MethodHandle SYSCONF = PosixNative.fn("sysconf", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
    private static final MethodHandle CONFSTR = PosixNative.fn("confstr", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle ERRNO_LOCATION = PosixNative.fnNoCapture("__errno_location", FunctionDescriptor.of(ADDRESS));

    private static final MethodHandle WAITPID = PosixNative.fn("waitpid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle WAITID = PosixNative.fn("waitid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle KILL = PosixNative.fn("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle KILLPG = PosixNative.fn("killpg", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GETPGID = PosixNative.fn("getpgid", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GETSID = PosixNative.fn("getsid", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle SETPGID = PosixNative.fn("setpgid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GETPGRP = PosixNative.fnNoCapture("getpgrp", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GETPID = PosixNative.fnNoCapture("getpid", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle FACCESSAT = PosixNative.fn("faccessat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final int AT_FDCWD = -100;

    private static final MethodHandle POLL = PosixNative.fn("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle SYSCALL_PIDFD = PosixNative.fn("syscall", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT),
        Linker.Option.firstVariadicArg(1));

    private static final int EINVAL = 22;
    private static final int EINTR = 4;
    private static final long SYS_PIDFD_OPEN = 434;
    private static final int WNOHANG = 1, WUNTRACED = 2, WSTOPPED = 2, WCONTINUED = 8, P_PID = 1, P_PIDFD = 3;
    private static final short POLLIN = 1;
    private static final int POLL_MS = 50;

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

    // ---------------------------------------------------------------- wait family

    /**
     * {@code waitpid(pid, options)}: a result {@code > 0} packs the pid in the high 32 bits and the wait status in the
     * low 32; 0 means "nothing ready" (only with {@code WNOHANG}); a negative result is {@code -errno}. A blocking wait
     * follows {@link #await}; "one process, exit only" is {@code pid > 0} without {@code WUNTRACED}/{@code WCONTINUED}.
     */
    static long waitpid(int pid, int options) {
        boolean single = pid > 0 && (options & (WUNTRACED | WCONTINUED)) == 0;
        return await(o -> waitpidOnce(pid, o), options, single ? pid : 0, -1);
    }

    /**
     * {@code waitid(idtype, id, options)}: 0 or {@code -errno}. On 0, {@code out} holds {@code si_pid, si_uid (unsigned),
     * si_signo, si_status, si_code}; {@code out[0] == 0} means nothing was ready ({@code WNOHANG}; {@code out} untouched).
     * A blocking wait follows {@link #await}: {@code P_PID} without {@code WSTOPPED}/{@code WCONTINUED} waits on a pidfd
     * of its own, {@code P_PIDFD} likewise on the caller's descriptor (never closed here).
     */
    static int waitid(int idtype, int id, int options, long[] out) {
        boolean exitOnly = (options & (WSTOPPED | WCONTINUED)) == 0;
        int pid = idtype == P_PID && exitOnly && id > 0 ? id : 0;
        int callerFd = idtype == P_PIDFD && exitOnly ? id : -1;
        long r = await(o -> waitidOnce(idtype, id, o, out), options, pid, callerFd);
        return r < 0 ? (int) r : 0;
    }

    /** One {@code waitpid(2)}: the packed pid and status, 0 when nothing was ready, or {@code -errno} (also -EINTR). */
    private static long waitpidOnce(int pid, int options) {
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(4, 4);
            int r = (int) WAITPID.invokeExact(cs, pid, st, options);
            if (r > 0) return ((long) r << 32) | (st.get(JAVA_INT, 0L) & 0xffffffffL);
            if (r == 0) return 0;
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        return -PosixNative.errno(cs);
    }

    /**
     * One {@code waitid(2)}: 1 with {@code out} filled, 0 when nothing was ready ({@code si_pid == 0}), or {@code -errno}.
     * The 128-byte {@code siginfo_t} is per call, zeroed before it and read only after it succeeded.
     */
    private static long waitidOnce(int idtype, int id, int options, long[] out) {
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(128, 8);     // zero-filled
            int r = (int) WAITID.invokeExact(cs, idtype, id, info, options);
            if (r == 0) {
                int pid = info.get(JAVA_INT, 16L);
                if (pid == 0) return 0;
                out[0] = pid;
                out[1] = info.get(JAVA_INT, 20L) & 0xffffffffL;
                out[2] = info.get(JAVA_INT, 0L);
                out[3] = info.get(JAVA_INT, 24L);
                out[4] = info.get(JAVA_INT, 8L);
                return 1;
            }
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        return -PosixNative.errno(cs);
    }

    /** One wait syscall with the given options: {@code > 0} a result, 0 "nothing ready", {@code < 0} {@code -errno}. */
    @FunctionalInterface
    interface WaitCall { long call(int options); }

    /**
     * The blocking-wait rule (N1 plan 1.5, D5). With {@code WNOHANG}, off the main thread, or before {@code _signal} is
     * imported: one call (retried after EINTR once pending handlers have run). On the main thread with signals active a
     * blocking wait never blocks in the kernel: a {@code WNOHANG} call first (an exited child costs nothing more), then
     * pending handlers run, then the thread waits up to 50 ms for the child to exit and repeats. For one process with
     * exit-only options ({@code pid > 0}) that wait is {@code poll} on a pidfd opened once per call (any failure of
     * {@code pidfd_open} falls back silently); for {@code waitid(P_PIDFD)} it is the caller's descriptor
     * ({@code callerPidfd >= 0}); otherwise {@code SignalModule.sleep} with a back-off of 1 ms doubling to 50 ms. A handler
     * that raises propagates (the child stays unreaped). The transient pidfd is close-on-exec, adopted, and closed in
     * {@code finally}. A result is returned straight from the call that produced it: nothing runs in between.
     */
    static long await(WaitCall call, int options, int pid, int callerPidfd) {
        if ((options & WNOHANG) != 0 || !(SignalModule.active && SignalModule.isMain())) {
            while (true) {
                long r = call.call(options);
                if (r != -EINTR) return r;
                SignalModule.dispatchPending();
            }
        }
        int pidfd = -1;                      // -1: not tried yet; -2: unavailable; >= 0: opened by this call
        long pidfdGen = 0;                   // its generation, so the close never hits a number reused after another close
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pfd = a.allocate(8, 4);
            long delay = 1;
            boolean usePoll = true, wasReady = false;
            while (true) {
                long r = call.call(options | WNOHANG);
                if (r != 0 && r != -EINTR) return r;
                if (wasReady) usePoll = false;   // readable yet nothing to collect: never spin on it, back off instead
                SignalModule.dispatchPending();
                int fd = callerPidfd;
                if (pid > 0) {
                    if (pidfd == -1) { pidfd = openTransientPidfd(pid); if (pidfd >= 0) pidfdGen = PosixFds.gen(pidfd); }
                    fd = pidfd;
                }
                wasReady = false;
                if (usePoll && fd >= 0) {
                    int n = pollIn(pfd, fd);
                    if (n >= 0) { wasReady = n > 0; continue; }
                    usePoll = false;             // poll itself failed (not EINTR): back off from now on
                }
                if (Thread.interrupted()) throw new PyException("KeyboardInterrupt", null);   // _thread.interrupt_main(): never spin
                SignalModule.sleep(delay * 1_000_000L);
                delay = Math.min(delay * 2, 50);
            }
        } finally {
            // A reaped status must reach the caller and a handler's exception must not be replaced: a failed close (another
            // thread's os.closerange took the descriptor) is ignored, and a number reused since then is left alone.
            if (pidfd >= 0 && PosixFds.gen(pidfd) == pidfdGen) {
                try { PosixFds.close(pidfd); } catch (PyException ignored) { }
            }
        }
    }

    /** {@code pidfd_open(pid, 0)} adopted, or -2 when it failed for any reason. */
    private static int openTransientPidfd(int pid) {
        long fd = pidfdOpen(pid, 0);
        if (fd < 0) return -2;
        PosixFds.adopt((int) fd);
        return (int) fd;
    }

    /** {@code poll({fd, POLLIN}, 1, 50)}: 1 readable, 0 timed out or interrupted (EINTR), -1 any other failure. */
    private static int pollIn(MemorySegment p, int fd) {
        MemorySegment cs = PosixNative.tl().cs;
        p.set(JAVA_INT, 0L, fd);
        p.set(JAVA_SHORT, 4L, POLLIN);
        p.set(JAVA_SHORT, 6L, (short) 0);
        int n;
        try { n = (int) POLL.invokeExact(cs, p, 1L, POLL_MS); } catch (Throwable t) { throw PosixNative.rethrow(t); }
        if (n >= 0) return n > 0 ? 1 : 0;
        return PosixNative.errno(cs) == EINTR ? 0 : -1;
    }

    /** {@code syscall(SYS_pidfd_open, pid, flags)}: a close-on-exec descriptor (not adopted) or {@code -errno}. */
    static long pidfdOpen(int pid, int flags) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            long r = (long) SYSCALL_PIDFD.invokeExact(cs, SYS_PIDFD_OPEN, pid, flags);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    // ---------------------------------------------------------------- signals and process groups

    /** {@code kill(pid, sig)}: 0 or {@code -errno}. The caller routes the own pid elsewhere (in-process delivery). */
    static int kill(int pid, int sig) { return call2(KILL, pid, sig); }

    static int killpg(int pgid, int sig) { return call2(KILLPG, pgid, sig); }

    static int setpgid(int pid, int pgid) { return call2(SETPGID, pid, pgid); }

    /** {@code getpgid(pid)}: the group id or {@code -errno}. */
    static int getpgid(int pid) { return call1(GETPGID, pid); }

    static int getsid(int pid) { return call1(GETSID, pid); }

    static int getpgrp() {
        try { return (int) GETPGRP.invokeExact(); } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    static int getpid() {
        try { return (int) GETPID.invokeExact(); } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    /**
     * {@code faccessat(AT_FDCWD, path, mode, flags)} of a NUL-free byte path taken as it is: 0, or {@code -errno}
     * (no exception). For the chdir diagnosis of {@code fork_exec} (plan 1.7 step 6).
     */
    static int faccessat(byte[] path, int mode, int flags) {
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(path.length + 1L);      // zero-filled: the last byte is the NUL
            MemorySegment.copy(path, 0, p, JAVA_BYTE, 0, path.length);
            int r = (int) FACCESSAT.invokeExact(cs, AT_FDCWD, p, mode, flags);
            return r == 0 ? 0 : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int call1(MethodHandle h, int a) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) h.invokeExact(cs, a);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }

    private static int call2(MethodHandle h, int a, int b) {
        MemorySegment cs = PosixNative.tl().cs;
        try {
            int r = (int) h.invokeExact(cs, a, b);
            return r >= 0 ? r : -PosixNative.errno(cs);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
    }
}
