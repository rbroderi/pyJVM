// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * Keccak-f[1600] sponge (FIPS 202) for the {@code _sha3} module: SHA3-224/256/384/512 and SHAKE128/256 share it.
 * {@link #squeeze} works on a copy of the state, so the object stays updatable after a digest, as in CPython.
 */
final class Keccak {
    private static final long[] RC = {
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
        0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
        0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
        0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L};
    private static final int[] ROTC = {1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44};
    private static final int[] PILN = {10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1};

    private long[] s = new long[25];
    final int rate;      // bytes
    final int suffix;    // 0x06 (SHA-3) or 0x1f (SHAKE)
    private int pos;

    Keccak(int rate, int suffix) { this.rate = rate; this.suffix = suffix; }

    Keccak copy() {
        Keccak k = new Keccak(rate, suffix);
        k.s = s.clone();
        k.pos = pos;
        return k;
    }

    private static void permute(long[] st) {
        long[] bc = new long[5];
        for (int r = 0; r < 24; r++) {
            for (int i = 0; i < 5; i++) bc[i] = st[i] ^ st[i + 5] ^ st[i + 10] ^ st[i + 15] ^ st[i + 20];
            for (int i = 0; i < 5; i++) {
                long t = bc[(i + 4) % 5] ^ Long.rotateLeft(bc[(i + 1) % 5], 1);
                for (int j = 0; j < 25; j += 5) st[j + i] ^= t;
            }
            long cur = st[1];
            for (int i = 0; i < 24; i++) {
                int j = PILN[i];
                long t = st[j];
                st[j] = Long.rotateLeft(cur, ROTC[i]);
                cur = t;
            }
            for (int j = 0; j < 25; j += 5) {
                for (int i = 0; i < 5; i++) bc[i] = st[j + i];
                for (int i = 0; i < 5; i++) st[j + i] ^= ~bc[(i + 1) % 5] & bc[(i + 2) % 5];
            }
            st[0] ^= RC[r];
        }
    }

    void absorb(byte[] data) {
        for (byte b : data) {
            s[pos >> 3] ^= (b & 0xffL) << ((pos & 7) << 3);
            if (++pos == rate) { permute(s); pos = 0; }
        }
    }

    byte[] squeeze(int n) {
        long[] t = s.clone();
        t[pos >> 3] ^= (long) suffix << ((pos & 7) << 3);
        t[(rate - 1) >> 3] ^= 0x80L << (((rate - 1) & 7) << 3);
        permute(t);
        byte[] out = new byte[n];
        int off = 0, p = 0;
        while (off < n) {
            if (p == rate) { permute(t); p = 0; }
            out[off++] = (byte) (t[p >> 3] >>> ((p & 7) << 3));
            p++;
        }
        return out;
    }
}
