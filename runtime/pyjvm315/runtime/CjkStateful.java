// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** The stateful CJK codecs: HZ (and, once added, the ISO-2022 family) as state machines over the shared tables. */
final class CjkStateful {
    private CjkStateful() {}

    /** The engine for a stateful codec name, or null when {@code name} is not one. */
    static CjkCodec create(String name) {
        return name.equals("hz") ? new Hz() : null;
    }

    /** HZ-GB-2312: ASCII with {@code ~{ ... ~}} bracketing GB 2312 pairs written with the high bits clear; state {@code c[0]} is 1 inside. */
    static final class Hz extends CjkCodec {
        private final CjkCodec.Stateless gb = new CjkCodec.Stateless("gb2312");

        Hz() { super("hz"); }

        @Override
        int dec(byte[] d, int pos, int n, StringBuilder out, long[] st) {
            int c = d[pos] & 255;
            if (c == '~') {
                if (n - pos < 2) return 0;
                int c2 = d[pos + 1] & 255;
                if ((st[0] & 0xFF) != 0) {
                    if (c2 != '}') return -1;
                    st[0] &= ~0xFFL;
                    return 2;
                }
                switch (c2) {
                    case '~': out.append('~'); return 2;
                    case '{': st[0] = (st[0] & ~0xFFL) | 1; return 2;
                    case '}': if ((st[0] & 0xFF) == 0) return -1; st[0] &= ~0xFFL; return 2;
                    case '\n': return 2;
                    default: return -1;
                }
            }
            if ((c & 0x80) != 0) return -1;
            if ((st[0] & 0xFF) == 0) { out.append((char) c); return 1; }
            if (n - pos < 2) return 0;
            int c2 = d[pos + 1] & 255;
            if (c2 >= 0x80 || c < 0x21 || c2 < 0x21) return -1;
            byte[] pair = {(byte) (c | 0x80), (byte) (c2 | 0x80)};
            int k = gb.dec(pair, 0, 2, out, st);
            return k == 2 ? 2 : -1;
        }

        @Override
        int enc(int[] cs, int i, int n, boolean fin, Bytes out, long[] st) {
            int cp = cs[i];
            boolean gbMode = (st[0] & 0xFF) != 0;
            if (cp < 0x80) {
                if (gbMode) { out.put('~'); out.put('}'); st[0] &= ~0xFFL; }
                if (cp == '~') out.put('~');
                out.put(cp);
                return 1;
            }
            long v = gb.lookup(cp);
            if (v < 0 || (v >>> 32) != 2) return -1;
            int b1 = (int) (v >>> 8) & 255, b2 = (int) v & 255;
            if (b1 < 0xA1 || b2 < 0xA1) return -1;
            if (!gbMode) { out.put('~'); out.put('{'); st[0] = (st[0] & ~0xFFL) | 1; }
            out.put(b1 & 0x7F); out.put(b2 & 0x7F);
            return 1;
        }

        @Override
        void flush(Bytes out, long[] st) {
            if ((st[0] & 0xFF) != 0) { out.put('~'); out.put('}'); st[0] &= ~0xFFL; }
        }
    }
}
