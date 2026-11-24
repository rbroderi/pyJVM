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
 * libc calls for process-level functions (N1 plan 3.1): {@code sysconf}/{@code confstr} (whose "no value" result does not
 * touch errno, plan 1.3), the wait family, {@code kill}/{@code killpg} and the process-group calls. The raw wrappers
 * return the result or {@code -errno}. Only touched on POSIX with kernel descriptors; never loaded on Windows.
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

    private static final int EINVAL = 22;
    private static final int EINTR = 4;

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
     * {@code waitpid(pid, options)}: a result {@code >= 0} packs the pid in the high 32 bits and the wait status in the
     * low 32 (pid 0 with {@code WNOHANG} means "nothing ready"); a negative result is {@code -errno}. A blocking call
     * retries after EINTR once the pending Python handlers have run (a handler that raises propagates; the child stays
     * unreaped, as in CPython). Nothing native is held across {@code dispatchPending}: the status cell is per attempt.
     */
    static long waitpid(int pid, int options) {
        MemorySegment cs = PosixNative.tl().cs;
        while (true) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment st = a.allocate(4, 4);
                int r = (int) WAITPID.invokeExact(cs, pid, st, options);
                if (r >= 0) return ((long) r << 32) | (st.get(JAVA_INT, 0L) & 0xffffffffL);
            } catch (Throwable t) { throw PosixNative.rethrow(t); }
            int e = PosixNative.errno(cs);
            if (e != EINTR) return -e;
            SignalModule.dispatchPending();
        }
    }

    /**
     * {@code waitid(idtype, id, options)}: 0 or {@code -errno}. On 0, {@code out} holds {@code si_pid, si_uid (unsigned),
     * si_signo, si_status, si_code}; {@code si_pid == 0} means nothing was ready ({@code WNOHANG}). The 128-byte
     * {@code siginfo_t} is per attempt, zeroed before the call and read only after it succeeded.
     */
    static int waitid(int idtype, int id, int options, long[] out) {
        MemorySegment cs = PosixNative.tl().cs;
        while (true) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment info = a.allocate(128, 8);     // zero-filled
                int r = (int) WAITID.invokeExact(cs, idtype, id, info, options);
                if (r == 0) {
                    out[0] = info.get(JAVA_INT, 16L);
                    out[1] = info.get(JAVA_INT, 20L) & 0xffffffffL;
                    out[2] = info.get(JAVA_INT, 0L);
                    out[3] = info.get(JAVA_INT, 24L);
                    out[4] = info.get(JAVA_INT, 8L);
                    return 0;
                }
            } catch (Throwable t) { throw PosixNative.rethrow(t); }
            int e = PosixNative.errno(cs);
            if (e != EINTR) return -e;
            SignalModule.dispatchPending();
        }
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
