// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * {@code binascii}: hex, base64, uu, quoted-printable, CRC-32 and CRC-CCITT conversions with CPython's error
 * messages. The 3.15 additions (base32/base85/ascii85 and the alphabet parameters of base64) are in
 * {@link BinasciiExtra}.
 */
public final class BinasciiModule {
    private BinasciiModule() {}

    static PyClass errorClass, incompleteClass;

    static PyException err(String msg) { return PyRuntime.userException(PyRuntime.instantiate(errorClass, new Object[]{msg})); }

    static synchronized PyModule create() {
        PyModule m = new PyModule("binascii", "pyjvm315.runtime.BinasciiModule");
        errorClass = (PyClass) PyRuntime.classCreate("Error", "binascii");
        PyRuntime.classAddBase(errorClass, PyRuntime.builtinType("ValueError"));
        finish(errorClass);
        incompleteClass = (PyClass) PyRuntime.classCreate("Incomplete", "binascii");
        PyRuntime.classAddBase(incompleteClass, PyRuntime.builtinType("Exception"));
        finish(incompleteClass);
        m.attrs.put("Error", errorClass);
        m.attrs.put("Incomplete", incompleteClass);
        fn(m, "a2b_uu", (a, k) -> a2bUu(NativeArgs.one("binascii.a2b_uu", a, k)));
        fn(m, "b2a_uu", (a, k) -> b2aUu(a, k));
        fn(m, "a2b_base64", (a, k) -> BinasciiExtra.a2bBase64(a, k));
        fn(m, "b2a_base64", (a, k) -> BinasciiExtra.b2aBase64(a, k));
        fn(m, "a2b_qp", (a, k) -> a2bQp(a, k));
        fn(m, "b2a_qp", (a, k) -> b2aQp(a, k));
        fn(m, "crc32", (a, k) -> crc32(a, k));
        fn(m, "crc_hqx", (a, k) -> crcHqx(a, k));
        NativeFunction hex = new NativeFunction("b2a_hex", (a, k) -> b2aHex("b2a_hex", a, k));
        m.attrs.put("b2a_hex", hex);
        m.attrs.put("hexlify", new NativeFunction("hexlify", (a, k) -> b2aHex("hexlify", a, k)));
        NativeFunction unhex = new NativeFunction("a2b_hex", (a, k) -> a2bHex("a2b_hex", a, k));
        m.attrs.put("a2b_hex", unhex);
        m.attrs.put("unhexlify", new NativeFunction("unhexlify", (a, k) -> a2bHex("unhexlify", a, k)));
        BinasciiExtra.install(m);
        m.attrs.put("__doc__", "Conversion between binary data and ASCII");
        return m;
    }

    // ---- argument helpers --------------------------------------------------------------------------------

    /** A bytes-like argument. */
    static byte[] bytesArg(Object o) { return IoUtil.bytesLike(o); }

    /** An ASCII str or a bytes-like argument (the a2b_* functions). */
    static byte[] asciiArg(Object o) {
        Object u = NativeSubclass.unwrap(o);
        if (u instanceof String s) {
            byte[] out = new byte[s.length()];
            for (int i = 0; i < out.length; i++) {
                char c = s.charAt(i);
                if (c >= 128) throw new PyException("ValueError", "string argument should contain only ASCII characters");
                out[i] = (byte) c;
            }
            return out;
        }
        return IoUtil.bytesLike(o);
    }

    static Object bytes(byte[] b) { return new PyBytes(b); }

    static boolean truthKw(Object v) { return v != NativeArgs.MISSING && PyRuntime.truth(v); }

    // ---- hex ---------------------------------------------------------------------------------------------

    private static final char[] HEXD = "0123456789abcdef".toCharArray();

    static Object b2aHex(String fname, List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse(fname, a, k, new String[]{"data", "sep", "bytes_per_sep"}, 1, 0);
        byte[] data = bytesArg(p[0]);
        int sepc = -1;
        long bps = 1;
        if (p[1] != NativeArgs.MISSING) {
            if (p[2] != NativeArgs.MISSING) bps = IoUtil.asLong(p[2]);
            Object sep = NativeSubclass.unwrap(p[1]);
            if (!(sep instanceof String) && !(sep instanceof PyByteSequence)) {
                try { PyRuntime.len(sep); } catch (RuntimeException e) { throw new PyException("TypeError", "object of type '" + PyRuntime.typeName(sep) + "' has no len()"); }
            }
            if (sep instanceof String s) {
                if (s.length() != 1) throw new PyException("ValueError", "sep must be length 1.");
                sepc = s.charAt(0) & 0xff;
            } else if (sep instanceof PyBytes bs) {
                if (bs.byteSize() != 1) throw new PyException("ValueError", "sep must be length 1.");
                sepc = bs.unsignedAt(0);
            } else throw new PyException("TypeError", "sep must be str or bytes.");
        } else if (p[2] != NativeArgs.MISSING) bps = IoUtil.asLong(p[2]);
        long step = Math.abs(bps);
        boolean sepOn = sepc >= 0 && step != 0 && step < data.length;
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (sepOn && i > 0 && (bps > 0 ? (data.length - i) % step == 0 : i % step == 0)) sb.append((char) sepc);
            sb.append(HEXD[(data[i] >> 4) & 15]).append(HEXD[data[i] & 15]);
        }
        return asciiBytes(sb);
    }

    static Object asciiBytes(CharSequence s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < out.length; i++) out[i] = (byte) s.charAt(i);
        return new PyBytes(out);
    }

    private static int digit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static Object a2bHex(String fname, List<Object> a, Map<Object, Object> k) {
        boolean modern = LangVersion.atLeast(3, 14);
        Object arg;
        boolean[] ignore = null;
        if (modern) {
            Object[] p = NativeArgs.parse(fname, a, k, new String[]{"hexstr", "ignorechars"}, 1, 1);
            arg = p[0];
            if (p[1] != NativeArgs.MISSING) { ignore = new boolean[256]; for (byte x : bytesArg(p[1])) ignore[x & 0xff] = true; }
        } else arg = NativeArgs.one("binascii." + fname, a, k);
        byte[] in = asciiArg(arg);
        if (!modern && in.length % 2 != 0) throw err("Odd-length string");
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length / 2);
        int hi = -1;
        for (byte x : in) {
            int c = x & 0xff;
            if (modern && ignore != null && ignore[c]) continue;
            int d = digit(c);
            if (d < 0) throw err("Non-hexadecimal digit found");
            if (hi < 0) hi = d; else { out.write(hi << 4 | d); hi = -1; }
        }
        if (hi >= 0) throw err("Odd number of hexadecimal digits");
        return bytes(out.toByteArray());
    }

    // ---- CRC ---------------------------------------------------------------------------------------------

    private static final int[] CRC32T = new int[256];
    private static final int[] CRCHQX = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
            CRC32T[i] = c;
            int h = i << 8;
            for (int j = 0; j < 8; j++) h = (h & 0x8000) != 0 ? (h << 1) ^ 0x1021 : h << 1;
            CRCHQX[i] = h & 0xffff;
        }
    }

    static long crc32Update(byte[] data, long start) {
        int c = ~(int) start;
        for (byte b : data) c = CRC32T[(c ^ b) & 0xff] ^ (c >>> 8);
        return (~c) & 0xffffffffL;
    }

    private static long maskedUnsigned(Object o) {
        BigInteger b = BuiltinsExtra.rangeIndex(o);
        return b.longValue();
    }

    private static Object crc32(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "binascii.crc32() takes no keyword arguments");
        if (a.isEmpty()) throw new PyException("TypeError", "crc32 expected at least 1 argument, got 0");
        if (a.size() > 2) throw new PyException("TypeError", "crc32 expected at most 2 arguments, got " + a.size());
        byte[] data = bytesArg(a.get(0));
        long start = a.size() > 1 ? maskedUnsigned(a.get(1)) : 0;
        return crc32Update(data, start & 0xffffffffL);
    }

    private static Object crcHqx(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "binascii.crc_hqx() takes no keyword arguments");
        if (a.size() != 2) throw new PyException("TypeError", "crc_hqx expected 2 arguments, got " + a.size());
        byte[] data = bytesArg(a.get(0));
        int crc = (int) (maskedUnsigned(a.get(1)) & 0xffff);
        for (byte b : data) crc = ((crc << 8) & 0xff00) ^ CRCHQX[((crc >> 8) & 0xff) ^ (b & 0xff)];
        return (long) (crc & 0xffff);
    }

    // ---- uu ----------------------------------------------------------------------------------------------

    static Object a2bUu(Object arg) {
        byte[] in = asciiArg(arg);
        int pos = 0, len = in.length;
        if (len == 0) throw err("Missing length byte");
        int binLen = len > 0 ? ((in[pos++] - ' ') & 077) : 32;
        if (len > 0) len--;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int leftbits = 0, leftchar = 0;
        for (; binLen > 0; len--, pos++) {
            int ch = len > 0 ? in[pos] & 0xff : 0;
            if (ch == '\n' || ch == '\r' || len <= 0) ch = 0;
            else {
                if (ch < ' ' || ch > ' ' + 64) throw err("Illegal char");
                ch = (ch - ' ') & 077;
            }
            leftchar = (leftchar << 6) | ch;
            leftbits += 6;
            if (leftbits >= 8) {
                leftbits -= 8;
                out.write((leftchar >> leftbits) & 0xff);
                leftchar &= (1 << leftbits) - 1;
                binLen--;
            }
        }
        while (len-- > 0) {
            int ch = in[pos++] & 0xff;
            if (ch != ' ' && ch != ' ' + 64 && ch != '\n' && ch != '\r') throw err("Trailing garbage");
        }
        return bytes(out.toByteArray());
    }

    private static Object b2aUu(List<Object> a, Map<Object, Object> k) {
        if (a.size() != 1) throw new PyException("TypeError", "b2a_uu() takes exactly 1 positional argument (" + a.size() + " given)");
        Object[] p = NativeArgs.parse("b2a_uu", List.of(a.get(0)), k, new String[]{"data", "backtick"}, 1, 1);
        boolean backtick = truthKw(p[1]);
        byte[] in = bytesArg(a.get(0));
        if (in.length > 45) throw err("At most 45 bytes at once");
        StringBuilder sb = new StringBuilder();
        int binLen = in.length;
        sb.append(backtick && binLen == 0 ? '`' : (char) (' ' + binLen));
        int leftbits = 0, leftchar = 0, at = 0;
        for (; binLen > 0 || leftbits != 0; binLen--, at++) {
            if (binLen > 0) leftchar = (leftchar << 8) | (in[at] & 0xff); else leftchar <<= 8;
            leftbits += 8;
            while (leftbits >= 6) {
                int ch = (leftchar >> (leftbits - 6)) & 0x3f;
                leftbits -= 6;
                sb.append(backtick && ch == 0 ? '`' : (char) (ch + ' '));
            }
        }
        sb.append('\n');
        return asciiBytes(sb);
    }

    // ---- quoted-printable --------------------------------------------------------------------------------

    private static Object a2bQp(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("a2b_qp", a, k, new String[]{"data", "header"}, 1, 0);
        byte[] d = asciiArg(p[0]);
        boolean header = truthKw(p[1]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int in = 0, n = d.length;
        while (in < n) {
            if (d[in] == '=') {
                in++;
                if (in >= n) break;
                if (d[in] == '\n' || d[in] == '\r') {
                    if (d[in] != '\n') while (in < n && d[in] != '\n') in++;
                    if (in < n) in++;
                } else if (d[in] == '=') {
                    out.write('=');
                    in++;
                } else if (in + 1 < n && digit(d[in] & 0xff) >= 0 && digit(d[in + 1] & 0xff) >= 0) {
                    out.write(digit(d[in] & 0xff) << 4 | digit(d[in + 1] & 0xff));
                    in += 2;
                } else out.write('=');
            } else if (header && d[in] == '_') {
                out.write(' ');
                in++;
            } else {
                out.write(d[in]);
                in++;
            }
        }
        return bytes(out.toByteArray());
    }

    private static final char[] HEXU = "0123456789ABCDEF".toCharArray();

    private static Object b2aQp(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("b2a_qp", a, k, new String[]{"data", "quotetabs", "istext", "header"}, 1, 0);
        byte[] d = bytesArg(p[0]);
        boolean quotetabs = truthKw(p[1]), istext = p[2] == NativeArgs.MISSING || PyRuntime.truth(p[2]), header = truthKw(p[3]);
        final int max = 76;
        int n = d.length;
        boolean crlf = false;
        for (int i = 0; i < n; i++) if (d[i] == '\n') { crlf = i > 0 && d[i - 1] == '\r'; break; }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] o = new byte[n * 6 + 16];
        int op = 0, in = 0, linelen = 0;
        while (in < n) {
            int c = d[in] & 0xff;
            boolean enc = c > 126 || c == '=' || (header && c == '_')
                || (c == '.' && linelen == 0 && (in + 1 == n || d[in + 1] == '\n' || d[in + 1] == '\r' || d[in + 1] == 0))
                || (!istext && (c == '\r' || c == '\n'))
                || ((c == '\t' || c == ' ') && in + 1 == n)
                || (c < 33 && c != '\r' && c != '\n' && (quotetabs || (c != '\t' && c != ' ')));
            if (enc) {
                if (linelen + 3 >= max) {
                    o[op++] = '=';
                    if (crlf) o[op++] = '\r';
                    o[op++] = '\n';
                    linelen = 0;
                }
                o[op++] = '='; o[op++] = (byte) HEXU[(c >> 4) & 15]; o[op++] = (byte) HEXU[c & 15];
                in++; linelen += 3;
            } else if (istext && (c == '\n' || (in + 1 < n && c == '\r' && d[in + 1] == '\n'))) {
                linelen = 0;
                if (op > 0 && (o[op - 1] == ' ' || o[op - 1] == '\t')) {
                    int ch = o[op - 1];
                    o[op - 1] = '=';
                    o[op++] = (byte) HEXU[(ch >> 4) & 15];
                    o[op++] = (byte) HEXU[ch & 15];
                }
                if (crlf) o[op++] = '\r';
                o[op++] = '\n';
                in += c == '\r' ? 2 : 1;
            } else {
                if (in + 1 != n && d[in + 1] != '\n' && linelen + 1 >= max) {
                    o[op++] = '=';
                    if (crlf) o[op++] = '\r';
                    o[op++] = '\n';
                    linelen = 0;
                }
                linelen++;
                if (header && c == ' ') { o[op++] = '_'; in++; } else { o[op++] = d[in++]; }
            }
        }
        out.write(o, 0, op);
        return bytes(out.toByteArray());
    }
}
