// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Process creation through glibc's {@code posix_spawn}/{@code posix_spawnp} (N1 plan 1.2, 1.4, 1.8). One entry point,
 * {@link #spawn}, takes a {@link Plan} of plain Java values and returns the child's pid or {@code -errno}; it never
 * raises for an OS error and creates no descriptor in the parent. Everything handed to libc is native memory from one
 * confined Arena per call: the child shares the address space until it execs ({@code CLONE_VM | CLONE_VFORK}), so
 * nothing heap-backed may be passed. The {@code posix_spawn*} family returns the error number instead of setting
 * errno, so these handles capture nothing. Only touched on POSIX with kernel descriptors; never loaded on Windows.
 */
final class PosixSpawn {
    private PosixSpawn() {}

    /** Action kinds of a {@link Plan}. */
    static final int OPEN = 0, CLOSE = 1, DUP2 = 2, CHDIR = 3, CLOSEFROM = 4;
    /** Internal: CLOSE of a JVM-owned descriptor, skipped if Python adopted the number since the listing (gen != 0). */
    private static final int JVM_CLOSE = 5;
    /** {@code posix_spawnattr_setflags} bits (glibc). */
    static final int RESETIDS = 1, SETPGROUP = 2, SETSIGDEF = 4, SETSIGMASK = 8, SETSID = 128;

    static {
        if (PosixNative.symbol("posix_spawn_file_actions_addclosefrom_np") == null) {
            throw new PyException("ImportError", "pyJVM's POSIX runtime supports Linux x86-64 glibc only (found "
                + System.getProperty("os.name", "") + "/" + System.getProperty("os.arch", "")
                + ") (glibc 2.34 or newer is required for process creation)");
        }
    }

    private static final FunctionDescriptor SPAWN_D = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor OBJ_D = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final MethodHandle SPAWN = PosixNative.fnNoCapture("posix_spawn", SPAWN_D);
    private static final MethodHandle SPAWNP = PosixNative.fnNoCapture("posix_spawnp", SPAWN_D);
    private static final MethodHandle FA_INIT = PosixNative.fnNoCapture("posix_spawn_file_actions_init", OBJ_D);
    private static final MethodHandle FA_DESTROY = PosixNative.fnNoCapture("posix_spawn_file_actions_destroy", OBJ_D);
    private static final MethodHandle FA_OPEN = PosixNative.fnNoCapture("posix_spawn_file_actions_addopen",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle FA_CLOSE = PosixNative.fnNoCapture("posix_spawn_file_actions_addclose",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle FA_DUP2 = PosixNative.fnNoCapture("posix_spawn_file_actions_adddup2",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle FA_CHDIR = PosixNative.fnNoCapture("posix_spawn_file_actions_addchdir_np",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FA_CLOSEFROM = PosixNative.fnNoCapture("posix_spawn_file_actions_addclosefrom_np",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle AT_INIT = PosixNative.fnNoCapture("posix_spawnattr_init", OBJ_D);
    private static final MethodHandle AT_DESTROY = PosixNative.fnNoCapture("posix_spawnattr_destroy", OBJ_D);
    private static final MethodHandle AT_FLAGS = PosixNative.fnNoCapture("posix_spawnattr_setflags",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT));
    private static final MethodHandle AT_PGROUP = PosixNative.fnNoCapture("posix_spawnattr_setpgroup",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle AT_SIGMASK = PosixNative.fnNoCapture("posix_spawnattr_setsigmask",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle AT_SIGDEF = PosixNative.fnNoCapture("posix_spawnattr_setsigdefault",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    /**
     * The address of libc's {@code char **environ} variable (a fixed location). Its value, the array itself, moves when
     * a new variable is set (spike item 3), so {@link #spawn} reads the value at every call.
     */
    private static final MemorySegment ENVIRON_VAR = PosixNative.symbol("environ").reinterpret(8);

    private static final long FA_SIZE = 80, ATTR_SIZE = 336, SIGSET_SIZE = 128;

    /** One file action: {@code OPEN(a = fd, path, b = oflag, c = mode)}, {@code CLOSE(a)}, {@code DUP2(a, b)}, {@code CHDIR(path)}, {@code CLOSEFROM(a)}. */
    record Action(int kind, int a, int b, int c, byte[] path) {}

    /** What to spawn; plain Java values only. */
    static final class Plan {
        /** The program (no NUL inside; the caller checks). */
        byte[] path;
        /** The argument vector; null passes a NULL {@code argv} pointer. */
        byte[][] argv;
        /** {@code NAME=value} entries; null passes libc's live {@code environ}, read at call time. */
        byte[][] envp;
        /** {@code posix_spawnp} (PATH search in the parent's environment) instead of {@code posix_spawn}. */
        boolean usePath;
        /** File actions, applied by the child in this order. */
        final List<Action> actions = new ArrayList<>();
        /** {@code POSIX_SPAWN_*} flags; {@link #SETSIGMASK} is always added by {@link #spawn}. */
        int flags;
        int pgroup;
        /** Signal {@code n} at bit {@code n - 1}. */
        long sigmask, sigdefault;
        /** Output: the error came from adding a file action or setting an attribute, not from the spawn itself. */
        boolean failedAtAdd;

        void add(int kind, int a, int b, int c, byte[] path) { actions.add(new Action(kind, a, b, c, path)); }
    }

    /**
     * {@code CHDIR} to the emulated working directory (N0 1.1), or null when {@code os.chdir} was never called (the
     * JVM's real cwd is then the child's, so a deleted start directory does not break spawning). Callers put it before
     * any action that names a relative path.
     */
    static Action cwdAction() {
        Path cwd = PosixModule.cwd;
        if (cwd == null) return null;
        return new Action(CHDIR, 0, 0, 0, Codecs.encode(cwd.toString(), "utf-8", "surrogateescape"));
    }

    /**
     * Spawns per the plan: the child's pid, or {@code -errno}. Unless the actions end with {@code CLOSEFROM(n)} for
     * {@code n <= 3}, {@code CLOSE} actions for the JVM's own inheritable descriptors ({@link #jvmInheritable}) are put
     * before the caller's actions (plan 1.8), so the child sees only stdio and what Python made inheritable or the JVM
     * inherited from its launcher. Each such action re-checks {@code gen(f) == 0} when it is added, just before the call,
     * so a number the JDK closed and Python reused after the listing is not closed; the window left is the few native
     * calls between that add and {@code posix_spawn} (accepted gap, plan 1.8).
     */
    static long spawn(Plan p) {
        p.failedAtAdd = false;
        List<Action> actions = p.actions;
        Action last = actions.isEmpty() ? null : actions.get(actions.size() - 1);
        if (last == null || last.kind() != CLOSEFROM || last.a() > 3) {
            int[] own = jvmInheritable();
            if (own.length > 0) {
                actions = new ArrayList<>(own.length + p.actions.size());
                for (int f : own) actions.add(new Action(JVM_CLOSE, f, 0, 0, null));
                actions.addAll(p.actions);
            }
        }
        int flags = p.flags | SETSIGMASK;
        try (Arena a = Arena.ofConfined()) {
            // every buffer first: nothing below allocates or encodes once the libc objects are initialised
            MemorySegment path = cString(a, p.path);
            MemorySegment argv = p.argv == null ? MemorySegment.NULL : strArray(a, p.argv);
            MemorySegment envp = p.envp == null ? MemorySegment.NULL : strArray(a, p.envp);
            MemorySegment[] actionPaths = new MemorySegment[actions.size()];
            for (int i = 0; i < actionPaths.length; i++) {
                byte[] ap = actions.get(i).path();
                actionPaths[i] = ap == null ? MemorySegment.NULL : cString(a, ap);
            }
            MemorySegment pidCell = a.allocate(4, 4);
            MemorySegment fa = a.allocate(FA_SIZE, 8);
            MemorySegment attr = a.allocate(ATTR_SIZE, 8);
            MemorySegment sigmask = a.allocate(SIGSET_SIZE, 8);   // zero-filled: bits above 64 stay clear
            sigmask.set(JAVA_LONG, 0, p.sigmask);
            MemorySegment sigdef = a.allocate(SIGSET_SIZE, 8);
            sigdef.set(JAVA_LONG, 0, p.sigdefault);
            return run(p, actions, actionPaths, flags, path, argv, envp, pidCell, fa, attr, sigmask, sigdef);
        }
    }

    /** The libc part of {@link #spawn}: init, add, spawn, and destroy in {@code finally}, on buffers that are all allocated. */
    private static long run(Plan p, List<Action> actions, MemorySegment[] actionPaths, int flags, MemorySegment path, MemorySegment argv,
                            MemorySegment envp, MemorySegment pidCell, MemorySegment fa, MemorySegment attr, MemorySegment sigmask,
                            MemorySegment sigdef) {
        boolean faInit = false, attrInit = false;
        try {
            int rc = (int) FA_INIT.invokeExact(fa);
            if (rc != 0) return failedAdd(p, rc);
            faInit = true;
            for (int i = 0; i < actions.size(); i++) {
                Action x = actions.get(i);
                rc = switch (x.kind()) {
                    case OPEN -> (int) FA_OPEN.invokeExact(fa, x.a(), actionPaths[i], x.b(), x.c());
                    case CLOSE -> (int) FA_CLOSE.invokeExact(fa, x.a());
                    case JVM_CLOSE -> PosixFds.gen(x.a()) != 0 ? 0 : (int) FA_CLOSE.invokeExact(fa, x.a());
                    case DUP2 -> (int) FA_DUP2.invokeExact(fa, x.a(), x.b());
                    case CHDIR -> (int) FA_CHDIR.invokeExact(fa, actionPaths[i]);
                    case CLOSEFROM -> (int) FA_CLOSEFROM.invokeExact(fa, x.a());
                    default -> throw new IllegalArgumentException("spawn action " + x.kind());
                };
                if (rc != 0) return failedAdd(p, rc);
            }
            rc = (int) AT_INIT.invokeExact(attr);
            if (rc != 0) return failedAdd(p, rc);
            attrInit = true;
            rc = (int) AT_FLAGS.invokeExact(attr, (short) flags);
            if (rc == 0 && (flags & SETPGROUP) != 0) rc = (int) AT_PGROUP.invokeExact(attr, p.pgroup);
            if (rc == 0) rc = (int) AT_SIGMASK.invokeExact(attr, sigmask);
            if (rc == 0 && (flags & SETSIGDEF) != 0) rc = (int) AT_SIGDEF.invokeExact(attr, sigdef);
            if (rc != 0) return failedAdd(p, rc);
            boolean liveEnv = envp.equals(MemorySegment.NULL);
            if (liveEnv || p.usePath) {
                // the child reads libc's environment (the array, or PATH for the search) until it execs: no setenv meanwhile
                synchronized (PosixNative.ENV_LOCK) {
                    MemorySegment env = liveEnv ? ENVIRON_VAR.get(ADDRESS, 0) : envp;
                    rc = p.usePath ? (int) SPAWNP.invokeExact(pidCell, path, fa, attr, argv, env)
                                   : (int) SPAWN.invokeExact(pidCell, path, fa, attr, argv, env);
                }
            } else {
                rc = (int) SPAWN.invokeExact(pidCell, path, fa, attr, argv, envp);
            }
            if (rc != 0) return -rc;
            return pidCell.get(JAVA_INT, 0);      // written only by a successful call
        } catch (Throwable t) {
            throw PosixNative.rethrow(t);
        } finally {
            try {
                if (attrInit) { int ignored = (int) AT_DESTROY.invokeExact(attr); }
                if (faInit) { int ignored = (int) FA_DESTROY.invokeExact(fa); }
            } catch (Throwable t) { throw PosixNative.rethrow(t); }
        }
    }

    private static long failedAdd(Plan p, int rc) {
        p.failedAtAdd = true;
        return -rc;
    }

    private static MemorySegment cString(Arena a, byte[] b) {
        MemorySegment seg = a.allocate(b.length + 1L);      // zero-filled: the last byte is the NUL
        MemorySegment.copy(b, 0, seg, JAVA_BYTE, 0, b.length);
        return seg;
    }

    /** A NULL-terminated array of C strings: {@code n + 1} pointer slots, the last one NULL (the Arena zero-fills). */
    private static MemorySegment strArray(Arena a, byte[][] items) {
        MemorySegment arr = a.allocate(8L * (items.length + 1), 8);
        for (int i = 0; i < items.length; i++) arr.setAtIndex(ADDRESS, i, cString(a, items[i]));
        return arr;
    }

    // ---- the JVM's own inheritable descriptors (plan 1.8) ----------------------------------------------------

    /**
     * The descriptors above 2 that belong to the JVM and would reach a child: never adopted by Python
     * ({@code PosixFds.gen == 0}), not close-on-exec, and naming a file the JDK opens itself. Measured in a running
     * pyJVM (ticket 4): {@code $JAVA_HOME/lib/modules} (the jimage file) and {@code /dev/random} and
     * {@code /dev/urandom} (the JDK's NativePRNG), all three opened without {@code O_CLOEXEC}. Descriptors the JVM
     * inherited from its launcher (also generation 0) name other files and pass through, as in CPython. Consequence of
     * the target rule (a gap against CPython): a launcher-inherited descriptor naming {@code /dev/random},
     * {@code /dev/urandom} or a file under {@code java.home} or a class-/module-path entry is closed in children too. Nothing in
     * the parent is changed. The listing closes its own directory stream before the check, so it is never reported.
     */
    static int[] jvmInheritable() {
        int[] fds = PosixFds.listFds();
        int[] out = new int[fds.length];
        int n = 0;
        for (int f : fds) {
            if (f <= 2 || PosixFds.gen(f) != 0) continue;
            int fl = PosixFds.fdFlagsOrNeg(f);
            if (fl < 0 || (fl & PosixFds.FD_CLOEXEC) != 0) continue;
            String target;
            try { target = Files.readSymbolicLink(Path.of("/proc/self/fd/" + f)).toString(); }
            catch (Exception e) { continue; }                     // closed since the listing
            if (JvmFiles.owns(target)) out[n++] = f;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** The files and directories whose open descriptors are the JVM's own: computed once. */
    private static final class JvmFiles {
        static final List<String> DIRS = new ArrayList<>(), FILES = new ArrayList<>(List.of("/dev/random", "/dev/urandom"));

        static {
            addEntry(System.getProperty("java.home"));
            for (String prop : new String[]{"java.class.path", "jdk.module.path"}) {
                String v = System.getProperty(prop);
                if (v == null || v.isEmpty()) continue;
                for (String e : v.split(File.pathSeparator)) addEntry(e);
            }
        }

        private static void addEntry(String e) {
            if (e == null || e.isEmpty()) return;
            try {
                Path real = Path.of(e).toRealPath();
                (Files.isDirectory(real) ? DIRS : FILES).add(real.toString());
            } catch (Exception ignored) {}                        // a class-path entry that does not exist
        }

        static boolean owns(String target) {
            if (FILES.contains(target)) return true;
            for (String d : DIRS) if (target.equals(d) || target.startsWith(d.endsWith("/") ? d : d + "/")) return true;
            return false;
        }
    }
}
