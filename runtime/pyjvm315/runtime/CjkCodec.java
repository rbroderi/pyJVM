// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * The CJK multi-byte codecs behind {@code _multibytecodec}: one engine per codec that decodes/encodes up to the first
 * error (or the end of input) and reports it as CPython's {@code multibytecodec.c} would, without Python dependencies so
 * that tools/CjkCheck.java can sweep it against CPython. The mappings are the generated tables in {@code cjk/*.bin}
 * (tools/cjk_gen.py); the stateful ISO-2022 and HZ codecs live in {@link CjkStateful}.
 *
 * <p>Return conventions of {@link #dec} / {@link #enc} follow CPython's: a positive count consumed, 0 for "too few"
 * (decode: incomplete sequence; encode: the character may combine with the next, so it stays pending), negative for an
 * illegal sequence (decode: minus its length in bytes; encode: -1 for the current character).
 */
abstract class CjkCodec {
    static final String ILLEGAL = "illegal multibyte sequence", INCOMPLETE = "incomplete multibyte sequence";

    final String name;

    CjkCodec(String name) { this.name = name; }

    /** Growable byte output. */
    static final class Bytes {
        byte[] b = new byte[64];
        int n;
        void put(int v) {
            if (n == b.length) b = java.util.Arrays.copyOf(b, n * 2);
            b[n++] = (byte) v;
        }
        void put(int v, int len) {
            for (int i = len - 1; i >= 0; i--) put(v >>> (8 * i));
        }
        byte[] toArray() { return java.util.Arrays.copyOf(b, n); }
    }

    /** Outcome of a run: {@code pos} is where it stopped; an error is {@code reason != null} with [errStart, errEnd). */
    static final class Res {
        int pos, errStart, errEnd;
        String reason;
    }

    abstract int dec(byte[] d, int pos, int n, StringBuilder out, long[] st);

    abstract int enc(int[] c, int i, int n, boolean fin, Bytes out, long[] st);

    /** What a final encode appends after the last character (ISO-2022 / HZ return to ASCII). */
    void flush(Bytes out, long[] st) {}

    final Res decode(byte[] d, int pos, boolean fin, long[] st, StringBuilder out) {
        Res r = new Res();
        int n = d.length;
        while (pos < n) {
            int k = dec(d, pos, n, out, st);
            if (k > 0) { pos += k; continue; }
            r.pos = pos;
            r.errStart = pos;
            if (k == 0) {
                if (!fin) return r;
                r.errEnd = n; r.reason = INCOMPLETE;
            } else {
                r.errEnd = pos - k; r.reason = ILLEGAL;
            }
            return r;
        }
        r.pos = n;
        return r;
    }

    final Res encode(int[] c, int pos, boolean fin, long[] st, Bytes out) {
        Res r = new Res();
        int n = c.length;
        while (pos < n) {
            int k = enc(c, pos, n, fin, out, st);
            if (k > 0) { pos += k; continue; }
            r.pos = pos;
            if (k == 0) return r;
            r.errStart = pos; r.errEnd = pos - k; r.reason = ILLEGAL;
            return r;
        }
        r.pos = n;
        if (fin) flush(out, st);
        return r;
    }

    // ---------------------------------------------------------------------------------------------- registry

    private static final Map<String, CjkCodec> CACHE = new HashMap<>();

    /** The engine for a CPython codec name ({@code cp932}, {@code iso2022_jp_2}, ...), or null when there is none. */
    static synchronized CjkCodec forName(String name) {
        CjkCodec c = CACHE.get(name);
        if (c == null) {
            c = CjkStateful.create(name);
            if (c == null && Stateless.NAMES.contains(name)) c = new Stateless(name);
            if (c != null) CACHE.put(name, c);
        }
        return c;
    }

    static InputStream resource(String file) {
        InputStream in = CjkCodec.class.getResourceAsStream("cjk/" + file);
        if (in == null) throw new IllegalStateException("missing codec table cjk/" + file);
        return new InflaterInputStream(in);
    }

    static byte[] readAll(InputStream in) {
        try (in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            in.transferTo(o);
            return o.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------- table codecs

    /** The stateless codecs (every codec except HZ and ISO-2022), driven by the table written by tools/cjk_gen.py. */
    static final class Stateless extends CjkCodec {
        static final java.util.Set<String> NAMES = java.util.Set.of("gb2312", "gbk", "gb18030", "big5hkscs", "big5", "cp950",
            "cp932", "euc_jis_2004", "euc_jisx0213", "euc_jp", "shift_jis", "shift_jis_2004", "shift_jisx0213", "cp949", "euc_kr", "johab");
        static final int LEAD = -9, NEED3 = -10, NEED4 = -11, NEED8 = -12, COMBO = 0x200000;

        private byte[] buf;
        private int at;
        int[] dec1;
        final int[][] rows2 = new int[256][];
        final Map<Integer, int[]> rows3 = new HashMap<>();
        int[][] pairs;
        int gbBad;
        int[] gbLin, gbCp, gbCnt;
        int[] bmpVal = new int[0x10000];
        byte[] bmpLen = new byte[0x10000];
        int[] runCp = new int[16], runCnt = new int[16], runVal = new int[16];
        byte[] runLen = new byte[16];
        int runs;
        int[] pairLen, pairVal;

        Stateless(String name) {
            super(name);
            buf = readAll(resource(name + ".bin"));
            at = 0;
            load();
            buf = null;
        }

        private int u() {
            int v = 0, shift = 0;
            while (true) {
                int c = buf[at++] & 255;
                v |= (c & 0x7F) << shift;
                if (c < 0x80) return v;
                shift += 7;
            }
        }

        private int s() {
            int v = u();
            return (v >>> 1) ^ -(v & 1);
        }

        /** A zigzag varint that may need 33 bits (value deltas of four-byte GB18030 codes). */
        private long sl() {
            long v = 0;
            int shift = 0;
            while (true) {
                int c = buf[at++] & 255;
                v |= (long) (c & 0x7F) << shift;
                if (c < 0x80) return (v >>> 1) ^ -(v & 1);
                shift += 7;
            }
        }

        private int[] row() {
            int[] r = new int[256];
            int prev = 0;
            for (int i = 0; i < 256; i++) r[i] = prev += s();
            return r;
        }

        private void load() {
            if (u() != 1) throw new IllegalStateException("bad codec table");
            dec1 = row();
            for (int k = u(); k > 0; k--) { int l = u(); rows2[l] = row(); }
            for (int k = u(); k > 0; k--) { int l = u(), b2 = u(); rows3.put(l << 8 | b2, row()); }
            pairs = new int[u()][];
            for (int k = 0; k < pairs.length; k++) pairs[k] = new int[]{u(), u()};
            if (u() == 1) loadJamo();
            if (u() == 1) loadGb4();
            java.util.Arrays.fill(bmpVal, -1);
            int cp = 0, val = 0, n = u();
            for (int k = 0; k < n; k++) {
                int len = u();
                cp += s(); val += (int) sl();
                if (cp < 0x10000) { bmpVal[cp] = val; bmpLen[cp] = (byte) len; } else addRun(cp, val, len);
            }
            pairLen = new int[u()]; pairVal = new int[pairLen.length];
            for (int k = 0; k < pairLen.length; k++) { pairLen[k] = u(); pairVal[k] = u(); }
        }

        /** EUC-KR make-up sequences: lead byte, filler byte and the code -> index tables of initial, medial, final jamo. */
        int jamoLead = -1, jamoFiller, jamoBad;
        int[] jCho, jJung, jJong;
        int[] invCho = new int[19], invJung = new int[21], invJong = new int[28];

        private void loadJamo() {
            int[][] t = new int[3][];
            for (int i = 0; i < 3; i++) { t[i] = new int[u()]; for (int k = 0; k < t[i].length; k++) t[i][k] = u(); }
            jCho = t[0]; jJung = t[1]; jJong = t[2];
            jamoBad = u();
            for (int l = 0; l < 256; l++) {
                if (rows2[l] == null) continue;
                for (int b = 0; b < 256; b++) if (rows2[l][b] == NEED8) { jamoLead = l; jamoFiller = b; }
            }
            java.util.Arrays.fill(invCho, -1); java.util.Arrays.fill(invJung, -1); java.util.Arrays.fill(invJong, -1);
            for (int k = t[0].length - 1; k >= 0; k--) if (t[0][k] != 255) invCho[t[0][k]] = 0xA1 + k;
            for (int k = t[1].length - 1; k >= 0; k--) if (t[1][k] != 255) invJung[t[1][k]] = 0xBF + k;
            for (int k = t[2].length - 1; k >= 0; k--) if (t[2][k] != 255) invJong[t[2][k]] = 0xA1 + k;
        }

        private int jamoDec(byte[] d, int pos, StringBuilder out) {
            int c = d[pos] & 255;
            if ((d[pos + 2] & 255) != c || (d[pos + 4] & 255) != c || (d[pos + 6] & 255) != c) return -jamoBad;
            int x = (d[pos + 3] & 255) - 0xA1, y = (d[pos + 5] & 255) - 0xBF, z = d[pos + 7] & 255;
            int cho = x >= 0 && x < jCho.length ? jCho[x] : 255, jung = y >= 0 && y < jJung.length ? jJung[y] : 255, jong;
            if (z == jamoFiller) jong = 0;
            else jong = z - 0xA1 >= 0 && z - 0xA1 < jJong.length ? jJong[z - 0xA1] : 255;
            if (cho == 255 || jung == 255 || jong == 255) return -jamoBad;
            out.appendCodePoint(0xAC00 + cho * 588 + jung * 28 + jong);
            return 8;
        }

        private void loadGb4() {
            int nb = u();
            gbBad = u();
            for (int k = 1; k < nb; k++) u();
            int n = u();
            gbLin = new int[n]; gbCp = new int[n]; gbCnt = new int[n];
            int pl = 0, pc = 0;
            for (int k = 0; k < n; k++) {
                gbLin[k] = pl + s(); gbCp[k] = pc + s(); gbCnt[k] = u();
                pl = gbLin[k] + gbCnt[k]; pc = gbCp[k] + gbCnt[k];
            }
        }

        private void addRun(int cp, int val, int len) {
            int last = runs - 1;
            if (last >= 0 && runCp[last] + runCnt[last] == cp && runVal[last] + runCnt[last] == val && runLen[last] == len) {
                runCnt[last]++;
                return;
            }
            if (runs == runCp.length) {
                int m = runs * 2;
                runCp = java.util.Arrays.copyOf(runCp, m); runCnt = java.util.Arrays.copyOf(runCnt, m);
                runVal = java.util.Arrays.copyOf(runVal, m); runLen = java.util.Arrays.copyOf(runLen, m);
            }
            runCp[runs] = cp; runCnt[runs] = 1; runVal[runs] = val; runLen[runs] = (byte) len;
            runs++;
        }

        private void emit(int w, StringBuilder out) {
            if (w >= COMBO) { int[] p = pairs[w - COMBO]; out.appendCodePoint(p[0]).appendCodePoint(p[1]); }
            else out.appendCodePoint(w);
        }

        @Override
        int dec(byte[] d, int pos, int n, StringBuilder out, long[] st) {
            int c = d[pos] & 255, v = dec1[c];
            if (v >= 0) { emit(v, out); return 1; }
            if (v != LEAD) return v;
            if (n - pos < 2) return 0;
            int b2 = d[pos + 1] & 255, w = rows2[c][b2];
            if (w >= 0) { emit(w, out); return 2; }
            if (w == NEED3) {
                if (n - pos < 3) return 0;
                int x = rows3.get(c << 8 | b2)[d[pos + 2] & 255];
                if (x >= 0) { emit(x, out); return 3; }
                return x;
            }
            if (w == NEED4) {
                if (n - pos < 4) return 0;
                return gb4(c, b2, d[pos + 2] & 255, d[pos + 3] & 255, out);
            }
            if (w == NEED8) {
                if (n - pos < 8) return 0;
                return jamoDec(d, pos, out);
            }
            return w;
        }

        private int gb4(int b1, int b2, int b3, int b4, StringBuilder out) {
            if (b3 < 0x81 || b3 > 0xFE || b4 < 0x30 || b4 > 0x39) return -1;
            int lin = (((b1 - 0x81) * 10 + b2 - 0x30) * 126 + b3 - 0x81) * 10 + b4 - 0x30;
            int lo = 0, hi = gbLin.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (gbLin[mid] > lin) hi = mid - 1;
                else if (gbLin[mid] + gbCnt[mid] <= lin) lo = mid + 1;
                else { out.appendCodePoint(gbCp[mid] + lin - gbLin[mid]); return 4; }
            }
            return -gbBad;
        }

        /** The packed bytes and length of one code point: {@code len << 32 | value}, or -1 when it has no encoding. */
        long lookup(int cp) {
            if (cp < 0x10000) return bmpLen[cp] == 0 ? -1 : ((long) bmpLen[cp] << 32) | (bmpVal[cp] & 0xFFFFFFFFL);
            int lo = 0, hi = runs - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (runCp[mid] > cp) hi = mid - 1;
                else if (runCp[mid] + runCnt[mid] <= cp) lo = mid + 1;
                else return ((long) runLen[mid] << 32) | ((runVal[mid] + cp - runCp[mid]) & 0xFFFFFFFFL);
            }
            return -1;
        }

        boolean isPairLead(int cp) {
            for (int[] p : pairs) if (p[0] == cp) return true;
            return false;
        }

        @Override
        int enc(int[] c, int i, int n, boolean fin, Bytes out, long[] st) {
            int cp = c[i];
            if (pairs.length > 0 && isPairLead(cp)) {
                if (i + 1 >= n) {
                    if (!fin) return 0;
                } else {
                    for (int k = 0; k < pairs.length; k++) {
                        if (pairs[k][0] == cp && pairs[k][1] == c[i + 1]) { out.put(pairVal[k], pairLen[k]); return 2; }
                    }
                }
            }
            long v = lookup(cp);
            if (v < 0 && jamoLead >= 0 && cp >= 0xAC00 && cp <= 0xD7A3) {
                int s = cp - 0xAC00, cho = invCho[s / 588], jung = invJung[s % 588 / 28], jong = s % 28;
                int tb = jong == 0 ? jamoFiller : invJong[jong];
                if (cho < 0 || jung < 0 || tb < 0) return -1;
                for (int b : new int[]{jamoLead, jamoFiller, jamoLead, cho, jamoLead, jung, jamoLead, tb}) out.put(b);
                return 1;
            }
            if (v < 0) return -1;
            out.put((int) v, (int) (v >>> 32));
            return 1;
        }
    }
}
