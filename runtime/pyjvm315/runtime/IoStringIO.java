// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.Arrays;

/** {@code _io.StringIO}: an in-memory text stream stored as code points, with CPython's newline modes. */
final class IoStringIO {
    private IoStringIO() {}

    private static final String ME = IoStringIO.class.getName();
    static PyClass cls;

    static final class St {
        int[] buf = new int[16]; int len, pos; boolean closed, inited;
        IoCodec.NewlineDec decoder; boolean readUniversal, readTranslate;
        String readnl, writenl;
    }

    static void define(PyModule m) {
        cls = NativeTypes.define("_io", "StringIO", IoBase.text);
        method(cls, IoStringIO.class, "__init__", "init", "self,initial_value,newline", defaults("initial_value", "", "newline", "\n"));
        method(cls, IoStringIO.class, "read", "read", "self,size", defaults("size", -1L));
        method(cls, IoStringIO.class, "readline", "readline", "self,size", defaults("size", -1L));
        method(cls, IoStringIO.class, "write", "write", "self,s");
        method(cls, IoStringIO.class, "seek", "seek", "self,pos,whence", defaults("whence", 0L));
        method(cls, IoStringIO.class, "tell", "tell", "self");
        method(cls, IoStringIO.class, "truncate", "truncate", "self,pos", defaults("pos", null));
        method(cls, IoStringIO.class, "getvalue", "getvalue", "self");
        method(cls, IoStringIO.class, "close", "close", "self");
        method(cls, IoStringIO.class, "readable", "yes", "self");
        method(cls, IoStringIO.class, "writable", "yes", "self");
        method(cls, IoStringIO.class, "seekable", "yes", "self");
        method(cls, IoStringIO.class, "__next__", "next", "self");
        method(cls, IoStringIO.class, "__getstate__", "getstate", "self");
        method(cls, IoStringIO.class, "__setstate__", "setstate", "self,state");
        PyRuntime.classAddProperty(cls, "closed", ME, "closedGet", null);
        PyRuntime.classAddProperty(cls, "newlines", ME, "newlinesGet", null);
        PyRuntime.classAddProperty(cls, "line_buffering", ME, "lineBufferingGet", null);
        finish(cls);
        m.attrs.put("StringIO", cls);
    }

    static St st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof St s) return s;
        if (self instanceof PyInstance i && i.cls.mro.contains(cls)) { St s = new St(); setState(self, s); return s; }
        throw new PyException("TypeError", "descriptor requires a '_io.StringIO' object but received '" + PyRuntime.typeName(self) + "'");
    }

    static St open(Object self) {
        St s = st(self);
        if (!s.inited) throw IoUtil.uninitialized();
        if (s.closed) throw new PyException("ValueError", "I/O operation on closed file");
        return s;
    }

    public static Object init(Object self, Object initial, Object newline) {
        St s = st(self);
        Object nl = NativeSubclass.unwrap(newline);
        if (nl != null && !(nl instanceof String)) throw new PyException("TypeError", "newline must be str or None, not " + PyRuntime.typeName(nl));
        String newl = (String) nl;
        if (newl != null && !(newl.isEmpty() || newl.equals("\n") || newl.equals("\r") || newl.equals("\r\n")))
            throw new PyException("ValueError", "illegal newline value: " + PyRuntime.pyRepr(newl));
        Object iv = NativeSubclass.unwrap(initial);
        if (iv != null && !(iv instanceof String)) throw new PyException("TypeError", "initial_value must be str or None, not " + PyRuntime.typeName(iv));
        s.readnl = newl;
        s.readUniversal = newl == null || newl.isEmpty();
        s.readTranslate = newl == null;
        s.writenl = newl != null && !newl.isEmpty() && newl.charAt(0) == '\r' ? newl : null;
        s.decoder = s.readUniversal ? new IoCodec.NewlineDec(null, s.readTranslate) : null;
        s.buf = new int[16]; s.len = 0; s.pos = 0; s.closed = false; s.inited = true;
        if (iv != null && !((String) iv).isEmpty()) { writeStr(s, (String) iv); s.pos = 0; }
        return null;
    }

    private static void writeStr(St s, String text) {
        if (s.decoder != null) text = s.decoder.process(text, true);
        if (s.writenl != null) text = text.replace("\n", s.writenl);
        int[] cps = text.codePoints().toArray();
        if (cps.length == 0) return;
        int end = s.pos + cps.length;
        if (end > s.buf.length) s.buf = Arrays.copyOf(s.buf, Math.max(end, s.buf.length * 2));
        if (s.pos > s.len) Arrays.fill(s.buf, s.len, s.pos, 0);
        System.arraycopy(cps, 0, s.buf, s.pos, cps.length);
        s.pos = end;
        if (end > s.len) s.len = end;
    }

    public static Object write(Object self, Object str) {
        St s = open(self);
        Object o = NativeSubclass.unwrap(str);
        if (!(o instanceof String text)) throw new PyException("TypeError", "string argument expected, got '" + PyRuntime.typeName(o) + "'");
        long n = text.codePointCount(0, text.length());
        writeStr(s, text);
        return n;
    }

    public static Object read(Object self, Object size) {
        St s = open(self);
        long n = IoUtil.sizeArg(size);
        if (s.pos >= s.len) return "";
        int end = n < 0 || s.pos + n > s.len ? s.len : (int) (s.pos + n);
        String out = new String(s.buf, s.pos, end - s.pos);
        s.pos = end;
        return out;
    }

    /** Index just past the line ending found in [from, len), or -1. */
    private static int lineEnd(St s, int from, int len) {
        if (s.readTranslate) {
            for (int i = from; i < len; i++) if (s.buf[i] == '\n') return i + 1;
            return -1;
        }
        if (s.readUniversal) {
            for (int i = from; i < len; i++) {
                int c = s.buf[i];
                if (c == '\n') return i + 1;
                if (c == '\r') return i + 1 < len && s.buf[i + 1] == '\n' ? i + 2 : i + 1;
            }
            return -1;
        }
        String nl = s.readnl;
        outer:
        for (int i = from; i + nl.length() <= len; i++) {
            for (int j = 0; j < nl.length(); j++) if (s.buf[i + j] != nl.charAt(j)) continue outer;
            return i + nl.length();
        }
        return -1;
    }

    public static Object readline(Object self, Object size) {
        St s = open(self);
        Object sz = NativeSubclass.unwrap(size);
        long limit = sz == null ? -1 : IoUtil.asLong(sz);
        if (s.pos >= s.len) return "";
        int stop = limit < 0 || s.pos + limit > s.len ? s.len : (int) (s.pos + limit);
        int end = lineEnd(s, s.pos, stop);
        if (end < 0) end = stop;
        String out = new String(s.buf, s.pos, end - s.pos);
        s.pos = end;
        return out;
    }

    public static Object next(Object self) {
        String line = (String) readline(self, -1L);
        if (line.isEmpty()) throw new PyException("StopIteration", null);
        return line;
    }

    public static Object seek(Object self, Object pos, Object whence) {
        St s = open(self);
        Object p = NativeSubclass.unwrap(pos);
        if (p instanceof Double) throw new PyException("TypeError", "'float' object cannot be interpreted as an integer");
        long off = IoUtil.asLong(p), w = IoUtil.asLong(whence);
        if (w < 0 || w > 2) throw new PyException("ValueError", "Invalid whence (" + w + ", should be 0, 1 or 2)");
        if (w == 0 && off < 0) throw new PyException("ValueError", "Negative seek position " + off);
        if (w == 1 && off != 0) throw new PyException("OSError", "Can't do nonzero cur-relative seeks");
        if (w == 2 && off != 0) throw new PyException("OSError", "Can't do nonzero cur-relative seeks");
        s.pos = (int) (w == 0 ? Math.min(off, Integer.MAX_VALUE) : w == 1 ? s.pos : s.len);
        return (long) s.pos;
    }

    public static Object tell(Object self) { return (long) open(self).pos; }

    public static Object truncate(Object self, Object pos) {
        St s = open(self);
        long n = pos == null ? s.pos : IoUtil.asLong(pos);
        if (n < 0) throw new PyException("ValueError", "negative pos value " + n);
        if (n < s.len) s.len = (int) n;
        return n;
    }

    public static Object getvalue(Object self) { St s = open(self); return new String(s.buf, 0, s.len); }
    public static Object close(Object self) { St s = st(self); s.closed = true; s.buf = new int[0]; s.len = s.pos = 0; return null; }
    public static Object yes(Object self) { open(self); return true; }
    public static Object closedGet(Object self) { St s = st(self); if (!s.inited) throw IoUtil.uninitialized(); return s.closed; }
    public static Object newlinesGet(Object self) { St s = open(self); return s.decoder == null ? null : s.decoder.newlines(); }
    public static Object lineBufferingGet(Object self) { open(self); return false; }

    public static Object getstate(Object self) {
        St s = open(self);
        PyInstance i = (PyInstance) self;
        PyDict d = null;
        if (!i.fields.isEmpty()) { d = new PyDict(); for (var e : i.fields.entrySet()) d.put(e.getKey(), e.getValue()); }
        return tuple(new String(s.buf, 0, s.len), s.readnl, (long) s.pos, d);
    }

    public static Object setstate(Object self, Object state) {
        if (!(state instanceof PyTuple t) || t.items.size() < 4) throw new PyException("TypeError", "__setstate__ argument should be 4-tuple, got " + PyRuntime.typeName(state));
        St s = st(self);
        Object nl = t.items.get(1);
        init(self, null, nl);
        String initial = (String) NativeSubclass.unwrap(t.items.get(0));
        // the stored text is already newline-translated: restore it verbatim
        int[] cps = initial.codePoints().toArray();
        s.buf = Arrays.copyOf(cps, Math.max(16, cps.length)); s.len = cps.length;
        long pos = IoUtil.asLong(t.items.get(2));
        if (pos < 0) throw new PyException("ValueError", "position value cannot be negative");
        s.pos = (int) pos;
        if (t.items.get(3) != null) {
            if (!(t.items.get(3) instanceof PyDict d)) throw new PyException("TypeError", "fourth item of state should be a dict, got a " + PyRuntime.typeName(t.items.get(3)));
            for (var e : d.entrySet()) ((PyInstance) self).fields.put((String) e.getKey(), e.getValue());
        }
        return null;
    }
}
