// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.BinasciiModule.*;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * base64 conversion for {@code binascii}: the classic {@code a2b_base64(data, strict_mode)} /
 * {@code b2a_base64(data, newline)} of 3.13 and the 3.15 keywords (padded, wrapcol, alphabet, ignorechars,
 * canonical), plus the 3.15 alphabet constants.
 */
final class BinasciiExtra {
    private BinasciiExtra() {}

    static final byte[] STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    static void install(PyModule m) {
        if (!LangVersion.atLeast(3, 14)) return;
        String[][] consts = {
            {"BASE64_ALPHABET", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"},
            {"URLSAFE_BASE64_ALPHABET", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"},
            {"BASE32_ALPHABET", "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"},
            {"BASE32HEX_ALPHABET", "0123456789ABCDEFGHIJKLMNOPQRSTUV"},
            {"ASCII85_ALPHABET", "!\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstu"},
            {"BASE85_ALPHABET", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~"},
            {"Z85_ALPHABET", "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?&<>()[]{}@%$#"},
            {"BINHEX_ALPHABET", "!\"#$%&'()*+,-012345689@ABCDEFGHIJKLMNPQRSTUVXYZ[`abcdefhijklmpqr"},
            {"CRYPT_ALPHABET", "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"},
            {"UU_ALPHABET", " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_"},
        };
        for (String[] c : consts) m.attrs.put(c[0], asciiBytes(c[1]));
    }

    private static byte[] alphabet(Object o) {
        if (o == NativeArgs.MISSING) return STD;
        byte[] a = bytesArg(o);
        if (a.length != 64) throw new PyException("ValueError", "alphabet must have length 64");
        return a;
    }

    private static long nonNegative(Object o) {
        long v = IoUtil.asLong(o);
        if (v < 0) throw new PyException("ValueError", "Cannot convert negative int");
        return v;
    }

    // ---- b2a_base64 --------------------------------------------------------------------------------------

    static Object b2aBase64(List<Object> a, Map<Object, Object> k) {
        if (a.size() != 1) throw new PyException("TypeError", "b2a_base64() takes exactly 1 positional argument (" + a.size() + " given)");
        boolean modern = LangVersion.atLeast(3, 14);
        String[] names = modern ? new String[]{"data", "padded", "wrapcol", "newline", "alphabet"} : new String[]{"data", "newline"};
        Object[] p = NativeArgs.parse("b2a_base64", List.of(a.get(0)), k, names, 1, 1);
        byte[] in = bytesArg(a.get(0));
        boolean padded = true, newline = true;
        long wrapcol = 0;
        byte[] alpha = STD;
        if (modern) {
            if (p[1] != NativeArgs.MISSING) padded = PyRuntime.truth(p[1]);
            if (p[2] != NativeArgs.MISSING) wrapcol = nonNegative(p[2]);
            if (p[3] != NativeArgs.MISSING) newline = PyRuntime.truth(p[3]);
            alpha = alphabet(p[4]);
        } else if (p[1] != NativeArgs.MISSING) newline = PyRuntime.truth(p[1]);
        if (wrapcol > 0) wrapcol = Math.max(4, wrapcol / 4 * 4);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 4 / 3 + 8);
        long col = 0;
        for (int i = 0; i < in.length; i += 3) {
            int n = Math.min(3, in.length - i);
            int v = (in[i] & 0xff) << 16 | (n > 1 ? (in[i + 1] & 0xff) << 8 : 0) | (n > 2 ? in[i + 2] & 0xff : 0);
            int chars = n + 1;
            for (int j = 0; j < 4; j++) {
                if (j >= chars && !padded) break;
                if (wrapcol > 0 && col == wrapcol) { out.write('\n'); col = 0; }
                out.write(j < chars ? alpha[(v >> (18 - 6 * j)) & 63] : '=');
                col++;
            }
        }
        if (newline) out.write('\n');
        return bytes(out.toByteArray());
    }

    // ---- a2b_base64 --------------------------------------------------------------------------------------

    static Object a2bBase64(List<Object> a, Map<Object, Object> k) {
        if (a.size() != 1) throw new PyException("TypeError", "a2b_base64() takes exactly 1 positional argument (" + a.size() + " given)");
        boolean modern = LangVersion.atLeast(3, 14);
        String[] names = modern ? new String[]{"data", "strict_mode", "padded", "alphabet", "ignorechars", "canonical"} : new String[]{"data", "strict_mode"};
        Object[] p = NativeArgs.parse("a2b_base64", List.of(a.get(0)), k, names, 1, 1);
        byte[] in = asciiArg(a.get(0));
        boolean strict = p[1] != NativeArgs.MISSING && PyRuntime.truth(p[1]);
        boolean padded = true, canonical = false;
        byte[] alpha = STD;
        boolean[] ignore = null;
        if (modern) {
            if (p[2] != NativeArgs.MISSING) padded = PyRuntime.truth(p[2]);
            alpha = alphabet(p[3]);
            if (p[4] != NativeArgs.MISSING) {
                ignore = new boolean[256];
                for (byte b : bytesArg(p[4])) ignore[b & 0xff] = true;
                if (p[1] == NativeArgs.MISSING) strict = true;
            }
            canonical = p[5] != NativeArgs.MISSING && PyRuntime.truth(p[5]);
        }
        int[] table = new int[256];
        Arrays.fill(table, -1);
        for (int i = 0; i < 64; i++) table[alpha[i] & 0xff] = i;
        boolean padIgnored = ignore != null && ignore['='];
        boolean strictPad = strict && !padIgnored;
        if (strictPad && padded && in.length > 0 && in[0] == '=') throw err("Leading padding not allowed");
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 3 / 4 + 3);
        int quad = 0, pads = 0, left = 0, finalQuad = 0, finalLeft = 0;
        boolean paddingStarted = false, padComplete = false;
        for (int i = 0; i < in.length; i++) {
            int ch = in[i] & 0xff;
            if (ch == '=' && padded) {
                paddingStarted = true;
                if (strictPad) {
                    if (quad == 0) throw err("Excess padding not allowed");
                    if (quad == 1) throw err("Invalid base64-encoded string: number of data characters (" + (out.size() / 3 * 4 + 1) + ") cannot be 1 more than a multiple of 4");
                }
                if (quad >= 2 && quad + ++pads >= 4) {
                    if (strictPad) {
                        padComplete = true;
                        finalQuad = quad; finalLeft = left;
                        quad = 0; left = 0;
                    }
                }
                continue;
            }
            int v = table[ch];
            if (v < 0) {
                if (strict && !(ignore != null && ignore[ch])) {
                    if (ch == '=') throw err("Padding not allowed");
                    throw err("Only base64 data is allowed");
                }
                continue;
            }
            if (strictPad && padComplete) throw err("Excess data after padding");
            if (strictPad && paddingStarted) throw err("Discontinuous padding not allowed");
            pads = 0;
            switch (quad) {
                case 0: quad = 1; left = v; break;
                case 1: quad = 2; out.write((left << 2) | (v >> 4)); left = v & 0x0f; break;
                case 2: quad = 3; out.write((left << 4) | (v >> 2)); left = v & 0x03; break;
                default: quad = 0; out.write((left << 6) | v); left = 0; break;
            }
        }
        if (padComplete) return finish(out, finalQuad, finalLeft, canonical);
        if (quad != 0) {
            if (quad == 1) throw err("Invalid base64-encoded string: number of data characters (" + (out.size() / 3 * 4 + 1) + ") cannot be 1 more than a multiple of 4");
            if (padded && quad + pads < 4) throw err("Incorrect padding");
        }
        return finish(out, quad, left, canonical);
    }

    private static Object finish(ByteArrayOutputStream out, int quad, int left, boolean canonical) {
        if (canonical && quad >= 2 && left != 0) throw err("Non-zero padding bits");
        return bytes(out.toByteArray());
    }
}
