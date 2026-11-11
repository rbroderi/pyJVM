// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** {@code _io.BufferedReader}, {@code BufferedWriter}, {@code BufferedRandom} and {@code BufferedRWPair}. */
final class IoBuffered {
    private IoBuffered() {}

    private static final String ME = IoBuffered.class.getName();
    static PyClass reader, writer, random, pair;

    /** The raw stream under a buffer: a FileIO served by direct descriptor calls, or any object with the raw protocol. */
    interface Ops {
        byte[] read(int n);
        int write(byte[] b, int off, int len);
        long seek(long pos, int how);
        long tell();
    }

    static final class FileOps implements Ops {
        final IoFile.St f;
        FileOps(IoFile.St f) { this.f = f; }
        public byte[] read(int n) { if (f.fd < 0) throw IoUtil.closed(); return IoFd.read(f.fd, n); }
        public int write(byte[] b, int off, int len) { if (f.fd < 0) throw IoUtil.closed(); return IoFd.write(f.fd, b, off, len); }
        public long seek(long pos, int how) { if (f.fd < 0) throw IoUtil.closed(); return IoFd.seek(f.fd, pos, how); }
        public long tell() { if (f.fd < 0) throw IoUtil.closed(); return IoFd.tell(f.fd); }
    }

    static final class ObjOps implements Ops {
        final Object raw;
        ObjOps(Object raw) { this.raw = raw; }
        public byte[] read(int n) {
            PyByteArray tmp = new PyByteArray(new byte[n]);
            Object got = PyRuntime.callMethod(raw, "readinto", new Object[]{new PyMemoryView(tmp)});
            if (got == null) return new byte[0];
            long len = IoUtil.asLong(got);
            if (len < 0 || len > n) throw new PyException("OSError", "raw readinto() returned invalid length " + len + " (should have been between 0 and " + n + ")");
            return Arrays.copyOf(tmp.toByteArray(), (int) len);
        }
        public int write(byte[] b, int off, int len) {
            Object r = PyRuntime.callMethod(raw, "write", new Object[]{new PyBytes(Arrays.copyOfRange(b, off, off + len))});
            if (r == null) throw new PyException("BlockingIOError", "write could not complete without blocking");
            long n = IoUtil.asLong(r);
            if (n < 0 || n > len) throw new PyException("OSError", "raw write() returned invalid length " + n + " (should have been between 0 and " + len + ")");
            return (int) n;
        }
        public long seek(long pos, int how) {
            Object r = PyRuntime.callMethod(raw, "seek", new Object[]{pos, (long) how});
            return IoUtil.asLong(r);
        }
        public long tell() {
            long r = IoUtil.asLong(PyRuntime.callMethod(raw, "tell", new Object[0]));
            if (r < 0) throw new PyException("OSError", "Raw stream returned invalid position " + r);
            return r;
        }
    }

    static final class B implements IoUtil.Pending {
        Object raw; Ops ops; int size = IoUtil.DEFAULT_BUFFER_SIZE;
        byte[] rbuf = new byte[0]; int rpos, rend;
        byte[] wbuf; int wlen;
        boolean isReader, isWriter, reg;
        public void flushQuietly() { if (raw != null && wlen > 0) flushWrite(this); }
    }

    // ---- class definitions ----------------------------------------------------------------------------

    private static PyClass make(PyModule m, String name, boolean rd, boolean wr) {
        PyClass c = NativeTypes.define("_io", name, IoBase.buffered);
        method(c, IoBuffered.class, "__init__", rd && wr ? "initRandom" : wr ? "initWriter" : "initReader", "self,raw,buffer_size", defaults("buffer_size", (long) IoUtil.DEFAULT_BUFFER_SIZE));
        method(c, IoBuffered.class, "close", "close", "self");
        method(c, IoBuffered.class, "detach", "detach", "self");
        method(c, IoBuffered.class, "flush", "flush", "self");
        method(c, IoBuffered.class, "seek", "seek", "self,target,whence", defaults("whence", 0L));
        method(c, IoBuffered.class, "tell", "tell", "self");
        method(c, IoBuffered.class, "fileno", "fileno", "self");
        method(c, IoBuffered.class, "isatty", "isatty", "self");
        method(c, IoBuffered.class, "readable", "readable", "self");
        method(c, IoBuffered.class, "writable", "writable", "self");
        method(c, IoBuffered.class, "seekable", "seekable", "self");
        method(c, IoBuffered.class, "__repr__", "repr", "self");
        method(c, IoBuffered.class, "__getstate__", "getstate", "self");
        method(c, IoBuffered.class, "_dealloc_warn", "deallocWarn", "self,source");
        if (rd) {
            method(c, IoBuffered.class, "read", "read", "self,size", defaults("size", -1L));
            method(c, IoBuffered.class, "read1", "read1", "self,size", defaults("size", -1L));
            method(c, IoBuffered.class, "readinto", "readinto", "self,buffer");
            method(c, IoBuffered.class, "readinto1", "readinto1", "self,buffer");
            method(c, IoBuffered.class, "readline", "readline", "self,size", defaults("size", -1L));
            method(c, IoBuffered.class, "peek", "peek", "self,size", defaults("size", 0L));
        }
        if (wr) {
            method(c, IoBuffered.class, "write", "write", "self,buffer");
            method(c, IoBuffered.class, "truncate", "truncate", "self,pos", defaults("pos", null));
        }
        PyRuntime.classAddProperty(c, "raw", ME, "rawGet", null);
        PyRuntime.classAddProperty(c, "closed", ME, "closedGet", null);
        PyRuntime.classAddProperty(c, "name", ME, "nameGet", null);
        PyRuntime.classAddProperty(c, "mode", ME, "modeGet", null);
        PyRuntime.classAddProperty(c, "_finalizing", ME, "finalizing", null);
        finish(c);
        m.attrs.put(name, c);
        return c;
    }

    static void define(PyModule m) {
        reader = make(m, "BufferedReader", true, false);
        writer = make(m, "BufferedWriter", false, true);
        random = make(m, "BufferedRandom", true, true);
        pair = NativeTypes.define("_io", "BufferedRWPair", IoBase.buffered);
        method(pair, IoBuffered.class, "__init__", "pairInit", "self,reader,writer,buffer_size", defaults("buffer_size", (long) IoUtil.DEFAULT_BUFFER_SIZE));
        for (String n : new String[]{"read", "peek", "read1", "readinto", "readinto1", "readline", "write", "flush", "readable", "writable", "close", "isatty"})
            pairDelegate(n);
        method(pair, IoBuffered.class, "seekable", "pairSeekable", "self");
        method(pair, IoBuffered.class, "__getstate__", "getstate", "self");
        PyRuntime.classAddProperty(pair, "closed", ME, "pairClosed", null);
        finish(pair);
        m.attrs.put("BufferedRWPair", pair);
    }

    private static void pairDelegate(String n) {
        String params = switch (n) {
            case "read", "read1", "readline", "peek" -> "self,size";
            case "readinto", "readinto1" -> "self,buffer";
            case "write" -> "self,buffer";
            default -> "self";
        };
        PyDict d = switch (n) {
            case "read", "read1", "readline" -> defaults("size", -1L);
            case "peek" -> defaults("size", 0L);
            default -> new PyDict();
        };
        PyRuntime.classAddMethodEx(pair, n, IoBuffered.class, "pair_" + n, "instance", "", params, "", null, null, d, "<native>", 0L);
        PyRuntime.classAddAttr(pair, n, pair.methods.get(n).function);
    }

    // ---- state access ---------------------------------------------------------------------------------

    static B st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof B b) return b;
        if (self instanceof PyInstance i && (i.cls.mro.contains(reader) || i.cls.mro.contains(writer) || i.cls.mro.contains(random))) {
            B b = new B(); setState(self, b); return b;
        }
        throw new PyException("TypeError", "descriptor requires a '_io._Buffered' object but received '" + PyRuntime.typeName(self) + "'");
    }

    static B live(Object self) {
        B b = st(self);
        if (b.raw == null) throw b.ops == null ? IoUtil.uninitialized() : new PyException("ValueError", "raw stream has been detached");
        return b;
    }

    static boolean rawClosed(B b) {
        if (b.ops instanceof FileOps f) return f.f.fd < 0;
        return PyRuntime.truth(PyRuntime.getattr(b.raw, "closed"));
    }

    static B open(Object self, String msg) {
        B b = live(self);
        if (rawClosed(b)) throw new PyException("ValueError", msg);
        return b;
    }

    private static Object init(Object self, Object raw, Object size, boolean rd, boolean wr) {
        B b = st(self);
        long sz = IoUtil.asLong(size);
        if (rd && wr) {
            if (!PyRuntime.truth(PyRuntime.callMethod(raw, "seekable", new Object[0]))) throw IoUtil.unsupported("File or stream is not seekable.");
        }
        if (rd && !PyRuntime.truth(PyRuntime.callMethod(raw, "readable", new Object[0]))) throw IoUtil.unsupported("File or stream is not readable.");
        if (wr && !PyRuntime.truth(PyRuntime.callMethod(raw, "writable", new Object[0]))) throw IoUtil.unsupported("File or stream is not writable.");
        if (sz <= 0) throw new PyException("ValueError", "buffer size must be strictly positive");
        b.raw = raw; b.size = (int) Math.min(sz, 1 << 28);
        b.ops = raw instanceof PyInstance pi && pi.cls == IoFile.cls && pi.slotValues.get(STATE) instanceof IoFile.St f ? new FileOps(f) : new ObjOps(raw);
        b.isReader = rd; b.isWriter = wr;
        b.rbuf = new byte[0]; b.rpos = b.rend = 0; b.wbuf = wr ? new byte[b.size] : null; b.wlen = 0;
        return null;
    }

    public static Object initReader(Object self, Object raw, Object size) { return init(self, raw, size, true, false); }
    public static Object initWriter(Object self, Object raw, Object size) { return init(self, raw, size, false, true); }
    public static Object initRandom(Object self, Object raw, Object size) { return init(self, raw, size, true, true); }

    // ---- reading ----------------------------------------------------------------------------------------

    private static int avail(B b) { return b.rend - b.rpos; }

    /** Drop the read-ahead, moving the raw position back to the logical one. */
    private static void dropRead(B b) {
        int a = avail(b);
        b.rpos = b.rend = 0;
        if (a > 0) b.ops.seek(-a, 1);
    }

    private static int fill(B b) {
        if (b.wlen > 0) flushWrite(b);
        b.rpos = 0; b.rend = 0;
        byte[] d = b.ops.read(b.size);
        b.rbuf = d; b.rend = d.length;
        return d.length;
    }

    private static byte[] take(B b, int n) {
        byte[] out = Arrays.copyOfRange(b.rbuf, b.rpos, b.rpos + n);
        b.rpos += n;
        return out;
    }

    public static Object read(Object self, Object size) {
        B b = open(self, "read of closed file");
        long n = IoUtil.sizeArg(size);
        if (n < -1) throw new PyException("ValueError", "read length must be non-negative or -1");
        if (b.wlen > 0) flushWrite(b);
        if (n == -1) return new PyBytes(readAll(b));
        if (n == 0) return new PyBytes(new byte[0]);
        int a = avail(b);
        if (a >= n) return new PyBytes(take(b, (int) n));
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(n, 1 << 20));
        if (a > 0) { out.write(b.rbuf, b.rpos, a); b.rpos += a; }
        long remaining = n - a;
        while (remaining > 0) {
            if (remaining >= b.size) {
                byte[] d = b.ops.read((int) Math.min(remaining, 1 << 26));
                if (d.length == 0) break;
                out.write(d, 0, d.length);
                remaining -= d.length;
            } else {
                if (fill(b) == 0) break;
                int t = (int) Math.min(remaining, avail(b));
                out.write(b.rbuf, b.rpos, t); b.rpos += t; remaining -= t;
            }
        }
        return new PyBytes(out.toByteArray());
    }

    private static byte[] readAll(B b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int a = avail(b);
        if (a > 0) { out.write(b.rbuf, b.rpos, a); b.rpos = b.rend = 0; }
        if (b.ops instanceof FileOps f) {
            while (true) {
                long hint = 8192;
                try { hint = Math.max(8192, IoFd.size(f.f.fd) - IoFd.tell(f.f.fd) + 1); } catch (PyException ignored) { }
                byte[] d = IoFd.read(f.f.fd, (int) Math.min(hint, 1 << 26));
                if (d.length == 0) break;
                out.write(d, 0, d.length);
            }
        } else if (PyRuntime.truth(PyRuntime.hasattr(b.raw, "readall"))) {
            Object r = PyRuntime.callMethod(b.raw, "readall", new Object[0]);
            if (r != null) {
                if (!(NativeSubclass.unwrap(r) instanceof PyByteSequence)) throw new PyException("TypeError", "readall() should return bytes");
                byte[] d = IoUtil.bytesLike(r);
                out.write(d, 0, d.length);
            }
        } else {
            while (true) {
                Object r = PyRuntime.callMethod(b.raw, "read", new Object[0]);
                if (r == null) break;
                byte[] d = IoUtil.bytesLike(r);
                if (d.length == 0) break;
                out.write(d, 0, d.length);
            }
        }
        return out.toByteArray();
    }

    public static Object read1(Object self, Object size) {
        B b = open(self, "read of closed file");
        long n = IoUtil.sizeArg(size);
        if (n < -1) throw new PyException("ValueError", "read length must be non-negative or -1");
        if (n == -1) n = b.size;
        if (n == 0) return new PyBytes(new byte[0]);
        if (b.wlen > 0) flushWrite(b);
        int a = avail(b);
        if (a > 0) return new PyBytes(take(b, (int) Math.min(a, n)));
        dropRead(b);
        return new PyBytes(b.ops.read((int) Math.min(n, b.size)));
    }

    public static Object readinto(Object self, Object buffer) { return readIntoImpl(self, buffer, false); }
    public static Object readinto1(Object self, Object buffer) { return readIntoImpl(self, buffer, true); }

    private static Object readIntoImpl(Object self, Object buffer, boolean one) {
        int n = IoUtil.bufferSize(buffer);
        byte[] d = IoUtil.bytesLike(one ? read1(self, (long) n) : read(self, (long) n));
        IoUtil.fill(buffer, d, 0, d.length);
        return (long) d.length;
    }

    public static Object peek(Object self, Object size) {
        B b = open(self, "peek of closed file");
        if (b.wlen > 0) flushWrite(b);
        if (avail(b) == 0) fill(b);
        return new PyBytes(Arrays.copyOfRange(b.rbuf, b.rpos, b.rend));
    }

    public static Object readline(Object self, Object size) {
        B b = open(self, "readline of closed file");
        long limit = IoUtil.sizeArg(size);
        if (b.wlen > 0) flushWrite(b);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (limit < 0 || out.size() < limit) {
            if (avail(b) == 0 && fill(b) == 0) break;
            int end = b.rpos, stop = b.rend;
            if (limit >= 0) stop = (int) Math.min(stop, b.rpos + (limit - out.size()));
            boolean found = false;
            while (end < stop) { if (b.rbuf[end++] == '\n') { found = true; break; } }
            out.write(b.rbuf, b.rpos, end - b.rpos);
            b.rpos = end;
            if (found) break;
        }
        return new PyBytes(out.toByteArray());
    }

    // ---- writing ----------------------------------------------------------------------------------------

    static void flushWrite(B b) {
        int off = 0;
        try {
            while (off < b.wlen) {
                int n = b.ops.write(b.wbuf, off, b.wlen - off);
                if (n <= 0) break;
                off += n;
            }
        } finally {
            if (off > 0) { System.arraycopy(b.wbuf, off, b.wbuf, 0, b.wlen - off); b.wlen -= off; }
            if (b.wlen == 0 && b.reg) { b.reg = false; IoUtil.clean(b); }
        }
    }

    public static Object write(Object self, Object buffer) {
        B b = open(self, "write to closed file");
        byte[] d = IoUtil.bytesLike(buffer);
        writeBytes(b, d);
        return (long) d.length;
    }

    /** Append to the write buffer, flushing to the raw stream when it is full. */
    static void writeBytes(B b, byte[] d) {
        if (avail(b) > 0) dropRead(b);
        if (d.length <= b.size - b.wlen) {
            System.arraycopy(d, 0, b.wbuf, b.wlen, d.length);
            b.wlen += d.length;
            if (!b.reg && b.wlen > 0) { b.reg = true; IoUtil.dirty(b); }
            return;
        }
        flushWrite(b);
        if (d.length >= b.size) {
            int off = 0;
            while (off < d.length) off += b.ops.write(d, off, d.length - off);
        } else {
            System.arraycopy(d, 0, b.wbuf, 0, d.length);
            b.wlen = d.length;
            if (!b.reg) { b.reg = true; IoUtil.dirty(b); }
        }
    }

    public static Object flush(Object self) {
        B b = open(self, "flush of closed file");
        if (b.wlen > 0) flushWrite(b);
        return null;
    }

    public static Object truncate(Object self, Object pos) {
        B b = open(self, "truncate of closed file");
        if (b.wlen > 0) flushWrite(b);
        if (avail(b) > 0) dropRead(b);
        Object p = pos == null ? (Object) b.ops.tell() : pos;
        return PyRuntime.callMethod(b.raw, "truncate", new Object[]{p});
    }

    // ---- positioning ------------------------------------------------------------------------------------

    public static Object seek(Object self, Object target, Object whence) {
        B b = open(self, "seek of closed file");
        Object t = NativeSubclass.unwrap(target);
        if (t instanceof Double) throw new PyException("TypeError", "'float' object cannot be interpreted as an integer");
        long w = IoUtil.asLong(whence);
        if (w < 0 || w > 2) throw new PyException("ValueError", "whence value " + w + " unsupported");
        if (!PyRuntime.truth(PyRuntime.callMethod(b.raw, "seekable", new Object[0]))) throw IoUtil.unsupported("File or stream is not seekable.");
        long off = IoUtil.asLong(t);
        if (b.wlen > 0) flushWrite(b);
        if (w == 1) off -= avail(b);
        b.rpos = b.rend = 0;
        long r = b.ops.seek(off, (int) w);
        if (r < 0) throw new PyException("OSError", "Raw stream returned invalid position " + r);
        return r;
    }

    public static Object tell(Object self) {
        B b = live(self);
        long raw = b.ops.tell();
        return raw - avail(b) + b.wlen;
    }

    // ---- delegation to the raw stream -------------------------------------------------------------------

    public static Object fileno(Object self) { return PyRuntime.callMethod(live(self).raw, "fileno", new Object[0]); }
    public static Object isatty(Object self) { return PyRuntime.callMethod(live(self).raw, "isatty", new Object[0]); }
    public static Object readable(Object self) { return PyRuntime.callMethod(live(self).raw, "readable", new Object[0]); }
    public static Object writable(Object self) { return PyRuntime.callMethod(live(self).raw, "writable", new Object[0]); }
    public static Object seekable(Object self) { return PyRuntime.callMethod(live(self).raw, "seekable", new Object[0]); }
    public static Object rawGet(Object self) { return live(self).raw; }
    public static Object closedGet(Object self) { return PyRuntime.getattr(live(self).raw, "closed"); }
    public static Object nameGet(Object self) { return PyRuntime.getattr(live(self).raw, "name"); }
    public static Object modeGet(Object self) { return PyRuntime.getattr(live(self).raw, "mode"); }
    public static Object finalizing(Object self) { return false; }
    public static Object deallocWarn(Object self, Object source) { return null; }

    public static Object close(Object self) {
        B b = live(self);
        if (rawClosed(b)) return null;
        PyException flushErr = null;
        try { if (b.wlen > 0) flushWrite(b); } catch (PyException e) { flushErr = e; }
        b.rpos = b.rend = 0;
        try { PyRuntime.callMethod(b.raw, "close", new Object[0]); }
        catch (PyException e) {
            if (flushErr != null) { e.context = flushErr.instance != null ? flushErr.instance : ExceptionGroupSupport.instanceFor(flushErr); }
            throw e;
        }
        if (flushErr != null) throw flushErr;
        return null;
    }

    public static Object detach(Object self) {
        B b = live(self);
        if (b.wlen > 0) flushWrite(b);
        Object raw = b.raw;
        b.raw = null;
        return raw;
    }

    public static Object getstate(Object self) {
        throw new PyException("TypeError", "cannot pickle '" + ((PyInstance) self).cls.name + "' instances");
    }

    public static Object repr(Object self) {
        B b = st(self);
        String t = IoFile.typeRepr(self);
        if (b.raw == null) return "<" + t + ">";
        Object name;
        try { name = PyRuntime.getattr(b.raw, "name"); }
        catch (PyException e) {
            if (PyRuntime.exceptionIsSubclass(e.typeName, "AttributeError")) return "<" + t + ">";
            throw e;
        }
        return "<" + t + " name=" + PyRuntime.pyRepr(name) + ">";
    }

    // ---- BufferedRWPair ---------------------------------------------------------------------------------

    static final class P { Object reader, writer; }

    static P pst(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof P p) return p;
        throw IoUtil.uninitialized();
    }

    public static Object pairInit(Object self, Object rd, Object wr, Object size) {
        if (!PyRuntime.truth(PyRuntime.callMethod(rd, "readable", new Object[0]))) throw IoUtil.unsupported("File or stream is not readable.");
        if (!PyRuntime.truth(PyRuntime.callMethod(wr, "writable", new Object[0]))) throw IoUtil.unsupported("File or stream is not writable.");
        P p = new P();
        p.reader = PyRuntime.instantiate(reader, new Object[]{rd, size});
        p.writer = PyRuntime.instantiate(writer, new Object[]{wr, size});
        setState(self, p);
        return null;
    }

    private static Object toReader(Object self, String m, Object... a) { return PyRuntime.callMethod(pst(self).reader, m, a); }
    private static Object toWriter(Object self, String m, Object... a) { return PyRuntime.callMethod(pst(self).writer, m, a); }

    public static Object pair_read(Object self, Object size) { return toReader(self, "read", size); }
    public static Object pair_peek(Object self, Object size) { return toReader(self, "peek", size); }
    public static Object pair_read1(Object self, Object size) { return toReader(self, "read1", size); }
    public static Object pair_readinto(Object self, Object buffer) { return toReader(self, "readinto", buffer); }
    public static Object pair_readinto1(Object self, Object buffer) { return toReader(self, "readinto1", buffer); }
    public static Object pair_readline(Object self, Object size) { return toReader(self, "readline", size); }
    public static Object pair_write(Object self, Object buffer) { return toWriter(self, "write", buffer); }
    public static Object pair_flush(Object self) { return toWriter(self, "flush"); }
    public static Object pair_readable(Object self) { return toReader(self, "readable"); }
    public static Object pair_writable(Object self) { return toWriter(self, "writable"); }
    public static Object pair_isatty(Object self) {
        return PyRuntime.truth(toWriter(self, "isatty")) ? (Object) true : toReader(self, "isatty");
    }
    public static Object pair_close(Object self) {
        PyException err = null;
        try { toWriter(self, "close"); } catch (PyException e) { err = e; }
        try { toReader(self, "close"); } catch (PyException e) { if (err == null) err = e; }
        if (err != null) throw err;
        return null;
    }
    public static Object pairSeekable(Object self) { return false; }
    public static Object pairClosed(Object self) { return PyRuntime.getattr(pst(self).writer, "closed"); }
}
