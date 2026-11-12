// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** Set membership over the charset sub-programs emitted by re._compiler (SRE(charset) of CPython's sre_lib.h). */
final class SreCharset {
    private SreCharset() {}

    static final int FAILURE = 0, LITERAL = 16, CATEGORY = 8, CHARSET = 9, BIGCHARSET = 10, NEGATE = 21, RANGE = 22, RANGE_UNI_IGNORE = 42;

    /** Is {@code ch} a member of the set whose code starts at {@code set}? */
    static boolean contains(int[] code, int set, int ch) {
        boolean ok = true;
        for (;;) {
            switch (code[set++]) {
                case FAILURE: return !ok;
                case LITERAL:
                    if (ch == code[set]) return ok;
                    set++;
                    break;
                case CATEGORY:
                    if (SreUnicode.category(code[set], ch)) return ok;
                    set++;
                    break;
                case CHARSET:
                    if (ch < 256 && (code[set + (ch >> 5)] & (1 << (ch & 31))) != 0) return ok;
                    set += 8;
                    break;
                case RANGE:
                    if (code[set] <= ch && ch <= code[set + 1]) return ok;
                    set += 2;
                    break;
                case RANGE_UNI_IGNORE: {
                    if (code[set] <= ch && ch <= code[set + 1]) return ok;
                    int uch = SreUnicode.upperUnicode(ch);
                    if (code[set] <= uch && uch <= code[set + 1]) return ok;
                    set += 2;
                    break;
                }
                case NEGATE:
                    ok = !ok;
                    break;
                case BIGCHARSET: {
                    int count = code[set++];
                    int block = -1;
                    if (ch < 0x10000) block = (code[set + (ch >> 10)] >>> (8 * ((ch >> 8) & 3))) & 0xff;
                    set += 64;
                    if (block >= 0) {
                        int bit = block * 256 + (ch & 255);
                        if ((code[set + (bit >> 5)] & (1 << (bit & 31))) != 0) return ok;
                    }
                    set += count * 8;
                    break;
                }
                default:
                    throw new IllegalStateException("internal: unsupported set operator");
            }
        }
    }
}
