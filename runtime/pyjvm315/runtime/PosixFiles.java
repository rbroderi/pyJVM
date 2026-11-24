// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The part of {@code posix} beyond importlib's needs: file descriptors (through {@link IoFd}: kernel descriptors on
 * POSIX, FileChannel-backed entries on Windows and under {@code PYJVM_POSIX_FDS=nio}), directory and file
 * manipulation, {@code scandir}, process identity and the {@code os.O_*} / {@code F_OK} constants. Process creation,
 * signals, ownership changes, symbolic-link creation and terminal control are not implemented (they are left out, so
 * os.py's {@code _exists(...)} probes report them as absent, as on a platform without them).
 */
final class PosixFiles {
    private PosixFiles() {}

        private static PyClass dirEntryClass, scandirClass;

    static void install(PyModule m) {
        m.attrs.put("F_OK", 0L); m.attrs.put("R_OK", 4L); m.attrs.put("W_OK", 2L); m.attrs.put("X_OK", 1L);
        for (Object[] c : new Object[][]{{"O_RDONLY", 0L}, {"O_WRONLY", 1L}, {"O_RDWR", 2L}, {"O_CREAT", 0100L}, {"O_EXCL", 0200L},
                {"O_NOCTTY", 0400L}, {"O_TRUNC", 01000L}, {"O_APPEND", 02000L}, {"O_NONBLOCK", 04000L}, {"O_DIRECTORY", 0200000L},
                {"O_NOFOLLOW", 0400000L}, {"O_CLOEXEC", 02000000L}, {"O_SYNC", 04010000L}, {"O_DSYNC", 010000L}, {"O_NDELAY", 04000L},
                {"SEEK_SET", 0L}, {"SEEK_CUR", 1L}, {"SEEK_END", 2L}, {"EX_OK", 0L}, {"WNOHANG", 1L}})
            m.attrs.put((String) c[0], c[1]);
        fn(m, "waitpid", (a, k) -> { throw new PyException("ChildProcessError", "[Errno 10] No child processes"); });
        fn(m, "wait", (a, k) -> { throw new PyException("ChildProcessError", "[Errno 10] No child processes"); });
        fn(m, "WIFEXITED", (a, k) -> (IoUtil.asLong(a.get(0)) & 0x7f) == 0);
        fn(m, "WEXITSTATUS", (a, k) -> (IoUtil.asLong(a.get(0)) >> 8) & 0xff);
        fn(m, "WIFSIGNALED", (a, k) -> { long s = IoUtil.asLong(a.get(0)) & 0x7f; return s != 0 && s != 0x7f; });
        fn(m, "WTERMSIG", (a, k) -> IoUtil.asLong(a.get(0)) & 0x7f);
        fn(m, "WIFSTOPPED", (a, k) -> (IoUtil.asLong(a.get(0)) & 0xff) == 0x7f);
        fn(m, "WSTOPSIG", (a, k) -> (IoUtil.asLong(a.get(0)) >> 8) & 0xff);
        fn(m, "WIFCONTINUED", (a, k) -> IoUtil.asLong(a.get(0)) == 0xffff);
        fn(m, "waitstatus_to_exitcode", (a, k) -> {
            long st = IoUtil.asLong(a.get(0)), sig = st & 0x7f;
            if (sig == 0) return (st >> 8) & 0xff;
            if (sig == 0x7f) throw new PyException("ValueError", "invalid wait status: " + st);
            return -sig;
        });
        m.attrs.put("_have_functions", new ArrayList<Object>());
        fn(m, "_exit", (a, k) -> { IoFd.flushProcessStreams(); Runtime.getRuntime().halt((int) IoUtil.asLong(a.get(0))); return null; });
        fn(m, "open", (a, k) -> open(a, k));
        fn(m, "close", (a, k) -> { IoFd.close(fd(a.get(0))); return null; });
        fn(m, "read", (a, k) -> read(fd(a.get(0)), a.get(1)));
        fn(m, "write", (a, k) -> write(fd(a.get(0)), a.get(1)));
        fn(m, "lseek", (a, k) -> IoFd.seek(fd(a.get(0)), IoUtil.asLong(a.get(1)), (int) IoUtil.asLong(a.get(2))));
        fn(m, "fstat", (a, k) -> fstat(fd(a.get(0))));
        fn(m, "isatty", (a, k) -> { long f = fd(a.get(0)); return IoFd.valid(f) && IoFd.isatty(f); });
        fn(m, "getcwdb", (a, k) -> new PyBytes(PosixModule.cwd().getBytes(StandardCharsets.UTF_8)));
        fn(m, "chdir", (a, k) -> { PosixModule.chdir(a.get(0)); return null; });
        fn(m, "dup", (a, k) -> IoFd.dup(fd(a.get(0))));
        fn(m, "ftruncate", (a, k) -> { IoFd.truncate(fd(a.get(0)), IoUtil.asLong(a.get(1))); return null; });
        fn(m, "fsync", (a, k) -> { IoFd.sync(fd(a.get(0))); return null; });
        fn(m, "fdatasync", (a, k) -> { IoFd.sync(fd(a.get(0))); return null; });
        fn(m, "get_inheritable", (a, k) -> { IoFd.get(fd(a.get(0))); return false; });
        fn(m, "set_inheritable", (a, k) -> { IoFd.get(fd(a.get(0))); return null; });
        fn(m, "get_blocking", (a, k) -> { IoFd.get(fd(a.get(0))); return true; });
        fn(m, "set_blocking", (a, k) -> { IoFd.get(fd(a.get(0))); return null; });
        fn(m, "umask", (a, k) -> IoFd.umask());
        fn(m, "utime", (a, k) -> PosixModule.utime(a, k));
        fn(m, "fsencode", (a, k) -> fsencode(a.get(0)));
        fn(m, "fsdecode", (a, k) -> fsdecode(a.get(0)));
        fn(m, "link", (a, k) -> link(a, false));
        fn(m, "symlink", (a, k) -> link(a, true));
        fn(m, "readlink", (a, k) -> readlink(a.get(0)));
        fn(m, "chmod", (a, k) -> chmod(PosixModule.pathString(a.get(0)), IoUtil.asLong(a.get(1))));
        fn(m, "fchmod", (a, k) -> fchmod(a, k));
        fn(m, "get_terminal_size", (a, k) -> { throw PosixModule.osError("OSError", 25, "Inappropriate ioctl for device", null); });
        fn(m, "mkdir", (a, k) -> mkdir(PosixModule.pathString(a.get(0))));
        fn(m, "rmdir", (a, k) -> delete(PosixModule.pathString(a.get(0)), true));
        fn(m, "unlink", (a, k) -> delete(PosixModule.pathString(a.get(0)), false));
        fn(m, "remove", (a, k) -> delete(PosixModule.pathString(a.get(0)), false));
        fn(m, "rename", (a, k) -> move(a, false));
        fn(m, "replace", (a, k) -> move(a, true));
        fn(m, "access", (a, k) -> access(PosixModule.pathString(a.get(0)), ((Number) a.get(1)).intValue()));
        fn(m, "getppid", (a, k) -> ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L));
        for (String id : new String[]{"getuid", "geteuid", "getgid", "getegid"}) fn(m, id, (a, k) -> unixId(id.endsWith("uid") ? "uid" : "gid"));
        fn(m, "urandom", (a, k) -> { byte[] b = new byte[(int) ((Number) a.get(0)).longValue()]; new SecureRandom().nextBytes(b); return new PyBytes(b); });
        fn(m, "cpu_count", (a, k) -> (long) Runtime.getRuntime().availableProcessors());
        fn(m, "device_encoding", (a, k) -> null);
        fn(m, "putenv", (a, k) -> putenv(a, k));
        fn(m, "unsetenv", (a, k) -> unsetenv(a, k));
        fn(m, "scandir", (a, k) -> scandir(a.isEmpty() ? null : a.get(0)));

        dirEntryClass = define("posix", "DirEntry", null);
        method(dirEntryClass, PosixFiles.class, "is_dir", "deIsDir", "self,follow_symlinks", defaults("follow_symlinks", true));
        method(dirEntryClass, PosixFiles.class, "is_file", "deIsFile", "self,follow_symlinks", defaults("follow_symlinks", true));
        method(dirEntryClass, PosixFiles.class, "is_symlink", "deIsSymlink", "self");
        method(dirEntryClass, PosixFiles.class, "stat", "deStat", "self,follow_symlinks", defaults("follow_symlinks", true));
        method(dirEntryClass, PosixFiles.class, "inode", "deInode", "self");
        method(dirEntryClass, PosixFiles.class, "__fspath__", "dePath", "self");
        method(dirEntryClass, PosixFiles.class, "__repr__", "deRepr", "self");
        method(dirEntryClass, PosixFiles.class, "__enter__", "deSelf", "self");
        methodVar(dirEntryClass, PosixFiles.class, "__exit__", "deExit", "self", "args");
        PyRuntime.classAddProperty(dirEntryClass, "name", PosixFiles.class.getName(), "deName", null);
        PyRuntime.classAddProperty(dirEntryClass, "path", PosixFiles.class.getName(), "dePathProp", null);
        finish(dirEntryClass);
        m.attrs.put("DirEntry", dirEntryClass);
        scandirClass = define("posix", "ScandirIterator", null);
        method(scandirClass, PosixFiles.class, "__iter__", "deSelf", "self");
        method(scandirClass, PosixFiles.class, "__next__", "sdNext", "self");
        method(scandirClass, PosixFiles.class, "__enter__", "deSelf", "self");
        methodVar(scandirClass, PosixFiles.class, "__exit__", "sdExit", "self", "args");
        method(scandirClass, PosixFiles.class, "close", "sdClose", "self");
        finish(scandirClass);
        if (IoFd.KERNEL) PosixOsFds.install(m);
    }

    // ---- file descriptors (the table lives in IoFd) ------------------------------------------------

    static long fd(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof Long l) return l;
        if (o instanceof Boolean bo) return bo ? 1 : 0;
        if (o instanceof java.math.BigInteger) throw new PyException("OverflowError", "Python int too large to convert to C int");
        try { return IoUtil.asLong(o); }
        catch (PyException e) { throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer"); }
    }

    private static Object open(List<Object> a, Map<Object,Object> k) {
        if (a.isEmpty() && !k.containsKey("path")) throw new PyException("TypeError", "open() missing required argument 'path' (pos 1)");
        String path = PosixModule.pathString(a.isEmpty() ? k.get("path") : a.get(0));
        Object fl = a.size() > 1 ? a.get(1) : k.get("flags");
        if (fl == null) throw new PyException("TypeError", "open() missing required argument 'flags' (pos 2)");
        Object mode = a.size() > 2 ? a.get(2) : k.containsKey("mode") ? k.get("mode") : (Object) 0777L;
        if (k.get("dir_fd") != null) throw new PyException("NotImplementedError", "os.open: dir_fd is not supported");
        return IoFd.open(path, IoUtil.asLong(fl), IoUtil.asLong(mode));
    }

    private static Object read(long fd, Object n) {
        long len = IoUtil.asLong(n);
        if (len < 0) throw PosixModule.osError("OSError", 22, "Invalid argument", null);
        return new PyBytes(IoFd.read(fd, (int) Math.min(len, 1 << 30)));
    }

    private static Object write(long fd, Object data) {
        byte[] b = IoUtil.bytesLike(data);
        return (long) IoFd.write(fd, b, 0, b.length);
    }

    private static Object fstat(long fd) {
        IoFd.Fd f = IoFd.get(fd);
        if (f.std >= 0) return ImportSupport.WINDOWS ? WindowsNtModule.fstatStd(f.std) : PosixModule.stat("/proc/self/fd/" + f.std, true);
        if (f.nativeIo) return WindowsNtModule.fstatHandle(f.handle);
        Object st = PosixModule.stat(f.path, true);
        if (f.ch != null) PosixModule.setSize(st, IoFd.size(fd));
        return st;
    }

    // ---- paths -----------------------------------------------------------------------------------

    private static Object mkdir(String path) {
        IoUtil.syncAll();
        try { Files.createDirectory(PosixModule.resolve(path)); return null; } catch (IOException e) { throw PosixModule.osError(e, path); }
    }

    private static Object delete(String path, boolean dir) {
        IoUtil.syncAll();
        Path p = PosixModule.resolve(path);
        try {
            boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
            if (!dir && isDir) throw PosixModule.osError("IsADirectoryError", 21, "Is a directory", path);
            if (dir && !isDir && Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw PosixModule.osError("NotADirectoryError", 20, "Not a directory", path);
            Files.delete(p);
            return null;
        } catch (IOException e) { throw PosixModule.osError(e, path); }
    }

    private static Object move(List<Object> a, boolean replace) {
        IoUtil.syncAll();
        String from = PosixModule.pathString(a.get(0)), to = PosixModule.pathString(a.get(1));
        try {
            if (replace) Files.move(PosixModule.resolve(from), PosixModule.resolve(to), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else Files.move(PosixModule.resolve(from), PosixModule.resolve(to));
            return null;
        } catch (IOException e) { throw PosixModule.osError(e, from, to); }
    }

    private static Object access(String path, int mode) {
        IoUtil.syncAll();
        Path p = PosixModule.resolve(path);
        if (!Files.exists(p)) return false;
        return ((mode & 4) == 0 || Files.isReadable(p)) && ((mode & 2) == 0 || Files.isWritable(p)) && ((mode & 1) == 0 || Files.isExecutable(p));
    }

    static Object fsencode(Object p) {
        Object f = PosixModule.fspath(p);
        return f instanceof String str ? new PyBytes(Codecs.encode(str, "utf-8", "surrogateescape")) : f;
    }

    static Object fsdecode(Object p) {
        Object f = PosixModule.fspath(p);
        return f instanceof PyBytes b ? Codecs.decode(b.data, "utf-8", "surrogateescape") : f;
    }

    private static Object link(List<Object> a, boolean sym) {
        String src = PosixModule.pathString(a.get(0)), dst = PosixModule.pathString(a.get(1));
        try {
            if (sym) Files.createSymbolicLink(PosixModule.resolve(dst), java.nio.file.Path.of(src));
            else Files.createLink(PosixModule.resolve(dst), PosixModule.resolve(src));
            return null;
        } catch (IOException e) { throw PosixModule.osError(e, src); }
    }

    private static Object readlink(Object p) {
        String path = PosixModule.pathString(p);
        try {
            String t = Files.readSymbolicLink(PosixModule.resolve(path)).toString();
            return p instanceof PyBytes ? new PyBytes(t.getBytes(StandardCharsets.UTF_8)) : t;
        } catch (java.nio.file.NotLinkException e) { throw PosixModule.osError("OSError", 22, "Invalid argument", path);
        } catch (IOException e) { throw PosixModule.osError(e, path); }
    }

    private static Object chmod(String path, long mode) {
        IoUtil.syncAll();
        try { Files.setPosixFilePermissions(PosixModule.resolve(path), IoFd.permissions(mode)); return null; }
        catch (IOException e) { throw PosixModule.osError(e, path); }
    }

    /**
     * fchmod (Windows and {@code PYJVM_POSIX_FDS=nio} only; kernel mode registers its own, {@link PosixOsFds}).
     * IoFd descriptors are FileChannels, not OS descriptors, so only the process streams (0-2, real descriptors)
     * go to libc fchmod; the others change the mode of the path the descriptor was opened with, but only if that path
     * still names the same file (BasicFileAttributes.fileKey, dev+inode, recorded at open). Known divergence from
     * fchmod: if the file was renamed or unlinked, or the name now belongs to another file, nothing is changed and
     * OSError(ENOENT) (path gone) or OSError(ESTALE) (different file, or no identity available) is raised, where a real
     * fchmod would still act on the open file.
     */
    private static Object fchmod(List<Object> a, Map<Object,Object> k) {
        checkFchmodArgs(a, k);
        long fd = fd(a.get(0)), mode = IoUtil.asLong(a.get(1));
        IoFd.Fd f = IoFd.get(fd);
        if (f.std >= 0) { PosixNative.fchmod((int) fd, (int) mode); return null; }
        java.nio.file.Path p = PosixModule.resolve(f.path);
        if (!java.nio.file.Files.exists(p)) throw PosixModule.osError("FileNotFoundError", 2, PosixModule.strerror(2), null);
        Object now = IoFd.fileKey(p);
        if (f.key == null || !f.key.equals(now)) throw PosixModule.osError("OSError", 116, "Stale file handle", null);
        return chmod(f.path, mode);
    }

    static void checkFchmodArgs(List<Object> a, Map<Object,Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "fchmod() takes no keyword arguments");
        if (a.size() != 2) throw new PyException("TypeError", "fchmod expected 2 arguments, got " + a.size());
    }

    /** posix.putenv / unsetenv: nt takes str only (ValueError for '' or '=' after the first char, a 32767-character limit). */
    private static Object putenv(List<Object> a, Map<Object,Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "putenv() takes no keyword arguments");
        if (a.size() != 2) throw new PyException("TypeError", "putenv expected 2 arguments, got " + a.size());
        setEnv("putenv", a.get(0), a.get(1), 1);
        return null;
    }

    private static Object unsetenv(List<Object> a, Map<Object,Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "unsetenv() takes no keyword arguments");
        if (a.size() != 1) throw new PyException("TypeError", ImportSupport.OS_MODULE + ".unsetenv() takes exactly one argument (" + a.size() + " given)");
        setEnv("unsetenv", a.get(0), null, 0);
        return null;
    }

    private static void setEnv(String who, Object name, Object value, int pos) {
        if (ImportSupport.WINDOWS) {
            // like CPython's nt putenv/unsetenv: the str type check, then the audit event, then the value checks
            Object n0 = NativeSubclass.unwrap(name), v0 = value == null ? null : NativeSubclass.unwrap(value);
            if (!(n0 instanceof String))
                throw new PyException("TypeError", who + "() argument" + (pos + 1 > 1 ? " 1" : "") + " must be str, not " + PyRuntime.typeName(n0));
            if (value != null && !(v0 instanceof String))
                throw new PyException("TypeError", who + "() argument 2 must be str, not " + PyRuntime.typeName(v0));
            if (SysExtras.auditing()) SysExtras.audit(value == null ? "os.unsetenv" : "os.putenv", value == null ? new Object[]{name} : new Object[]{name, value});
            String n = (String) n0, v = value == null ? "" : (String) v0;
            if (n.isEmpty() || n.indexOf('=', 1) >= 0) throw new PyException("ValueError", "illegal environment variable name");
            if (n.indexOf('\0') >= 0 || v.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character");
            if (n.length() + v.length() >= 32767) throw new PyException("ValueError", "the environment variable is longer than 32767 characters");
            int err = WindowsNative.putenv(n, v);
            if (err != 0) throw PosixModule.osError("OSError", err, PosixModule.strerror(err), null);
            return;
        }
        byte[] n = envBytes(name), v = value == null ? null : envBytes(value);
        // CPython's posix putenv rejects '=' itself; unsetenv leaves it to libc (EINVAL -> OSError 22)
        if (v != null) for (byte b : n) if (b == '=') throw new PyException("ValueError", "illegal environment variable name");
        if (SysExtras.auditing()) SysExtras.audit(v == null ? "os.unsetenv" : "os.putenv", v == null ? new Object[]{new PyBytes(n)} : new Object[]{new PyBytes(n), new PyBytes(v)});
        if (v == null) PosixNative.unsetenv(n); else PosixNative.setenv(n, v);
    }

    private static String envStr(String who, Object o, int arg, int total) {
        o = NativeSubclass.unwrap(o);
        if (!(o instanceof String s))
            throw new PyException("TypeError", who + "() argument" + (total > 1 ? " " + arg : "") + " must be str, not " + PyRuntime.typeName(o));
        if (s.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character");
        return s;
    }

    private static byte[] envBytes(Object o) {
        byte[] b = ((PyBytes) fsencode(o)).data;
        for (byte x : b) if (x == 0) throw new PyException("ValueError", "embedded null byte");
        return b;
    }

    private static Object unixId(String which) {
        try { return ((Number) Files.getAttribute(PosixModule.resolve("/proc/self"), "unix:" + which)).longValue(); }
        catch (IOException | RuntimeException e) { return 0L; }
    }

    // ---- scandir ---------------------------------------------------------------------------------

    private static Object scandir(Object p) {
        IoUtil.syncAll();
        String path = p == null ? "." : PosixModule.pathString(p);
        boolean bytes = p instanceof PyBytes;
        // the entry's path joins the argument and the name as CPython does: '\' on Windows, none after a separator or drive colon
        String sep = ImportSupport.WINDOWS ? "\\" : "/";
        boolean joined = path.endsWith(sep) || ImportSupport.WINDOWS && (path.endsWith("/") || path.endsWith(":"));
        ArrayList<Object> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(PosixModule.resolve(path))) {
            s.forEach(e -> {
                String name = e.getFileName().toString();
                String full = path.equals(".") && p == null ? "." + sep + name : joined ? path + name : path + sep + name;
                entries.add(newInstance(dirEntryClass, new String[]{name, full, bytes ? "b" : "s"}));
            });
        } catch (IOException e) {
            throw PosixModule.osError(e, path);
        }
        return newInstance(scandirClass, entries.iterator());
    }

    @SuppressWarnings("unchecked")
    public static Object sdNext(Object self) {
        java.util.Iterator<Object> it = state(self, java.util.Iterator.class, "posix.ScandirIterator");
        if (it.hasNext()) return it.next();
        throw new PyException("StopIteration", "");
    }

    public static Object sdClose(Object self) { setState(self, java.util.Collections.emptyIterator()); return null; }
    public static Object sdExit(Object self, Object args) { return sdClose(self); }

    private static String[] de(Object self) { return state(self, String[].class, "posix.DirEntry"); }

    private static Object fromName(String[] s, String value) { return s[2].equals("b") ? new PyBytes(value.getBytes(StandardCharsets.UTF_8)) : value; }

    public static Object deIsDir(Object self, Object follow) {
        return Files.isDirectory(PosixModule.resolve(de(self)[1]), PyRuntime.truth(follow) ? new LinkOption[0] : new LinkOption[]{LinkOption.NOFOLLOW_LINKS});
    }

    public static Object deIsFile(Object self, Object follow) {
        return Files.isRegularFile(PosixModule.resolve(de(self)[1]), PyRuntime.truth(follow) ? new LinkOption[0] : new LinkOption[]{LinkOption.NOFOLLOW_LINKS});
    }

    public static Object deIsSymlink(Object self) { return Files.isSymbolicLink(PosixModule.resolve(de(self)[1])); }
    public static Object deStat(Object self, Object follow) { return PosixModule.stat(de(self)[1], PyRuntime.truth(follow)); }
    public static Object deInode(Object self) {
        Object st = PosixModule.stat(de(self)[1], false);
        return ((PyInstance) st).fields.get("st_ino");
    }
    public static Object deName(Object self) { String[] s = de(self); return fromName(s, s[0]); }
    public static Object dePathProp(Object self) { String[] s = de(self); return fromName(s, s[1]); }
    public static Object dePath(Object self) { String[] s = de(self); return fromName(s, s[1]); }
    public static Object deRepr(Object self) { return "<DirEntry " + PyRuntime.pyRepr(fromName(de(self), de(self)[0])) + ">"; }
    public static Object deSelf(Object self) { return self; }
    public static Object deExit(Object self, Object args) { return null; }
}
