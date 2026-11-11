// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * The backtracking operators of the sre engine (BRANCH, the REPEAT family, asserts, atomic groups) and the resume
 * points that continue them when a child context returns. Each helper corresponds to one case of SRE(match) in
 * CPython's sre_lib.h; the code after a DO_JUMP lives in {@link #resume(int)}. Helpers read and write the shared
 * registers {@code pc} (the "pattern" pointer, just past the opcode), {@code lp} (the context's local ptr) and
 * {@code cb} (the current context) and return an action code.
 */
abstract class SreOps extends SreState {
    SreOps(int[] code, int groups) { super(code, groups); }

    private int top() { return st[cb + F_TOP]; }

    // ---- BRANCH -----------------------------------------------------------------------------------

    final int opBranch() {
        lastmarkSave();
        if (repeat >= 0) markPush(lastmark);
        return branchNext();
    }

    private int branchNext() {
        int[] code = this.code;
        int ptr = lp;
        for (; code[pc] != 0; pc += code[pc]) {
            int c1 = code[pc + 1];
            if (c1 == LITERAL && (ptr >= end || str[ptr] != code[pc + 2])) continue;
            if (c1 == IN && (ptr >= end || !SreCharset.contains(code, pc + 3, str[ptr]))) continue;
            sptr = ptr;
            return jump(J_BRANCH, pc + 1, top());
        }
        if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
        return A_FAIL;
    }

    // ---- REPEAT_ONE family --------------------------------------------------------------------------

    final int opRepeatOne() {
        int min = code[pc + 1], max = code[pc + 2];
        if (min > end - lp) return A_FAIL;
        sptr = lp;
        int cnt = count(pc + 3, max);
        st[cb + F_COUNT] = cnt;
        lp += cnt;
        if (cnt < min) return A_FAIL;
        int tail = pc + code[pc];
        if (code[tail] == SUCCESS && lp == end && !(top() != 0 && mustAdvance && lp == start)) {
            sptr = lp;
            return A_SUCC;
        }
        lastmarkSave();
        if (repeat >= 0) markPush(lastmark);
        if (code[tail] == LITERAL) {
            st[cb + F_U] = code[tail + 1];
            return repeatOneLoop(1);
        }
        return repeatOneLoop(2);
    }

    private int repeatOneLoop(int mode) {
        int min = code[pc + 1];
        int cnt = st[cb + F_COUNT];
        int ptr = lp;
        if (mode == 1) {
            int chr = st[cb + F_U];
            while (cnt >= min && (ptr >= end || str[ptr] != chr)) {
                ptr--;
                cnt--;
            }
            if (cnt >= min) {
                lp = ptr;
                st[cb + F_COUNT] = cnt;
                sptr = ptr;
                return jump(J_REPEAT_ONE_1, pc + code[pc], top());
            }
        } else if (cnt >= min) {
            st[cb + F_COUNT] = cnt;
            sptr = ptr;
            return jump(J_REPEAT_ONE_2, pc + code[pc], top());
        }
        if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
        return A_FAIL;
    }

    private int resumeRepeatOne(int mode) {
        if (ret != 0) {
            if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
            return A_SUCC;
        }
        if (repeat >= 0) markPopKeep(st[cb + F_LASTMARK]);
        lastmarkRestore();
        lp--;
        st[cb + F_COUNT]--;
        return repeatOneLoop(mode);
    }

    final int opMinRepeatOne() {
        int min = code[pc + 1];
        if (min > end - lp) return A_FAIL;
        sptr = lp;
        if (min == 0) {
            st[cb + F_COUNT] = 0;
        } else {
            int r = count(pc + 3, min);
            if (r < min) return A_FAIL;
            st[cb + F_COUNT] = r;
            lp += r;
        }
        int tail = pc + code[pc];
        if (code[tail] == SUCCESS && !(top() != 0 && ((matchAll && lp != end) || (mustAdvance && lp == start)))) {
            sptr = lp;
            return A_SUCC;
        }
        lastmarkSave();
        if (repeat >= 0) markPush(lastmark);
        return minRepeatOneLoop();
    }

    private int minRepeatOneLoop() {
        int max = code[pc + 2];
        if (max == UNBOUNDED || st[cb + F_COUNT] <= max) {
            sptr = lp;
            return jump(J_MIN_REPEAT_ONE, pc + code[pc], top());
        }
        if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
        return A_FAIL;
    }

    private int resumeMinRepeatOne() {
        if (ret != 0) {
            if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
            return A_SUCC;
        }
        if (repeat >= 0) markPopKeep(st[cb + F_LASTMARK]);
        lastmarkRestore();
        sptr = lp;
        int r = count(pc + 3, 1);
        if (r == 0) {
            if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
            return A_FAIL;
        }
        lp++;
        st[cb + F_COUNT]++;
        return minRepeatOneLoop();
    }

    final int opPossessiveRepeatOne() {
        int min = code[pc + 1];
        if (min > end - lp) return A_FAIL;
        sptr = lp;
        int cnt = count(pc + 3, code[pc + 2]);
        st[cb + F_COUNT] = cnt;
        lp += cnt;
        if (cnt < min) return A_FAIL;
        pc += code[pc];
        return A_CONT;
    }

    // ---- REPEAT / MAX_UNTIL / MIN_UNTIL --------------------------------------------------------------

    final int opRepeat() {
        int id = repAlloc();
        rcount[id] = -1;
        rpat[id] = pc;
        rprev[id] = repeat;
        rlast[id] = -1;
        repeat = id;
        st[cb + F_U] = id;
        sptr = lp;
        return jump(J_REPEAT, pc + code[pc], top());
    }

    private int resumeRepeat() {
        int id = st[cb + F_U];
        repeat = rprev[id];
        repFree(id);
        return ret != 0 ? A_SUCC : A_FAIL;
    }

    final int opMaxUntil() {
        int rep = repeat;
        if (rep < 0) throw new PyException("RuntimeError", "maximum recursion limit exceeded or invalid repeat state");
        sptr = lp;
        int cnt = rcount[rep] + 1;
        st[cb + F_COUNT] = cnt;
        st[cb + F_U] = rep;
        int rpc = rpat[rep];
        if (cnt < code[rpc + 1]) {
            rcount[rep] = cnt;
            return jump(J_MAX_UNTIL_1, rpc + 3, top());
        }
        return maxUntil2();
    }

    private int maxUntil2() {
        int rep = st[cb + F_U], cnt = st[cb + F_COUNT];
        int rpc = rpat[rep];
        int max = code[rpc + 2];
        if ((max == UNBOUNDED || cnt < max) && sptr != rlast[rep]) {
            rcount[rep] = cnt;
            lastmarkSave();
            markPush(lastmark);
            push(rlast[rep]);
            rlast[rep] = sptr;
            return jump(J_MAX_UNTIL_2, rpc + 3, top());
        }
        return maxUntil3();
    }

    private int maxUntil3() {
        int rep = st[cb + F_U];
        repeat = rprev[rep];
        return jump(J_MAX_UNTIL_3, pc, top());
    }

    private int resumeMaxUntil1() {
        int rep = st[cb + F_U];
        if (ret != 0) return A_SUCC;
        rcount[rep] = st[cb + F_COUNT] - 1;
        sptr = lp;
        return A_FAIL;
    }

    private int resumeMaxUntil2() {
        int rep = st[cb + F_U];
        rlast[rep] = pop();
        int lm = st[cb + F_LASTMARK];
        if (ret != 0) {
            markPopDiscard(lm);
            return A_SUCC;
        }
        markPop(lm);
        lastmarkRestore();
        rcount[rep] = st[cb + F_COUNT] - 1;
        sptr = lp;
        return maxUntil3();
    }

    private int resumeMaxUntil3() {
        repeat = st[cb + F_U];
        if (ret != 0) return A_SUCC;
        sptr = lp;
        return A_FAIL;
    }

    final int opMinUntil() {
        int rep = repeat;
        if (rep < 0) throw new PyException("RuntimeError", "maximum recursion limit exceeded or invalid repeat state");
        sptr = lp;
        int cnt = rcount[rep] + 1;
        st[cb + F_COUNT] = cnt;
        st[cb + F_U] = rep;
        int rpc = rpat[rep];
        if (cnt < code[rpc + 1]) {
            rcount[rep] = cnt;
            return jump(J_MIN_UNTIL_1, rpc + 3, top());
        }
        lastmarkSave();
        repeat = rprev[rep];
        if (repeat >= 0) markPush(lastmark);
        return jump(J_MIN_UNTIL_2, pc, top());
    }

    private int resumeMinUntil1() {
        int rep = st[cb + F_U];
        if (ret != 0) return A_SUCC;
        rcount[rep] = st[cb + F_COUNT] - 1;
        sptr = lp;
        return A_FAIL;
    }

    private int resumeMinUntil2() {
        int rep = st[cb + F_U], cnt = st[cb + F_COUNT];
        int lm = st[cb + F_LASTMARK];
        boolean tailRepeat = rprev[rep] >= 0;
        repeat = rep;
        if (ret != 0) {
            if (tailRepeat) markPopDiscard(lm);
            return A_SUCC;
        }
        if (tailRepeat) markPop(lm);
        lastmarkRestore();
        sptr = lp;
        int rpc = rpat[rep];
        int max = code[rpc + 2];
        if ((max != UNBOUNDED && cnt >= max) || sptr == rlast[rep]) return A_FAIL;
        rcount[rep] = cnt;
        push(rlast[rep]);
        rlast[rep] = sptr;
        return jump(J_MIN_UNTIL_3, rpc + 3, top());
    }

    private int resumeMinUntil3() {
        int rep = st[cb + F_U];
        rlast[rep] = pop();
        if (ret != 0) return A_SUCC;
        rcount[rep] = st[cb + F_COUNT] - 1;
        sptr = lp;
        return A_FAIL;
    }

    // ---- POSSESSIVE_REPEAT, ATOMIC_GROUP, asserts -----------------------------------------------------

    final int opPossessiveRepeat() {
        sptr = lp;
        int id = repAlloc();
        rcount[id] = -1;
        rpat[id] = pc;
        rprev[id] = repeat;
        rlast[id] = -1;
        repeat = id;
        st[cb + F_U] = id;
        st[cb + F_COUNT] = 0;
        return possMin();
    }

    private int possMin() {
        if (st[cb + F_COUNT] < code[pc + 1]) return jump(J_POSS_REPEAT_1, pc + 3, 0);
        lp = -1;
        return possMore();
    }

    private int possMore() {
        int max = code[pc + 2];
        int cnt = st[cb + F_COUNT];
        if ((max == UNBOUNDED || cnt < max) && sptr != lp) {
            lastmarkSave();
            markPush(lastmark);
            lp = sptr;
            return jump(J_POSS_REPEAT_2, pc + 3, 0);
        }
        return possDone();
    }

    private int possDone() {
        int id = st[cb + F_U];
        repeat = rprev[id];
        repFree(id);
        pc += code[pc] + 1;
        lp = sptr;
        return A_CONT;
    }

    private int resumePoss1() {
        if (ret != 0) {
            st[cb + F_COUNT]++;
            return possMin();
        }
        sptr = lp;
        int id = st[cb + F_U];
        repeat = rprev[id];
        repFree(id);
        return A_FAIL;
    }

    private int resumePoss2() {
        int lm = st[cb + F_LASTMARK];
        if (ret != 0) {
            markPopDiscard(lm);
            st[cb + F_COUNT]++;
            return possMore();
        }
        markPop(lm);
        lastmarkRestore();
        sptr = lp;
        return possDone();
    }

    final int opAtomicGroup() {
        sptr = lp;
        return jump(J_ATOMIC_GROUP, pc + 1, 0);
    }

    final int opAssert() {
        if (lp < code[pc + 1]) return A_FAIL;
        sptr = lp - code[pc + 1];
        return jump(J_ASSERT, pc + 2, 0);
    }

    final int opAssertNot() {
        if (lp >= code[pc + 1]) {
            sptr = lp - code[pc + 1];
            lastmarkSave();
            if (repeat >= 0) markPush(lastmark);
            return jump(J_ASSERT_NOT, pc + 2, 0);
        }
        pc += code[pc];
        return A_CONT;
    }

    // ---- resume points ----------------------------------------------------------------------------

    /** Continue the operator that created the child context which just returned {@code ret}. */
    final int resume(int j) {
        switch (j) {
            case J_BRANCH: {
                if (ret != 0) {
                    if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
                    return A_SUCC;
                }
                if (repeat >= 0) markPopKeep(st[cb + F_LASTMARK]);
                lastmarkRestore();
                pc += code[pc];
                return branchNext();
            }
            case J_REPEAT_ONE_1: return resumeRepeatOne(1);
            case J_REPEAT_ONE_2: return resumeRepeatOne(2);
            case J_MIN_REPEAT_ONE: return resumeMinRepeatOne();
            case J_REPEAT: return resumeRepeat();
            case J_MAX_UNTIL_1: return resumeMaxUntil1();
            case J_MAX_UNTIL_2: return resumeMaxUntil2();
            case J_MAX_UNTIL_3: return resumeMaxUntil3();
            case J_MIN_UNTIL_1: return resumeMinUntil1();
            case J_MIN_UNTIL_2: return resumeMinUntil2();
            case J_MIN_UNTIL_3: return resumeMinUntil3();
            case J_POSS_REPEAT_1: return resumePoss1();
            case J_POSS_REPEAT_2: return resumePoss2();
            case J_ATOMIC_GROUP: {
                if (ret == 0) return A_FAIL;
                pc += code[pc];
                lp = sptr;
                return A_CONT;
            }
            case J_ASSERT: {
                if (ret == 0) return A_FAIL;
                pc += code[pc];
                return A_CONT;
            }
            case J_ASSERT_NOT: {
                if (ret != 0) {
                    if (repeat >= 0) markPopDiscard(st[cb + F_LASTMARK]);
                    return A_FAIL;
                }
                if (repeat >= 0) markPop(st[cb + F_LASTMARK]);
                lastmarkRestore();
                pc += code[pc];
                return A_CONT;
            }
            default: throw new IllegalStateException("bad resume point " + j);
        }
    }
}
