// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

/** The abstract base classes {@code _io._IOBase}, {@code _RawIOBase}, {@code _BufferedIOBase} and {@code _TextIOBase}. */
final class IoBase {
    private IoBase() {}

    private static final String ME = IoBase.class.getName();

    static PyClass iobase, raw, buffered, text;

    static void define(PyModule m) {
        iobase = NativeTypes.define("_io", "_IOBase", null);
        method(iobase, IoBase.class, "close", "close", "self");
        method(iobase, IoBase.class, "flush", "flush", "self");
        method(iobase, IoBase.class, "__enter__", "enter", "self");
        methodVar(iobase, IoBase.class, "__exit__", "exit", "self", "args");
        method(iobase, IoBase.class, "__iter__", "iter", "self");
        method(iobase, IoBase.class, "__next__", "next", "self");
        method(iobase, IoBase.class, "readline", "readline", "self,size", defaults("size", -1L));
        method(iobase, IoBase.class, "readlines", "readlines", "self,hint", defaults("hint", -1L));
        method(iobase, IoBase.class, "writelines", "writelines", "self,lines");
        method(iobase, IoBase.class, "seek", "seek", "self,offset,whence", defaults("whence", 0L));
        method(iobase, IoBase.class, "tell", "tell", "self");
        method(iobase, IoBase.class, "truncate", "truncate", "self,pos", defaults("pos", null));
        method(iobase, IoBase.class, "fileno", "fileno", "self");
        method(iobase, IoBase.class, "isatty", "isatty", "self");
        method(iobase, IoBase.class, "readable", "no", "self");
        method(iobase, IoBase.class, "writable", "no", "self");
        method(iobase, IoBase.class, "seekable", "no", "self");
        method(iobase, IoBase.class, "_checkClosed", "checkClosedM", "self");
        method(iobase, IoBase.class, "_checkReadable", "checkReadable", "self");
        method(iobase, IoBase.class, "_checkWritable", "checkWritable", "self");
        method(iobase, IoBase.class, "_checkSeekable", "checkSeekable", "self");
        method(iobase, IoBase.class, "__del__", "del", "self");
        PyRuntime.classAddProperty(iobase, "closed", ME, "closedGet", null);
        finish(iobase);
        m.attrs.put("_IOBase", iobase);

        raw = NativeTypes.define("_io", "_RawIOBase", iobase);
        method(raw, IoBase.class, "read", "rawRead", "self,size", defaults("size", -1L));
        method(raw, IoBase.class, "readall", "rawReadall", "self");
        finish(raw);
        m.attrs.put("_RawIOBase", raw);

        buffered = NativeTypes.define("_io", "_BufferedIOBase", iobase);
        method(buffered, IoBase.class, "read", "bufUnsupportedRead", "self,size", defaults("size", -1L));
        method(buffered, IoBase.class, "read1", "bufUnsupportedRead1", "self,size", defaults("size", -1L));
        method(buffered, IoBase.class, "write", "bufUnsupportedWrite", "self,b");
        method(buffered, IoBase.class, "detach", "bufUnsupportedDetach", "self");
        method(buffered, IoBase.class, "readinto", "bufReadinto", "self,b");
        method(buffered, IoBase.class, "readinto1", "bufReadinto1", "self,b");
        finish(buffered);
        m.attrs.put("_BufferedIOBase", buffered);

        text = NativeTypes.define("_io", "_TextIOBase", iobase);
        method(text, IoBase.class, "read", "txtUnsupportedRead", "self,size", defaults("size", -1L));
        method(text, IoBase.class, "readline", "txtUnsupportedReadline", "self,size", defaults("size", -1L));
        method(text, IoBase.class, "write", "txtUnsupportedWrite", "self,s");
        method(text, IoBase.class, "detach", "txtUnsupportedDetach", "self");
        PyRuntime.classAddProperty(text, "encoding", ME, "none", null);
        PyRuntime.classAddProperty(text, "newlines", ME, "none", null);
        PyRuntime.classAddProperty(text, "errors", ME, "none", null);
        finish(text);
        m.attrs.put("_TextIOBase", text);
    }

    // ---- _IOBase ----------------------------------------------------------------------------------------

    static boolean isClosed(Object self) { return PyRuntime.truth(PyRuntime.getattr(self, "closed")); }

    static void checkClosed(Object self) { if (isClosed(self)) throw IoUtil.closed(); }

    public static Object closedGet(Object self) {
        return self instanceof PyInstance i && PyRuntime.truth(i.fields.get("__IOBase_closed"));
    }

    public static Object close(Object self) {
        if (isClosed(self)) return null;
        try { PyRuntime.callMethod(self, "flush", new Object[0]); }
        finally { ((PyInstance) self).fields.put("__IOBase_closed", true); }
        return null;
    }

    public static Object flush(Object self) { checkClosed(self); return null; }
    public static Object enter(Object self) { checkClosed(self); return self; }
    public static Object exit(Object self, Object args) { PyRuntime.callMethod(self, "close", new Object[0]); return null; }
    public static Object iter(Object self) { checkClosed(self); return self; }

    public static Object next(Object self) {
        Object line = PyRuntime.callMethod(self, "readline", new Object[0]);
        if (((Number) PyRuntime.len(line)).longValue() == 0) throw new PyException("StopIteration", null);
        return line;
    }

    public static Object readline(Object self, Object size) {
        long limit = IoUtil.sizeArg(size);
        boolean peek = PyRuntime.truth(PyRuntime.hasattr(self, "peek"));
        ByteArrayOutputStream res = new ByteArrayOutputStream();
        while (limit < 0 || res.size() < limit) {
            long n = 1;
            if (peek) {
                Object ahead = PyRuntime.callMethod(self, "peek", new Object[]{1L});
                byte[] a = IoUtil.bytesLike(ahead);
                if (a.length > 0) {
                    int nl = -1;
                    for (int i = 0; i < a.length; i++) if (a[i] == '\n') { nl = i; break; }
                    n = nl >= 0 ? nl + 1 : a.length;
                    if (limit >= 0) n = Math.min(n, limit - res.size());
                }
            }
            Object chunk = PyRuntime.callMethod(self, "read", new Object[]{n});
            if (chunk == null) break;
            byte[] c = IoUtil.bytesLike(chunk);
            if (c.length == 0) break;
            res.write(c, 0, c.length);
            if (c[c.length - 1] == '\n') break;
        }
        return new PyBytes(res.toByteArray());
    }

    public static Object readlines(Object self, Object hint) {
        long h = IoUtil.sizeArg(hint);
        ArrayList<Object> out = new ArrayList<>();
        Object it = PyRuntime.iter(self);
        long total = 0;
        while (true) {
            Object line;
            try { line = PyRuntime.callMethod(it, "__next__", new Object[0]); }
            catch (PyException e) { if (e.typeName.equals("StopIteration")) break; throw e; }
            out.add(line);
            if (h > 0) { total += ((Number) PyRuntime.len(line)).longValue(); if (total >= h) break; }
        }
        return out;
    }

    public static Object writelines(Object self, Object lines) {
        checkClosed(self);
        Object it = PyRuntime.iter(lines);
        while (true) {
            Object line;
            try { line = PyRuntime.callMethod(it, "__next__", new Object[0]); }
            catch (PyException e) { if (e.typeName.equals("StopIteration")) break; throw e; }
            PyRuntime.callMethod(self, "write", new Object[]{line});
        }
        return null;
    }

    public static Object seek(Object self, Object offset, Object whence) { throw IoUtil.unsupported("seek"); }
    public static Object tell(Object self) { return PyRuntime.callMethod(self, "seek", new Object[]{0L, 1L}); }
    public static Object truncate(Object self, Object pos) { throw IoUtil.unsupported("truncate"); }
    public static Object fileno(Object self) { throw IoUtil.unsupported("fileno"); }
    public static Object isatty(Object self) { checkClosed(self); return false; }
    public static Object no(Object self) { return false; }
    public static Object none(Object self) { return null; }
    public static Object checkClosedM(Object self) { checkClosed(self); return null; }

    public static Object checkReadable(Object self) {
        if (!PyRuntime.truth(PyRuntime.callMethod(self, "readable", new Object[0]))) throw IoUtil.unsupported("File or stream is not readable.");
        return null;
    }

    public static Object checkWritable(Object self) {
        if (!PyRuntime.truth(PyRuntime.callMethod(self, "writable", new Object[0]))) throw IoUtil.unsupported("File or stream is not writable.");
        return null;
    }

    public static Object checkSeekable(Object self) {
        if (!PyRuntime.truth(PyRuntime.callMethod(self, "seekable", new Object[0]))) throw IoUtil.unsupported("File or stream is not seekable.");
        return null;
    }

    public static Object del(Object self) {
        try { if (!isClosed(self)) PyRuntime.callMethod(self, "close", new Object[0]); } catch (RuntimeException ignored) { }
        return null;
    }

    // ---- _RawIOBase -------------------------------------------------------------------------------------

    public static Object rawRead(Object self, Object size) {
        long n = IoUtil.sizeArg(size);
        if (n < 0) return PyRuntime.callMethod(self, "readall", new Object[0]);
        PyByteArray b = new PyByteArray(new byte[(int) n]);
        Object got = PyRuntime.callMethod(self, "readinto", new Object[]{b});
        if (got == null) return null;
        long len = IoUtil.asLong(got);
        if (len < 0 || len > n) throw new PyException("ValueError", "readinto returned " + len + " outside buffer size " + n);
        byte[] all = b.toByteArray();
        return new PyBytes(java.util.Arrays.copyOf(all, (int) len));
    }

    public static Object rawReadall(Object self) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            Object chunk = PyRuntime.callMethod(self, "read", new Object[]{(long) IoUtil.DEFAULT_BUFFER_SIZE});
            if (chunk == null) return out.size() == 0 ? null : new PyBytes(out.toByteArray());
            byte[] c = IoUtil.bytesLike(chunk);
            if (c.length == 0) return new PyBytes(out.toByteArray());
            out.write(c, 0, c.length);
        }
    }

    // ---- _BufferedIOBase ----------------------------------------------------------------------------------

    public static Object bufUnsupportedRead(Object self, Object size) { throw IoUtil.unsupported("read"); }
    public static Object bufUnsupportedRead1(Object self, Object size) { throw IoUtil.unsupported("read1"); }
    public static Object bufUnsupportedWrite(Object self, Object b) { throw IoUtil.unsupported("write"); }
    public static Object bufUnsupportedDetach(Object self) { throw IoUtil.unsupported("detach"); }

    private static Object readintoVia(Object self, Object b, String method) {
        int n = IoUtil.bufferSize(b);
        Object data = PyRuntime.callMethod(self, method, new Object[]{(long) n});
        byte[] d = IoUtil.bytesLike(data);
        if (d.length > n) throw new PyException("ValueError", method + "() returned too much data: " + n + " bytes requested, " + d.length + " returned");
        IoUtil.fill(b, d, 0, d.length);
        return (long) d.length;
    }

    public static Object bufReadinto(Object self, Object b) { return readintoVia(self, b, "read"); }
    public static Object bufReadinto1(Object self, Object b) { return readintoVia(self, b, "read1"); }

    // ---- _TextIOBase --------------------------------------------------------------------------------------

    public static Object txtUnsupportedRead(Object self, Object size) { throw IoUtil.unsupported("read"); }
    public static Object txtUnsupportedReadline(Object self, Object size) { throw IoUtil.unsupported("readline"); }
    public static Object txtUnsupportedWrite(Object self, Object s) { throw IoUtil.unsupported("write"); }
    public static Object txtUnsupportedDetach(Object self) { throw IoUtil.unsupported("detach"); }
}
