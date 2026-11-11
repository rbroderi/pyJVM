// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.ByteArrayOutputStream;

/** {@code _io.FileIO}: raw binary access to a file descriptor. */
final class IoFile {
    private IoFile() {}

    private static final String ME = IoFile.class.getName();
    static PyClass cls;

    static final class St {
        long fd = -1; boolean readable, writable, appending, created, closefd = true;
    }

    static void define(PyModule m) {
        cls = NativeTypes.define("_io", "FileIO", IoBase.raw);
        method(cls, IoFile.class, "__init__", "init", "self,file,mode,closefd,opener", defaults("mode", "r", "closefd", true, "opener", null));
        method(cls, IoFile.class, "read", "read", "self,size", defaults("size", -1L));
        method(cls, IoFile.class, "readall", "readall", "self");
        method(cls, IoFile.class, "readinto", "readinto", "self,buffer");
        method(cls, IoFile.class, "write", "write", "self,b");
        method(cls, IoFile.class, "seek", "seek", "self,pos,whence", defaults("whence", 0L));
        method(cls, IoFile.class, "tell", "tell", "self");
        method(cls, IoFile.class, "truncate", "truncate", "self,size", defaults("size", null));
        method(cls, IoFile.class, "close", "close", "self");
        method(cls, IoFile.class, "fileno", "fileno", "self");
        method(cls, IoFile.class, "isatty", "isatty", "self");
        method(cls, IoFile.class, "readable", "readable", "self");
        method(cls, IoFile.class, "writable", "writable", "self");
        method(cls, IoFile.class, "seekable", "seekable", "self");
        method(cls, IoFile.class, "__repr__", "repr", "self");
        method(cls, IoFile.class, "__getstate__", "getstate", "self");
        PyRuntime.classAddProperty(cls, "closed", ME, "closedGet", null);
        PyRuntime.classAddProperty(cls, "closefd", ME, "closefdGet", null);
        PyRuntime.classAddProperty(cls, "mode", ME, "modeGet", null);
        PyRuntime.classAddProperty(cls, "_blksize", ME, "blksize", null);
        finish(cls);
        m.attrs.put("FileIO", cls);
    }

    static St st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof St s) return s;
        if (self instanceof PyInstance i && i.cls.mro.contains(cls)) { St s = new St(); setState(self, s); return s; }
        throw new PyException("TypeError", "descriptor requires a '_io.FileIO' object but received '" + PyRuntime.typeName(self) + "'");
    }

    static St open(Object self) {
        St s = st(self);
        if (s.fd < 0) throw IoUtil.closed();
        return s;
    }

    /** The descriptor of an open FileIO (used by the buffered layer's fast path). */
    static long fdOf(Object raw) { return raw instanceof PyInstance i && i.slotValues.get(STATE) instanceof St s ? s.fd : -1; }

    public static Object init(Object self, Object file, Object mode, Object closefd, Object opener) {
        St s = st(self);
        if (s.fd >= 0 && s.closefd) { try { IoFd.close(s.fd); } catch (PyException ignored) { } }
        s.fd = -1;
        file = NativeSubclass.unwrap(file);
        if (file instanceof Double) throw new PyException("TypeError", "integer argument expected, got float");
        long fd = -1;
        String path = null;
        boolean isFd = file instanceof Long || file instanceof java.math.BigInteger || (file instanceof Boolean);
        if (isFd) {
            fd = IoUtil.asLong(file);
            if (fd < 0) throw new PyException("ValueError", "negative file descriptor");
        } else {
            Object p = PosixModule.fspath(file);
            path = p instanceof PyBytes b ? new String(b.data, java.nio.charset.StandardCharsets.UTF_8) : (String) p;
            if (path.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character in path");
        }
        if (!(mode instanceof String ms)) throw new PyException("TypeError", "FileIO() argument 'mode' must be str, not " + PyRuntime.typeName(mode));
        boolean rwa = false, plus = false, read = false, write = false, append = false, create = false;
        int flags = 0;
        for (int i = 0; i < ms.length(); i++) {
            char c = ms.charAt(i);
            switch (c) {
                case 'x': if (rwa) throw badMode(); rwa = true; create = true; write = true; flags |= 0200 | 0100; break;
                case 'r': if (rwa) throw badMode(); rwa = true; read = true; break;
                case 'w': if (rwa) throw badMode(); rwa = true; write = true; flags |= 0100 | 01000; break;
                case 'a': if (rwa) throw badMode(); rwa = true; write = true; append = true; flags |= 0100 | 02000; break;
                case 'b': break;
                case '+': if (plus) throw badMode(); read = write = true; plus = true; break;
                default: throw new PyException("ValueError", "invalid mode: " + ms);
            }
        }
        if (!rwa) throw badMode();
        flags |= read && write ? 2 : write ? 1 : 0;
        boolean cf = PyRuntime.truth(closefd);
        if (!cf && !isFd) throw new PyException("ValueError", "Cannot use closefd=False with file name");
        if (isFd) {
            IoFd.get(fd);
        } else if (opener != null) {
            Object r = PyRuntime.callFunction(opener, new java.util.ArrayList<Object>(java.util.List.of(file, (long) flags)), new java.util.LinkedHashMap<Object,Object>());
            if (!(r instanceof Long) && !(r instanceof Boolean)) throw new PyException("TypeError", "expected integer from opener");
            fd = IoUtil.asLong(r);
            if (fd < 0) throw new PyException("ValueError", "opener returned " + fd);
        } else {
            fd = IoFd.open(path, flags, 0666);
        }
        IoFd.Fd e = IoFd.get(fd);
        if (e.dir) {
            if (!isFd || cf) IoFd.close(fd);
            throw PosixModule.osError("IsADirectoryError", 21, "Is a directory", path);
        }
        s.fd = fd; s.readable = read; s.writable = write; s.appending = append; s.created = create; s.closefd = cf;
        ((PyInstance) self).fields.put("name", file);
        ((PyInstance) self).fields.remove("__IOBase_closed");
        if (append) { try { IoFd.seek(fd, 0, 2); } catch (PyException ex) { if (!"OSError".equals(ex.typeName)) throw ex; } }
        return null;
    }

    private static PyException badMode() { return new PyException("ValueError", "Must have exactly one of create/read/write/append mode and at most one plus"); }

    public static Object read(Object self, Object size) {
        St s = open(self);
        if (!s.readable) throw IoUtil.unsupported("File not open for reading");
        long n = IoUtil.sizeArg(size);
        if (n < 0) return readall(self);
        return new PyBytes(IoFd.read(s.fd, (int) Math.min(n, 1 << 30)));
    }

    public static Object readall(Object self) {
        St s = open(self);
        if (!s.readable) throw IoUtil.unsupported("File not open for reading");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            long hint = 8192;
            try { hint = Math.max(8192, IoFd.size(s.fd) - IoFd.tell(s.fd) + 1); } catch (PyException ignored) { }
            byte[] chunk = IoFd.read(s.fd, (int) Math.min(hint, 1 << 26));
            if (chunk.length == 0) break;
            out.write(chunk, 0, chunk.length);
        }
        return new PyBytes(out.toByteArray());
    }

    public static Object readinto(Object self, Object buffer) {
        St s = open(self);
        if (!s.readable) throw IoUtil.unsupported("File not open for reading");
        int n = IoUtil.bufferSize(buffer);
        byte[] d = IoFd.read(s.fd, n);
        IoUtil.fill(buffer, d, 0, d.length);
        return (long) d.length;
    }

    public static Object write(Object self, Object b) {
        St s = open(self);
        if (!s.writable) throw IoUtil.unsupported("File not open for writing");
        byte[] d = IoUtil.bytesLike(b);
        return (long) IoFd.write(s.fd, d, 0, d.length);
    }

    public static Object seek(Object self, Object pos, Object whence) {
        St s = open(self);
        Object p = NativeSubclass.unwrap(pos);
        if (p instanceof Double) throw new PyException("TypeError", "an integer is required");
        long w = IoUtil.asLong(whence);
        return IoFd.seek(s.fd, IoUtil.asLong(p), (int) w);
    }

    public static Object tell(Object self) { return IoFd.tell(open(self).fd); }

    public static Object truncate(Object self, Object size) {
        St s = open(self);
        if (!s.writable) throw IoUtil.unsupported("File not open for writing");
        long n = size == null ? IoFd.tell(s.fd) : IoUtil.asLong(size);
        IoFd.truncate(s.fd, n);
        return n;
    }

    public static Object close(Object self) {
        St s = st(self);
        if (s.fd < 0) return null;
        try { IoBase.close(self); }
        finally {
            long fd = s.fd;
            s.fd = -1;
            if (s.closefd) IoFd.close(fd);
        }
        return null;
    }

    public static Object fileno(Object self) { return open(self).fd; }
    public static Object isatty(Object self) { return IoFd.isatty(open(self).fd); }
    public static Object readable(Object self) { return open(self).readable; }
    public static Object writable(Object self) { return open(self).writable; }
    public static Object seekable(Object self) { return IoFd.seekable(open(self).fd); }
    public static Object closedGet(Object self) { return st(self).fd < 0; }
    public static Object closefdGet(Object self) { return st(self).closefd; }
    public static Object blksize(Object self) { return 4096L; }

    static String modeString(St s) {
        if (s.created) return s.readable ? "xb+" : "xb";
        if (s.appending) return s.readable ? "ab+" : "ab";
        if (s.readable) return s.writable ? "rb+" : "rb";
        return "wb";
    }

    public static Object modeGet(Object self) { return modeString(st(self)); }

    public static Object getstate(Object self) { throw new PyException("TypeError", "cannot pickle 'FileIO' instances"); }

    static String typeRepr(Object self) {
        PyClass c = ((PyInstance) self).cls;
        return c.moduleName.equals("builtins") ? c.qualname : c.moduleName + "." + c.qualname;
    }

    public static Object repr(Object self) {
        St s = st(self);
        String t = typeRepr(self);
        if (s.fd < 0) return "<" + t + " [closed]>";
        Object name = ((PyInstance) self).fields.get("name");
        return "<" + t + " name=" + PyRuntime.pyRepr(name) + " mode='" + modeString(s) + "' closefd=" + (s.closefd ? "True" : "False") + ">";
    }
}
