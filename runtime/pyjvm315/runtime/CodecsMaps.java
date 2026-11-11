// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.function.Function;

/** Charmap codecs and the escape codecs ({@code unicode_escape}, {@code raw_unicode_escape}, {@code escape}). */
final class CodecsMaps {
    private CodecsMaps() {}

    // ---- charmap -------------------------------------------------------------------------------

    /** The text a byte maps to: a String (possibly empty), or null when undefined. */
    private static String mapped(Object mapping, int b) {
        if (mapping instanceof String table) {
            if (b >= table.length()) return null;
            char c = table.charAt(b);
            return c == '￾' ? null : String.valueOf(c);
        }
        Object v;
        try { v = PyRuntime.getitem(mapping, (long) b); }
        catch (PyException e) {
            if (PyRuntime.exceptionIsSubclass(e.typeName, "LookupError")) return null;
            throw e;
        }
        if (v == null) return null;
        if (v instanceof Boolean) v = ((Boolean) v) ? 1L : 0L;
        if (v instanceof Long l) {
            if (l < 0 || l >= 0x110000) throw new PyException("TypeError", "character mapping must be in range(0x110000)");
            return new String(Character.toChars((int) (long) l));
        }
        if (v instanceof String s) return s.equals("￾") ? null : s;
        throw new PyException("TypeError", "character mapping must return integer, None or str");
    }

    static String decodeCharmap(byte[] d, String errors, Object mapping) {
        if (mapping == null) return CodecsCore.decodeLatin1(d);
        StringBuilder sb = new StringBuilder(d.length);
        for (int i = 0; i < d.length; ) {
            String m = mapped(mapping, d[i] & 0xFF);
            if (m != null) { sb.append(m); i++; }
            else i = CodecsCore.decodeFault(errors, "charmap", d, i, i + 1, "character maps to <undefined>", sb);
        }
        return sb.toString();
    }

    /** The bytes a code point maps to, or null when undefined. */
    private static byte[] encodeOne(Object mapping, int cp) {
        Object v;
        try { v = PyRuntime.getitem(mapping, (long) cp); }
        catch (PyException e) {
            if (PyRuntime.exceptionIsSubclass(e.typeName, "LookupError")) return null;
            throw e;
        }
        if (v == null) return null;
        if (v instanceof Boolean) v = ((Boolean) v) ? 1L : 0L;
        if (v instanceof Long l) {
            if (l < 0 || l >= 256) throw new PyException("TypeError", "character mapping must be in range(256)");
            return new byte[]{(byte) (long) l};
        }
        if (v instanceof PyBytes b) return b.data;
        throw new PyException("TypeError", "character mapping must return integer, bytes or None, not " + PyRuntime.typeName(v));
    }

    static byte[] encodeCharmap(String s, String errors, Object mapping) {
        if (mapping == null) return CodecsCore.encodeLimited(s, errors, 256, "latin-1");
        CodecsCore.Buf out = new CodecsCore.Buf(s.length());
        Function<String,byte[]> strict = r -> encodeCharmap(r, "strict", mapping);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            byte[] m = encodeOne(mapping, cp);
            if (m != null) { out.put(m); i += Character.charCount(cp); continue; }
            int j = i + Character.charCount(cp);
            while (j < s.length()) { int c2 = s.codePointAt(j); if (encodeOne(mapping, c2) != null) break; j += Character.charCount(c2); }
            i = CodecsCore.encodeFault(errors, "charmap", s, i, j, "character maps to <undefined>", out, strict);
        }
        return out.toArray();
    }

    // ---- unicode_escape / raw_unicode_escape -----------------------------------------------------------

    static byte[] encodeUnicodeEscape(String s, boolean raw) {
        CodecsCore.Buf out = new CodecsCore.Buf(s.length() + 8);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (raw) {
                if (cp < 0x100) out.put(cp);
                else out.put((cp < 0x10000 ? String.format("\\u%04x", cp) : String.format("\\U%08x", cp)).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                continue;
            }
            switch (cp) {
                case '\\' -> { out.put('\\'); out.put('\\'); }
                case '\t' -> { out.put('\\'); out.put('t'); }
                case '\n' -> { out.put('\\'); out.put('n'); }
                case '\r' -> { out.put('\\'); out.put('r'); }
                default -> {
                    if (cp >= 0x20 && cp < 0x7F) out.put(cp);
                    else out.put((cp < 0x100 ? String.format("\\x%02x", cp) : cp < 0x10000 ? String.format("\\u%04x", cp) : String.format("\\U%08x", cp))
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }
        }
        return out.toArray();
    }

    private static int hexRun(byte[] d, int i, int max) {
        int k = 0;
        while (k < max && i + k < d.length && Character.digit(d[i + k] & 0xFF, 16) >= 0 && (d[i + k] & 0x80) == 0) k++;
        return k;
    }

    static String decodeUnicodeEscape(byte[] d, String errors, boolean raw, String enc) {
        StringBuilder sb = new StringBuilder(d.length);
        int i = 0;
        while (i < d.length) {
            int b = d[i] & 0xFF;
            if (b != '\\') { sb.append((char) b); i++; continue; }
            int start = i++;
            if (i >= d.length) {
                if (raw) { sb.append('\\'); continue; }
                i = CodecsCore.decodeFault(errors, enc, d, start, d.length, "\\ at end of string", sb);
                continue;
            }
            int c = d[i++] & 0xFF;
            if (raw && c != 'u' && c != 'U') { sb.append('\\'); i--; continue; }
            if (raw && c == 'u' || c == 'u' || c == 'U' || c == 'x') {
                int want = c == 'x' ? 2 : c == 'u' ? 4 : 8;
                int got = hexRun(d, i, want);
                if (got < want) {
                    String name = c == 'x' ? "\\xXX" : c == 'u' ? "\\uXXXX" : "\\UXXXXXXXX";
                    i = CodecsCore.decodeFault(errors, enc, d, start, i + got, "truncated " + name + " escape", sb);
                    continue;
                }
                long v = Long.parseLong(new String(d, i, want, java.nio.charset.StandardCharsets.ISO_8859_1), 16);
                if (v > 0x10FFFF) { i = CodecsCore.decodeFault(errors, enc, d, start, i + want, "illegal Unicode character", sb); continue; }
                sb.appendCodePoint((int) v);
                i += want;
                continue;
            }
            i = unicodeEscapeSimple(d, errors, enc, sb, start, i, c);
        }
        return sb.toString();
    }

    private static int unicodeEscapeSimple(byte[] d, String errors, String enc, StringBuilder sb, int start, int i, int c) {
        switch (c) {
            case '\n': return i;
            case '\\': sb.append('\\'); return i;
            case '\'': sb.append('\''); return i;
            case '"': sb.append('"'); return i;
            case 'a': sb.append('\u0007'); return i;
            case 'b': sb.append('\b'); return i;
            case 'f': sb.append('\f'); return i;
            case 'n': sb.append('\n'); return i;
            case 'r': sb.append('\r'); return i;
            case 't': sb.append('\t'); return i;
            case 'v': sb.append('\u000b'); return i;
            case 'N': return namedEscape(d, errors, enc, sb, start, i);
            default: break;
        }
        if (c >= '0' && c <= '7') {
            int v = c - '0';
            for (int k = 0; k < 2 && i < d.length && d[i] >= '0' && d[i] <= '7'; k++) v = v * 8 + (d[i++] - '0');
            sb.append((char) v);
            return i;
        }
        sb.append('\\').append((char) c);
        return i;
    }

    private static int namedEscape(byte[] d, String errors, String enc, StringBuilder sb, int start, int i) {
        if (i < d.length && d[i] == '{') {
            int close = i + 1;
            while (close < d.length && d[close] != '}') close++;
            if (close < d.length && close > i + 1) {
                String name = new String(d, i + 1, close - i - 1, java.nio.charset.StandardCharsets.ISO_8859_1);
                try { sb.appendCodePoint(Character.codePointOf(name)); return close + 1; }
                catch (IllegalArgumentException e) {
                    return CodecsCore.decodeFault(errors, enc, d, start, close + 1, "unknown Unicode character name", sb);
                }
            }
        }
        return CodecsCore.decodeFault(errors, enc, d, start, Math.min(d.length, i + 1), "malformed \\N character escape", sb);
    }

    // ---- escape (bytes) ----------------------------------------------------------------------------------

    static byte[] escapeEncode(byte[] d) {
        CodecsCore.Buf out = new CodecsCore.Buf(d.length + 8);
        for (byte x : d) {
            int c = x & 0xFF;
            switch (c) {
                case '\\' -> { out.put('\\'); out.put('\\'); }
                case '\t' -> { out.put('\\'); out.put('t'); }
                case '\n' -> { out.put('\\'); out.put('n'); }
                case '\r' -> { out.put('\\'); out.put('r'); }
                case '\'' -> { out.put('\\'); out.put('\''); }
                default -> {
                    if (c < 0x20 || c >= 0x7F) out.put(String.format("\\x%02x", c).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    else out.put(c);
                }
            }
        }
        return out.toArray();
    }

    static byte[] escapeDecode(byte[] d, String errors) {
        CodecsCore.Buf out = new CodecsCore.Buf(d.length);
        int i = 0;
        while (i < d.length) {
            int c = d[i++] & 0xFF;
            if (c != '\\') { out.put(c); continue; }
            if (i >= d.length) throw new PyException("ValueError", "Trailing \\ in string");
            c = d[i++] & 0xFF;
            switch (c) {
                case '\n': break;
                case '\\': out.put('\\'); break;
                case '\'': out.put('\''); break;
                case '"': out.put('"'); break;
                case 'b': out.put('\b'); break;
                case 'f': out.put('\f'); break;
                case 't': out.put('\t'); break;
                case 'n': out.put('\n'); break;
                case 'r': out.put('\r'); break;
                case 'v': out.put(0x0B); break;
                case 'a': out.put(0x07); break;
                case 'x': {
                    if (hexRun(d, i, 2) == 2) { out.put(Integer.parseInt(new String(d, i, 2, java.nio.charset.StandardCharsets.ISO_8859_1), 16)); i += 2; break; }
                    switch (CodecsCore.errs(errors)) {
                        case "strict": throw new PyException("ValueError", "invalid \\x escape at position " + (i - 2));
                        case "replace": out.put('?'); i += hexRun(d, i, 2); break;
                        case "ignore": i += hexRun(d, i, 2); break;
                        default: throw new PyException("ValueError", "decoding error; unknown error handling code: " + errors);
                    }
                    break;
                }
                default:
                    if (c >= '0' && c <= '7') {
                        int v = c - '0';
                        for (int k = 0; k < 2 && i < d.length && d[i] >= '0' && d[i] <= '7'; k++) v = v * 8 + (d[i++] - '0');
                        out.put(v & 0xFF);
                    } else { out.put('\\'); out.put(c); }
            }
        }
        return out.toArray();
    }
}
