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
        finish(statResult);
        m.attrs.put("stat_result", statResult);
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        fn(m, "getcwd", (a, k) -> cwd());
        fn(m, "fspath", (a, k) -> fspath(a.get(0)));
        fn(m, "stat", (a, k) -> stat(path(a, "stat"), true));
        fn(m, "lstat", (a, k) -> stat(path(a, "lstat"), false));
        fn(m, "listdir", (a, k) -> listdir(a.isEmpty() ? null : a.get(0)));
        fn(m, "getpid", (a, k) -> ProcessHandle.current().pid());
        fn(m, "strerror", (a, k) -> strerror(((Number) a.get(0)).intValue()));
        PyDict env = new PyDict();
        for (Map.Entry<String,String> e : System.getenv().entrySet())
            env.put(new PyBytes(e.getKey().getBytes(StandardCharsets.UTF_8)), new PyBytes(e.getValue().getBytes(StandardCharsets.UTF_8)));
        m.attrs.put("environ", env);
        return m;
    }

    static String cwd() { return Path.of("").toAbsolutePath().toString(); }

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

    static PyException osError(String type, int errno, String strerror, String path) {
        java.util.LinkedHashMap<String,Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("errno", (long) errno); attrs.put("strerror", strerror); attrs.put("filename", path);
        PyTuple args = path == null ? tuple((long) errno, strerror) : tuple((long) errno, strerror, path);
        return new PyException(type, new PyExcArgs(args, null, attrs));
    }

    static Object stat(String path, boolean follow) {
        Path p = Path.of(path);
        try {
            LinkOption[] opts = follow ? new LinkOption[0] : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
            Map<String,Object> a;
            try { a = Files.readAttributes(p, "unix:*", opts); }
            catch (UnsupportedOperationException | IllegalArgumentException e) { a = null; }
            PyInstance r = new PyInstance(statResult);
            if (a != null) {
                long mode = ((Number) a.get("mode")).longValue();
                put(r, "st_mode", mode); put(r, "st_ino", ((Number) a.get("ino")).longValue());
                put(r, "st_dev", ((Number) a.get("dev")).longValue()); put(r, "st_nlink", ((Number) a.get("nlink")).longValue());
                put(r, "st_uid", ((Number) a.get("uid")).longValue()); put(r, "st_gid", ((Number) a.get("gid")).longValue());
                put(r, "st_size", ((Number) a.get("size")).longValue());
                time(r, "st_atime", (java.nio.file.attribute.FileTime) a.get("lastAccessTime"));
                time(r, "st_mtime", (java.nio.file.attribute.FileTime) a.get("lastModifiedTime"));
                time(r, "st_ctime", (java.nio.file.attribute.FileTime) a.get("ctime"));
            } else {
                java.nio.file.attribute.BasicFileAttributes b = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, opts);
                long mode = (b.isDirectory() ? 0040000L : b.isSymbolicLink() ? 0120000L : 0100000L) | 0644;
                put(r, "st_mode", mode); put(r, "st_ino", 0L); put(r, "st_dev", 0L); put(r, "st_nlink", 1L);
                put(r, "st_uid", 0L); put(r, "st_gid", 0L); put(r, "st_size", b.size());
                time(r, "st_atime", b.lastAccessTime()); time(r, "st_mtime", b.lastModifiedTime()); time(r, "st_ctime", b.creationTime());
            }
            return r;
        } catch (IOException e) {
            throw osError(e, path);
        }
    }

    private static void put(PyInstance r, String name, Object value) { r.fields.put(name, value); }

    private static void time(PyInstance r, String name, java.nio.file.attribute.FileTime t) {
        long ns = t.to(java.util.concurrent.TimeUnit.NANOSECONDS);
        put(r, name, ns / 1e9);
        put(r, name + "_ns", ns);
    }

    static Object listdir(Object p) {
        String path = p == null ? "." : pathString(p);
        boolean bytes = p instanceof PyBytes;
        try (Stream<Path> s = Files.list(Path.of(path))) {
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
