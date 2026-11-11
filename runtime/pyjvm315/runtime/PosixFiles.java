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
 * The part of {@code posix} beyond importlib's needs: file descriptors (backed by FileChannel), directory and file
 * manipulation, {@code scandir}, process identity and the {@code os.O_*} / {@code F_OK} constants. Process creation,
 * signals, ownership changes, symbolic-link creation and terminal control are not implemented (they are left out, so
 * os.py's {@code _exists(...)} probes report them as absent, as on a platform without them).
 */
final class PosixFiles {
    private PosixFiles() {}

    private static final TreeMap<Integer,FileChannel> FDS = new TreeMap<>();
    private static PyClass dirEntryClass;

    static void install(PyModule m) {
        m.attrs.put("F_OK", 0L); m.attrs.put("R_OK", 4L); m.attrs.put("W_OK", 2L); m.attrs.put("X_OK", 1L);
        for (Object[] c : new Object[][]{{"O_RDONLY", 0L}, {"O_WRONLY", 1L}, {"O_RDWR", 2L}, {"O_CREAT", 0100L}, {"O_EXCL", 0200L},
                {"O_NOCTTY", 0400L}, {"O_TRUNC", 01000L}, {"O_APPEND", 02000L}, {"O_NONBLOCK", 04000L}, {"O_DIRECTORY", 0200000L},
                {"O_NOFOLLOW", 0400000L}, {"O_CLOEXEC", 02000000L}, {"O_SYNC", 04010000L}, {"EX_OK", 0L}, {"WNOHANG", 1L}})
            m.attrs.put((String) c[0], c[1]);
        m.attrs.put("_have_functions", new ArrayList<Object>());
        fn(m, "_exit", (a, k) -> { System.out.flush(); System.err.flush(); Runtime.getRuntime().halt((int) ((Number) a.get(0)).longValue()); return null; });
        fn(m, "open", (a, k) -> open(a, k));
        fn(m, "close", (a, k) -> { close(fd(a.get(0))); return null; });
        fn(m, "read", (a, k) -> read(fd(a.get(0)), ((Number) a.get(1)).intValue()));
        fn(m, "write", (a, k) -> write(fd(a.get(0)), a.get(1)));
        fn(m, "lseek", (a, k) -> lseek(fd(a.get(0)), ((Number) a.get(1)).longValue(), ((Number) a.get(2)).intValue()));
        fn(m, "fstat", (a, k) -> fstat(fd(a.get(0))));
        fn(m, "isatty", (a, k) -> { long f = fd(a.get(0)); return f <= 2 && System.console() != null; });
        fn(m, "getcwdb", (a, k) -> new PyBytes(PosixModule.cwd().getBytes(StandardCharsets.UTF_8)));
        fn(m, "chdir", (a, k) -> { throw new PyException("NotImplementedError", "os.chdir: the JVM cannot change its working directory"); });
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
        fn(m, "putenv", (a, k) -> null);
        fn(m, "unsetenv", (a, k) -> null);
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
    }

    // ---- file descriptors ------------------------------------------------------------------------

    private static long fd(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Boolean b) return b ? 1 : 0;
        throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer");
    }

    private static PyException badFd() { return PosixModule.osError("OSError", 9, "Bad file descriptor", null); }

    private static synchronized FileChannel channel(long fd) {
        FileChannel c = FDS.get((int) fd);
        if (c == null) throw badFd();
        return c;
    }

    private static Object open(List<Object> a, Map<Object,Object> k) {
        String path = PosixModule.pathString(a.get(0));
        long flags = ((Number) a.get(1)).longValue();
        if (a.size() > 2 || k.containsKey("dir_fd")) {
            if (k.get("dir_fd") != null) throw new PyException("NotImplementedError", "os.open: dir_fd is not supported");
        }
        Set<StandardOpenOption> opts = EnumSet.noneOf(StandardOpenOption.class);
        long access = flags & 3;
        if (access == 0 || access == 2) opts.add(StandardOpenOption.READ);
        if (access == 1 || access == 2) opts.add(StandardOpenOption.WRITE);
        if ((flags & 0100) != 0) opts.add((flags & 0200) != 0 ? StandardOpenOption.CREATE_NEW : StandardOpenOption.CREATE);
        if ((flags & 01000) != 0) opts.add(StandardOpenOption.TRUNCATE_EXISTING);
        if ((flags & 02000) != 0) opts.add(StandardOpenOption.APPEND);
        Path p = Path.of(path);
        try {
            if (Files.isDirectory(p) && access == 0) {
                // a directory can be opened read-only (os.walk uses it with scandir fds); reads fail with EISDIR
                return register(null, path);
            }
            return register(FileChannel.open(p, opts), path);
        } catch (IOException e) {
            throw PosixModule.osError(e, path);
        }
    }

    private static synchronized Object register(FileChannel channel, String path) {
        int fd = 3;
        while (FDS.containsKey(fd)) fd++;
        if (channel == null) throw new PyException("NotImplementedError", "os.open of a directory is not supported");
        FDS.put(fd, channel);
        return (long) fd;
    }

    private static synchronized void close(long fd) {
        FileChannel c = FDS.remove((int) fd);
        if (c == null) {
            if (fd >= 0 && fd <= 2) return;
            throw badFd();
        }
        try { c.close(); } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    private static Object read(long fd, int n) {
        if (fd == 0) {
            try { byte[] b = System.in.readNBytes(n); return new PyBytes(b); } catch (IOException e) { throw PosixModule.osError(e, null); }
        }
        FileChannel c = channel(fd);
        ByteBuffer buf = ByteBuffer.allocate(n);
        try {
            int got = 0;
            while (got < n) { int r = c.read(buf); if (r < 0) break; got += r; if (r == 0) break; }
            byte[] out = new byte[buf.position()];
            buf.flip(); buf.get(out);
            return new PyBytes(out);
        } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    private static Object write(long fd, Object data) {
        byte[] b = data instanceof PyBytes pb ? pb.data : data instanceof PyByteArray ba ? ba.toByteArray() : null;
        if (b == null) throw new PyException("TypeError", "a bytes-like object is required, not '" + PyRuntime.typeName(data) + "'");
        if (fd == 1 || fd == 2) { (fd == 1 ? System.out : System.err).write(b, 0, b.length); return (long) b.length; }
        FileChannel c = channel(fd);
        try {
            ByteBuffer buf = ByteBuffer.wrap(b);
            while (buf.hasRemaining()) c.write(buf);
            return (long) b.length;
        } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    private static Object lseek(long fd, long pos, int how) {
        FileChannel c = channel(fd);
        try {
            long target = how == 0 ? pos : how == 1 ? c.position() + pos : c.size() + pos;
            if (target < 0) throw PosixModule.osError("OSError", 22, "Invalid argument", null);
            c.position(target);
            return target;
        } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    private static Object fstat(long fd) {
        if (fd >= 0 && fd <= 2) throw new PyException("NotImplementedError", "os.fstat of a standard stream is not supported");
        channel(fd);
        throw new PyException("NotImplementedError", "os.fstat of an open descriptor is not supported");
    }

    // ---- paths -----------------------------------------------------------------------------------

    private static Object mkdir(String path) {
        try { Files.createDirectory(Path.of(path)); return null; } catch (IOException e) { throw PosixModule.osError(e, path); }
    }

    private static Object delete(String path, boolean dir) {
        Path p = Path.of(path);
        try {
            boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
            if (!dir && isDir) throw PosixModule.osError("IsADirectoryError", 21, "Is a directory", path);
            if (dir && !isDir && Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw PosixModule.osError("NotADirectoryError", 20, "Not a directory", path);
            Files.delete(p);
            return null;
        } catch (IOException e) { throw PosixModule.osError(e, path); }
    }

    private static Object move(List<Object> a, boolean replace) {
        String from = PosixModule.pathString(a.get(0)), to = PosixModule.pathString(a.get(1));
        try {
            if (replace) Files.move(Path.of(from), Path.of(to), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else Files.move(Path.of(from), Path.of(to));
            return null;
        } catch (IOException e) { throw PosixModule.osError(e, from); }
    }

    private static Object access(String path, int mode) {
        Path p = Path.of(path);
        if (!Files.exists(p)) return false;
        return ((mode & 4) == 0 || Files.isReadable(p)) && ((mode & 2) == 0 || Files.isWritable(p)) && ((mode & 1) == 0 || Files.isExecutable(p));
    }

    private static Object unixId(String which) {
        try { return ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:" + which)).longValue(); }
        catch (IOException | RuntimeException e) { return 0L; }
    }

    // ---- scandir ---------------------------------------------------------------------------------

    private static Object scandir(Object p) {
        String path = p == null ? "." : PosixModule.pathString(p);
        boolean bytes = p instanceof PyBytes;
        ArrayList<Object> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of(path))) {
            s.forEach(e -> {
                String name = e.getFileName().toString();
                String full = path.equals(".") && p == null ? "./" + name : path.endsWith("/") ? path + name : path + "/" + name;
                entries.add(newInstance(dirEntryClass, new String[]{name, full, bytes ? "b" : "s"}));
            });
        } catch (IOException e) {
            throw PosixModule.osError(e, path);
        }
        return entries.iterator();
    }

    private static String[] de(Object self) { return state(self, String[].class, "posix.DirEntry"); }

    private static Object fromName(String[] s, String value) { return s[2].equals("b") ? new PyBytes(value.getBytes(StandardCharsets.UTF_8)) : value; }

    public static Object deIsDir(Object self, Object follow) {
        return Files.isDirectory(Path.of(de(self)[1]), PyRuntime.truth(follow) ? new LinkOption[0] : new LinkOption[]{LinkOption.NOFOLLOW_LINKS});
    }

    public static Object deIsFile(Object self, Object follow) {
        return Files.isRegularFile(Path.of(de(self)[1]), PyRuntime.truth(follow) ? new LinkOption[0] : new LinkOption[]{LinkOption.NOFOLLOW_LINKS});
    }

    public static Object deIsSymlink(Object self) { return Files.isSymbolicLink(Path.of(de(self)[1])); }
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
