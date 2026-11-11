// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Incremental decoders and encoders for the text layer, and {@code IncrementalNewlineDecoder}. utf-8, ascii and
 * latin-1 run natively; every other codec goes through the incremental classes of its CodecInfo.
 */
final class IoCodec {
    private IoCodec() {}

    private static final String ME = IoCodec.class.getName();
    static PyClass newlineDecoderClass;

    // ---- decoders -----------------------------------------------------------------------------------------

    interface Dec {
        String decode(byte[] in, boolean fin);
        /** (pending bytes, flags) */
        Object[] getstate();
        void setstate(byte[] pending, long flags);
        void reset();
    }

    static final class NativeDec implements Dec {
        final String name, errors;
        byte[] pending = new byte[0];
        NativeDec(String name, String errors) { this.name = name; this.errors = errors; }
        public String decode(byte[] in, boolean fin) {
            byte[] d = in;
            if (pending.length > 0) { d = new byte[pending.length + in.length]; System.arraycopy(pending, 0, d, 0, pending.length); System.arraycopy(in, 0, d, pending.length, in.length); }
            if (!name.equals("utf-8")) { pending = new byte[0]; return Codecs.decodeNative(d, name, errors, true, new int[1], new int[1]); }
            int[] used = new int[1];
            String s = CodecsCore.decodeUtf8(d, errors, fin, used);
            pending = used[0] < d.length ? Arrays.copyOfRange(d, used[0], d.length) : new byte[0];
            return s;
        }
        public Object[] getstate() { return new Object[]{pending, 0L}; }
        public void setstate(byte[] p, long flags) { pending = p; }
        public void reset() { pending = new byte[0]; }
    }

    /** A decoder object from a codec's {@code incrementaldecoder}. */
    static final class ObjDec implements Dec {
        final Object obj;
        ObjDec(Object obj) { this.obj = obj; }
        String decodeObj(Object input, boolean fin) {
            Object r = PyRuntime.callMethod(obj, "decode", new Object[]{input, fin});
            r = NativeSubclass.unwrap(r);
            if (!(r instanceof String s)) throw new PyException("TypeError", "decoder should return a string result, not '" + PyRuntime.typeName(r) + "'");
            return s;
        }
        public String decode(byte[] in, boolean fin) { return decodeObj(new PyBytes(in), fin); }
        public Object[] getstate() {
            Object r = PyRuntime.callMethod(obj, "getstate", new Object[0]);
            if (!(r instanceof PyTuple t) || t.items.size() != 2) throw new PyException("TypeError", "illegal decoder state");
            return new Object[]{IoUtil.bytesLike(t.items.get(0)), IoUtil.asLong(t.items.get(1))};
        }
        public void setstate(byte[] p, long flags) { PyRuntime.callMethod(obj, "setstate", new Object[]{tuple(new PyBytes(p), flags)}); }
        public void reset() { PyRuntime.callMethod(obj, "reset", new Object[0]); }
    }

    static String normalizeEncoding(String enc) {
        String n = Codecs.nativeName(enc);
        return n == null ? null : n;
    }

    private static boolean nativeText(String n) { return n != null && (n.equals("utf-8") || n.equals("ascii") || n.equals("latin-1")); }

    /** The incremental decoder for {@code encoding}; a LookupError for an unknown one. */
    static Dec decoder(String encoding, String errors) {
        String n = Codecs.nativeName(encoding);
        if (nativeText(n)) return new NativeDec(n, errors);
        Object info = textInfo(encoding);
        Object cls = PyRuntime.getattr(info, "incrementaldecoder");
        if (cls == null) throw new PyException("LookupError", "codec for '" + encoding + "' has no incremental decoder");
        return new ObjDec(PyRuntime.callFunction(cls, new ArrayList<Object>(List.of(errors)), new LinkedHashMap<Object,Object>()));
    }

    static Object textInfo(String encoding) {
        Object info = Codecs.lookup(encoding);
        if (PyRuntime.truth(PyRuntime.hasattr(info, "_is_text_encoding")) && !PyRuntime.truth(PyRuntime.getattr(info, "_is_text_encoding")))
            throw new PyException("LookupError", "'" + encoding + "' is not a text encoding; use codecs.open() to handle arbitrary codecs");
        return info;
    }

    // ---- encoders -----------------------------------------------------------------------------------------

    interface Enc {
        byte[] encode(String s);
        void reset();
        void setstate(long state);
    }

    static final class NativeEnc implements Enc {
        final String name, errors;
        NativeEnc(String name, String errors) { this.name = name; this.errors = errors; }
        public byte[] encode(String s) { return Codecs.encodeNative(s, name, errors); }
        public void reset() { }
        public void setstate(long state) { }
    }

    static final class ObjEnc implements Enc {
        final Object obj;
        ObjEnc(Object obj) { this.obj = obj; }
        public byte[] encode(String s) {
            Object r = NativeSubclass.unwrap(PyRuntime.callMethod(obj, "encode", new Object[]{s}));
            if (r instanceof PyBytes b) return b.data;
            if (r instanceof PyByteSequence q) return q.toByteArray();
            throw new PyException("TypeError", "encoder should return a bytes object, not '" + PyRuntime.typeName(r) + "'");
        }
        public void reset() { PyRuntime.callMethod(obj, "reset", new Object[0]); }
        public void setstate(long state) { PyRuntime.callMethod(obj, "setstate", new Object[]{state}); }
    }

    static Enc encoder(String encoding, String errors) {
        String n = Codecs.nativeName(encoding);
        if (nativeText(n)) return new NativeEnc(n, errors);
        Object info = textInfo(encoding);
        Object cls = PyRuntime.getattr(info, "incrementalencoder");
        if (cls == null) throw new PyException("LookupError", "codec for '" + encoding + "' has no incremental encoder");
        return new ObjEnc(PyRuntime.callFunction(cls, new ArrayList<Object>(List.of(errors)), new LinkedHashMap<Object,Object>()));
    }

    // ---- IncrementalNewlineDecoder --------------------------------------------------------------------------

    static final int SEEN_CR = 1, SEEN_LF = 2, SEEN_CRLF = 4;

    /** Universal-newline filter over a decoder: translates when asked and records which newline kinds were seen. */
    static final class NewlineDec implements Dec {
        final Dec inner; final boolean translate;
        int seen; boolean pendingCr;
        NewlineDec(Dec inner, boolean translate) { this.inner = inner; this.translate = translate; }

        public String decode(byte[] in, boolean fin) { return process(inner.decode(in, fin), fin); }

        String process(String output, boolean fin) {
            if (pendingCr && (fin || !output.isEmpty())) { output = "\r" + output; pendingCr = false; }
            if (!fin && !output.isEmpty() && output.charAt(output.length() - 1) == '\r') { output = output.substring(0, output.length() - 1); pendingCr = true; }
            if (output.indexOf('\r') < 0 && output.indexOf('\n') < 0) return output;
            int n = output.length();
            StringBuilder sb = translate ? new StringBuilder(n) : null;
            for (int i = 0; i < n; i++) {
                char c = output.charAt(i);
                if (c == '\r') {
                    if (i + 1 < n && output.charAt(i + 1) == '\n') { seen |= SEEN_CRLF; if (translate) sb.append('\n'); i++; }
                    else { seen |= SEEN_CR; if (translate) sb.append('\n'); }
                } else {
                    if (c == '\n') seen |= SEEN_LF;
                    if (sb != null) sb.append(c);
                }
            }
            return translate ? sb.toString() : output;
        }

        public Object[] getstate() {
            Object[] s = inner == null ? new Object[]{new byte[0], 0L} : inner.getstate();
            long flag = ((Long) s[1]) << 1;
            if (pendingCr) flag |= 1;
            return new Object[]{s[0], flag};
        }
        public void setstate(byte[] p, long flags) {
            pendingCr = (flags & 1) != 0;
            if (inner != null) inner.setstate(p, flags >> 1);
        }
        public void reset() { seen = 0; pendingCr = false; if (inner != null) inner.reset(); }

        Object newlines() {
            switch (seen) {
                case 0: return null;
                case SEEN_LF: return "\n";
                case SEEN_CR: return "\r";
                case SEEN_CRLF: return "\r\n";
                case SEEN_CR | SEEN_LF: return tuple("\r", "\n");
                case SEEN_CR | SEEN_CRLF: return tuple("\r", "\r\n");
                case SEEN_LF | SEEN_CRLF: return tuple("\n", "\r\n");
                default: return tuple("\r", "\n", "\r\n");
            }
        }
    }

    // ---- the Python class ---------------------------------------------------------------------------------

    static final class NdState { NewlineDec nl; ObjDec obj; }

    static void define(PyModule m) {
        newlineDecoderClass = NativeTypes.define("_io", "IncrementalNewlineDecoder", null);
        method(newlineDecoderClass, IoCodec.class, "__init__", "ndInit", "self,decoder,translate,errors", defaults("errors", "strict"));
        method(newlineDecoderClass, IoCodec.class, "decode", "ndDecode", "self,input,final", defaults("final", false));
        method(newlineDecoderClass, IoCodec.class, "getstate", "ndGetstate", "self");
        method(newlineDecoderClass, IoCodec.class, "setstate", "ndSetstate", "self,state");
        method(newlineDecoderClass, IoCodec.class, "reset", "ndReset", "self");
        PyRuntime.classAddProperty(newlineDecoderClass, "newlines", ME, "ndNewlines", null);
        finish(newlineDecoderClass);
        m.attrs.put("IncrementalNewlineDecoder", newlineDecoderClass);
    }

    private static NdState nd(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof NdState s) return s;
        throw new PyException("ValueError", "IncrementalNewlineDecoder.__init__() not called");
    }

    public static Object ndInit(Object self, Object decoder, Object translate, Object errors) {
        NdState s = new NdState();
        if (decoder != null) s.obj = new ObjDec(decoder);
        s.nl = new NewlineDec(s.obj, PyRuntime.truth(translate));
        setState(self, s);
        return null;
    }

    public static Object ndDecode(Object self, Object input, Object fin) {
        NdState s = nd(self);
        boolean f = PyRuntime.truth(fin);
        String out;
        if (s.obj != null) out = s.obj.decodeObj(input, f);
        else {
            Object in = NativeSubclass.unwrap(input);
            if (!(in instanceof String str)) throw new PyException("TypeError", "decoder should return a string result, not '" + PyRuntime.typeName(in) + "'");
            out = str;
        }
        return s.nl.process(out, f);
    }

    public static Object ndGetstate(Object self) {
        Object[] st = nd(self).nl.getstate();
        return tuple(new PyBytes((byte[]) st[0]), st[1]);
    }

    public static Object ndSetstate(Object self, Object state) {
        if (!(state instanceof PyTuple t) || t.items.size() != 2) throw new PyException("TypeError", "state argument must be a tuple");
        nd(self).nl.setstate(IoUtil.bytesLike(t.items.get(0)), IoUtil.asLong(t.items.get(1)));
        return null;
    }

    public static Object ndReset(Object self) { nd(self).nl.reset(); return null; }
    public static Object ndNewlines(Object self) { return nd(self).nl.newlines(); }
}
