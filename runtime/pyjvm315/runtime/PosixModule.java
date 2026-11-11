// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code posix}: the file-system primitives importlib's path finder needs ({@code stat}, {@code listdir},
 * {@code getcwd}, {@code fspath}, {@code environ}), backed by java.nio. Everything else of CPython's posix module
 * is absent rather than stubbed; importing {@code os} therefore fails on the first missing name until the os
 * layer (Phase 4) is written.
 */
public final class PosixModule {
    private PosixModule() {}

    private static PyClass statResult;

    static synchronized PyModule create() {
        PyModule m = new PyModule("posix", "pyjvm315.runtime.PosixModule");
        statResult = define("os", "stat_result", null);
        method(statResult, PosixModule.class, "__getitem__", "srGetitem", "self,index");
        method(statResult, PosixModule.class, "__len__", "srLen", "self");
        method(statResult, PosixModule.class, "__iter__", "srIter", "self");
        method(statResult, PosixModule.class, "__repr__", "srRepr", "self");
        method(statResult, PosixModule.class, "__eq__", "srEq", "self,other");
        method(statResult, PosixModule.class, "__hash__", "srHash", "self");
        finish(statResult);
        m.attrs.put("stat_result", statResult);
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        fn(m, "getcwd", (a, k) -> cwd());
        fn(m, "fspath", (a, k) -> fspath(a.get(0)));
        fn(m, "stat", (a, k) -> stat(path(a, "stat"), true));
        fn(m, "lstat", (a, k) -> stat(path(a, "lstat"), false));
        fn(m, "listdir", (a, k) -> listdir(a.isEmpty() ? null : a.get(0)));
        fn(m, "getpid", (a, k) -> ProcessHandle.current().pid());
        fn(m, "kill", (a, k) -> { SignalModule.kill(IoUtil.asLong(a.get(0)), IoUtil.asLong(a.get(1))); return null; });
        fn(m, "strerror", (a, k) -> strerror(((Number) a.get(0)).intValue()));
        PyDict env = new PyDict();
        for (Map.Entry<String,String> e : System.getenv().entrySet())
            env.put(new PyBytes(e.getKey().getBytes(StandardCharsets.UTF_8)), new PyBytes(e.getValue().getBytes(StandardCharsets.UTF_8)));
        m.attrs.put("environ", env);
        PosixFiles.install(m);
        return m;
    }

    /** The working directory the process emulates: the JVM cannot chdir, so relative paths resolve against this. */
    private static volatile Path cwd;

    static String cwd() { return (cwd != null ? cwd : Path.of("").toAbsolutePath()).toString(); }

    static Path resolve(String p) {
        Path q = Path.of(p);
        return q.isAbsolute() || cwd == null ? q : cwd.resolve(q).normalize();
    }

    static void chdir(Object p) {
        String path = pathString(p);
        Path target = resolve(path).toAbsolutePath().normalize();
        if (!Files.exists(target)) throw osError("FileNotFoundError", 2, strerror(2), path);
        if (!Files.isDirectory(target)) throw osError("NotADirectoryError", 20, strerror(20), path);
        cwd = target;
    }

    static void setSize(Object st, long size) {
        PyInstance r = (PyInstance) st;
        r.fields.put("st_size", size);
        PyTuple t = (PyTuple) r.slotValues.get(STATE);
        t.items.set(6, size);
    }

    static Object utime(java.util.List<Object> a, Map<Object,Object> k) {
        IoUtil.syncAll();
        Object target = a.get(0);
        String path = pathString(target);
        Object times = a.size() > 1 ? a.get(1) : k.get("times");
        Object ns = k.get("ns");
        long atime, mtime;     // nanoseconds
        if (times != null && ns != null) throw new PyException("ValueError", "utime: you may specify either 'times' or 'ns' but not both");
        if (times != null) {
            if (!(times instanceof PyTuple t) || t.items.size() != 2) throw new PyException("TypeError", "utime: 'times' must be either a tuple of two ints or None");
            atime = secondsToNs(t.items.get(0)); mtime = secondsToNs(t.items.get(1));
        } else if (ns != null) {
            if (!(ns instanceof PyTuple t) || t.items.size() != 2) throw new PyException("TypeError", "utime: 'ns' must be a tuple of two ints");
            atime = IoUtil.asLong(t.items.get(0)); mtime = IoUtil.asLong(t.items.get(1));
        } else { atime = mtime = System.currentTimeMillis() * 1_000_000L; }
        try {
            java.nio.file.attribute.BasicFileAttributeView v = Files.getFileAttributeView(resolve(path), java.nio.file.attribute.BasicFileAttributeView.class);
            v.setTimes(java.nio.file.attribute.FileTime.from(mtime, java.util.concurrent.TimeUnit.NANOSECONDS),
                       java.nio.file.attribute.FileTime.from(atime, java.util.concurrent.TimeUnit.NANOSECONDS), null);
            return null;
        } catch (IOException e) { throw osError(e, path); }
    }

    private static long secondsToNs(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof Double d) return (long) (d * 1e9);
        return IoUtil.asLong(o) * 1_000_000_000L;
    }

    // ---- os.stat_result: a struct sequence of the first ten fields plus named extras ------------------

    private static final String[] SR_NAMES = {"st_mode", "st_ino", "st_dev", "st_nlink", "st_uid", "st_gid", "st_size", "st_atime", "st_mtime", "st_ctime"};

    private static PyTuple srItems(Object self) { return state(self, PyTuple.class, "stat_result"); }
    public static Object srGetitem(Object self, Object index) { return PyRuntime.getitem(srItems(self), index); }
    public static Object srLen(Object self) { return (long) srItems(self).items.size(); }
    public static Object srIter(Object self) { return PyRuntime.iter(srItems(self)); }
    public static Object srEq(Object self, Object other) {
        Object o = other instanceof PyInstance i && i.slotValues.get(STATE) instanceof PyTuple t ? t : other;
        return PyRuntime.eq(srItems(self), o);
    }
    public static Object srHash(Object self) { return PyRuntime.hash(srItems(self)); }
    public static Object srRepr(Object self) {
        PyTuple t = srItems(self);
        StringBuilder sb = new StringBuilder("os.stat_result(");
        for (int i = 0; i < SR_NAMES.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(SR_NAMES[i]).append('=').append(PyRuntime.pyRepr(t.items.get(i)));
        }
        return sb.append(')').toString();
    }


    /** os.fspath for str and bytes-free paths; objects with __fspath__ are asked. */
    static Object fspath(Object p) {
        if (p instanceof String || p instanceof PyBytes) return p;
        if (p instanceof PyInstance i && i.cls.lookupMethod("__fspath__") != null) {
            Object r = PyRuntime.callMethod(p, "__fspath__", new Object[0]);
            if (r instanceof String || r instanceof PyBytes) return r;
            throw new PyException("TypeError", "expected " + PyRuntime.typeName(p) + ".__fspath__() to return str or bytes, not " + PyRuntime.typeName(r));
        }
        throw new PyException("TypeError", "expected str, bytes or os.PathLike object, not " + PyRuntime.typeName(p));
    }

    private static String path(java.util.List<Object> a, String who) {
        if (a.isEmpty()) throw new PyException("TypeError", who + "() missing required argument 'path' (pos 1)");
        return pathString(a.get(0));
    }

    static String pathString(Object p) {
        Object f = fspath(p);
        return f instanceof PyBytes b ? new String(b.data, StandardCharsets.UTF_8) : (String) f;
    }

    static String strerror(int errno) {
        return switch (errno) {
            case 1 -> "Operation not permitted";
            case 2 -> "No such file or directory";
            case 13 -> "Permission denied";
            case 17 -> "File exists";
            case 20 -> "Not a directory";
            case 21 -> "Is a directory";
            case 22 -> "Invalid argument";
            case 39 -> "Directory not empty";
            default -> "Unknown error " + errno;
        };
    }

    static PyException osError(IOException e, String path) {
        int errno; String type;
        if (e instanceof NoSuchFileException) { errno = 2; type = "FileNotFoundError"; }
        else if (e instanceof FileAlreadyExistsException) { errno = 17; type = "FileExistsError"; }
        else if (e instanceof AccessDeniedException) { errno = 13; type = "PermissionError"; }
        else if (e instanceof NotDirectoryException) { errno = 20; type = "NotADirectoryError"; }
        else if (e instanceof DirectoryNotEmptyException) { errno = 39; type = "OSError"; }
        else { errno = 5; type = "OSError"; }
        return osError(type, errno, errno == 5 ? String.valueOf(e.getMessage()) : strerror(errno), path);
    }

    /** An error that names two paths (rename, link, symlink, replace). */
    static PyException osError(IOException e, String path, String path2) {
        PyException base = osError(e, path);
        PyExcArgs a = (PyExcArgs) base.value;
        java.util.LinkedHashMap<String,Object> attrs = new java.util.LinkedHashMap<>(a.attrs);
        attrs.put("filename2", path2);
        PyTuple t = a.args;
        return new PyException(base.typeName, new PyExcArgs(tuple(t.items.get(0), t.items.get(1), path, null, path2), null, attrs));
    }

    static PyException osError(String type, int errno, String strerror, String path) {
        java.util.LinkedHashMap<String,Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("errno", (long) errno); attrs.put("strerror", strerror); attrs.put("filename", path);
        PyTuple args = path == null ? tuple((long) errno, strerror) : tuple((long) errno, strerror, path);
        return new PyException(type, new PyExcArgs(args, null, attrs));
    }

    static Object stat(String path, boolean follow) {
        IoUtil.syncAll();
        Path p = resolve(path);
        try {
            LinkOption[] opts = follow ? new LinkOption[0] : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
            Map<String,Object> a;
            try { a = Files.readAttributes(p, "unix:*", opts); }
            catch (UnsupportedOperationException | IllegalArgumentException e) { a = null; }
            PyInstance r = new PyInstance(statResult);
            long mode, ino, dev, nlink, uid, gid, size;
            long[] at = new long[3];
            if (a != null) {
                mode = ((Number) a.get("mode")).longValue(); ino = ((Number) a.get("ino")).longValue();
                dev = ((Number) a.get("dev")).longValue(); nlink = ((Number) a.get("nlink")).longValue();
                uid = ((Number) a.get("uid")).longValue(); gid = ((Number) a.get("gid")).longValue();
                size = ((Number) a.get("size")).longValue();
                at[0] = ns((java.nio.file.attribute.FileTime) a.get("lastAccessTime"));
                at[1] = ns((java.nio.file.attribute.FileTime) a.get("lastModifiedTime"));
                at[2] = ns((java.nio.file.attribute.FileTime) a.get("ctime"));
                put(r, "st_rdev", a.get("rdev") instanceof Number rd ? rd.longValue() : 0L);
            } else {
                java.nio.file.attribute.BasicFileAttributes b = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, opts);
                mode = (b.isDirectory() ? 0040000L : b.isSymbolicLink() ? 0120000L : 0100000L) | 0644;
                ino = 0; dev = 0; nlink = 1; uid = 0; gid = 0; size = b.size();
                at[0] = ns(b.lastAccessTime()); at[1] = ns(b.lastModifiedTime()); at[2] = ns(b.creationTime());
                put(r, "st_rdev", 0L);
            }
            PyTuple items = tuple(mode, ino, dev, nlink, uid, gid, size, Math.floorDiv(at[0], 1_000_000_000L), Math.floorDiv(at[1], 1_000_000_000L), Math.floorDiv(at[2], 1_000_000_000L));
            r.slotValues.put(STATE, items);
            String[] names = {"st_mode", "st_ino", "st_dev", "st_nlink", "st_uid", "st_gid", "st_size"};
            for (int i = 0; i < names.length; i++) put(r, names[i], items.items.get(i));
            String[] tn = {"st_atime", "st_mtime", "st_ctime"};
            for (int i = 0; i < 3; i++) { put(r, tn[i], at[i] / 1e9); put(r, tn[i] + "_ns", at[i]); }
            put(r, "st_blksize", 4096L); put(r, "st_blocks", (size + 511) / 512);
            return r;
        } catch (IOException e) {
            throw osError(e, path);
        }
    }

    private static long ns(java.nio.file.attribute.FileTime t) { return t.to(java.util.concurrent.TimeUnit.NANOSECONDS); }

    private static void put(PyInstance r, String name, Object value) { r.fields.put(name, value); }

    static Object listdir(Object p) {
        IoUtil.syncAll();
        String path = p == null ? "." : pathString(p);
        boolean bytes = p instanceof PyBytes;
        try (Stream<Path> s = Files.list(resolve(path))) {
            ArrayList<Object> out = new ArrayList<>();
            s.forEach(e -> {
                String n = e.getFileName().toString();
                out.add(bytes ? new PyBytes(n.getBytes(StandardCharsets.UTF_8)) : n);
            });
            return out;
        } catch (IOException e) {
            throw osError(e, path);
        }
    }
}
