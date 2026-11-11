// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.Arrays;

/** {@code _io.TextIOWrapper}: text over a buffered binary stream, with CPython's newline handling and tell/seek cookies. */
final class IoText {
    private IoText() {}

    private static final String ME = IoText.class.getName();
    static PyClass cls;

    static final class T implements IoUtil.Pending {
        Object buffer; IoBuffered.B fast;
        String encoding, errors = "strict";
        IoCodec.Dec decoder; IoCodec.NewlineDec nldec; IoCodec.Enc encoder;
        boolean readUniversal, readTranslate, writeTranslate, lineBuffering, writeThrough, seekable, telling, hasRead1, reg;
        String readnl, writenl;
        String decoded = ""; int used;
        byte[] snapBytes; long snapFlags; boolean hasSnap;
        double b2c; int chunk = 8192;
        byte[] pbuf = new byte[256]; int plen;
        public void flushQuietly() { if (buffer != null && plen > 0) writeFlush(this); }
    }

    static void define(PyModule m) {
        cls = NativeTypes.define("_io", "TextIOWrapper", IoBase.text);
        method(cls, IoText.class, "__init__", "init", "self,buffer,encoding,errors,newline,line_buffering,write_through",
            defaults("encoding", null, "errors", null, "newline", null, "line_buffering", false, "write_through", false));
        method(cls, IoText.class, "write", "write", "self,s");
        method(cls, IoText.class, "read", "read", "self,size", defaults("size", -1L));
        method(cls, IoText.class, "readline", "readline", "self,size", defaults("size", -1L));
        method(cls, IoText.class, "__next__", "next", "self");
        method(cls, IoText.class, "flush", "flush", "self");
        method(cls, IoText.class, "close", "close", "self");
        method(cls, IoText.class, "detach", "detach", "self");
        method(cls, IoText.class, "fileno", "fileno", "self");
        method(cls, IoText.class, "isatty", "isatty", "self");
        method(cls, IoText.class, "readable", "readable", "self");
        method(cls, IoText.class, "writable", "writable", "self");
        method(cls, IoText.class, "seekable", "seekable", "self");
        method(cls, IoText.class, "tell", "tell", "self");
        method(cls, IoText.class, "seek", "seek", "self,cookie,whence", defaults("whence", 0L));
        method(cls, IoText.class, "truncate", "truncate", "self,pos", defaults("pos", null));
        methodVarKw(cls, IoText.class, "reconfigure", "reconfigure", "self", "args", "kwargs", new PyDict());
        method(cls, IoText.class, "__repr__", "repr", "self");
        method(cls, IoText.class, "__getstate__", "getstate", "self");
        PyRuntime.classAddProperty(cls, "buffer", ME, "bufferGet", null);
        PyRuntime.classAddProperty(cls, "encoding", ME, "encodingGet", null);
        PyRuntime.classAddProperty(cls, "errors", ME, "errorsGet", null);
        PyRuntime.classAddProperty(cls, "newlines", ME, "newlinesGet", null);
        PyRuntime.classAddProperty(cls, "line_buffering", ME, "lineBufferingGet", null);
        PyRuntime.classAddProperty(cls, "write_through", ME, "writeThroughGet", null);
        PyRuntime.classAddProperty(cls, "name", ME, "nameGet", null);
        PyRuntime.classAddProperty(cls, "closed", ME, "closedGet", null);
        PyRuntime.classAddProperty(cls, "_CHUNK_SIZE", ME, "chunkGet", "chunkSet");
        PyRuntime.classAddProperty(cls, "_finalizing", ME, "finalizing", null);
        finish(cls);
        m.attrs.put("TextIOWrapper", cls);
    }

    // ---- state ---------------------------------------------------------------------------------------------

    static T st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof T t) return t;
        if (self instanceof PyInstance i && i.cls.mro.contains(cls)) { T t = new T(); setState(self, t); return t; }
        throw new PyException("TypeError", "descriptor requires a '_io.TextIOWrapper' object but received '" + PyRuntime.typeName(self) + "'");
    }

    static T live(Object self) {
        T t = st(self);
        if (t.buffer == null) throw t.encoding == null ? IoUtil.uninitialized() : new PyException("ValueError", "underlying buffer has been detached");
        return t;
    }

    static boolean isClosed(T t) {
        if (t.fast != null) return IoBuffered.rawClosed(t.fast);
        return PyRuntime.truth(PyRuntime.getattr(t.buffer, "closed"));
    }

    static T open(Object self) {
        T t = live(self);
        if (isClosed(t)) throw IoUtil.closed();
        return t;
    }

    // ---- construction -----------------------------------------------------------------------------------

    private static String optStr(Object o, String what) {
        o = NativeSubclass.unwrap(o);
        if (o == null) return null;
        if (o instanceof String s) return s;
        throw new PyException("TypeError", "TextIOWrapper() argument '" + what + "' must be str or None, not " + PyRuntime.typeName(o));
    }

    public static Object init(Object self, Object buffer, Object encoding, Object errors, Object newline, Object lineBuffering, Object writeThrough) {
        T t = st(self);
        Object nl = NativeSubclass.unwrap(newline);
        if (nl != null && !(nl instanceof String)) throw new PyException("TypeError", "TextIOWrapper() argument 'newline' must be str or None, not " + PyRuntime.typeName(nl));
        String newl = (String) nl;
        if (newl != null && !(newl.isEmpty() || newl.equals("\n") || newl.equals("\r") || newl.equals("\r\n")))
            throw new PyException("ValueError", "illegal newline value: " + newl);
        String enc = optStr(encoding, "encoding"), err = optStr(errors, "errors");
        if (enc != null && enc.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character");
        if (err != null && err.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character");
        if (enc == null || enc.equals("locale")) enc = LangVersion.atLeast(3, 15) ? "utf-8" : "UTF-8";
        if (err == null) err = "strict";
        if (Codecs.nativeName(enc) == null) IoCodec.textInfo(enc);
        t.buffer = buffer; t.encoding = enc; t.errors = err;
        t.fast = buffer instanceof PyInstance bi && (bi.cls == IoBuffered.writer || bi.cls == IoBuffered.random || bi.cls == IoBuffered.reader)
            && bi.slotValues.get(STATE) instanceof IoBuffered.B b ? b : null;
        t.lineBuffering = PyRuntime.truth(lineBuffering); t.writeThrough = PyRuntime.truth(writeThrough);
        t.readUniversal = newl == null || newl.isEmpty();
        t.readTranslate = newl == null;
        t.readnl = newl;
        t.writeTranslate = newl == null || !newl.isEmpty();
        t.writenl = newl == null || newl.isEmpty() || newl.equals("\n") ? null : newl;
        t.decoded = ""; t.used = 0; t.hasSnap = false; t.plen = 0;
        boolean canRead = PyRuntime.truth(PyRuntime.callMethod(buffer, "readable", new Object[0]));
        t.hasRead1 = PyRuntime.truth(PyRuntime.hasattr(buffer, "read1"));
        t.seekable = t.telling = PyRuntime.truth(PyRuntime.callMethod(buffer, "seekable", new Object[0]));
        t.decoder = null; t.nldec = null; t.encoder = null;
        if (canRead) setupDecoder(t);
        if (PyRuntime.truth(PyRuntime.callMethod(buffer, "writable", new Object[0]))) {
            t.encoder = IoCodec.encoder(enc, err);
            if (t.seekable && t.encoder instanceof IoCodec.ObjEnc e) {
                Object cookie = PyRuntime.callMethod(buffer, "tell", new Object[0]);
                if (!(cookie instanceof Long l && l == 0L)) e.setstate(0);
            }
        }
        return null;
    }

    private static void setupDecoder(T t) {
        IoCodec.Dec d = IoCodec.decoder(t.encoding, t.errors);
        if (t.readUniversal) { t.nldec = new IoCodec.NewlineDec(d, t.readTranslate); d = t.nldec; }
        t.decoder = d;
    }

    // ---- writing ----------------------------------------------------------------------------------------

    static void writeFlush(T t) {
        if (t.plen == 0) return;
        byte[] data = Arrays.copyOf(t.pbuf, t.plen);
        t.plen = 0;
        if (t.reg) { t.reg = false; IoUtil.clean(t); }
        if (t.fast != null && t.fast.isWriter) IoBuffered.writeBytes(t.fast, data);
        else PyRuntime.callMethod(t.buffer, "write", new Object[]{new PyBytes(data)});
    }

    /** The write path shared by {@code write()} and the print fast path. Returns the code point count. */
    static long writeText(T t, String text) {
        if (t.encoder == null) throw IoUtil.unsupported("not writable");
        boolean haslf = false, needflush = false;
        if (t.writenl != null || t.lineBuffering) {
            haslf = text.indexOf('\n') >= 0;
            needflush = t.lineBuffering && (haslf || text.indexOf('\r') >= 0);
        }
        String out = text;
        if (haslf && t.writeTranslate && t.writenl != null) out = text.replace("\n", t.writenl);
        byte[] enc = t.encoder.encode(out);
        if (t.plen + enc.length > t.pbuf.length) t.pbuf = Arrays.copyOf(t.pbuf, Math.max(t.pbuf.length * 2, t.plen + enc.length));
        System.arraycopy(enc, 0, t.pbuf, t.plen, enc.length);
        t.plen += enc.length;
        if (!t.reg && t.plen > 0) { t.reg = true; IoUtil.dirty(t); }
        if (t.plen > t.chunk || needflush || t.writeThrough) writeFlush(t);
        t.decoded = ""; t.used = 0; t.hasSnap = false;
        if (t.decoder != null) t.decoder.reset();
        if (needflush) PyRuntime.callMethod(t.buffer, "flush", new Object[0]);
        return text.codePointCount(0, text.length());
    }

    public static Object write(Object self, Object s) {
        T t = open(self);
        Object o = NativeSubclass.unwrap(s);
        if (!(o instanceof String text)) throw new PyException("TypeError", "write() argument must be str, not " + PyRuntime.typeName(o));
        return writeText(t, text);
    }

    /** print()'s direct path: true when {@code file} is a plain native TextIOWrapper and the text was written. */
    static boolean fastWrite(Object file, String text) {
        if (file instanceof PyInstance i && i.cls == cls && i.slotValues.get(STATE) instanceof T t && t.buffer != null && t.encoder != null && !isClosed(t)) {
            writeText(t, text);
            return true;
        }
        return false;
    }

    public static Object flush(Object self) {
        T t = open(self);
        writeFlush(t);
        return PyRuntime.callMethod(t.buffer, "flush", new Object[0]);
    }

    // ---- reading ----------------------------------------------------------------------------------------

    private static Object bufRead(T t, long n) {
        if (n < 0 || !t.hasRead1) return PyRuntime.callMethod(t.buffer, "read", n < 0 ? new Object[0] : new Object[]{n});
        if (t.fast != null && t.fast.isReader) return IoBuffered.read1(t.buffer, n);
        return PyRuntime.callMethod(t.buffer, "read1", new Object[]{n});
    }

    private static boolean readChunk(T t) {
        if (t.decoder == null) throw IoUtil.unsupported("not readable");
        byte[] decBuf = null; long decFlags = 0;
        if (t.telling) { Object[] s = t.decoder.getstate(); decBuf = (byte[]) s[0]; decFlags = (Long) s[1]; }
        Object chunk = bufRead(t, t.chunk);
        Object c = NativeSubclass.unwrap(chunk);
        if (!(c instanceof PyByteSequence)) throw new PyException("TypeError", "underlying read1() should have returned a bytes-like object, not '" + PyRuntime.typeName(c) + "'");
        byte[] in = IoUtil.bytesLike(c);
        boolean eof = in.length == 0;
        String d = t.decoder.decode(in, eof);
        t.decoded = d; t.used = 0;
        t.b2c = d.isEmpty() ? 0.0 : (double) in.length / d.length();
        if (t.telling) {
            byte[] all = new byte[decBuf.length + in.length];
            System.arraycopy(decBuf, 0, all, 0, decBuf.length);
            System.arraycopy(in, 0, all, decBuf.length, in.length);
            t.snapBytes = all; t.snapFlags = decFlags; t.hasSnap = true;
        }
        return !eof;
    }

    private static String takeAll(T t) {
        String s = t.used == 0 ? t.decoded : t.decoded.substring(t.used);
        t.used = t.decoded.length();
        return s;
    }

    public static Object read(Object self, Object size) {
        T t = open(self);
        if (t.decoder == null) throw IoUtil.unsupported("not readable");
        long n = IoUtil.sizeArg(size);
        writeFlush(t);
        if (n < 0) {
            Object data = bufRead(t, -1);
            String d = t.decoder.decode(IoUtil.bytesLike(data), true);
            String result = takeAll(t) + d;
            t.decoded = ""; t.used = 0; t.hasSnap = false;
            return result;
        }
        StringBuilder sb = new StringBuilder();
        long need = n;
        while (need > 0) {
            if (t.used >= t.decoded.length()) {
                if (!readChunk(t)) break;
                continue;
            }
            String rest = t.decoded.substring(t.used);
            int cps = rest.codePointCount(0, rest.length());
            if (cps <= need) { sb.append(rest); t.used = t.decoded.length(); need -= cps; }
            else { int end = rest.offsetByCodePoints(0, (int) need); sb.append(rest, 0, end); t.used += end; need = 0; }
        }
        return sb.toString();
    }

    public static Object readline(Object self, Object size) {
        T t = open(self);
        if (t.decoder == null) throw IoUtil.unsupported("not readable");
        Object sz = NativeSubclass.unwrap(size);
        long limit = sz == null ? -1 : IoUtil.asLong(sz);
        writeFlush(t);
        String line = takeAll(t);
        int start = 0, endpos;
        while (true) {
            endpos = -1;
            if (t.readTranslate) {
                int pos = line.indexOf('\n', start);
                if (pos >= 0) { endpos = pos + 1; break; }
                start = line.length();
            } else if (t.readUniversal) {
                int nlpos = line.indexOf('\n', start), crpos = line.indexOf('\r', start);
                if (crpos == -1) {
                    if (nlpos == -1) start = line.length();
                    else { endpos = nlpos + 1; break; }
                } else if (nlpos == -1) {
                    endpos = crpos + 1; break;
                } else if (nlpos < crpos) { endpos = nlpos + 1; break; }
                else if (nlpos == crpos + 1) { endpos = crpos + 2; break; }
                else { endpos = crpos + 1; break; }
            } else {
                int pos = line.indexOf(t.readnl, start);
                if (pos >= 0) { endpos = pos + t.readnl.length(); break; }
                start = Math.max(start, line.length() - t.readnl.length() + 1);
            }
            if (limit >= 0 && line.codePointCount(0, line.length()) >= limit) { endpos = line.offsetByCodePoints(0, (int) limit); break; }
            boolean more;
            do { more = readChunk(t); } while (more && t.decoded.isEmpty());
            if (!t.decoded.isEmpty()) line += takeAll(t);
            else { t.decoded = ""; t.used = 0; t.hasSnap = false; return line; }
        }
        if (limit >= 0 && line.codePointCount(0, endpos) > limit) endpos = line.offsetByCodePoints(0, (int) limit);
        t.used -= line.length() - endpos;
        return line.substring(0, endpos);
    }

    public static Object next(Object self) {
        T t = open(self);
        t.telling = false;
        Object line = readline(self, -1L);
        if (((String) line).isEmpty()) {
            t.hasSnap = false;
            t.telling = t.seekable;
            throw new PyException("StopIteration", null);
        }
        return line;
    }

    // ---- tell / seek ------------------------------------------------------------------------------------

    private static final BigInteger MASK = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private static Object pack(long pos, long decFlags, long bytesToFeed, long charsToSkip, boolean needEof) {
        BigInteger c = BigInteger.valueOf(pos)
            .or(BigInteger.valueOf(decFlags).shiftLeft(64)).or(BigInteger.valueOf(bytesToFeed).shiftLeft(128))
            .or(BigInteger.valueOf(charsToSkip).shiftLeft(192)).or(needEof ? BigInteger.ONE.shiftLeft(256) : BigInteger.ZERO);
        return c.bitLength() < 64 ? (Object) c.longValue() : c;
    }

    public static Object tell(Object self) {
        T t = open(self);
        if (!t.seekable) throw IoUtil.unsupported("underlying stream is not seekable");
        if (!t.telling) throw new PyException("OSError", "telling position disabled by next() call");
        writeFlush(t);
        PyRuntime.callMethod(t.buffer, "flush", new Object[0]);
        long position = IoUtil.asLong(PyRuntime.callMethod(t.buffer, "tell", new Object[0]));
        IoCodec.Dec decoder = t.decoder;
        if (decoder == null || !t.hasSnap) {
            if (!t.decoded.isEmpty() && t.used < t.decoded.length()) throw new PyException("AssertionError", "pending decoded text");
            return position;
        }
        long decFlags = t.snapFlags;
        byte[] next = t.snapBytes;
        position -= next.length;
        long charsToSkip = t.used;
        if (charsToSkip == 0) return pack(position, decFlags, 0, 0, false);
        Object[] saved = decoder.getstate();
        try {
            int skipBytes = (int) (t.b2c * charsToSkip);
            int skipBack = 1;
            boolean broke = false;
            while (skipBytes > 0) {
                decoder.setstate(new byte[0], decFlags);
                int n = decoder.decode(Arrays.copyOfRange(next, 0, Math.min(skipBytes, next.length)), false).length();
                if (n <= charsToSkip) {
                    Object[] s = decoder.getstate();
                    if (((byte[]) s[0]).length == 0) { decFlags = (Long) s[1]; charsToSkip -= n; broke = true; break; }
                    skipBytes -= ((byte[]) s[0]).length;
                    skipBack = 1;
                } else { skipBytes -= skipBack; skipBack *= 2; }
            }
            if (!broke) skipBytes = 0;
            decoder.setstate(new byte[0], decFlags);
            long startPos = position + skipBytes, startFlags = decFlags;
            if (charsToSkip == 0) return pack(startPos, startFlags, 0, 0, false);
            long bytesFed = 0, charsDecoded = 0;
            boolean needEof = false, hitBreak = false;
            for (int i = skipBytes; i < next.length; i++) {
                bytesFed++;
                charsDecoded += decoder.decode(new byte[]{next[i]}, false).length();
                Object[] s = decoder.getstate();
                decFlags = (Long) s[1];
                if (((byte[]) s[0]).length == 0 && charsDecoded <= charsToSkip) {
                    startPos += bytesFed; charsToSkip -= charsDecoded;
                    startFlags = decFlags; bytesFed = 0; charsDecoded = 0;
                }
                if (charsDecoded >= charsToSkip) { hitBreak = true; break; }
            }
            if (!hitBreak) {
                charsDecoded += decoder.decode(new byte[0], true).length();
                needEof = true;
                if (charsDecoded < charsToSkip) throw new PyException("OSError", "can't reconstruct logical file position");
            }
            return pack(startPos, startFlags, bytesFed, charsToSkip, needEof);
        } finally {
            decoder.setstate((byte[]) saved[0], (Long) saved[1]);
        }
    }

    public static Object seek(Object self, Object cookieObj, Object whenceObj) {
        T t = open(self);
        if (!t.seekable) throw IoUtil.unsupported("underlying stream is not seekable");
        Object c0 = NativeSubclass.unwrap(cookieObj);
        if (c0 instanceof Double) throw new PyException("TypeError", "an integer is required");
        BigInteger cookie = BuiltinsExtra.rangeIndex(c0);
        long whence = IoUtil.asLong(whenceObj);
        if (whence == 1) {
            if (cookie.signum() != 0) throw IoUtil.unsupported("can't do nonzero cur-relative seeks");
            Object p = tell(self);
            cookie = BuiltinsExtra.rangeIndex(p);
            whence = 0;
        } else if (whence == 2) {
            if (cookie.signum() != 0) throw IoUtil.unsupported("can't do nonzero end-relative seeks");
            flush(self);
            Object pos = PyRuntime.callMethod(t.buffer, "seek", new Object[]{0L, 2L});
            t.decoded = ""; t.used = 0; t.hasSnap = false;
            if (t.decoder != null) t.decoder.reset();
            return pos;
        }
        if (whence != 0) throw new PyException("ValueError", "unsupported whence (" + whence + ")");
        if (cookie.signum() < 0) throw new PyException("ValueError", "negative seek position " + cookie);
        flush(self);
        long startPos = cookie.and(MASK).longValue();
        long decFlags = cookie.shiftRight(64).and(MASK).longValue();
        long bytesToFeed = cookie.shiftRight(128).and(MASK).longValue();
        long charsToSkip = cookie.shiftRight(192).and(MASK).longValue();
        boolean needEof = cookie.shiftRight(256).signum() != 0;
        PyRuntime.callMethod(t.buffer, "seek", new Object[]{startPos});
        t.decoded = ""; t.used = 0; t.hasSnap = false;
        if (cookie.signum() == 0 && t.decoder != null) t.decoder.reset();
        else if (t.decoder != null || decFlags != 0 || charsToSkip != 0) {
            if (t.decoder == null) setupDecoder(t);
            t.decoder.setstate(new byte[0], decFlags);
            t.snapBytes = new byte[0]; t.snapFlags = decFlags; t.hasSnap = true;
        }
        if (charsToSkip != 0) {
            byte[] in = IoUtil.bytesLike(PyRuntime.callMethod(t.buffer, "read", new Object[]{bytesToFeed}));
            t.decoded = t.decoder.decode(in, needEof); t.used = 0;
            t.snapBytes = in; t.snapFlags = decFlags; t.hasSnap = true;
            if (t.decoded.length() < charsToSkip) throw new PyException("OSError", "can't restore logical file position");
            t.used = (int) charsToSkip;
        }
        if (t.encoder != null) { if (startPos != 0) t.encoder.setstate(0); else t.encoder.reset(); }
        return cookie.bitLength() < 64 ? (Object) cookie.longValue() : cookie;
    }

    // ---- misc methods -----------------------------------------------------------------------------------

    public static Object close(Object self) {
        T t = live(self);
        if (isClosed(t)) return null;
        PyException flushErr = null;
        try { flush(self); } catch (PyException e) { flushErr = e; }
        try { PyRuntime.callMethod(t.buffer, "close", new Object[0]); }
        catch (PyException e) {
            if (flushErr != null) e.context = ExceptionGroupSupport.instanceFor(flushErr);
            throw e;
        }
        if (flushErr != null) throw flushErr;
        return null;
    }

    public static Object detach(Object self) {
        T t = live(self);
        flush(self);
        Object b = t.buffer;
        t.buffer = null; t.fast = null;
        return b;
    }

    public static Object fileno(Object self) { return PyRuntime.callMethod(live(self).buffer, "fileno", new Object[0]); }
    public static Object isatty(Object self) { return PyRuntime.callMethod(live(self).buffer, "isatty", new Object[0]); }
    public static Object readable(Object self) { return PyRuntime.callMethod(live(self).buffer, "readable", new Object[0]); }
    public static Object writable(Object self) { return PyRuntime.callMethod(live(self).buffer, "writable", new Object[0]); }
    public static Object seekable(Object self) { return PyRuntime.callMethod(live(self).buffer, "seekable", new Object[0]); }

    public static Object truncate(Object self, Object pos) {
        T t = open(self);
        flush(self);
        return PyRuntime.callMethod(t.buffer, "truncate", new Object[]{pos});
    }

    public static Object bufferGet(Object self) { return live(self).buffer; }
    public static Object encodingGet(Object self) { T t = st(self); if (t.encoding == null) throw IoUtil.uninitialized(); return t.encoding; }
    public static Object errorsGet(Object self) { T t = st(self); if (t.encoding == null) throw IoUtil.uninitialized(); return t.errors; }
    public static Object newlinesGet(Object self) { T t = live(self); return t.nldec == null ? null : t.nldec.newlines(); }
    public static Object lineBufferingGet(Object self) { return live(self).lineBuffering; }
    public static Object writeThroughGet(Object self) { return live(self).writeThrough; }
    public static Object nameGet(Object self) { return PyRuntime.getattr(live(self).buffer, "name"); }
    public static Object closedGet(Object self) { return PyRuntime.getattr(live(self).buffer, "closed"); }
    public static Object chunkGet(Object self) { return (long) live(self).chunk; }
    public static Object chunkSet(Object self, Object v) {
        long n = IoUtil.asLong(v);
        if (n <= 0) throw new PyException("ValueError", "a strictly positive integer is required");
        live(self).chunk = (int) n;
        return null;
    }
    public static Object finalizing(Object self) { return false; }
    public static Object getstate(Object self) { throw new PyException("TypeError", "cannot pickle 'TextIOWrapper' instances"); }

    public static Object repr(Object self) {
        T t = st(self);
        StringBuilder sb = new StringBuilder("<").append(IoFile.typeRepr(self));
        for (String attr : new String[]{"name", "mode"}) {
            try { Object v = PyRuntime.getattr(self, attr); sb.append(' ').append(attr).append('=').append(PyRuntime.pyRepr(v)); }
            catch (PyException e) { if (!PyRuntime.exceptionIsSubclass(e.typeName, "AttributeError")) throw e; }
        }
        sb.append(" encoding=").append(PyRuntime.pyRepr(t.encoding)).append('>');
        return sb.toString();
    }

    public static Object reconfigure(Object self, Object args, Object kwargs) {
        T t = live(self);
        PyDict kw = (PyDict) kwargs;
        if (!((PyTuple) args).items.isEmpty()) throw new PyException("TypeError", "reconfigure() takes 0 positional arguments but " + ((PyTuple) args).items.size() + " were given");
        for (Object k : kw.keySet())
            if (!java.util.Set.of("encoding", "errors", "newline", "line_buffering", "write_through").contains(k))
                throw new PyException("TypeError", "reconfigure() got an unexpected keyword argument '" + k + "'");
        Object enc = kw.get("encoding"), err = kw.get("errors"), nl = kw.containsKey("newline") ? kw.get("newline") : PyRuntime.ELLIPSIS;
        Object lb = kw.get("line_buffering"), wt = kw.get("write_through");
        boolean started = t.hasSnap || !t.decoded.isEmpty();
        boolean newEnc = enc != null || err != null || nl != PyRuntime.ELLIPSIS;
        if (newEnc && started && t.decoder != null) throw IoUtil.unsupported("It is not possible to set the encoding or newline of stream after the first read");
        String e2 = t.encoding, r2 = t.errors;
        if (enc != null) { e2 = optStr(enc, "encoding"); }
        if (err != null) r2 = optStr(err, "errors");
        else if (enc != null) r2 = "strict";
        if (nl != PyRuntime.ELLIPSIS) {
            Object n = NativeSubclass.unwrap(nl);
            if (n != null && !(n instanceof String s && (s.isEmpty() || s.equals("\n") || s.equals("\r") || s.equals("\r\n"))))
                throw new PyException("ValueError", "illegal newline value: " + n);
            String newl = (String) n;
            t.readUniversal = newl == null || newl.isEmpty(); t.readTranslate = newl == null; t.readnl = newl;
            t.writeTranslate = newl == null || !newl.isEmpty();
            t.writenl = newl == null || newl.isEmpty() || newl.equals("\n") ? null : newl;
        }
        flush(self);
        if (lb != null) t.lineBuffering = PyRuntime.truth(lb);
        if (wt != null) t.writeThrough = PyRuntime.truth(wt);
        if (newEnc) {
            if (Codecs.nativeName(e2) == null) IoCodec.textInfo(e2);
            t.encoding = e2; t.errors = r2;
            if (t.decoder != null) { t.nldec = null; setupDecoder(t); }
            if (t.encoder != null) t.encoder = IoCodec.encoder(e2, r2);
        }
        return null;
    }
}
