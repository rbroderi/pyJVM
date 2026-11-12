// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.Arrays;

/**
 * Matching state of the sre engine (SRE_STATE of CPython's sre.h) together with the explicit data stack that replaces
 * C recursion: match contexts and saved capture marks live in one growing {@code int[]}, exactly as in sre_lib.h.
 * Subject positions are code point indexes into {@link #str}. The opcode interpreter proper is {@link SreEngine}.
 */
class SreState {
    // opcodes (re._constants.OPCODES order, MAGIC 20230612)
    static final int FAILURE = 0, SUCCESS = 1, ANY = 2, ANY_ALL = 3, ASSERT = 4, ASSERT_NOT = 5, AT = 6, BRANCH = 7,
        CATEGORY = 8, CHARSET = 9, BIGCHARSET = 10, GROUPREF = 11, GROUPREF_EXISTS = 12, IN = 13, INFO = 14, JUMP = 15,
        LITERAL = 16, MARK = 17, MAX_UNTIL = 18, MIN_UNTIL = 19, NOT_LITERAL = 20, NEGATE = 21, RANGE = 22, REPEAT = 23,
        REPEAT_ONE = 24, SUBPATTERN = 25, MIN_REPEAT_ONE = 26, ATOMIC_GROUP = 27, POSSESSIVE_REPEAT = 28,
        POSSESSIVE_REPEAT_ONE = 29, GROUPREF_IGNORE = 30, IN_IGNORE = 31, LITERAL_IGNORE = 32, NOT_LITERAL_IGNORE = 33,
        GROUPREF_LOC_IGNORE = 34, IN_LOC_IGNORE = 35, LITERAL_LOC_IGNORE = 36, NOT_LITERAL_LOC_IGNORE = 37,
        GROUPREF_UNI_IGNORE = 38, IN_UNI_IGNORE = 39, LITERAL_UNI_IGNORE = 40, NOT_LITERAL_UNI_IGNORE = 41, RANGE_UNI_IGNORE = 42;
    static final int OPCODE_COUNT = 43;
    /** Canonical "unbounded" repeat count: _sre.MAXREPEAT (0xFFFFFFFF) is stored as -1. */
    static final int UNBOUNDED = -1;
    static final int INFO_PREFIX = 1, INFO_LITERAL = 2, INFO_CHARSET = 4;

    // action codes passed between the interpreter loop and the operator helpers
    static final int A_CONT = 0, A_FAIL = 1, A_SUCC = 2, A_ENTER = 3;

    // context frame layout in the data stack
    static final int F_LAST = 0, F_JUMP = 1, F_TOP = 2, F_PC = 3, F_PTR = 4, F_COUNT = 5, F_LASTMARK = 6, F_LASTINDEX = 7, F_U = 8, FS = 9;

    // resume points (the JUMP_* labels of sre_lib.h)
    static final int J_NONE = 0, J_BRANCH = 1, J_REPEAT_ONE_1 = 2, J_REPEAT_ONE_2 = 3, J_MIN_REPEAT_ONE = 4, J_REPEAT = 5,
        J_MAX_UNTIL_1 = 6, J_MAX_UNTIL_2 = 7, J_MAX_UNTIL_3 = 8, J_MIN_UNTIL_1 = 9, J_MIN_UNTIL_2 = 10, J_MIN_UNTIL_3 = 11,
        J_POSS_REPEAT_1 = 12, J_POSS_REPEAT_2 = 13, J_ATOMIC_GROUP = 14, J_ASSERT = 15, J_ASSERT_NOT = 16;

    final int[] code;
    final int groups;
    int[] str;
    int end;
    int start;      // state->start: where the current attempt began
    int sptr;       // state->ptr
    int pos, endpos;
    final int[] mark;
    int lastmark = -1, lastindex = -1;
    boolean mustAdvance, matchAll;

    // active SRE_REPEAT records (stack allocated: REPEAT contexts nest strictly)
    int repeat = -1;
    int[] rcount = new int[8], rpat = new int[8], rprev = new int[8], rlast = new int[8];
    int repTop;

    int[] st = new int[256];
    int sp;

    // interpreter registers shared with the operator helpers
    int pc, lp, cb, ret;

    SreState(int[] code, int groups) {
        this.code = code;
        this.groups = groups;
        this.mark = new int[2 * groups + 2];
    }

    final void init(int[] subject, int length, int startPos, int endPos) {
        this.str = subject;
        this.pos = startPos;
        this.endpos = endPos;
        this.start = startPos;
        this.sptr = startPos;
        this.end = endPos;
        this.mustAdvance = false;
        this.matchAll = false;
        reset();
    }

    /** state_reset(): forget captures, repeats and the data stack. */
    final void reset() {
        lastmark = -1;
        lastindex = -1;
        repeat = -1;
        repTop = 0;
        sp = 0;
    }

    final void resetCapture() { lastmark = -1; lastindex = -1; }

    // ---- data stack -------------------------------------------------------------------------------

    final void ensure(int n) {
        if (sp + n > st.length) {
            long want = Math.max((long) st.length * 2, (long) sp + n);
            if (want > (1L << 30)) throw new PyException("MemoryError", "regular expression data stack is too large");
            st = Arrays.copyOf(st, (int) want);
        }
    }

    final void markPush(int lm) {
        if (lm >= 0) {
            int n = lm + 1;
            ensure(n);
            System.arraycopy(mark, 0, st, sp, n);
            sp += n;
        }
    }

    final void markPop(int lm) {
        if (lm >= 0) {
            int n = lm + 1;
            sp -= n;
            System.arraycopy(st, sp, mark, 0, n);
        }
    }

    final void markPopKeep(int lm) {
        if (lm >= 0) System.arraycopy(st, sp - (lm + 1), mark, 0, lm + 1);
    }

    final void markPopDiscard(int lm) {
        if (lm >= 0) sp -= lm + 1;
    }

    final void push(int v) { ensure(1); st[sp++] = v; }

    final int pop() { return st[--sp]; }

    /** DO_JUMP: save this context's pc/ptr, allocate the child context and make it current. */
    final int jump(int j, int next, int top) {
        st[cb + F_PC] = pc;
        st[cb + F_PTR] = lp;
        ensure(FS);
        int nb = sp;
        st[nb + F_LAST] = cb;
        st[nb + F_JUMP] = j;
        st[nb + F_TOP] = top;
        sp = nb + FS;
        cb = nb;
        pc = next;
        return A_ENTER;
    }

    final void lastmarkSave() {
        st[cb + F_LASTMARK] = lastmark;
        st[cb + F_LASTINDEX] = lastindex;
    }

    final void lastmarkRestore() {
        lastmark = st[cb + F_LASTMARK];
        lastindex = st[cb + F_LASTINDEX];
    }

    final int repAlloc() {
        int id = repTop++;
        if (id >= rcount.length) {
            int n = rcount.length * 2;
            rcount = Arrays.copyOf(rcount, n);
            rpat = Arrays.copyOf(rpat, n);
            rprev = Arrays.copyOf(rprev, n);
            rlast = Arrays.copyOf(rlast, n);
        }
        return id;
    }

    final void repFree(int id) { repTop = id; }

    // ---- positions --------------------------------------------------------------------------------

    private boolean isWord(int atCode, int ch) {
        if (atCode < 8) return ch < 128 && SreUnicode.isAsciiWord(ch);
        if (atCode < 10) return ch < 128 && SreUnicode.isAsciiWord(ch);
        return SreUnicode.isUniWord(ch);
    }

    /** SRE(at) */
    final boolean at(int ptr, int atCode) {
        int[] s = str;
        switch (atCode) {
            case 0: case 2: return ptr == 0;
            case 1: return ptr == 0 || s[ptr - 1] == '\n';
            case 5: return (end - ptr == 1 && s[ptr] == '\n') || ptr == end;
            case 6: return ptr == end || s[ptr] == '\n';
            case 7: return ptr == end;
            case 3: case 8: case 10: {
                if (end == 0) return false;
                boolean that = ptr > 0 && isWord(atCode, s[ptr - 1]);
                boolean thisp = ptr < end && isWord(atCode, s[ptr]);
                return thisp != that;
            }
            case 4: case 9: case 11: {
                if (end == 0) return LangVersion.atLeast(3, 14);
                boolean that = ptr > 0 && isWord(atCode, s[ptr - 1]);
                boolean thisp = ptr < end && isWord(atCode, s[ptr]);
                return thisp == that;
            }
            default: return false;
        }
    }

    // ---- single-character repeats -----------------------------------------------------------------

    /** SRE(count): how many times the single-character item at {@code p} matches from {@code sptr} (at most maxcount). */
    final int count(int p, int maxcount) {
        int[] s = str;
        int ptr = sptr;
        int lim = end;
        if (maxcount != UNBOUNDED && maxcount < lim - ptr) lim = ptr + maxcount;
        int chr;
        switch (code[p]) {
            case IN:
                while (ptr < lim && SreCharset.contains(code, p + 2, s[ptr])) ptr++;
                break;
            case ANY:
                while (ptr < lim && s[ptr] != '\n') ptr++;
                break;
            case ANY_ALL:
                ptr = lim;
                break;
            case LITERAL:
                chr = code[p + 1];
                while (ptr < lim && s[ptr] == chr) ptr++;
                break;
            case LITERAL_IGNORE:
                chr = code[p + 1];
                while (ptr < lim && SreUnicode.lowerAscii(s[ptr]) == chr) ptr++;
                break;
            case LITERAL_UNI_IGNORE:
                chr = code[p + 1];
                while (ptr < lim && SreUnicode.lowerUnicode(s[ptr]) == chr) ptr++;
                break;
            case LITERAL_LOC_IGNORE:
                chr = code[p + 1];
                while (ptr < lim && locIgnore(chr, s[ptr])) ptr++;
                break;
            case NOT_LITERAL:
                chr = code[p + 1];
                while (ptr < lim && s[ptr] != chr) ptr++;
                break;
            case NOT_LITERAL_IGNORE:
                chr = code[p + 1];
                while (ptr < lim && SreUnicode.lowerAscii(s[ptr]) != chr) ptr++;
                break;
            case NOT_LITERAL_UNI_IGNORE:
                chr = code[p + 1];
                while (ptr < lim && SreUnicode.lowerUnicode(s[ptr]) != chr) ptr++;
                break;
            case NOT_LITERAL_LOC_IGNORE:
                chr = code[p + 1];
                while (ptr < lim && !locIgnore(chr, s[ptr])) ptr++;
                break;
            default: {
                // repeated single character pattern: run the generic matcher one item at a time
                int begin = sptr;
                while (sptr < lim) {
                    if (matchHere(p, 0) == 0) break;
                }
                return sptr - begin;
            }
        }
        return ptr - sptr;
    }

    /** char_loc_ignore(): case-insensitive in the C locale. */
    static boolean locIgnore(int pattern, int ch) {
        return ch == pattern || SreUnicode.lowerAscii(ch) == pattern || SreUnicode.upperAscii(ch) == pattern;
    }

    boolean charsetLocIgnore(int set, int ch) {
        int lo = SreUnicode.lowerAscii(ch);
        if (SreCharset.contains(code, set, ch)) return true;
        int up = SreUnicode.upperAscii(ch);
        return up != lo && SreCharset.contains(code, set, up);
    }

    /** Nested SRE(match) from count(): implemented by the interpreter. */
    int matchHere(int p, int toplevel) { throw new IllegalStateException(); }
}
