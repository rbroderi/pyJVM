// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Native encoders and decoders for utf-8, ascii, latin-1, utf-16 and utf-32 with CPython's error handling
 * ({@code strict}, {@code ignore}, {@code replace}, {@code backslashreplace}, {@code xmlcharrefreplace},
 * {@code namereplace}, {@code surrogateescape}, {@code surrogatepass} and registered handlers) and its exact
 * error positions and reasons.
 */
final class CodecsCore {
    private CodecsCore() {}

    /** Growable byte buffer. */
    static final class Buf {
        byte[] a; int n;
        Buf(int cap) { a = new byte[Math.max(16, cap)]; }
        void put(int b) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = (byte) b; }
        void put(byte[] b) { for (byte x : b) put(x); }
        byte[] toArray() { return Arrays.copyOf(a, n); }
    }

    static String errs(String errors) { return errors == null ? "strict" : errors; }

    // ---- decode error dispatch ---------------------------------------------------------------

    /** Handle a decoding error over data[start, end): appends the replacement to sb and returns the byte position to resume at. */
    static int decodeFault(String errors, String enc, byte[] data, int start, int end, String reason, StringBuilder sb) {
        switch (errs(errors)) {
            case "strict": throw CodecErrors.decodeError(enc, data, start, end, reason);
            case "ignore": return end;
            case "replace": sb.append('�'); return end;
            default: break;
        }
        PyExceptionValue ev = CodecErrors.decodeValue(enc, data, start, end, reason);
        Object r = callHandler(errors, ev);
        PyTuple t = handlerResult(r, "decoding", String.class);
        sb.append((String) t.items.get(0));
        long pos = ((Number) t.items.get(1)).longValue();
        if (pos < 0) pos += data.length;
        if (pos < 0 || pos > data.length) throw new PyException("IndexError", "position " + ((Number) t.items.get(1)).longValue() + " from error handler out of bounds");
        return (int) pos;
    }

    private static Object callHandler(String errors, PyExceptionValue ev) {
        Object h = CodecErrors.lookup(errs(errors));
        return PyRuntime.callFunction(h, new ArrayList<Object>(List.of(ev)), new java.util.LinkedHashMap<Object,Object>());
    }

    private static PyTuple handlerResult(Object r, String what, Class<?> first) {
        if (!(r instanceof PyTuple t) || t.items.size() != 2 || !(first.isInstance(t.items.get(0)) || what.equals("encoding") && t.items.get(0) instanceof PyBytes)
                || !(t.items.get(1) instanceof Long || t.items.get(1) instanceof java.math.BigInteger))
            throw new PyException("TypeError", what + " error handler must return (" + (what.equals("encoding") ? "str/bytes" : "str") + ", int) tuple");
        return t;
    }

    /** Handle an encoding error over s[start, end) (UTF-16 indexes); appends bytes to out and returns the UTF-16 index to resume at. */
    static int encodeFault(String errors, String enc, String s, int start, int end, String reason, Buf out, Function<String,byte[]> strict) {
        switch (errs(errors)) {
            case "strict": throw CodecErrors.encodeError(enc, s, start, end, reason);
            case "ignore": return end;
            case "replace": out.put(strict.apply("?".repeat(s.codePointCount(start, end)))); return end;
            default: break;
        }
        PyExceptionValue ev = CodecErrors.encodeValue(enc, s, s.codePointCount(0, start), s.codePointCount(0, end), reason);
        Object r = callHandler(errors, ev);
        PyTuple t = handlerResult(r, "encoding", String.class);
        if (t.items.get(0) instanceof PyBytes b) out.put(b.data);
        else out.put(strict.apply((String) t.items.get(0)));
        long pos = ((Number) t.items.get(1)).longValue();
        int cpCount = s.codePointCount(0, s.length());
        if (pos < 0) pos += cpCount;
        if (pos < 0 || pos > cpCount) throw new PyException("IndexError", "position " + ((Number) t.items.get(1)).longValue() + " from error handler out of bounds");
        return s.offsetByCodePoints(0, (int) pos);
    }

    private static boolean loneSurrogateAt(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
        return Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
    }

    private static boolean hasSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isSurrogate(s.charAt(i))) return true;
        return false;
    }

    // ---- UTF-8 ---------------------------------------------------------------------------------

    static byte[] encodeUtf8(String s, String errors) {
        if (!hasSurrogate(s)) return s.getBytes(StandardCharsets.UTF_8);
        Buf out = new Buf(s.length() + 8);
        Function<String,byte[]> strict = r -> encodeUtf8(r, "strict");
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) && loneSurrogateAt(s, i)) {
                int j = i + 1;
                while (j < s.length() && Character.isSurrogate(s.charAt(j)) && loneSurrogateAt(s, j)) j++;
                if (errs(errors).equals("surrogatepass")) {
                    for (int k = i; k < j; k++) { char x = s.charAt(k); out.put(0xE0 | (x >> 12)); out.put(0x80 | ((x >> 6) & 0x3F)); out.put(0x80 | (x & 0x3F)); }
                    i = j;
                } else i = encodeFault(errors, "utf-8", s, i, j, "surrogates not allowed", out, strict);
                continue;
            }
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            putUtf8(out, cp);
        }
        return out.toArray();
    }

    private static void putUtf8(Buf out, int cp) {
        if (cp < 0x80) out.put(cp);
        else if (cp < 0x800) { out.put(0xC0 | (cp >> 6)); out.put(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out.put(0xE0 | (cp >> 12)); out.put(0x80 | ((cp >> 6) & 0x3F)); out.put(0x80 | (cp & 0x3F)); }
        else { out.put(0xF0 | (cp >> 18)); out.put(0x80 | ((cp >> 12) & 0x3F)); out.put(0x80 | ((cp >> 6) & 0x3F)); out.put(0x80 | (cp & 0x3F)); }
    }

    private static boolean cont(byte[] d, int i) { return (d[i] & 0xC0) == 0x80; }

    /** UTF-8 decoding. {@code consumed[0]} receives the number of bytes used (less than the length only when {@code fin} is false). */
    static String decodeUtf8(byte[] d, String errors, boolean fin, int[] consumed) {
        int n = d.length, i = 0;
        while (i < n && d[i] >= 0) i++;
        if (i == n) { consumed[0] = n; return new String(d, StandardCharsets.ISO_8859_1); }
        StringBuilder sb = new StringBuilder(n);
        for (int k = 0; k < i; k++) sb.append((char) d[k]);
        boolean pass = errs(errors).equals("surrogatepass");
        while (i < n) {
            int b = d[i] & 0xFF;
            if (b < 0x80) { sb.append((char) b); i++; continue; }
            int[] r = utf8Sequence(d, i, n, pass);
            // r = {kind, length-or-end, codepoint}: kind 0 ok, 1 incomplete, 2 error(start..end, reason index)
            if (r[0] == 0) { sb.appendCodePoint(r[2]); i += r[1]; continue; }
            if (r[0] == 1 && !fin) break;
            int end = r[0] == 1 ? n : r[1];
            String reason = r[0] == 1 ? "unexpected end of data" : r[2] == 0 ? "invalid start byte" : "invalid continuation byte";
            i = decodeFault(errors, "utf-8", d, i, end, reason, sb);
        }
        consumed[0] = i;
        return sb.toString();
    }

    /** One non-ASCII UTF-8 sequence at d[i]: {0, length, cp} valid; {1} incomplete at end; {2, endpos, 0 start byte|1 continuation} invalid. */
    private static int[] utf8Sequence(byte[] d, int i, int n, boolean pass) {
        int b = d[i] & 0xFF, left = n - i;
        if (b < 0xC2 || b > 0xF4) return new int[]{2, i + 1, 0};
        if (b < 0xE0) {
            if (left < 2) return new int[]{1};
            if (!cont(d, i + 1)) return new int[]{2, i + 1, 1};
            return new int[]{0, 2, ((b & 0x1F) << 6) | (d[i + 1] & 0x3F)};
        }
        if (b < 0xF0) {
            if (left < 2) return new int[]{1};
            int c2 = d[i + 1] & 0xFF;
            boolean bad2 = !cont(d, i + 1) || (c2 < 0xA0 ? b == 0xE0 : b == 0xED && !pass);
            if (bad2) return new int[]{2, i + 1, 1};
            if (left < 3) return new int[]{1};
            if (!cont(d, i + 2)) return new int[]{2, i + 2, 1};
            return new int[]{0, 3, ((b & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (d[i + 2] & 0x3F)};
        }
        if (left < 2) return new int[]{1};
        int c2 = d[i + 1] & 0xFF;
        if (!cont(d, i + 1) || (c2 < 0x90 ? b == 0xF0 : b == 0xF4)) return new int[]{2, i + 1, 1};
        if (left < 3) return new int[]{1};
        if (!cont(d, i + 2)) return new int[]{2, i + 2, 1};
        if (left < 4) return new int[]{1};
        if (!cont(d, i + 3)) return new int[]{2, i + 3, 1};
        return new int[]{0, 4, ((b & 0x07) << 18) | ((c2 & 0x3F) << 12) | ((d[i + 2] & 0x3F) << 6) | (d[i + 3] & 0x3F)};
    }

    // ---- ASCII / Latin-1 --------------------------------------------------------------------------

    static byte[] encodeLimited(String s, String errors, int limit, String enc) {
        int i = 0;
        while (i < s.length() && s.charAt(i) < limit) i++;
        if (i == s.length()) return s.getBytes(StandardCharsets.ISO_8859_1);
        Buf out = new Buf(s.length());
        for (int k = 0; k < i; k++) out.put(s.charAt(k));
        Function<String,byte[]> strict = r -> encodeLimited(r, "strict", limit, enc);
        String reason = "ordinal not in range(" + limit + ")";
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (cp < limit) { out.put(cp); i++; continue; }
            int j = i;
            while (j < s.length()) { int c2 = s.codePointAt(j); if (c2 < limit) break; j += Character.charCount(c2); }
            i = encodeFault(errors, enc, s, i, j, reason, out, strict);
        }
        return out.toArray();
    }

    static String decodeAscii(byte[] d, String errors) {
        int i = 0;
        while (i < d.length && d[i] >= 0) i++;
        if (i == d.length) return new String(d, StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder(d.length);
        for (int k = 0; k < i; k++) sb.append((char) d[k]);
        while (i < d.length) {
            if (d[i] >= 0) { sb.append((char) d[i]); i++; continue; }
            i = decodeFault(errors, "ascii", d, i, i + 1, "ordinal not in range(128)", sb);
        }
        return sb.toString();
    }

    static String decodeLatin1(byte[] d) { return new String(d, StandardCharsets.ISO_8859_1); }

    // ---- UTF-16 / UTF-32 ---------------------------------------------------------------------------

    /** {@code order}: -1 little endian, 1 big endian, 0 little endian preceded by a byte order mark. */
    static byte[] encodeUtf16(String s, String errors, int order) {
        Buf out = new Buf(s.length() * 2 + 2);
        boolean le = order <= 0;
        if (order == 0) { out.put(0xFF); out.put(0xFE); }
        Function<String,byte[]> strict = r -> encodeUtf16(r, "strict", le ? -1 : 1);
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) && loneSurrogateAt(s, i)) {
                int j = i + 1;
                while (j < s.length() && Character.isSurrogate(s.charAt(j)) && loneSurrogateAt(s, j)) j++;
                if (errs(errors).equals("surrogatepass")) { for (int k = i; k < j; k++) unit16(out, s.charAt(k), le); i = j; }
                else i = encodeFault(errors, order == 0 ? "utf-16" : le ? "utf-16-le" : "utf-16-be", s, i, j, "surrogates not allowed", out, strict);
                continue;
            }
            unit16(out, c, le);
            i++;
        }
        return out.toArray();
    }

    private static void unit16(Buf out, int u, boolean le) {
        if (le) { out.put(u & 0xFF); out.put(u >> 8); } else { out.put(u >> 8); out.put(u & 0xFF); }
    }

    /** UTF-16 decoding; {@code order[0]} in/out: 0 detect (BOM), -1 little, 1 big. {@code consumed[0]} gets the bytes used. */
    static String decodeUtf16(byte[] d, String errors, boolean fin, int[] order, int[] consumed, String enc) {
        int n = d.length, i = 0;
        if (order[0] == 0 && n >= 2) {
            if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) { order[0] = -1; i = 2; }
            else if ((d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) { order[0] = 1; i = 2; }
        }
        boolean le = order[0] != 1;
        StringBuilder sb = new StringBuilder(n / 2);
        boolean pass = errs(errors).equals("surrogatepass");
        String name = enc;
        while (i < n) {
            if (n - i < 2) {
                if (!fin) break;
                i = decodeFault(errors, name, d, i, n, "truncated data", sb);
                continue;
            }
            int u = le ? (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) : ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            if (u < 0xD800 || u > 0xDFFF) { sb.append((char) u); i += 2; continue; }
            if (u >= 0xDC00) {
                if (pass) { sb.append((char) u); i += 2; continue; }
                i = decodeFault(errors, name, d, i, i + 2, "illegal encoding", sb);
                continue;
            }
            if (n - i < 4) {
                if (!fin) break;
                if (pass && n - i == 2) { sb.append((char) u); i += 2; continue; }
                i = decodeFault(errors, name, d, i, n, "unexpected end of data", sb);
                continue;
            }
            int u2 = le ? (d[i + 2] & 0xFF) | ((d[i + 3] & 0xFF) << 8) : ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (u2 >= 0xDC00 && u2 <= 0xDFFF) { sb.append((char) u).append((char) u2); i += 4; continue; }
            if (pass) { sb.append((char) u); i += 2; continue; }
            i = decodeFault(errors, name, d, i, i + 2, "illegal UTF-16 surrogate", sb);
        }
        consumed[0] = i;
        return sb.toString();
    }

    static byte[] encodeUtf32(String s, String errors, int order) {
        Buf out = new Buf(s.length() * 4 + 4);
        boolean le = order <= 0;
        if (order == 0) { out.put(0xFF); out.put(0xFE); out.put(0); out.put(0); }
        Function<String,byte[]> strict = r -> encodeUtf32(r, "strict", le ? -1 : 1);
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) && loneSurrogateAt(s, i)) {
                int j = i + 1;
                while (j < s.length() && Character.isSurrogate(s.charAt(j)) && loneSurrogateAt(s, j)) j++;
                if (errs(errors).equals("surrogatepass")) { for (int k = i; k < j; k++) unit32(out, s.charAt(k), le); i = j; }
                else i = encodeFault(errors, order == 0 ? "utf-32" : le ? "utf-32-le" : "utf-32-be", s, i, j, "surrogates not allowed", out, strict);
                continue;
            }
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            unit32(out, cp, le);
        }
        return out.toArray();
    }

    private static void unit32(Buf out, int u, boolean le) {
        if (le) { out.put(u & 0xFF); out.put((u >> 8) & 0xFF); out.put((u >> 16) & 0xFF); out.put(u >>> 24); }
        else { out.put(u >>> 24); out.put((u >> 16) & 0xFF); out.put((u >> 8) & 0xFF); out.put(u & 0xFF); }
    }

    static String decodeUtf32(byte[] d, String errors, boolean fin, int[] order, int[] consumed, String enc) {
        int n = d.length, i = 0;
        if (order[0] == 0 && n >= 4) {
            if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE && d[2] == 0 && d[3] == 0) { order[0] = -1; i = 4; }
            else if (d[0] == 0 && d[1] == 0 && (d[2] & 0xFF) == 0xFE && (d[3] & 0xFF) == 0xFF) { order[0] = 1; i = 4; }
        }
        boolean le = order[0] != 1;
        StringBuilder sb = new StringBuilder(n / 4);
        boolean pass = errs(errors).equals("surrogatepass");
        while (i < n) {
            if (n - i < 4) {
                if (!fin) break;
                i = decodeFault(errors, enc, d, i, n, "truncated data", sb);
                continue;
            }
            long u = le ? (d[i] & 0xFFL) | ((d[i + 1] & 0xFFL) << 8) | ((d[i + 2] & 0xFFL) << 16) | ((d[i + 3] & 0xFFL) << 24)
                : ((d[i] & 0xFFL) << 24) | ((d[i + 1] & 0xFFL) << 16) | ((d[i + 2] & 0xFFL) << 8) | (d[i + 3] & 0xFFL);
            if (u >= 0x110000) { i = decodeFault(errors, enc, d, i, i + 4, "code point not in range(0x110000)", sb); continue; }
            if (u >= 0xD800 && u <= 0xDFFF && !pass) { i = decodeFault(errors, enc, d, i, i + 4, "code point in surrogate code point range(0xd800, 0xe000)", sb); continue; }
            sb.appendCodePoint((int) u);
            i += 4;
        }
        consumed[0] = i;
        return sb.toString();
    }
}
