// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code nt}: the Windows counterpart of {@code posix}, which importlib and os.py use on Windows. It shares posix's
 * java.nio functions ({@link PosixModule#build}) and changes what differs on Windows: the {@code O_*} values (mapped
 * onto the flags {@link IoFd} understands), a str-keyed {@code environ}, chmod as the read-only attribute, and stat
 * from Win32 file identity via FFM ({@link WindowsNative}). The accelerators ntpath uses when present
 * ({@code _path_normpath}, {@code _path_splitroot_ex}, {@code _path_is*}, {@code _getvolumepathname}) are absent, so
 * ntpath uses its pure-Python versions; process creation ({@code spawnv}, {@code execv}, {@code system},
 * {@code startfile}), {@code pipe}/{@code dup2} and DLL directories are absent as they are in posix.
 */
final class WindowsNtModule {
    private WindowsNtModule() {}

    /** What posix's shared part has that CPython's nt does not (os.py defines SEEK_* and fsencode/fsdecode itself). */
    private static final String[] NOT_IN_NT = {"getuid", "geteuid", "getgid", "getegid", "fdatasync", "wait", "WIFEXITED",
        "WEXITSTATUS", "WIFSIGNALED", "WTERMSIG", "WIFSTOPPED", "WSTOPSIG", "WIFCONTINUED", "WNOHANG", "O_NOCTTY", "O_NONBLOCK",
        "O_DIRECTORY", "O_NOFOLLOW", "O_CLOEXEC", "O_SYNC", "O_DSYNC", "O_NDELAY", "SEEK_SET", "SEEK_CUR", "SEEK_END",
        "fsencode", "fsdecode"};

    private static final long O_APPEND = 8, O_TEMPORARY = 64, O_CREAT = 256, O_TRUNC = 512, O_EXCL = 1024;
    private static final long S_IWRITE = 0200;

    static synchronized PyModule create() {
        PyModule m = PosixModule.build("nt", WindowsNtModule.class.getName());
        for (String n : NOT_IN_NT) m.attrs.remove(n);
        for (Object[] c : new Object[][]{{"O_RDONLY", 0L}, {"O_WRONLY", 1L}, {"O_RDWR", 2L}, {"O_APPEND", O_APPEND}, {"O_RANDOM", 16L},
                {"O_SEQUENTIAL", 32L}, {"O_TEMPORARY", O_TEMPORARY}, {"O_NOINHERIT", 128L}, {"O_CREAT", O_CREAT}, {"O_TRUNC", O_TRUNC},
                {"O_EXCL", O_EXCL}, {"O_SHORT_LIVED", 4096L}, {"O_TEXT", 16384L}, {"O_BINARY", 32768L}})
            m.attrs.put((String) c[0], c[1]);
        m.attrs.put("environ", environ());
        fn(m, "_create_environ", (a, k) -> environ());
        fn(m, "open", (a, k) -> open(a, k));
        fn(m, "chmod", (a, k) -> chmod(PosixModule.pathString(a.get(0)), IoUtil.asLong(a.get(1)), PyRuntime.truth(kw(k, "follow_symlinks", true))));
        fn(m, "lchmod", (a, k) -> chmod(PosixModule.pathString(a.get(0)), IoUtil.asLong(a.get(1)), false));
        fn(m, "fchmod", (a, k) -> chmod(IoFd.get(PosixFiles.fd(a.get(0))).path, IoUtil.asLong(a.get(1)), true));
        fn(m, "waitstatus_to_exitcode", (a, k) -> IoUtil.asLong(a.get(0)) >> 8);   // Windows waitpid reports exit code << 8
        fn(m, "get_terminal_size", (a, k) -> terminalSize(m, a.isEmpty() ? 1L : PosixFiles.fd(a.get(0))));
        fn(m, "_getfullpathname", (a, k) -> sameKind(a.get(0), WindowsNative.fullPath(absolute(PosixModule.pathString(a.get(0))), PosixModule.pathString(a.get(0)))));
        fn(m, "_getfinalpathname", (a, k) -> sameKind(a.get(0), WindowsNative.finalPath(absolute(PosixModule.pathString(a.get(0))), PosixModule.pathString(a.get(0)))));
        fn(m, "_findfirstfile", (a, k) -> sameKind(a.get(0), WindowsNative.findFirstFile(absolute(PosixModule.pathString(a.get(0))), PosixModule.pathString(a.get(0)))));
        fn(m, "_getdiskusage", (a, k) -> diskUsage(PosixModule.pathString(a.get(0))));
        fn(m, "_path_splitroot", (a, k) -> WindowsNative.splitRoot(str(a.isEmpty() ? k.get("path") : a.get(0))));
        AuditEvents.install(m);
        return m;
    }

    /** nt.environ: str keys and values as the process received them (entries for per-drive directories, "=C:", excluded). */
    private static PyDict environ() {
        PyDict env = new PyDict();
        for (Map.Entry<String,String> e : System.getenv().entrySet())
            if (!e.getKey().startsWith("=")) env.put(e.getKey(), e.getValue());
        return env;
    }

    /** Relative paths resolve against the emulated working directory (os.chdir), not the JVM's. */
    private static String absolute(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() || path.startsWith("\\\\") ? path : PosixModule.resolve(path).toAbsolutePath().toString();
    }

    private static String str(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof String s) return s;
        throw new PyException("TypeError", "_path_splitroot: path should be string, not " + PyRuntime.typeName(o));
    }

    private static Object sameKind(Object arg, String result) {
        return arg instanceof PyBytes ? new PyBytes(result.getBytes(java.nio.charset.StandardCharsets.UTF_8)) : result;
    }

    private static Object open(List<Object> a, Map<Object,Object> k) {
        if (a.isEmpty() && !k.containsKey("path")) throw new PyException("TypeError", "open() missing required argument 'path' (pos 1)");
        String path = PosixModule.pathString(a.isEmpty() ? k.get("path") : a.get(0));
        Object fl = a.size() > 1 ? a.get(1) : k.get("flags");
        if (fl == null) throw new PyException("TypeError", "open() missing required argument 'flags' (pos 2)");
        long mode = IoUtil.asLong(a.size() > 2 ? a.get(2) : k.containsKey("mode") ? k.get("mode") : (Object) 0777L);
        if (k.get("dir_fd") != null) throw new PyException("NotImplementedError", "os.open: dir_fd is not supported");
        long w = IoUtil.asLong(fl);
        // the access mode and O_APPEND/O_CREAT/O_TRUNC/O_EXCL in IoFd's (Linux) encoding; O_TEMPORARY deletes on close;
        // O_NOINHERIT/O_RANDOM/O_SEQUENTIAL/O_SHORT_LIVED are hints; O_TEXT is not translated (binary I/O)
        long flags = (w & 3) | ((w & O_APPEND) != 0 ? 02000 : 0) | ((w & O_CREAT) != 0 ? 0100 : 0) | ((w & O_TRUNC) != 0 ? 01000 : 0)
            | ((w & O_EXCL) != 0 ? 0200 : 0) | ((w & O_TEMPORARY) != 0 ? IoFd.DELETE_ON_CLOSE : 0);
        Path p = PosixModule.resolve(path);
        boolean created = (w & O_CREAT) != 0 && !Files.exists(p);
        long fd = IoFd.open(path, flags, mode);
        if (created && (mode & S_IWRITE) == 0) setReadOnly(p, true, path, true);   // _wopen makes such a file read-only
        return fd;
    }

    private static Object chmod(String path, long mode, boolean follow) {
        IoUtil.syncAll();
        setReadOnly(PosixModule.resolve(path), (mode & S_IWRITE) == 0, path, follow);
        return null;
    }

    private static void setReadOnly(Path p, boolean readOnly, String shown, boolean follow) {
        try { Files.setAttribute(p, "dos:readonly", readOnly, follow ? new LinkOption[0] : new LinkOption[]{LinkOption.NOFOLLOW_LINKS}); }
        catch (IOException e) { throw PosixModule.osError(e, shown); }
    }

    private static Object diskUsage(String path) {
        try {
            java.nio.file.FileStore s = Files.getFileStore(PosixModule.resolve(path));
            return tuple(s.getTotalSpace(), s.getUsableSpace());
        } catch (IOException e) { throw PosixModule.osError(e, path); }
    }

    private static Object terminalSize(PyModule m, long fd) {
        if (fd < 0 || fd > 2) throw new PyException("ValueError", "bad file descriptor");
        long[] size = WindowsNative.consoleSize(WindowsNative.stdHandle((int) fd));
        return PyRuntime.instantiate((PyClass) m.attrs.get("terminal_size"), new Object[]{tuple(size[0], size[1])});
    }

    /** The Universal CRT's strerror texts, as CPython 3.13/3.15 report them on Windows (os.strerror). */
    static String strerror(int errno) {
        return switch (errno) {
            case 0 -> "No error";
            case 1 -> "Operation not permitted";
            case 2 -> "No such file or directory";
            case 3 -> "No such process";
            case 4 -> "Interrupted function call";
            case 5 -> "Input/output error";
            case 6 -> "No such device or address";
            case 7 -> "Arg list too long";
            case 8 -> "Exec format error";
            case 9 -> "Bad file descriptor";
            case 10 -> "No child processes";
            case 11 -> "Resource temporarily unavailable";
            case 12 -> "Not enough space";
            case 13 -> "Permission denied";
            case 14 -> "Bad address";
            case 16 -> "Resource device";
            case 17 -> "File exists";
            case 18 -> "Improper link";
            case 19 -> "No such device";
            case 20 -> "Not a directory";
            case 21 -> "Is a directory";
            case 22 -> "Invalid argument";
            case 23 -> "Too many open files in system";
            case 24 -> "Too many open files";
            case 25 -> "Inappropriate I/O control operation";
            case 27 -> "File too large";
            case 28 -> "No space left on device";
            case 29 -> "Invalid seek";
            case 30 -> "Read-only file system";
            case 31 -> "Too many links";
            case 32 -> "Broken pipe";
            case 33 -> "Domain error";
            case 34 -> "Result too large";
            case 36 -> "Resource deadlock avoided";
            case 38 -> "Filename too long";
            case 39 -> "No locks available";
            case 40 -> "Function not implemented";
            case 41 -> "Directory not empty";
            case 42 -> "Illegal byte sequence";
            case 100 -> "address in use";
            case 101 -> "address not available";
            case 102 -> "address family not supported";
            case 103 -> "connection already in progress";
            case 104 -> "bad message";
            case 105 -> "operation canceled";
            case 106 -> "connection aborted";
            case 107 -> "connection refused";
            case 108 -> "connection reset";
            case 109 -> "destination address required";
            case 110 -> "host unreachable";
            case 111 -> "identifier removed";
            case 112 -> "operation in progress";
            case 113 -> "already connected";
            case 114 -> "too many symbolic link levels";
            case 115 -> "message size";
            case 116 -> "network down";
            case 117 -> "network reset";
            case 118 -> "network unreachable";
            case 119 -> "no buffer space";
            case 120 -> "no message available";
            case 121 -> "no link";
            case 122 -> "no message";
            case 123 -> "no protocol option";
            case 124 -> "no stream resources";
            case 125 -> "not a stream";
            case 126 -> "not connected";
            case 127 -> "state not recoverable";
            case 128 -> "not a socket";
            case 129 -> "not supported";
            case 130 -> "operation not supported";
            case 132 -> "value too large";
            case 133 -> "owner dead";
            case 134 -> "protocol error";
            case 135 -> "protocol not supported";
            case 136 -> "wrong protocol type";
            case 137 -> "stream timeout";
            case 138 -> "timed out";
            case 139 -> "text file busy";
            case 140 -> "operation would block";
            default -> "Unknown error";
        };
    }

    // ---- stat -------------------------------------------------------------------------------------

    /** os.stat / os.lstat as CPython 3.12+ reports them on Windows (Modules/posixmodule.c, Python/fileutils.c). */
    static Object stat(String path, boolean follow) {
        WindowsNative.FileInfo fi = WindowsNative.fileInfo(absolute(path), follow, path);
        long mode = modeOf(fi, follow);
        if ((fi.attributes & WindowsNative.FILE_ATTRIBUTE_DIRECTORY) == 0) {
            int dot = path.lastIndexOf('.');
            String ext = dot < 0 ? "" : path.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.equals(".exe") || ext.equals(".bat") || ext.equals(".cmd") || ext.equals(".com")) mode |= 0111;
        }
        return result(fi, mode);
    }

    /** os.fstat of a standard stream: a disk file is described in full, a console or pipe by its type alone. */
    static Object fstatStd(int fd) {
        long h = WindowsNative.stdHandle(fd);
        int type = WindowsNative.fileType(h);
        if (type == WindowsNative.FILE_TYPE_DISK) {
            WindowsNative.FileInfo fi = WindowsNative.handleInfo(h);
            return result(fi, modeOf(fi, true));
        }
        WindowsNative.FileInfo none = new WindowsNative.FileInfo();
        long mode = type == WindowsNative.FILE_TYPE_CHAR ? 0020000 : type == WindowsNative.FILE_TYPE_PIPE ? 0010000 : 0;
        return result(none, mode);
    }

    private static long modeOf(WindowsNative.FileInfo fi, boolean follow) {
        int attr = fi.attributes;
        long mode = (attr & WindowsNative.FILE_ATTRIBUTE_DIRECTORY) != 0 ? 0040000 | 0111 : 0100000;
        mode |= (attr & WindowsNative.FILE_ATTRIBUTE_READONLY) != 0 ? 0444 : 0666;
        if (!follow && fi.reparseTag == WindowsNative.IO_REPARSE_TAG_SYMLINK) mode = (mode & ~0170000) | 0120000;
        return mode;
    }

    private static Object result(WindowsNative.FileInfo fi, long mode) {
        PyInstance r = PosixModule.newStatResult();
        long[] at = {fi.accessed, fi.written, fi.changed};
        PosixModule.fill(r, mode, 0, 0, fi.links, 0, 0, fi.size, at);
        Object ino = unsigned128(fi.indexHigh, fi.index), dev = unsigned64(fi.volume);
        PyTuple items = (PyTuple) r.slotValues.get(STATE);
        items.items.set(1, ino); items.items.set(2, dev);
        PosixModule.put(r, "st_ino", ino); PosixModule.put(r, "st_dev", dev);
        PosixModule.put(r, "st_birthtime", fi.created / 1e9); PosixModule.put(r, "st_birthtime_ns", fi.created);
        PosixModule.put(r, "st_file_attributes", Integer.toUnsignedLong(fi.attributes));
        PosixModule.put(r, "st_reparse_tag", Integer.toUnsignedLong(fi.reparseTag));
        return r;
    }

    private static Object unsigned64(long v) { return v >= 0 ? (Object) v : new BigInteger(Long.toUnsignedString(v)); }

    private static Object unsigned128(long high, long low) {
        if (high == 0) return unsigned64(low);
        return new BigInteger(Long.toUnsignedString(high)).shiftLeft(64).add(new BigInteger(Long.toUnsignedString(low)));
    }
}
