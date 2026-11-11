// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The codec registry and the str/bytes entry points. utf-8, ascii, latin-1, utf-16 and utf-32 are encoded and
 * decoded natively; everything else goes through the registered search functions, which are CPython's own
 * {@code encodings} package running unmodified (imported on first use, as CPython does).
 */
final class Codecs {
    private Codecs() {}

    static final ArrayList<Object> SEARCH = new ArrayList<>();
    static final Map<String,Object> CACHE = new ConcurrentHashMap<>();

    // ---- name normalization ------------------------------------------------------------------------

    /** The native codec for an encoding name, else null. */
    static String nativeName(String enc) {
        String n = enc.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (n) {
            case "utf_8", "utf8", "u8", "utf", "utf8_ucs2", "utf8_ucs4", "cp65001": return "utf-8";
            case "ascii", "us_ascii", "646", "ansi_x3.4_1968", "iso646_us", "ansi_x3_4_1968", "cp367", "ibm367", "iso_ir_6", "us": return "ascii";
            case "latin_1", "latin1", "iso8859_1", "iso_8859_1", "l1", "latin", "8859", "cp819", "iso_ir_100", "ibm819", "iso8859": return "latin-1";
            case "utf_16", "utf16", "u16": return "utf-16";
            case "utf_16_le", "utf_16le", "unicodelittleunmarked": return "utf-16-le";
            case "utf_16_be", "utf_16be", "unicodebigunmarked": return "utf-16-be";
            case "utf_32", "utf32", "u32": return "utf-32";
            case "utf_32_le", "utf_32le": return "utf-32-le";
            case "utf_32_be", "utf_32be": return "utf-32-be";
            default: return null;
        }
    }

    /** {@code encodings.normalize_encoding}: runs of non-alphanumerics (except the dot) become one underscore. */
    static String normalizeEncoding(String s) {
        StringBuilder sb = new StringBuilder();
        boolean punct = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128 && (Character.isLetterOrDigit(c) || c == '.')) {
                if (punct && sb.length() > 0) sb.append('_');
                punct = false;
                sb.append(c);
            } else punct = true;
        }
        return sb.toString();
    }

    // ---- registry --------------------------------------------------------------------------------------

    static Object lookup(String encoding) {
        String key = encoding.toLowerCase(Locale.ROOT).replace(' ', '-');
        Object hit = CACHE.get(key);
        if (hit != null) return hit;
        synchronized (SEARCH) {
            if (SEARCH.isEmpty()) {
                try { ImportSupport.importBuiltin(new ArrayList<Object>(List.of("encodings")), new LinkedHashMap<Object,Object>()); }
                catch (PyException e) { if (!PyRuntime.exceptionIsSubclass(e.typeName, "ImportError")) throw e; }
            }
            for (Object fn : new ArrayList<>(SEARCH)) {
                Object r = PyRuntime.callFunction(fn, new ArrayList<Object>(List.of(key)), new LinkedHashMap<Object,Object>());
                if (r == null) continue;
                Object tuple = NativeSubclass.unwrap(r);
                if (!(tuple instanceof PyTuple t) || t.items.size() != 4) throw new PyException("TypeError", "codec search functions must return 4-tuples");
                CACHE.put(key, r);
                return r;
            }
        }
        throw new PyException("LookupError", "unknown encoding: " + encoding);
    }

    static Object textCodec(String encoding, String attr, String what) {
        Object info = lookup(encoding);
        Object isText = PyRuntime.truth(PyRuntime.hasattr(info, "_is_text_encoding")) ? PyRuntime.getattr(info, "_is_text_encoding") : Boolean.TRUE;
        if (!PyRuntime.truth(isText))
            throw new PyException("LookupError", "'" + encoding + "' is not a text encoding; use " + what + " to handle arbitrary codecs");
        return PyRuntime.getattr(info, attr);
    }

    private static Object callTuple(Object fn, Object arg, String errors, String codecKind) {
        Object r = PyRuntime.callFunction(fn, new ArrayList<Object>(List.of(arg, errors)), new LinkedHashMap<Object,Object>());
        if (!(r instanceof PyTuple t) || t.items.size() != 2)
            throw new PyException("TypeError", "'" + codecKind + "' encoder returned '" + PyRuntime.typeName(r) + "' instead of 'tuple'; use codecs.encode() to encode to arbitrary types".replace("encoder", codecKind.equals("decode") ? "decoder" : "encoder"));
        return t.items.get(0);
    }

    // ---- str.encode / bytes.decode -----------------------------------------------------------------------

    static byte[] encode(String s, String encoding, String errors) {
        String n = nativeName(encoding);
        if (n != null) return encodeNative(s, n, errors);
        Object fn = textCodec(encoding, "encode", "codecs.encode()");
        Object r = callTuple(fn, s, errors == null ? "strict" : errors, "encode");
        if (!(r instanceof PyBytes b)) throw new PyException("TypeError", "'" + encoding + "' encoder returned '" + PyRuntime.typeName(r) + "' instead of 'bytes'; use codecs.encode() to encode to arbitrary types");
        return b.data;
    }

    static byte[] encodeNative(String s, String n, String errors) {
        return switch (n) {
            case "utf-8" -> CodecsCore.encodeUtf8(s, errors);
            case "ascii" -> CodecsCore.encodeLimited(s, errors, 128, "ascii");
            case "latin-1" -> CodecsCore.encodeLimited(s, errors, 256, "latin-1");
            case "utf-16" -> CodecsCore.encodeUtf16(s, errors, 0);
            case "utf-16-le" -> CodecsCore.encodeUtf16(s, errors, -1);
            case "utf-16-be" -> CodecsCore.encodeUtf16(s, errors, 1);
            case "utf-32" -> CodecsCore.encodeUtf32(s, errors, 0);
            case "utf-32-le" -> CodecsCore.encodeUtf32(s, errors, -1);
            default -> CodecsCore.encodeUtf32(s, errors, 1);
        };
    }

    static String decode(byte[] data, String encoding, String errors) {
        String n = nativeName(encoding);
        if (n != null) return decodeNative(data, n, errors, true, new int[1], new int[1]);
        Object fn = textCodec(encoding, "decode", "codecs.decode()");
        Object r = callTuple(fn, new PyBytes(data), errors == null ? "strict" : errors, "decode");
        if (!(r instanceof String s)) throw new PyException("TypeError", "'" + encoding + "' decoder returned '" + PyRuntime.typeName(r) + "' instead of 'str'; use codecs.decode() to decode to arbitrary types");
        return s;
    }

    /** {@code order[0]}: byte order state for utf-16/32 (in/out); {@code consumed[0]}: bytes used. */
    static String decodeNative(byte[] d, String n, String errors, boolean fin, int[] order, int[] consumed) {
        consumed[0] = d.length;
        return switch (n) {
            case "utf-8" -> CodecsCore.decodeUtf8(d, errors, fin, consumed);
            case "ascii" -> CodecsCore.decodeAscii(d, errors);
            case "latin-1" -> CodecsCore.decodeLatin1(d);
            case "utf-16" -> CodecsCore.decodeUtf16(d, errors, fin, order, consumed, "utf-16");
            case "utf-16-le" -> { order[0] = -1; yield CodecsCore.decodeUtf16(d, errors, fin, order, consumed, "utf-16-le"); }
            case "utf-16-be" -> { order[0] = 1; yield CodecsCore.decodeUtf16(d, errors, fin, order, consumed, "utf-16-be"); }
            case "utf-32" -> CodecsCore.decodeUtf32(d, errors, fin, order, consumed, "utf-32");
            case "utf-32-le" -> { order[0] = -1; yield CodecsCore.decodeUtf32(d, errors, fin, order, consumed, "utf-32-le"); }
            default -> { order[0] = 1; yield CodecsCore.decodeUtf32(d, errors, fin, order, consumed, "utf-32-be"); }
        };
    }

    /** Bytes of a bytes-like object. */
    static byte[] bytesOf(Object o, String who) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof PyBytes b) return b.data;
        if (o instanceof PyByteSequence s) return s.toByteArray();
        throw new PyException("TypeError", who + " argument 1 must be a bytes-like object, not '" + PyRuntime.typeName(o) + "'");
    }
}
