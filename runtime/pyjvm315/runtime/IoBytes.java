// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.Arrays;

/** {@code _io.BytesIO}: an in-memory binary stream whose buffer is a bytearray, so {@code getbuffer()} shares it. */
final class IoBytes {
    private IoBytes() {}

    private static final String ME = IoBytes.class.getName();
    static PyClass cls;

    static final class St {
        PyByteArray buf = new PyByteArray(); int pos; boolean closed;
        byte[] snapshot() { return buf.toByteArray(); }
    }

    static void define(PyModule m) {
        cls = NativeTypes.define("_io", "BytesIO", IoBase.buffered);
        method(cls, IoBytes.class, "__init__", "init", "self,initial_bytes", defaults("initial_bytes", null));
        method(cls, IoBytes.class, "read", "read", "self,size", defaults("size", -1L));
        method(cls, IoBytes.class, "read1", "read", "self,size", defaults("size", -1L));
        method(cls, IoBytes.class, "readline", "readline", "self,size", defaults("size", -1L));
        method(cls, IoBytes.class, "readlines", "readlines", "self,size", defaults("size", null));
        method(cls, IoBytes.class, "readinto", "readinto", "self,buffer");
        method(cls, IoBytes.class, "readinto1", "readinto", "self,buffer");
        method(cls, IoBytes.class, "write", "write", "self,b");
        method(cls, IoBytes.class, "seek", "seek", "self,pos,whence", defaults("whence", 0L));
        method(cls, IoBytes.class, "tell", "tell", "self");
        method(cls, IoBytes.class, "truncate", "truncate", "self,size", defaults("size", null));
        method(cls, IoBytes.class, "getvalue", "getvalue", "self");
        method(cls, IoBytes.class, "getbuffer", "getbuffer", "self");
        method(cls, IoBytes.class, "close", "close", "self");
        method(cls, IoBytes.class, "flush", "flush", "self");
        method(cls, IoBytes.class, "isatty", "isatty", "self");
        method(cls, IoBytes.class, "readable", "yes", "self");
        method(cls, IoBytes.class, "writable", "yes", "self");
        method(cls, IoBytes.class, "seekable", "yes", "self");
        method(cls, IoBytes.class, "__next__", "next", "self");
        method(cls, IoBytes.class, "__getstate__", "getstate", "self");
        method(cls, IoBytes.class, "__setstate__", "setstate", "self,state");
        PyRuntime.classAddProperty(cls, "closed", ME, "closedGet", null);
        finish(cls);
        m.attrs.put("BytesIO", cls);
    }

    static St st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof St s) return s;
        if (self instanceof PyInstance i && i.cls.mro.contains(cls)) { St s = new St(); setState(self, s); return s; }
        throw new PyException("TypeError", "descriptor requires a '_io.BytesIO' object but received '" + PyRuntime.typeName(self) + "'");
    }

    static St open(Object self) {
        St s = st(self);
        if (s.closed) throw IoUtil.closed();
        return s;
    }

    public static Object init(Object self, Object initial) {
        St s = st(self);
        s.buf = new PyByteArray(); s.pos = 0; s.closed = false;
        if (initial != null) { byte[] d = IoUtil.bytesLike(initial); for (byte b : d) s.buf.data.add(b); }
        return null;
    }

    private static byte[] take(St s, long n) {
        int size = s.buf.byteSize();
        if (s.pos >= size) return new byte[0];
        int end = n < 0 ? size : (int) Math.min(size, s.pos + n);
        byte[] out = new byte[end - s.pos];
        for (int i = 0; i < out.length; i++) out[i] = s.buf.data.get(s.pos + i);
        s.pos = end;
        return out;
    }

    public static Object read(Object self, Object size) {
        St s = open(self);
        return new PyBytes(take(s, IoUtil.sizeArg(size)));
    }

    public static Object readline(Object self, Object size) {
        St s = open(self);
        long limit = IoUtil.sizeArg(size);
        int n = s.buf.byteSize();
        int end = s.pos;
        while (end < n && (limit < 0 || end - s.pos < limit)) { if (s.buf.data.get(end++) == '\n') break; }
        return new PyBytes(take(s, Math.max(0, end - s.pos)));
    }

    public static Object readlines(Object self, Object hint) {
        St s = open(self);
        long h = hint == null ? 0 : IoUtil.asLong(hint);
        ArrayList<Object> out = new ArrayList<>();
        long total = 0;
        while (s.pos < s.buf.byteSize()) {
            PyBytes line = (PyBytes) readline(self, -1L);
            out.add(line);
            total += line.data.length;
            if (h > 0 && total >= h) break;
        }
        return out;
    }

    public static Object readinto(Object self, Object buffer) {
        St s = open(self);
        int n = IoUtil.bufferSize(buffer);
        byte[] d = take(s, n);
        IoUtil.fill(buffer, d, 0, d.length);
        return (long) d.length;
    }

    static long writeBytes(St s, byte[] d) {
        if (d.length == 0) return 0;
        int size = s.buf.data.size();
        while (size < s.pos) { s.buf.data.add((byte) 0); size++; }
        int i = 0;
        for (; s.pos + i < size && i < d.length; i++) s.buf.data.set(s.pos + i, d[i]);
        for (; i < d.length; i++) s.buf.data.add(d[i]);
        s.pos += d.length;
        return d.length;
    }

    public static Object write(Object self, Object b) {
        St s = open(self);
        return writeBytes(s, IoUtil.bytesLike(b));
    }

    public static Object seek(Object self, Object pos, Object whence) {
        St s = open(self);
        Object p = NativeSubclass.unwrap(pos);
        if (p instanceof Double) throw new PyException("TypeError", "'float' object cannot be interpreted as an integer");
        long off = IoUtil.asLong(p), w = IoUtil.asLong(whence);
        if (w < 0 || w > 2) throw new PyException("ValueError", "invalid whence (" + w + ", should be 0, 1 or 2)");
        if (w == 0 && off < 0) throw new PyException("ValueError", "negative seek value " + off);
        long target = w == 0 ? off : w == 1 ? s.pos + off : s.buf.byteSize() + off;
        s.pos = (int) Math.max(0, Math.min(target, Integer.MAX_VALUE));
        return (long) s.pos;
    }

    public static Object tell(Object self) { return (long) open(self).pos; }

    public static Object truncate(Object self, Object size) {
        St s = open(self);
        long n = size == null ? s.pos : IoUtil.asLong(size);
        if (n < 0) throw new PyException("ValueError", "negative size value " + n);
        if (n < s.buf.byteSize()) while (s.buf.data.size() > n) s.buf.data.remove(s.buf.data.size() - 1);
        return n;
    }

    public static Object getvalue(Object self) { return new PyBytes(open(self).snapshot()); }

    public static Object getbuffer(Object self) { return new PyMemoryView(open(self).buf); }

    public static Object close(Object self) { St s = st(self); s.closed = true; s.buf = new PyByteArray(); return null; }
    public static Object flush(Object self) { open(self); return null; }
    public static Object isatty(Object self) { open(self); return false; }
    public static Object yes(Object self) { open(self); return true; }
    public static Object closedGet(Object self) { return st(self).closed; }

    public static Object next(Object self) {
        PyBytes line = (PyBytes) readline(self, -1L);
        if (line.data.length == 0) throw new PyException("StopIteration", null);
        return line;
    }

    public static Object getstate(Object self) {
        St s = open(self);
        PyInstance i = (PyInstance) self;
        return tuple(new PyBytes(s.snapshot()), (long) s.pos, i.fields.isEmpty() ? null : copyDict(i));
    }

    private static PyDict copyDict(PyInstance i) {
        PyDict d = new PyDict();
        for (var e : i.fields.entrySet()) d.put(e.getKey(), e.getValue());
        return d;
    }

    public static Object setstate(Object self, Object state) {
        if (!(state instanceof PyTuple t) || t.items.size() < 3) throw new PyException("TypeError", "__setstate__ argument should be 3-tuple, got " + PyRuntime.typeName(state));
        St s = st(self);
        s.closed = false;
        s.buf = new PyByteArray(IoUtil.bytesLike(t.items.get(0)));
        long pos = IoUtil.asLong(t.items.get(1));
        if (pos < 0) throw new PyException("ValueError", "position value cannot be negative");
        s.pos = (int) pos;
        if (t.items.get(2) != null) {
            if (!(t.items.get(2) instanceof PyDict d)) throw new PyException("TypeError", "third item of state should be a dict, got a " + PyRuntime.typeName(t.items.get(2)));
            for (var e : d.entrySet()) ((PyInstance) self).fields.put((String) e.getKey(), e.getValue());
        }
        return null;
    }
}
