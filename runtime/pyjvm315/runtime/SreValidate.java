// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * Structural validation of compiled sre code (the equivalent of _validate() in CPython's Modules/_sre/sre.c): every
 * skip lands inside the program, every group and mark index exists, every structure ends in the expected opcode, so
 * the engine never reads outside the code array. Repeat bounds of 2**31 or more, which no subject can reach, are
 * folded to "unbounded" (max) or Integer.MAX_VALUE (min) in the copy of the code that the engine runs.
 */
final class SreValidate {
    private SreValidate() {}

    private static final class Invalid extends RuntimeException {
        Invalid() { super(null, null, false, false); }
    }

    private static void fail() { throw new Invalid(); }

    private static void need(boolean ok) { if (!ok) throw new Invalid(); }

    /** True if the program is well formed; repeat bounds are normalised in place. */
    static boolean validate(int[] c, int groups) {
        try {
            int n = c.length;
            need(groups >= 0 && groups <= 0x3fffffff);
            need(n >= 1);
            need(c[n - 1] == SreState.SUCCESS);
            int end = inner(c, 0, n - 1, groups);
            need(end == n - 1);
            return true;
        } catch (Invalid | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    private static long u(int v) { return v & 0xffffffffL; }

    private static void charset(int[] c, int i, int end) {
        while (i < end) {
            int op = c[i++];
            switch (op) {
                case SreState.NEGATE: break;
                case SreState.LITERAL: need(i < end); i++; break;
                case SreState.RANGE: case SreState.RANGE_UNI_IGNORE:
                    need(i + 1 < end);
                    need(u(c[i]) <= u(c[i + 1]));
                    i += 2;
                    break;
                case SreState.CHARSET: need(8 <= end - i); i += 8; break;
                case SreState.BIGCHARSET: {
                    need(i < end);
                    long n = u(c[i++]);
                    need(n <= 0xffff);
                    need(64 <= end - i);
                    for (int b = 0; b < 256; b++) need(((c[i + (b >> 2)] >>> (8 * (b & 3))) & 0xff) < n);
                    i += 64;
                    need(n * 8 <= end - i);
                    i += (int) n * 8;
                    break;
                }
                case SreState.CATEGORY:
                    need(i < end);
                    need(u(c[i]) < 18);
                    i++;
                    break;
                default: fail();
            }
        }
    }

    private static void bounds(int[] c, int at) {
        long min = u(c[at]), max = u(c[at + 1]);
        need(min <= max);
        if (max >= (1L << 31)) c[at + 1] = SreState.UNBOUNDED;
        if (min >= (1L << 31)) c[at] = Integer.MAX_VALUE;
    }

    /** Validate ops in [i, end); returns the index where validation stopped (always end on success). */
    private static int inner(int[] c, int i, int end, int groups) {
        while (i < end) {
            int op = c[i++];
            switch (op) {
                case SreState.ANY: case SreState.ANY_ALL: case SreState.FAILURE: case SreState.SUCCESS: break;
                case SreState.LITERAL: case SreState.NOT_LITERAL: case SreState.LITERAL_IGNORE: case SreState.NOT_LITERAL_IGNORE:
                case SreState.LITERAL_LOC_IGNORE: case SreState.NOT_LITERAL_LOC_IGNORE: case SreState.LITERAL_UNI_IGNORE:
                case SreState.NOT_LITERAL_UNI_IGNORE:
                    need(i < end);
                    i++;
                    break;
                case SreState.AT:
                    need(i < end && u(c[i]) < 12);
                    i++;
                    break;
                case SreState.CATEGORY:
                    need(i < end && u(c[i]) < 18);
                    i++;
                    break;
                case SreState.MARK:
                    need(i < end && u(c[i]) < 2L * groups);
                    i++;
                    break;
                case SreState.GROUPREF: case SreState.GROUPREF_IGNORE: case SreState.GROUPREF_LOC_IGNORE: case SreState.GROUPREF_UNI_IGNORE:
                    need(i < end && u(c[i]) < groups);
                    i++;
                    break;
                case SreState.IN: case SreState.IN_IGNORE: case SreState.IN_LOC_IGNORE: case SreState.IN_UNI_IGNORE: {
                    need(i < end);
                    long skip = u(c[i]);
                    need(skip >= 2 && skip <= end - i);
                    int body = i + 1, stop = i + (int) skip - 1;
                    charset(c, body, stop);
                    need(c[stop] == SreState.FAILURE);
                    i = stop + 1;
                    break;
                }
                case SreState.INFO: i = info(c, i, end); break;
                case SreState.BRANCH: i = branch(c, i, end, groups); break;
                case SreState.REPEAT_ONE: case SreState.MIN_REPEAT_ONE: case SreState.POSSESSIVE_REPEAT_ONE: {
                    need(i + 2 < end);
                    long skip = u(c[i]);
                    need(skip >= 4 && skip <= end - i);
                    bounds(c, i + 1);
                    int stop = i + (int) skip - 1;
                    need(inner(c, i + 3, stop, groups) == stop);
                    need(c[stop] == SreState.SUCCESS);
                    i = stop + 1;
                    break;
                }
                case SreState.REPEAT: case SreState.POSSESSIVE_REPEAT: {
                    need(i + 2 < end);
                    long skip = u(c[i]);
                    need(skip >= 3 && skip < end - i);
                    bounds(c, i + 1);
                    int stop = i + (int) skip;
                    need(inner(c, i + 3, stop, groups) == stop);
                    if (op == SreState.POSSESSIVE_REPEAT) need(c[stop] == SreState.SUCCESS);
                    else need(c[stop] == SreState.MAX_UNTIL || c[stop] == SreState.MIN_UNTIL);
                    i = stop + 1;
                    break;
                }
                case SreState.ATOMIC_GROUP: {
                    need(i < end);
                    long skip = u(c[i]);
                    need(skip >= 2 && skip <= end - i);
                    int stop = i + (int) skip - 1;
                    need(inner(c, i + 1, stop, groups) == stop);
                    need(c[stop] == SreState.SUCCESS);
                    i = stop + 1;
                    break;
                }
                case SreState.ASSERT: case SreState.ASSERT_NOT: {
                    need(i + 1 < end);
                    long skip = u(c[i]);
                    need(skip >= 3 && skip <= end - i);
                    int stop = i + (int) skip - 1;
                    need(inner(c, i + 2, stop, groups) == stop);
                    need(c[stop] == SreState.SUCCESS);
                    i = stop + 1;
                    break;
                }
                case SreState.GROUPREF_EXISTS: i = groupRefExists(c, i, end, groups); break;
                default: fail();
            }
        }
        return i;
    }

    private static int info(int[] c, int i, int end) {
        need(i + 3 < end);
        long skip = u(c[i]);
        need(skip >= 4 && skip <= end - i);
        int next = i + (int) skip;
        int flags = c[i + 1];
        need((flags & ~(SreState.INFO_PREFIX | SreState.INFO_LITERAL | SreState.INFO_CHARSET)) == 0);
        need(!((flags & SreState.INFO_PREFIX) != 0 && (flags & SreState.INFO_CHARSET) != 0));
        need(!((flags & SreState.INFO_LITERAL) != 0 && (flags & SreState.INFO_PREFIX) == 0));
        int p = i + 4;
        if ((flags & SreState.INFO_PREFIX) != 0) {
            need(p + 1 < next);
            long plen = u(c[p]);
            need(plen >= 1 && 2 * plen <= next - (p + 2));
            need(c[p + 1] >= 0 && c[p + 1] <= plen);
            p += 2 + 2 * (int) plen;
            need(p == next);
        } else if ((flags & SreState.INFO_CHARSET) != 0) {
            need(next - 1 >= p && c[next - 1] == SreState.FAILURE);
            charset(c, p, next - 1);
            p = next;
        }
        need(p == next);
        return next;
    }

    private static int branch(int[] c, int i, int end, int groups) {
        int target = -1;
        for (;;) {
            need(i < end);
            long skip = u(c[i]);
            if (skip == 0) {
                need(target < 0 || target == i + 1);
                return i + 1;
            }
            need(skip >= 2 && skip <= end - i);
            int jumpOp = i + (int) skip - 2;
            need(inner(c, i + 1, jumpOp, groups) == jumpOp);
            need(c[jumpOp] == SreState.JUMP);
            int t = jumpOp + 1 + c[jumpOp + 1];
            need(t >= 0);
            if (target < 0) target = t;
            else need(t == target);
            i += (int) skip;
        }
    }

    private static int groupRefExists(int[] c, int i, int end, int groups) {
        need(i + 1 < end);
        need(u(c[i]) < groups);
        long skip = u(c[i + 1]);
        need(skip >= 2 && skip <= end - i);
        int target = i + (int) skip;
        if (skip >= 4 && c[target - 2] == SreState.JUMP) {
            int yesEnd = target - 2;
            need(inner(c, i + 2, yesEnd, groups) == yesEnd);
            long jskip = u(c[target - 1]);
            need(jskip <= end - (target - 1));
            int after = target - 1 + (int) jskip;
            need(inner(c, target, after, groups) == after);
            return after;
        }
        need(inner(c, i + 2, target, groups) == target);
        return target;
    }
}
