// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code _posixsubprocess} on the spawn core (N1 plan 1.7): {@code fork_exec} builds one {@link PosixSpawn.Plan} from
 * CPython's 22 arguments (23 on 3.13, whose {@code allow_vfork} is ignored) and tries each entry of
 * {@code executable_list} with {@code posix_spawn}. There is no fork, so nothing runs in the child before exec: what
 * CPython's child does by hand (dup2 of the stdio descriptors, closing descriptors, chdir, signals, session and group)
 * is expressed as file actions and attributes, and the error record CPython's child writes to {@code errpipe_write}
 * is written by the parent after a failed spawn. {@code errpipe_write} itself is never made inheritable: a child
 * holding it would keep {@code subprocess} blocked reading the pipe until the child exits. Kernel descriptors only;
 * never loaded on Windows ({@code ImportSupport.POSIX_ONLY}).
 */
final class PosixSubprocessModule {
    private PosixSubprocessModule() {}

    private static final int EPERM = 1, ENOENT = 2, ESRCH = 3, EBADF = 9, ENOTDIR = 20;
    private static final int SIGPIPE = 13, SIGXFSZ = 25;
    private static final int X_OK = 1, AT_EACCESS = 0x200;
    /** The stand-in child of plan 1.7 step 7: exits 255 like CPython's forked child after a failed exec. */
    private static final byte[] STAND_IN_PATH = ascii("/bin/sh");
    private static final byte[][] STAND_IN_ARGV = {ascii("sh"), ascii("-c"), ascii("exit 255")};

    static PyModule create() {
        PyModule m = new PyModule("_posixsubprocess", PosixSubprocessModule.class.getName());
        fn(m, "fork_exec", PosixSubprocessModule::forkExec);
        return m;
    }

    /** The bound arguments of one call, in CPython's order (plan 1.7). */
    private static final class Call {
        Object args, executableList, fdsToKeep, cwd, envList, gid, extraGroups, uid, preexecFn;
        boolean closeFds, restoreSignals, callSetsid;
        int p2cread, p2cwrite, c2pread, c2pwrite, errread, errwrite, errpipeRead, errpipeWrite, pgidToSet, childUmask;
        /** Converted by {@link #convert}: the candidates, argv, envp (null = libc environ), cwd, kept fds. */
        List<byte[]> programs;
        byte[][] argv, envp;
        byte[] cwdBytes;
        int[] keep;
    }

    /**
     * CPython's order: the argument parser (count, then each argument in turn, argument 4 must be a tuple), the
     * precondition checks, the conversions; the audit event (3.14+) once everything is converted, just before the
     * spawn; then what {@code posix_spawn} cannot do. A call that is wrong in a way CPython checks therefore reports
     * CPython's error even when it also passes an unsupported argument.
     */
    private static Object forkExec(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_posixsubprocess.fork_exec() takes no keyword arguments");
        int n = LangVersion.atLeast(3, 14) ? 22 : 23;
        if (a.size() != n) throw new PyException("TypeError", "fork_exec expected " + n + " arguments, got " + a.size());
        Call c = bind(a);
        check(c);
        convert(c);
        if (LangVersion.atLeast(3, 14) && SysExtras.auditing())
            SysExtras.audit("_posixsubprocess.fork_exec", c.executableList, c.args, c.envList);
        unsupported(c);
        return spawn(c);
    }

    /**
     * The checks of {@code subprocess_fork_exec_impl} before any conversion: {@code errpipe_write >= 3} when
     * {@code close_fds}, then {@code _sanity_check_python_fd_sequence} (every item an int, ascending without
     * duplicates, in {@code [0, INT_MAX]}).
     */
    private static void check(Call c) {
        if (c.closeFds && c.errpipeWrite < 3) throw new PyException("ValueError", "errpipe_write must be >= 3");
        List<Object> items = ((PyTuple) NativeSubclass.unwrap(c.fdsToKeep)).items;
        int[] keep = new int[items.size()];
        long prev = -1;
        for (int i = 0; i < keep.length; i++) {
            Object u = NativeSubclass.unwrap(items.get(i));
            long v = u instanceof Long l ? l : u instanceof Boolean b ? (b ? 1 : 0) : -1;   // a BigInteger is beyond INT_MAX
            if (v < 0 || v <= prev || v > Integer.MAX_VALUE) throw new PyException("ValueError", "bad value(s) in fds_to_keep");
            keep[i] = (int) v;
            prev = v;
        }
        c.keep = keep;
    }

    /** CPython's conversion order: {@code executable_list}, then {@code args}, {@code env_list}, {@code cwd}. */
    private static void convert(Call c) {
        c.programs = bytesSeq(c.executableList);
        // args None: CPython passes a NULL argv to execve, which Linux treats as an empty one; glibc's posix_spawn
        // counts the vector and would dereference NULL (a crash, found at ticket 7), so pass an empty vector instead
        c.argv = c.args == null ? new byte[0][] : argList(c.args);
        c.envp = c.envList == null ? null : bytesSeq(c.envList).toArray(new byte[0][]);
        c.cwdBytes = c.cwd == null ? null : PosixOsProc.fsArg(c.cwd);
    }

    /** Positional binding; the C-int arguments are converted here, in order, as CPython's argument parser does. */
    private static Call bind(List<Object> a) {
        Call c = new Call();
        c.args = a.get(0);
        c.executableList = a.get(1);
        c.closeFds = PyRuntime.truth(a.get(2));
        c.fdsToKeep = a.get(3);
        if (!(NativeSubclass.unwrap(c.fdsToKeep) instanceof PyTuple))
            throw new PyException("TypeError", "fork_exec() argument 4 must be tuple, not " + PyRuntime.typeName(c.fdsToKeep));
        c.cwd = a.get(4);
        c.envList = a.get(5);
        c.p2cread = PosixOsProc.cInt(a.get(6));
        c.p2cwrite = PosixOsProc.cInt(a.get(7));
        c.c2pread = PosixOsProc.cInt(a.get(8));
        c.c2pwrite = PosixOsProc.cInt(a.get(9));
        c.errread = PosixOsProc.cInt(a.get(10));
        c.errwrite = PosixOsProc.cInt(a.get(11));
        c.errpipeRead = PosixOsProc.cInt(a.get(12));
        c.errpipeWrite = PosixOsProc.cInt(a.get(13));
        c.restoreSignals = PyRuntime.truth(a.get(14));
        c.callSetsid = PyRuntime.truth(a.get(15));
        c.pgidToSet = PosixOsProc.cInt(a.get(16));
        c.gid = a.get(17);
        c.extraGroups = a.get(18);
        c.uid = a.get(19);
        c.childUmask = PosixOsProc.cInt(a.get(20));
        c.preexecFn = a.get(21);
        return c;                                    // a.get(22) on 3.13 is allow_vfork: ignored
    }

    /** What {@code posix_spawn} cannot do (plan 1.7), before anything is created. */
    private static void unsupported(Call c) {
        if (c.preexecFn != null)
            throw new PyException("RuntimeError", "preexec_fn is not supported by pyJVM: there is no fork, so no Python code can run in the child");
        if (c.uid != null) throw notImplemented("user", "user id");
        if (c.gid != null) throw notImplemented("group", "group id");
        if (c.extraGroups != null) throw notImplemented("extra_groups", "supplementary groups");
        if (c.childUmask >= 0) throw notImplemented("umask", "umask");
    }

    private static PyException notImplemented(String what, String cannot) {
        return new PyException("NotImplementedError", "subprocess: " + what + " is not supported by pyJVM (posix_spawn cannot change the " + cannot + ")");
    }

    /**
     * Plan 1.7 steps 1-5: the temporaries, the plan, one {@code posix_spawn} per {@code executable_list} entry. Returns
     * the child's pid, or the result of {@link #failed} when no entry could be started. The temporaries are closed on
     * every path.
     */
    private static long spawn(Call c) {
        int tmpOut = -1, tmpErr = -1;
        try {
            // step 1: file actions cannot pick a free number, so a stdout/stderr source that an earlier dup2 would
            // overwrite is first copied above 2 here (CPython dups inside its child, where a failed dup is "noexec")
            int c2pwrite = c.c2pwrite, errwrite = c.errwrite;
            if (c2pwrite == 0) {
                tmpOut = PosixFds.dupAbove(0, 3);
                if (tmpOut < 0) return failed(c, osRecord(-tmpOut, "noexec"));
                c2pwrite = tmpOut;
            }
            if (errwrite == 0 || errwrite == 1) {
                tmpErr = PosixFds.dupAbove(errwrite, 3);
                if (tmpErr < 0) return failed(c, osRecord(-tmpErr, "noexec"));
                errwrite = tmpErr;
            }
            PosixSpawn.Plan p = plan(c, c.keep, c2pwrite, errwrite, c.cwdBytes);
            p.argv = c.argv;
            p.envp = c.envp;
            if (c.programs.isEmpty()) return failed(c, "SubprocessError:0:");
            // step 5: the list already is the PATH expansion, so posix_spawn (never posix_spawnp); errno rule of
            // CPython's child: the first errno that is neither ENOENT nor ENOTDIR, else the last one
            int saved = 0;
            boolean settled = false;
            for (byte[] program : c.programs) {
                p.path = program;
                long r = PosixSpawn.spawn(p);
                if (r > 0) return r;
                if (!settled) {
                    saved = (int) -r;
                    settled = saved != ENOENT && saved != ENOTDIR;
                }
            }
            return failed(c, classify(c, saved));
        } finally {
            closeQuietly(tmpOut);
            closeQuietly(tmpErr);
        }
    }

    /** Plan 1.7 steps 2 and 3: the file actions in their fixed order, and the attributes. */
    private static PosixSpawn.Plan plan(Call c, int[] keep, int c2pwrite, int errwrite, byte[] cwd) {
        PosixSpawn.Plan p = new PosixSpawn.Plan();
        // a. kept descriptors become inheritable in the child (glibc clears close-on-exec for dup2(fd, fd)); never errpipe_write
        for (int fd : keep) if (fd != c.errpipeWrite) p.add(PosixSpawn.DUP2, fd, fd, 0, null);
        // b. the parent's ends of the pipes
        for (int fd : new int[]{c.p2cwrite, c.c2pread, c.errread, c.errpipeRead}) if (fd != -1) p.add(PosixSpawn.CLOSE, fd, 0, 0, null);
        // c. stdio; an equal source (0, 1, 2 as themselves) is dup2(fd, fd), which only clears close-on-exec
        if (c.p2cread != -1) p.add(PosixSpawn.DUP2, c.p2cread, 0, 0, null);
        if (c2pwrite != -1) p.add(PosixSpawn.DUP2, c2pwrite, 1, 0, null);
        if (errwrite != -1) p.add(PosixSpawn.DUP2, errwrite, 2, 0, null);
        // d. the emulated cwd, then the caller's cwd (a relative one is resolved by this second chdir)
        PosixSpawn.Action home = PosixSpawn.cwdAction();
        if (home != null) p.actions.add(home);
        if (cwd != null) p.add(PosixSpawn.CHDIR, 0, 0, 0, cwd);
        // e. close_fds: K = {0, 1, 2} + fds_to_keep - errpipe_write; CLOSE what is open below max(K), CLOSEFROM above
        if (c.closeFds) {
            TreeSet<Integer> k = new TreeSet<>(List.of(0, 1, 2));
            for (int fd : keep) if (fd != c.errpipeWrite) k.add(fd);
            int maxK = k.last();
            for (int f : PosixFds.listFds())       // listed after the temporaries exist; used for child actions only
                if (f >= 3 && f < maxK && !k.contains(f)) p.add(PosixSpawn.CLOSE, f, 0, 0, null);
            p.add(PosixSpawn.CLOSEFROM, maxK + 1, 0, 0, null);
        }
        // step 3: attributes (SETSIGMASK is always added by PosixSpawn.spawn)
        p.sigmask = SignalModule.blockedMask();
        if (c.restoreSignals) {
            p.flags |= PosixSpawn.SETSIGDEF;
            p.sigdefault = (1L << (SIGPIPE - 1)) | (1L << (SIGXFSZ - 1));
        }
        if (c.callSetsid) p.flags |= PosixSpawn.SETSID;
        if (c.pgidToSet >= 0) {
            p.flags |= PosixSpawn.SETPGROUP;
            p.pgroup = c.pgidToSet;
        }
        return p;
    }

    /**
     * Plan 1.7 step 6: the record CPython's child would write for the errno of the failed spawn. {@code posix_spawn}
     * reports a failing file action or attribute exactly like a failed exec, so the cause is told apart here: EBADF
     * cannot come from exec (a bad stdio or kept descriptor: {@code noexec}); with a chdir action, the directories are
     * checked in the parent ({@code noexec:chdir} with the diagnosed errno); EPERM/ESRCH with a process group to set is
     * the group ({@code noexec}); anything else is the exec's own error (empty suffix). The diagnosis runs after the
     * failure, so a directory changed in between can be misreported (accepted race, plan 1.7).
     */
    private static String classify(Call c, int errno) {
        if (errno == EBADF) return osRecord(errno, "noexec");
        PosixSpawn.Action home = PosixSpawn.cwdAction();
        if (home != null || c.cwdBytes != null) {
            int d = chdirError(home == null ? null : home.path(), c.cwdBytes);
            if (d != 0) return osRecord(d, "noexec:chdir");
        }
        if ((errno == EPERM || errno == ESRCH) && c.pgidToSet >= 0) return osRecord(errno, "noexec");
        return osRecord(errno, "");
    }

    private static String osRecord(int errno, String suffix) { return "OSError:" + Integer.toHexString(errno) + ":" + suffix; }

    /**
     * The errno the child's chdir actions would have failed with, or 0: the emulated cwd first, then the caller's
     * {@code cwd} (joined to the emulated cwd when relative, as the second chdir resolves it). Not a directory is
     * ENOTDIR from the {@code S_IFMT} test ({@code faccessat} says EACCES for a file); no search permission is
     * {@code faccessat(X_OK, AT_EACCESS)}'s errno.
     */
    private static int chdirError(byte[] home, byte[] cwd) {
        if (home != null) {
            int e = dirError(home);
            if (e != 0) return e;
        }
        if (cwd == null) return 0;
        if (home == null || cwd.length == 0 || cwd[0] == '/') return dirError(cwd);
        byte[] full = java.util.Arrays.copyOf(home, home.length + 1 + cwd.length);
        full[home.length] = '/';
        System.arraycopy(cwd, 0, full, home.length + 1, cwd.length);
        return dirError(full);
    }

    private static int dirError(byte[] path) {
        int mode = PosixFds.statModeOrNeg(path);
        if (mode < 0) return -mode;
        if ((mode & PosixFds.S_IFMT) != PosixFds.S_IFDIR) return ENOTDIR;
        return -PosixProc.faccessat(path, X_OK, AT_EACCESS);
    }

    /**
     * No entry of {@code executable_list} could be started (or a temporary could not be made). The record goes to
     * {@code errpipe_write} first, so {@code subprocess} cannot see EOF on its read end before the record is there;
     * {@code subprocess} turns it into the exception. Then the stand-in child of plan 1.7 step 7 is started and its
     * pid returned: CPython's forked child exits 255 after a failed exec, and {@code subprocess} waits for it
     * ({@code returncode 255}). If the record cannot be written, the OSError propagates and no child exists. If the
     * stand-in cannot be started, the own pid is returned without raising: {@code os.waitpid} on it is ECHILD, which
     * {@code subprocess} ignores (its discarded object keeps {@code returncode None}).
     */
    private static long failed(Call c, String record) {
        byte[] b = ascii(record);
        int off = 0;
        while (off < b.length) {                              // a pipe with an empty buffer: one write in practice
            int w = PosixFds.write(c.errpipeWrite, b, off, b.length - off);
            if (w <= 0) break;
            off += w;
        }
        PosixSpawn.Plan p = new PosixSpawn.Plan();
        p.path = STAND_IN_PATH;
        p.argv = STAND_IN_ARGV;
        p.add(PosixSpawn.CLOSEFROM, 3, 0, 0, null);           // the only action: no descriptor of ours, no chdir
        p.sigmask = 0;                                        // empty mask; envp null = libc environ
        long pid = PosixSpawn.spawn(p);
        return pid > 0 ? pid : PosixProc.getpid();
    }

    private static byte[] ascii(String s) { return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }

    private static void closeQuietly(int fd) {
        if (fd < 0) return;
        try { PosixFds.close(fd); } catch (PyException ignored) {}   // our own temporary: nothing to report
    }

    // ---------------------------------------------------------------- argument conversion

    /**
     * {@code args}: {@code PySequence_Fast(args, "argv must be a tuple")}, each item through the file-system converter
     * (str, bytes, PathLike). Only a TypeError from getting the iterator becomes "argv must be a tuple"; an exception
     * raised while iterating propagates as it is (measured on the three lanes).
     */
    private static byte[][] argList(Object args) {
        Object it;
        try { it = PyRuntime.iter(args); }
        catch (PyException e) {
            if (!PyRuntime.exceptionMatches(e, "TypeError")) throw e;
            throw new PyException("TypeError", "argv must be a tuple");
        }
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) PyRuntime.listFrom(it);
        byte[][] out = new byte[items.size()][];
        for (int i = 0; i < out.length; i++) out[i] = PosixOsProc.fsArg(items.get(i));
        return out;
    }

    /**
     * {@code _PySequence_BytesToCharpArray}: {@code PySequence_Size}, then {@code PySequence_GetItem(i)} for each index,
     * each item bytes without a NUL. Texts as measured: a dict is "dict is not a sequence", no length is the {@code len()}
     * error, a set or frozenset (a length but no indexing) "'set' object does not support indexing".
     */
    private static List<byte[]> bytesSeq(Object seq) {
        Object u = NativeSubclass.unwrap(seq);
        if (u instanceof Map && !(seq instanceof PyInstance))
            throw new PyException("TypeError", PyRuntime.typeName(seq) + " is not a sequence");
        long n;
        try { n = ((Number) PyRuntime.len(seq)).longValue(); }
        catch (IllegalArgumentException e) {      // the runtime's generic "no __len__" (a user __len__ raises PyException)
            throw new PyException("TypeError", "object of type '" + PyRuntime.typeName(seq) + "' has no len()");
        }
        List<byte[]> out = new ArrayList<>();
        for (long i = 0; i < n; i++) {
            if (u instanceof java.util.Set && !(seq instanceof PyInstance))
                throw new PyException("TypeError", "'" + PyRuntime.typeName(seq) + "' object does not support indexing");
            Object item = PyRuntime.getitem(seq, i);
            if (!(NativeSubclass.unwrap(item) instanceof PyBytes b))
                throw new PyException("TypeError", "expected bytes, " + PyRuntime.typeName(item) + " found");
            for (byte x : b.data) if (x == 0) throw new PyException("ValueError", "embedded null byte");
            out.add(b.data);
        }
        return out;
    }
}
