// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.parser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Decoding of string/bytes literal bodies (escape sequences) as CPython's parser does. */
final class StringLiterals {
    private StringLiterals() {}

    /** Decode the text between the quotes of a str literal. */
    static String decodeString(String body, boolean raw, PyPos pos) {
        if (raw || body.indexOf('\\') < 0) return body;
        StringBuilder out = new StringBuilder(body.length());
        int n = body.length();
        for (int i = 0; i < n; i++) {
            char c = body.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i >= n) { out.append('\\'); break; }
            char e = body.charAt(i);
            switch (e) {
                case '\n' -> {}
                case '\r' -> { if (i + 1 < n && body.charAt(i + 1) == '\n') i++; }
                case '\\' -> out.append('\\'); case '\'' -> out.append('\''); case '"' -> out.append('"');
                case 'a' -> out.append('\u0007'); case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t'); case 'v' -> out.append('\u000b');
                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    int v = e - '0'; int k = 1;
                    while (k < 3 && i + 1 < n && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') { v = v * 8 + (body.charAt(++i) - '0'); k++; }
                    out.appendCodePoint(v);
                }
                case 'x' -> { out.append((char) hex(body, i + 1, 2, pos, "\\xXX")); i += 2; }
                case 'u' -> { out.append((char) hex(body, i + 1, 4, pos, "\\uXXXX")); i += 4; }
                case 'U' -> {
                    int v = hex(body, i + 1, 8, pos, "\\UXXXXXXXX"); i += 8;
                    if (v > 0x10FFFF) throw pos.error("(unicode error) 'unicodeescape' codec can't decode bytes: illegal Unicode character");
                    out.appendCodePoint(v);
                }
                case 'N' -> {
                    int close = body.indexOf('}', i);
                    if (i + 1 >= n || body.charAt(i + 1) != '{' || close < 0) throw pos.error("(unicode error) 'unicodeescape' codec can't decode bytes: malformed \\N character escape");
                    String name = body.substring(i + 2, close);
                    Integer alias = UnicodeAliases.BY_NAME.get(name.toUpperCase(java.util.Locale.ROOT));
                    int algorithmic = alias == null ? algorithmicName(name) : -1;
                    if (alias != null) out.appendCodePoint(alias);
                    else if (algorithmic >= 0) out.appendCodePoint(algorithmic);
                    else {
                        try { out.appendCodePoint(Character.codePointOf(name)); }
                        catch (IllegalArgumentException ex) { throw pos.error("(unicode error) 'unicodeescape' codec can't decode bytes: unknown Unicode character name"); }
                    }
                    i = close;
                }
                default -> { out.append('\\').append(e); }
            }
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern ALGORITHMIC = java.util.regex.Pattern.compile(
        "(CJK UNIFIED IDEOGRAPH|CJK COMPATIBILITY IDEOGRAPH|TANGUT IDEOGRAPH|NUSHU CHARACTER|KHITAN SMALL SCRIPT CHARACTER)-([0-9A-F]{4,6})");
    /** Names Unicode derives from the code point (ideographs); -1 if {@code name} is not of that form. */
    private static int algorithmicName(String name) {
        java.util.regex.Matcher m = ALGORITHMIC.matcher(name.toUpperCase(java.util.Locale.ROOT));
        if (!m.matches()) return -1;
        int cp = Integer.parseInt(m.group(2), 16);
        return cp <= 0x10FFFF && Character.isDefined(cp) && Character.getType(cp) == Character.OTHER_LETTER ? cp : -1;
    }

    private static int hex(String s, int from, int digits, PyPos pos, String form) {
        if (from + digits > s.length()) throw pos.error("(unicode error) 'unicodeescape' codec can't decode bytes: truncated " + form + " escape");
        int v = 0;
        for (int k = 0; k < digits; k++) {
            int d = Character.digit(s.charAt(from + k), 16);
            if (d < 0) throw pos.error("(unicode error) 'unicodeescape' codec can't decode bytes: truncated " + form + " escape");
            v = v * 16 + d;
        }
        return v;
    }

    /** Decode a bytes literal body. */
    static byte[] decodeBytes(String body, boolean raw, PyPos pos) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length());
        int n = body.length();
        for (int i = 0; i < n; i++) {
            char c = body.charAt(i);
            if (c > 127) throw pos.error("bytes can only contain ASCII literal characters");
            if (c != '\\' || raw) { out.write(c); continue; }
            if (++i >= n) { out.write('\\'); break; }
            char e = body.charAt(i);
            switch (e) {
                case '\n' -> {}
                case '\r' -> { if (i + 1 < n && body.charAt(i + 1) == '\n') i++; }
                case '\\' -> out.write('\\'); case '\'' -> out.write('\''); case '"' -> out.write('"');
                case 'a' -> out.write(7); case 'b' -> out.write(8); case 'f' -> out.write(12);
                case 'n' -> out.write(10); case 'r' -> out.write(13); case 't' -> out.write(9); case 'v' -> out.write(11);
                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    int v = e - '0'; int k = 1;
                    while (k < 3 && i + 1 < n && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') { v = v * 8 + (body.charAt(++i) - '0'); k++; }
                    out.write(v & 0xff);
                }
                case 'x' -> {
                    if (i + 2 >= n + 0 || Character.digit(body.charAt(i + 1), 16) < 0 || Character.digit(body.charAt(i + 2), 16) < 0)
                        throw pos.error("(value error) invalid \\x escape at position " + (i - 1));
                    out.write(Character.digit(body.charAt(i + 1), 16) * 16 + Character.digit(body.charAt(i + 2), 16)); i += 2;
                }
                default -> { out.write('\\'); out.write(e); }
            }
        }
        return out.toByteArray();
    }

    /** Where a literal error is reported. */
    interface PyPos { PySyntaxError error(String message); }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
