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

    private static final int ENOENT = 2, ENOTDIR = 20;
    private static final int SIGPIPE = 13, SIGXFSZ = 25;
    /** Marker errno for an empty {@code executable_list} (CPython's child reports {@code SubprocessError:0:}). */
    private static final int NO_PROGRAM = -1;

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
    }

    private static Object forkExec(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_posixsubprocess.fork_exec() takes no keyword arguments");
        int n = LangVersion.atLeast(3, 14) ? 22 : 23;
        if (a.size() != n) throw new PyException("TypeError", "fork_exec expected " + n + " arguments, got " + a.size());
        Call c = bind(a);
        unsupported(c);
        return spawn(c);
    }

    /** Positional binding; the C-int arguments are converted here, in order, as CPython's argument parser does. */
    private static Call bind(List<Object> a) {
        Call c = new Call();
        c.args = a.get(0);
        c.executableList = a.get(1);
        c.closeFds = PyRuntime.truth(a.get(2));
        c.fdsToKeep = a.get(3);
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
        byte[][] argv = c.args == null ? null : argList(c.args);
        List<byte[]> programs = bytesList(c.executableList);
        byte[][] envp = c.envList == null ? null : bytesList(c.envList).toArray(new byte[0][]);
        byte[] cwd = c.cwd == null ? null : PosixOsProc.fsArg(c.cwd);
        int[] keep = fdList(c.fdsToKeep);
        int tmpOut = -1, tmpErr = -1;
        try {
            // step 1: file actions cannot pick a free number, so a stdout/stderr source that an earlier dup2 would
            // overwrite is first copied above 2 here (CPython dups inside its child)
            int c2pwrite = c.c2pwrite, errwrite = c.errwrite;
            if (c2pwrite == 0) {
                tmpOut = PosixFds.dupAbove(0, 3);
                if (tmpOut < 0) return failed(c, -tmpOut);
                c2pwrite = tmpOut;
            }
            if (errwrite == 0 || errwrite == 1) {
                tmpErr = PosixFds.dupAbove(errwrite, 3);
                if (tmpErr < 0) return failed(c, -tmpErr);
                errwrite = tmpErr;
            }
            PosixSpawn.Plan p = plan(c, keep, c2pwrite, errwrite, cwd);
            p.argv = argv;
            p.envp = envp;
            if (programs.isEmpty()) return failed(c, NO_PROGRAM);
            // step 5: the list already is the PATH expansion, so posix_spawn (never posix_spawnp); errno rule of
            // CPython's child: the first errno that is neither ENOENT nor ENOTDIR, else the last one
            int saved = 0;
            boolean settled = false;
            for (byte[] program : programs) {
                p.path = program;
                long r = PosixSpawn.spawn(p);
                if (r > 0) return r;
                if (!settled) {
                    saved = (int) -r;
                    settled = saved != ENOENT && saved != ENOTDIR;
                }
            }
            return failed(c, saved);
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
     * No entry of {@code executable_list} could be started (or a temporary could not be made): the error record goes to
     * {@code errpipe_write}, where {@code subprocess} reads it after closing its copy. Interim (ticket 6): the record
     * names the errno only ({@code OSError:<hex>:}; {@code SubprocessError:0:} for an empty list) and the own pid is
     * returned ({@code os.waitpid} on it is ECHILD, which {@code subprocess} ignores). Ticket 7 replaces exactly this
     * method: classification by plan 1.7 step 6 and the stand-in child of step 7.
     */
    private static long failed(Call c, int errno) {
        String rec = errno == NO_PROGRAM ? "SubprocessError:0:" : "OSError:" + Integer.toHexString(errno) + ":";
        byte[] b = rec.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        PosixFds.write(c.errpipeWrite, b, 0, b.length);    // an empty pipe: the short record cannot block
        return PosixProc.getpid();
    }

    private static void closeQuietly(int fd) {
        if (fd < 0) return;
        try { PosixFds.close(fd); } catch (PyException ignored) {}   // our own temporary: nothing to report
    }

    // ---------------------------------------------------------------- argument conversion

    /** {@code args}: any sequence ({@code PySequence_Fast}), each item through the file-system converter (str, bytes, PathLike). */
    private static byte[][] argList(Object args) {
        List<Object> items;
        try { items = asList(args); }
        catch (PyException e) {
            if (!e.typeName.equals("TypeError")) throw e;
            throw new PyException("TypeError", "argv must be a tuple");
        }
        byte[][] out = new byte[items.size()][];
        for (int i = 0; i < out.length; i++) out[i] = PosixOsProc.fsArg(items.get(i));
        return out;
    }

    /** {@code _PySequence_BytesToCharpArray}: a sequence whose items are bytes. */
    private static List<byte[]> bytesList(Object seq) {
        List<byte[]> out = new ArrayList<>();
        for (Object item : asList(seq)) {
            if (!(NativeSubclass.unwrap(item) instanceof PyBytes b))
                throw new PyException("TypeError", "expected bytes, " + PyRuntime.typeName(item) + " found");
            out.add(b.data);
        }
        return out;
    }

    private static int[] fdList(Object fds) {
        List<Object> items = asList(fds);
        int[] out = new int[items.size()];
        for (int i = 0; i < out.length; i++) out[i] = PosixOsProc.cInt(items.get(i));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) { return (List<Object>) PyRuntime.listFrom(o); }
}
