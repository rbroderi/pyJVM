// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** BLAKE2b / BLAKE2s (RFC 7693) with the full parameter block (key, salt, person, tree hashing) for {@code _blake2}. */
final class Blake2 {
    private static final long[] IV_B = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L};
    private static final int[] IV_S = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    private static final int[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    final boolean big;        // true: BLAKE2b
    final int outLen, block;
    private long[] hb;        // BLAKE2b state
    private int[] hs;         // BLAKE2s state
    private long t;
    private byte[] buf;
    private int buflen;
    private final boolean lastNode;

    /** The salt and person arrays must already be at most the algorithm's sizes. */
    Blake2(boolean big, int outLen, byte[] key, byte[] salt, byte[] person, int fanout, int depth, long leaf, long nodeOffset,
           int nodeDepth, int innerSize, boolean lastNode) {
        this.big = big; this.outLen = outLen; this.block = big ? 128 : 64; this.lastNode = lastNode;
        byte[] p = new byte[big ? 64 : 32];
        p[0] = (byte) outLen; p[1] = (byte) key.length; p[2] = (byte) fanout; p[3] = (byte) depth;
        for (int i = 0; i < 4; i++) p[4 + i] = (byte) (leaf >>> (8 * i));
        if (big) {
            for (int i = 0; i < 8; i++) p[8 + i] = (byte) (nodeOffset >>> (8 * i));
            p[16] = (byte) nodeDepth; p[17] = (byte) innerSize;
            System.arraycopy(salt, 0, p, 32, salt.length);
            System.arraycopy(person, 0, p, 48, person.length);
            hb = IV_B.clone();
            for (int i = 0; i < 8; i++) hb[i] ^= le64(p, i * 8);
        } else {
            for (int i = 0; i < 6; i++) p[8 + i] = (byte) (nodeOffset >>> (8 * i));
            p[14] = (byte) nodeDepth; p[15] = (byte) innerSize;
            System.arraycopy(salt, 0, p, 16, salt.length);
            System.arraycopy(person, 0, p, 24, person.length);
            hs = IV_S.clone();
            for (int i = 0; i < 8; i++) hs[i] ^= (int) le64(p, i * 4, 4);
        }
        buf = new byte[block];
        if (key.length > 0) {
            System.arraycopy(key, 0, buf, 0, key.length);
            buflen = block;
        }
    }

    private Blake2(Blake2 o) {
        big = o.big; outLen = o.outLen; block = o.block; lastNode = o.lastNode;
        hb = o.hb == null ? null : o.hb.clone();
        hs = o.hs == null ? null : o.hs.clone();
        t = o.t; buf = o.buf.clone(); buflen = o.buflen;
    }

    Blake2 copy() { return new Blake2(this); }

    private static long le64(byte[] b, int off) { return le64(b, off, 8); }

    private static long le64(byte[] b, int off, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    void update(byte[] d) {
        int off = 0, len = d.length;
        while (len > 0) {
            if (buflen == block) { t += block; compress(false); buflen = 0; }
            int n = Math.min(block - buflen, len);
            System.arraycopy(d, off, buf, buflen, n);
            buflen += n; off += n; len -= n;
        }
    }

    byte[] digest() {
        Blake2 c = new Blake2(this);
        c.t += c.buflen;
        java.util.Arrays.fill(c.buf, c.buflen, c.block, (byte) 0);
        c.compress(true);
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) {
            out[i] = big ? (byte) (c.hb[i >> 3] >>> (8 * (i & 7))) : (byte) (c.hs[i >> 2] >>> (8 * (i & 3)));
        }
        return out;
    }

    private void compress(boolean last) {
        if (big) compressB(last); else compressS(last);
    }

    private void compressB(boolean last) {
        long[] m = new long[16], v = new long[16];
        for (int i = 0; i < 16; i++) m[i] = le64(buf, i * 8);
        System.arraycopy(hb, 0, v, 0, 8);
        System.arraycopy(IV_B, 0, v, 8, 8);
        v[12] ^= t;
        if (last) { v[14] = ~v[14]; if (lastNode) v[15] = ~v[15]; }
        for (int r = 0; r < 12; r++) {
            int[] s = SIGMA[r % 10];
            gB(v, 0, 4, 8, 12, m[s[0]], m[s[1]]); gB(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            gB(v, 2, 6, 10, 14, m[s[4]], m[s[5]]); gB(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            gB(v, 0, 5, 10, 15, m[s[8]], m[s[9]]); gB(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            gB(v, 2, 7, 8, 13, m[s[12]], m[s[13]]); gB(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) hb[i] ^= v[i] ^ v[i + 8];
    }

    private static void gB(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] += v[b] + x; v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] += v[d];     v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] += v[b] + y; v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] += v[d];     v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private void compressS(boolean last) {
        int[] m = new int[16], v = new int[16];
        for (int i = 0; i < 16; i++) m[i] = (int) le64(buf, i * 4, 4);
        System.arraycopy(hs, 0, v, 0, 8);
        System.arraycopy(IV_S, 0, v, 8, 8);
        v[12] ^= (int) t; v[13] ^= (int) (t >>> 32);
        if (last) { v[14] = ~v[14]; if (lastNode) v[15] = ~v[15]; }
        for (int r = 0; r < 10; r++) {
            int[] s = SIGMA[r];
            gS(v, 0, 4, 8, 12, m[s[0]], m[s[1]]); gS(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            gS(v, 2, 6, 10, 14, m[s[4]], m[s[5]]); gS(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            gS(v, 0, 5, 10, 15, m[s[8]], m[s[9]]); gS(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            gS(v, 2, 7, 8, 13, m[s[12]], m[s[13]]); gS(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) hs[i] ^= v[i] ^ v[i + 8];
    }

    private static void gS(int[] v, int a, int b, int c, int d, int x, int y) {
        v[a] += v[b] + x; v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
        v[c] += v[d];     v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
        v[a] += v[b] + y; v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
        v[c] += v[d];     v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
    }
}
