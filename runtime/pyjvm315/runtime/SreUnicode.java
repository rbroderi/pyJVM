// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.Locale;

/**
 * Character predicates of CPython's sre engine (Modules/_sre/sre.c): the ASCII, "locale" (always the C locale: ASCII)
 * and Unicode flavours of digit/space/word/linebreak, and the simple case mappings {@code Py_UNICODE_TOLOWER} /
 * {@code Py_UNICODE_TOUPPER}, which return the first code point of the full mapping for the characters whose full
 * mapping is longer (U+00DF upper-cases to 'S', U+0130 lower-cases to 'i').
 */
final class SreUnicode {
    private SreUnicode() {}

    // lazily filled BMP caches: value 0 = not computed, else mapped code point + 1
    private static final int[] LOWER = new int[0x10000];
    private static final int[] UPPER = new int[0x10000];

    static boolean isAsciiDigit(int c) { return c >= '0' && c <= '9'; }
    static boolean isAsciiSpace(int c) { return c == ' ' || (c >= 9 && c <= 13); }
    static boolean isAsciiWord(int c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; }

    static boolean isUniDigit(int c) { return c < 128 ? (c >= '0' && c <= '9') : Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER; }

    /** Py_UNICODE_ISSPACE */
    static boolean isUniSpace(int c) {
        if (c < 128) return c == ' ' || (c >= 9 && c <= 13) || (c >= 0x1c && c <= 0x1f);
        switch (c) {
            case 0x85: case 0xa0: case 0x1680: case 0x2028: case 0x2029: case 0x202f: case 0x205f: case 0x3000: return true;
            default: return c >= 0x2000 && c <= 0x200a;
        }
    }

    /** Py_UNICODE_ISALNUM: alphabetic, or any numeric character. */
    static boolean isUniAlnum(int c) {
        if (c < 128) return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        switch (Character.getType(c)) {
            case Character.UPPERCASE_LETTER: case Character.LOWERCASE_LETTER: case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER: case Character.OTHER_LETTER: case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER: case Character.OTHER_NUMBER: return true;
            default: return false;
        }
    }

    static boolean isUniWord(int c) { return c == '_' || isUniAlnum(c); }

    /** Py_UNICODE_ISLINEBREAK */
    static boolean isUniLinebreak(int c) {
        switch (c) {
            case 10: case 11: case 12: case 13: case 0x1c: case 0x1d: case 0x1e: case 0x85: case 0x2028: case 0x2029: return true;
            default: return false;
        }
    }

    static int lowerAscii(int c) { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }
    static int upperAscii(int c) { return (c >= 'a' && c <= 'z') ? c - 32 : c; }

    static int lowerUnicode(int c) {
        if (c < 128) return (c >= 'A' && c <= 'Z') ? c + 32 : c;
        if (c < 0x10000) {
            int v = LOWER[c];
            if (v == 0) { v = computeLower(c) + 1; LOWER[c] = v; }
            return v - 1;
        }
        return computeLower(c);
    }

    static int upperUnicode(int c) {
        if (c < 128) return (c >= 'a' && c <= 'z') ? c - 32 : c;
        if (c < 0x10000) {
            int v = UPPER[c];
            if (v == 0) { v = computeUpper(c) + 1; UPPER[c] = v; }
            return v - 1;
        }
        return computeUpper(c);
    }

    private static int computeLower(int c) {
        if (c >= 0xd800 && c <= 0xdfff) return c;
        int simple = Character.toLowerCase(c);
        if (simple != c) return simple;
        String full = new String(Character.toChars(c)).toLowerCase(Locale.ROOT);
        return full.codePointAt(0);
    }

    private static int computeUpper(int c) {
        if (c >= 0xd800 && c <= 0xdfff) return c;
        String full = new String(Character.toChars(c)).toUpperCase(Locale.ROOT);
        int first = full.codePointAt(0);
        if (full.codePointCount(0, full.length()) > 1) return first;
        int simple = Character.toUpperCase(c);
        return simple != c ? simple : first;
    }

    /** The code point is cased: its lower or upper simple mapping differs from itself (sre's unicode_iscased). */
    static boolean uniIsCased(int c) { return c != lowerUnicode(c) || c != upperUnicode(c); }

    static boolean asciiIsCased(int c) { return c < 128 && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')); }

    /** sre_category(): the CATEGORY_* codes of _constants.py. */
    static boolean category(int code, int ch) {
        switch (code) {
            case 0: return ch < 128 && isAsciiDigit(ch);
            case 1: return !(ch < 128 && isAsciiDigit(ch));
            case 2: return ch < 128 && isAsciiSpace(ch);
            case 3: return !(ch < 128 && isAsciiSpace(ch));
            case 4: return ch < 128 && isAsciiWord(ch);
            case 5: return !(ch < 128 && isAsciiWord(ch));
            case 6: return ch == '\n';
            case 7: return ch != '\n';
            case 8: return ch < 128 && isAsciiWord(ch);
            case 9: return !(ch < 128 && isAsciiWord(ch));
            case 10: return isUniDigit(ch);
            case 11: return !isUniDigit(ch);
            case 12: return isUniSpace(ch);
            case 13: return !isUniSpace(ch);
            case 14: return isUniWord(ch);
            case 15: return !isUniWord(ch);
            case 16: return isUniLinebreak(ch);
            case 17: return !isUniLinebreak(ch);
            default: return false;
        }
    }
}
