// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.Arrays;

/**
 * A str or bytes-like object prepared for the sre engine: its characters as an {@code int[]} of code points (bytes
 * as 0..255), so positions are Python indexes even for strings with characters outside the BMP. Conversions of long
 * immutable subjects are cached by identity so that repeated {@code pattern.match(text, pos)} calls stay linear.
 */
final class SreSubject {
    final Object orig;
    final boolean isBytes;
    final int[] cp;
    final String str;        // the text for str subjects
    final byte[] raw;        // the data for bytes subjects (never mutated after construction)
    final boolean bmp;       // every code point is one UTF-16 unit, so substring offsets equal code point offsets

    private SreSubject(Object orig, boolean isBytes, int[] cp, String str, byte[] raw, boolean bmp) {
        this.orig = orig; this.isBytes = isBytes; this.cp = cp; this.str = str; this.raw = raw; this.bmp = bmp;
    }

    private static volatile SreSubject lastStr, lastBytes;
    private static final int CACHE_MIN = 64;

    int length() { return cp.length; }

    static SreSubject of(Object o) {
        Object v = o;
        if (v instanceof PyInstance pi && pi.payload != null) v = pi.payload;
        if (v instanceof String s) {
            SreSubject c = lastStr;
            if (c != null && c.str == s) return c;
            SreSubject r = fromString(o, s);
            if (s.length() >= CACHE_MIN) lastStr = r;
            return r;
        }
        if (v instanceof PyBytes b) {
            SreSubject c = lastBytes;
            if (c != null && c.raw == b.data) return c;
            SreSubject r = fromBytes(o, b.data);
            if (b.data.length >= CACHE_MIN) lastBytes = r;
            return r;
        }
        if (v instanceof PyByteSequence bs) return fromBytes(o, bs.toByteArray());
        throw new PyException("TypeError", "expected string or bytes-like object, got '" + PyRuntime.typeName(o) + "'");
    }

    private static SreSubject fromString(Object orig, String s) {
        int n = s.length();
        int[] a = new int[n];
        boolean surrogates = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            a[i] = c;
            if (c >= 0xd800 && c <= 0xdfff) surrogates = true;
        }
        if (!surrogates) return new SreSubject(orig, false, a, s, null, true);
        return new SreSubject(orig, false, s.codePoints().toArray(), s, null, false);
    }

    private static SreSubject fromBytes(Object orig, byte[] b) {
        int[] a = new int[b.length];
        for (int i = 0; i < a.length; i++) a[i] = b[i] & 0xff;
        return new SreSubject(orig, true, a, null, b, true);
    }

    /** getslice(): the text between two positions as a new str or bytes object. */
    Object slice(int from, int to) {
        if (isBytes) {
            if (from == 0 && to == raw.length && orig instanceof PyBytes) return orig;
            return new PyBytes(Arrays.copyOfRange(raw, from, to));
        }
        if (bmp) return str.substring(from, to);
        return new String(cp, from, to - from);
    }

    Object empty() { return isBytes ? new PyBytes(new byte[0]) : ""; }
}
