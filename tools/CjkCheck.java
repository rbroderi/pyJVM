// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Exhaustive differential check of {@link CjkCodec} (see tools/cjk_check.py, which runs the same sweep on CPython and
 * compares): prints one SHA-256 per sweep ({@code dec}, {@code enc}) over lines identical in format to the Python side;
 * with a second argument the lines themselves are written there so a mismatch can be diffed.
 *
 * <p>Decode domain (final decode, first error reported as start,end,kind): every 1- and 2-byte string; every 3-byte
 * string extending a 2-byte prefix that decodes as incomplete; every 4-byte string extending an incomplete 3-byte
 * prefix with a fourth byte from a fixed set. Encode domain: every code point alone, then the combining-pair strings.
 */
public final class CjkCheck {
    private static final int[] FOURTH = {0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x7F, 0x80, 0xFF};

    private final CjkCodec codec;
    private final MessageDigest md;
    private final PrintWriter dump;

    private CjkCheck(CjkCodec codec, MessageDigest md, PrintWriter dump) { this.codec = codec; this.md = md; this.dump = dump; }

    private void line(String s) {
        md.update((s + "\n").getBytes(StandardCharsets.UTF_8));
        if (dump != null) dump.println(s);
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i] & 255));
        return sb.toString();
    }

    private String decodeOne(byte[] d) {
        StringBuilder out = new StringBuilder();
        CjkCodec.Res r = codec.decode(d, 0, true, new long[4], out);
        if (r.reason == null) {
            StringBuilder sb = new StringBuilder("o");
            int[] cps = out.codePoints().toArray();
            for (int i = 0; i < cps.length; i++) sb.append(i > 0 ? "," : "").append(Integer.toHexString(cps[i]));
            return sb.toString();
        }
        return "e" + r.errStart + "," + r.errEnd + "," + (r.reason.equals(CjkCodec.INCOMPLETE) ? "i" : "c");
    }

    private boolean incomplete(byte[] d) {
        return codec.decode(d, 0, true, new long[4], new StringBuilder()).reason == CjkCodec.INCOMPLETE;
    }

    private void seq(byte[] d) { line(hex(d, d.length) + " " + decodeOne(d)); }

    void decodeSweep() {
        for (int a = 0; a < 256; a++) seq(new byte[]{(byte) a});
        for (int a = 0; a < 256; a++) for (int b = 0; b < 256; b++) seq(new byte[]{(byte) a, (byte) b});
        List<int[]> three = new ArrayList<>();
        for (int a = 0; a < 256; a++) for (int b = 0; b < 256; b++) {
            if (!incomplete(new byte[]{(byte) a, (byte) b})) continue;
            for (int c = 0; c < 256; c++) {
                byte[] d = {(byte) a, (byte) b, (byte) c};
                seq(d);
                if (incomplete(d)) three.add(new int[]{a, b, c});
            }
        }
        for (int[] p : three) for (int x : FOURTH) seq(new byte[]{(byte) p[0], (byte) p[1], (byte) p[2], (byte) x});
        if (codec.name.equals("hz")) {
            byte[] pre = {'~', '{'};
            for (int a = 0; a < 256; a++) seq(cat(pre, new byte[]{(byte) a}));
            for (int a = 0; a < 256; a++) for (int b = 0; b < 256; b++) seq(cat(pre, new byte[]{(byte) a, (byte) b}));
        }
        if (codec instanceof CjkCodec.Stateless st && st.jamoLead >= 0) {
            int l = st.jamoLead, f = st.jamoFiller;
            for (int x = 0xA0; x < 256; x++) for (int y = 0xA0; y < 256; y++) for (int z = 0xA0; z < 256; z++)
                seq(new byte[]{(byte) l, (byte) f, (byte) l, (byte) x, (byte) l, (byte) y, (byte) l, (byte) z});
            byte[] base = {(byte) l, (byte) f, (byte) l, (byte) 0xA1, (byte) l, (byte) 0xBF, (byte) l, (byte) 0xA1};
            for (int mark = -1; mark < 3; mark++) {
                byte[] b = base.clone();
                if (mark >= 0) b[2 + 2 * mark] = (byte) 0xA5;
                for (int k = 2; k <= 8; k++) seq(java.util.Arrays.copyOf(b, k));
            }
        }
    }

    private static byte[] cat(byte[] a, byte[] b) {
        byte[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private String encodeOne(String s) {
        int[] cps = s.codePoints().toArray();
        CjkCodec.Bytes out = new CjkCodec.Bytes();
        CjkCodec.Res r = codec.encode(cps, 0, true, new long[4], out);
        if (r.reason != null) return "e" + r.errStart + "," + r.errEnd;
        return hex(out.b, out.n);
    }

    void encodeSweep() {
        for (int cp = 0; cp < 0x110000; cp++) line(Integer.toHexString(cp) + " " + encodeOne(new String(Character.toChars(cp))));
        if (codec.name.equals("hz")) {
            for (int cp = 0; cp < 0x110000; cp++) {
                String c = new String(Character.toChars(cp));
                pairLine(c + "a"); pairLine("a" + c); pairLine(c + c);
            }
        }
        if (codec instanceof CjkCodec.Stateless st) {
            List<Integer> firsts = new ArrayList<>(), seconds = new ArrayList<>();
            for (int[] p : st.pairs) { if (!firsts.contains(p[0])) firsts.add(p[0]); if (!seconds.contains(p[1])) seconds.add(p[1]); }
            for (int a : firsts) {
                List<Integer> bs = new ArrayList<>(seconds);
                bs.add(0x41); bs.add(0x3042); bs.add(a);
                String A = new String(Character.toChars(a));
                for (int b : bs) pairLine(A + new String(Character.toChars(b)));
                pairLine(A);
                pairLine("A" + A);
                pairLine(A + "ÿ");
            }
        }
    }

    private void pairLine(String s) {
        StringBuilder k = new StringBuilder();
        s.codePoints().forEach(c -> k.append(Integer.toHexString(c)).append('.'));
        line("s" + k + " " + encodeOne(s));
    }

    public static void main(String[] args) throws Exception {
        CjkCodec codec = CjkCodec.forName(args[0]);
        if (codec == null) throw new IllegalArgumentException("no codec " + args[0]);
        for (String which : new String[]{"dec", "enc"}) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            PrintWriter dump = args.length > 1 ? new PrintWriter(args[1] + "." + which + ".txt", StandardCharsets.UTF_8) : null;
            CjkCheck c = new CjkCheck(codec, md, dump);
            if (which.equals("dec")) c.decodeSweep(); else c.encodeSweep();
            if (dump != null) dump.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 255));
            System.out.println(which + " " + sb);
        }
    }
}
