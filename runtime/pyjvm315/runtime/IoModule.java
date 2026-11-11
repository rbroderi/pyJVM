// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * {@code _io}: only the read-only file primitives importlib needs ({@code FileIO} opened for reading and
 * {@code open_code}). BytesIO, StringIO, the buffered classes, TextIOWrapper and {@code open} are Phase 4 work;
 * they are absent, so {@code import io} fails with an ImportError naming the first missing name.
 */
public final class IoModule {
    private IoModule() {}

    private static PyClass fileIO;

    /** Whole-file read-only state: the bytes are read when first needed. */
    static final class FileState {
        final String path; final String mode; byte[] data; int pos; boolean closed;
        FileState(String path, String mode) { this.path = path; this.mode = mode; }
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_io", "pyjvm315.runtime.IoModule");
        fileIO = define("_io", "FileIO", null);
        method(fileIO, IoModule.class, "__init__", "fileInit", "self,name,mode,closefd", defaults("mode", "r", "closefd", true));
        method(fileIO, IoModule.class, "read", "fileRead", "self,size", defaults("size", -1L));
        method(fileIO, IoModule.class, "readall", "fileReadall", "self");
        method(fileIO, IoModule.class, "readable", "fileReadable", "self");
        method(fileIO, IoModule.class, "writable", "fileWritable", "self");
        method(fileIO, IoModule.class, "close", "fileClose", "self");
        method(fileIO, IoModule.class, "__enter__", "fileEnter", "self");
        methodVar(fileIO, IoModule.class, "__exit__", "fileExit", "self", "args");
        PyRuntime.classAddProperty(fileIO, "closed", IoModule.class.getName(), "fileClosed", null);
        PyRuntime.classAddProperty(fileIO, "name", IoModule.class.getName(), "fileName", null);
        PyRuntime.classAddProperty(fileIO, "mode", IoModule.class.getName(), "fileMode", null);
        finish(fileIO);
        m.attrs.put("FileIO", fileIO);
        m.attrs.put("DEFAULT_BUFFER_SIZE", 8192L);
        fn(m, "open_code", (a, k) -> {
            if (a.size() != 1 || !(a.get(0) instanceof String)) throw new PyException("TypeError", "open_code() argument 'path' must be str, not " + PyRuntime.typeName(a.isEmpty() ? null : a.get(0)));
            return PyRuntime.instantiate(fileIO, new Object[]{a.get(0), "rb"});
        });
        return m;
    }

    private static FileState st(Object self) { return state(self, FileState.class, "FileIO"); }

    public static Object fileInit(Object self, Object name, Object mode, Object closefd) {
        String path = PosixModule.pathString(name);
        String md = (String) mode;
        if (!md.replace("b", "").equals("r"))
            throw new PyException("NotImplementedError", "_io.FileIO supports only reading (mode 'r'/'rb'), not '" + md + "'");
        Path p = Path.of(path);
        if (Files.isDirectory(p)) throw PosixModule.osError("IsADirectoryError", 21, PosixModule.strerror(21), path);
        FileState s = new FileState(path, md);
        try { s.data = Files.readAllBytes(p); }
        catch (IOException e) { throw PosixModule.osError(e, path); }
        setState(self, s);
        return null;
    }

    private static void open(FileState s) { if (s.closed) throw new PyException("ValueError", "I/O operation on closed file"); }

    public static Object fileRead(Object self, Object size) {
        FileState s = st(self); open(s);
        long n = size == null ? -1 : ((Number) size).longValue();
        int end = n < 0 ? s.data.length : (int) Math.min(s.data.length, s.pos + n);
        PyBytes out = new PyBytes(Arrays.copyOfRange(s.data, s.pos, end));
        s.pos = end;
        return out;
    }
    public static Object fileReadall(Object self) { return fileRead(self, -1L); }
    public static Object fileReadable(Object self) { open(st(self)); return true; }
    public static Object fileWritable(Object self) { open(st(self)); return false; }
    public static Object fileClose(Object self) { st(self).closed = true; return null; }
    public static Object fileEnter(Object self) { open(st(self)); return self; }
    public static Object fileExit(Object self, Object args) { return fileClose(self); }
    public static Object fileClosed(Object self) { return st(self).closed; }
    public static Object fileName(Object self) { return st(self).path; }
    public static Object fileMode(Object self) { st(self); return "rb"; }
}
