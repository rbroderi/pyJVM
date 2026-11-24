// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static pyjvm315.runtime.NativeTypes.*;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;

/**
 * The {@code posix} functions that work on kernel descriptors ({@code IoFd.KERNEL}): registered over the shared
 * java.nio-era lambdas by {@code PosixFiles.install}, before {@code AuditEvents.install} wraps whatever is registered.
 * Never loaded on Windows.
 */
final class PosixOsFds {
    private PosixOsFds() {}

    // These cannot fail, so no errno capture.
    private static final MethodHandle UMASK = PosixNative.fnNoCapture("umask", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GETUID = id("getuid"), GETEUID = id("geteuid"), GETGID = id("getgid"), GETEGID = id("getegid"), GETPPID = id("getppid");

    private static MethodHandle id(String name) { return PosixNative.fnNoCapture(name, FunctionDescriptor.of(JAVA_INT)); }

    static void install(PyModule m) {
        fn(m, "close", (a, k) -> { PosixFds.close(PosixFiles.fd(a.get(0))); return null; });
        fn(m, "read", (a, k) -> {
            long fd = PosixFds.cfd(PosixFiles.fd(a.get(0)));       // fd converted first, as CPython (OverflowError before EINVAL)
            long len = IoUtil.asLong(a.get(1));
            if (len < 0) throw PosixNative.osError(22, null);
            return new PyBytes(PosixFds.read(fd, (int) Math.min(len, 1 << 30)));
        });
        fn(m, "write", (a, k) -> {
            long fd = PosixFiles.fd(a.get(0));
            byte[] b = IoUtil.bytesLike(a.get(1));
            return (long) PosixFds.write(fd, b, 0, b.length);
        });
        fn(m, "lseek", (a, k) -> PosixFds.lseek(PosixFiles.fd(a.get(0)), IoUtil.asLong(a.get(1)), (int) IoUtil.asLong(a.get(2))));
        fn(m, "ftruncate", (a, k) -> { PosixFds.ftruncate(PosixFiles.fd(a.get(0)), IoUtil.asLong(a.get(1))); return null; });
        fn(m, "fsync", (a, k) -> { PosixFds.fsync(PosixFiles.fd(a.get(0))); return null; });
        fn(m, "fdatasync", (a, k) -> { PosixFds.fdatasync(PosixFiles.fd(a.get(0))); return null; });
        fn(m, "dup", (a, k) -> PosixFds.dup(PosixFiles.fd(a.get(0))));
        fn(m, "isatty", (a, k) -> PosixFds.isatty(PosixFds.cfd(PosixFiles.fd(a.get(0)))));   // os.isatty(2**40): OverflowError as in CPython
        fn(m, "fstat", (a, k) -> PosixFds.fstat(PosixFiles.fd(a.get(0))));
        fn(m, "fchmod", (a, k) -> {
            PosixFiles.checkFchmodArgs(a, k);
            long fd = PosixFiles.fd(a.get(0)), mode = IoUtil.asLong(a.get(1));
            PosixNative.fchmod(PosixFds.cfd(fd), PosixFds.cfd(mode));   // mode is a C int: OverflowError rather than truncation
            return null;
        });
        fn(m, "get_inheritable", (a, k) -> PosixFds.getInheritable(PosixFiles.fd(a.get(0))));
        fn(m, "set_inheritable", (a, k) -> { PosixFds.setInheritable(PosixFiles.fd(a.get(0)), IoUtil.asLong(a.get(1)) != 0); return null; });   // C int argument
        fn(m, "get_blocking", (a, k) -> PosixFds.getBlocking(PosixFiles.fd(a.get(0))));
        fn(m, "set_blocking", (a, k) -> { PosixFds.setBlocking(PosixFiles.fd(a.get(0)), PyRuntime.truth(a.get(1))); return null; });
        fn(m, "umask", (a, k) -> umask(PosixFds.cfd(PosixFiles.fd(a.get(0)))));
        fn(m, "getuid", (a, k) -> ids(GETUID));
        fn(m, "geteuid", (a, k) -> ids(GETEUID));
        fn(m, "getgid", (a, k) -> ids(GETGID));
        fn(m, "getegid", (a, k) -> ids(GETEGID));
        fn(m, "getppid", (a, k) -> ids(GETPPID));
        fn(m, "get_terminal_size", (a, k) -> terminalSize(m, a));
        fn(m, "stat", (a, k) -> stat(a, k));
        fn(m, "lstat", (a, k) -> lstat(a, k));
        installPipes(m);
    }

    /** {@code open(2)} flags and {@code lseek} whence values the shared table (PosixFiles) does not define. */
    private static final String[] EXTRA_CONSTANT_NAMES = {"O_ACCMODE", "O_ASYNC", "O_DIRECT", "O_NOATIME", "O_PATH", "O_TMPFILE",
        "O_RSYNC", "O_FSYNC", "O_LARGEFILE", "SEEK_DATA", "SEEK_HOLE"};
    private static final long[] EXTRA_CONSTANT_VALUES = {3, 8192, 16384, 262144, 2097152, 4259840, 1052672, 1052672, 0, 3, 4};

    /** pipe, pipe2, dup2, pread, pwrite, closerange, readinto (3.14+) and their constants, with CPython's argument errors. */
    private static void installPipes(PyModule m) {
        for (int i = 0; i < EXTRA_CONSTANT_NAMES.length; i++) m.attrs.put(EXTRA_CONSTANT_NAMES[i], EXTRA_CONSTANT_VALUES[i]);
        fn(m, "pipe", (a, k) -> {
            noKeywords("posix.pipe", k);
            if (!a.isEmpty()) throw new PyException("TypeError", "posix.pipe() takes no arguments (" + a.size() + " given)");
            long[] p = PosixFds.pipe(PosixFds.O_CLOEXEC);
            return tuple(p[0], p[1]);
        });
        fn(m, "pipe2", (a, k) -> {
            noKeywords("posix.pipe2", k);
            if (a.size() != 1) throw new PyException("TypeError", "posix.pipe2() takes exactly one argument (" + a.size() + " given)");
            long[] p = PosixFds.pipe(PosixFds.cfd(PosixFiles.fd(a.get(0))));       // flags verbatim: the kernel rejects bad ones
            return tuple(p[0], p[1]);
        });
        fn(m, "dup2", (a, k) -> dup2(a, k));
        fn(m, "pread", (a, k) -> {
            positional("pread", a, k, 3);
            long fd = PosixFds.cfd(PosixFiles.fd(a.get(0)));
            long len = IoUtil.asLong(a.get(1)), off = offset(a.get(2));
            if (len < 0) throw PosixNative.osError(22, null);
            return new PyBytes(PosixFds.pread(fd, (int) Math.min(len, 1 << 30), off));
        });
        fn(m, "pwrite", (a, k) -> {
            positional("pwrite", a, k, 3);
            long fd = PosixFds.cfd(PosixFiles.fd(a.get(0)));
            byte[] b = IoUtil.bytesLike(a.get(1));
            return (long) PosixFds.pwrite(fd, b, 0, b.length, offset(a.get(2)));
        });
        fn(m, "closerange", (a, k) -> {
            positional("closerange", a, k, 2);
            int low = PosixFds.cfd(PosixFiles.fd(a.get(0))), high = PosixFds.cfd(PosixFiles.fd(a.get(1)));
            PosixFds.closeRange(low, high);
            return null;
        });
        if (LangVersion.atLeast(3, 14)) fn(m, "readinto", (a, k) -> readinto(a, k));
    }

    private static void noKeywords(String qualified, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", qualified + "() takes no keyword arguments");
    }

    /** The argument check of a positional-only function with a fixed count. */
    private static void positional(String name, List<Object> a, Map<Object, Object> k, int n) {
        noKeywords("posix." + name, k);
        if (a.size() != n) throw new PyException("TypeError", name + " expected " + n + " arguments, got " + a.size());
    }

    /** An {@code off_t}: out of the 64-bit range is OverflowError "C long" (not the ssize_t text of {@code IoUtil.asLong}). */
    private static long offset(Object o) {
        java.math.BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (b.bitLength() > 63) throw new PyException("OverflowError", "Python int too large to convert to C long");
        return b.longValue();
    }

    private static final String[] DUP2_NAMES = {"fd", "fd2", "inheritable"};

    /** {@code os.dup2(fd, fd2, inheritable=True)}: all three by position or keyword, in Argument Clinic's order of checks. */
    private static Object dup2(List<Object> a, Map<Object, Object> k) {
        int given = a.size() + k.size();                  // Argument Clinic counts keywords too ("keyword " when none is positional)
        if (given > 3) throw new PyException("TypeError", "dup2() takes at most 3 " + (a.isEmpty() ? "keyword " : "") + "arguments (" + given + " given)");
        Object[] v = new Object[3];
        boolean[] have = new boolean[3];
        for (int i = 0; i < a.size(); i++) { v[i] = a.get(i); have[i] = true; }
        int positionalCount = a.size();
        for (int i = positionalCount; i < 3; i++) {
            if (k.containsKey(DUP2_NAMES[i])) { v[i] = k.get(DUP2_NAMES[i]); have[i] = true; }
            else if (i < 2) throw new PyException("TypeError", "dup2() missing required argument '" + DUP2_NAMES[i] + "' (pos " + (i + 1) + ")");
        }
        for (Object key : k.keySet()) {                  // what is left over: a name that does not exist, or one given twice
            int i = java.util.Arrays.asList(DUP2_NAMES).indexOf(key);
            if (i < 0) throw new PyException("TypeError", "dup2() got an unexpected keyword argument '" + key + "'");
            if (i < positionalCount) throw new PyException("TypeError", "argument for dup2() given by name ('" + DUP2_NAMES[i] + "') and position (" + (i + 1) + ")");
        }
        long fd = PosixFds.cfd(PosixFiles.fd(v[0])), fd2 = PosixFds.cfd(PosixFiles.fd(v[1]));
        return PosixFds.dup2(fd, fd2, !have[2] || PyRuntime.truth(v[2]));
    }

    /** {@code os.readinto(fd, buffer)}: one read of up to len(buffer) bytes into a writable buffer; returns the count. */
    private static Object readinto(List<Object> a, Map<Object, Object> k) {
        positional("readinto", a, k, 2);
        long fd = PosixFds.cfd(PosixFiles.fd(a.get(0)));
        Object target = NativeSubclass.unwrap(a.get(1));
        boolean ok = target instanceof PyByteArray || (target instanceof PyMemoryView v && !v.readonly() && v.contiguous()) || ArrayModule.asArray(target) != null;
        if (!ok) throw new PyException("TypeError", "readinto() argument 2 must be read-write bytes-like object, not "
            + (target == null ? "None" : PyRuntime.typeName(a.get(1))));
        byte[] got = PosixFds.read(fd, Math.min(IoUtil.bufferSize(target), 1 << 30));
        IoUtil.fill(target, got, 0, got.length);
        return (long) got.length;
    }

    // ---- stat, lstat -------------------------------------------------------------------------------------------

    private static boolean isInt(Object o) { return o instanceof Long || o instanceof Boolean || o instanceof java.math.BigInteger; }

    private static boolean isPath(Object o) {
        return o instanceof String || o instanceof PyBytes || (o instanceof PyInstance i && i.cls.lookupMethod("__fspath__") != null);
    }

    private static Object pathArg(String func, List<Object> a, Map<Object, Object> k) {
        Object o = a.isEmpty() ? k.get("path") : a.get(0);
        if (a.isEmpty() && !k.containsKey("path")) throw new PyException("TypeError", func + "() missing required argument 'path' (pos 1)");
        return o;
    }

    private static PyException badPath(String func, String accepts, Object o) {
        return new PyException("TypeError", func + ": path should be " + accepts + ", not " + PyRuntime.typeName(o));
    }

    private static void noDirFd(String func, Map<Object, Object> k) {
        if (k.get("dir_fd") != null) throw new PyException("NotImplementedError", "os." + func + ": dir_fd is not supported");
    }

    /** {@code os.stat(path, *, dir_fd=None, follow_symlinks=True)}; an int is a descriptor ({@code True} is 1). */
    private static Object stat(List<Object> a, Map<Object, Object> k) {
        Object o = pathArg("stat", a, k);
        noDirFd("stat", k);
        Object u = NativeSubclass.unwrap(o);
        boolean follow = !k.containsKey("follow_symlinks") || PyRuntime.truth(k.get("follow_symlinks"));
        if (isInt(u)) {
            if (!follow) throw new PyException("ValueError", "stat: cannot use fd and follow_symlinks together");
            return PosixFds.fstat(PosixFiles.fd(u), u instanceof Boolean b ? (Object) (b ? 1L : 0L) : u);
        }
        if (!isPath(o) && !isPath(u)) throw badPath("stat", "string, bytes, os.PathLike or integer", o);
        return PosixModule.stat(PosixModule.pathString(o), follow);
    }

    /** {@code os.lstat(path, *, dir_fd=None)}: a descriptor is a TypeError. */
    private static Object lstat(List<Object> a, Map<Object, Object> k) {
        Object o = pathArg("lstat", a, k);
        noDirFd("lstat", k);
        if (!isPath(o) && !isPath(NativeSubclass.unwrap(o))) throw badPath("lstat", "string, bytes or os.PathLike", o);
        return PosixModule.stat(PosixModule.pathString(o), false);
    }

    /** {@code umask(2)} stores {@code mask & 0777} and returns the previous mask; nothing is cached on the Java side. */
    private static Object umask(int mask) {
        try { return Integer.toUnsignedLong((int) UMASK.invokeExact(mask)); } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    /** uid_t, gid_t and pid_t results as unsigned 32-bit values. */
    private static Object ids(MethodHandle h) {
        try { return Integer.toUnsignedLong((int) h.invokeExact()); } catch (Throwable x) { throw PosixNative.rethrow(x); }
    }

    /** {@code os.get_terminal_size(fd=1)}, built like nt's: an {@code os.terminal_size((columns, lines))}. */
    private static Object terminalSize(PyModule m, List<Object> a) {
        long[] size = PosixFds.winsize(a.isEmpty() ? 1L : PosixFiles.fd(a.get(0)));
        return PyRuntime.instantiate((PyClass) m.attrs.get("terminal_size"), new Object[]{tuple(size[0], size[1])});
    }
}
