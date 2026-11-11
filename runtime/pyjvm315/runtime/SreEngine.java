// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * The sre opcode interpreter: a port of SRE(match) and SRE(search) from CPython's Modules/_sre/sre_lib.h. Matching is
 * iterative: every DO_JUMP of the C code pushes a context frame on {@link #st} and the code after it runs from
 * {@link #resume(int)}, so deeply nested repeats cannot overflow the Java stack. The simple, hot operators are handled
 * inline in {@link #dispatch()}; the backtracking ones are in {@link SreOps}.
 */
final class SreEngine extends SreOps {
    SreEngine(int[] code, int groups) { super(code, groups); }

    @Override
    int matchHere(int p, int toplevel) { return match(p, toplevel); }

    /** sre_match(): match at {@code sptr} with the compiled pattern (toplevel). */
    int matchTop() { return match(0, 1); }

    /** SRE(match) re-entrantly: the registers of an interrupted outer interpreter are preserved. */
    int match(int p, int toplevel) {
        int sPc = pc, sLp = lp, sCb = cb, sRet = ret;
        ensure(FS);
        int base = sp;
        st[base + F_LAST] = -1;
        st[base + F_JUMP] = J_NONE;
        st[base + F_TOP] = toplevel;
        sp = base + FS;
        cb = base;
        pc = p;
        int r = run();
        sp = base;
        pc = sPc;
        lp = sLp;
        cb = sCb;
        ret = sRet;
        return r;
    }

    private int run() {
        int act = A_ENTER;
        for (;;) {
            if (act == A_ENTER) {
                lp = sptr;
                int min = code[pc] == INFO ? code[pc + 3] : 0;
                if (min != 0 && end - lp < min) {
                    act = A_FAIL;
                } else {
                    if (code[pc] == INFO) pc += code[pc + 1] + 1;
                    act = dispatch();
                }
            } else {
                ret = act == A_SUCC ? 1 : 0;
                int prev = st[cb + F_LAST], j = st[cb + F_JUMP];
                sp = cb;
                if (prev < 0) return ret;
                cb = prev;
                pc = st[cb + F_PC];
                lp = st[cb + F_PTR];
                act = resume(j);
                if (act == A_CONT) act = dispatch();
            }
        }
    }

    /** The interpreter loop; returns the first non-continue action (the registers are only written back for helpers). */
    private int dispatch() {
        final int[] code = this.code;
        final int[] s = this.str;
        final int end = this.end;
        int pc = this.pc, ptr = this.lp;
        for (;;) {
            switch (code[pc++]) {
                case MARK: {
                    int i = code[pc];
                    if ((i & 1) != 0) lastindex = i / 2 + 1;
                    if (i > lastmark) {
                        int j = lastmark + 1;
                        while (j < i) mark[j++] = -1;
                        lastmark = i;
                    }
                    mark[i] = ptr;
                    pc++;
                    continue;
                }
                case LITERAL:
                    if (ptr >= end || s[ptr] != code[pc]) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case NOT_LITERAL:
                    if (ptr >= end || s[ptr] == code[pc]) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case SUCCESS:
                    if (st[cb + F_TOP] != 0 && ((matchAll && ptr != end) || (mustAdvance && ptr == start))) return A_FAIL;
                    sptr = ptr;
                    return A_SUCC;
                case AT:
                    if (!at(ptr, code[pc])) return A_FAIL;
                    pc++;
                    continue;
                case CATEGORY:
                    if (ptr >= end || !SreUnicode.category(code[pc], s[ptr])) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case ANY:
                    if (ptr >= end || s[ptr] == '\n') return A_FAIL;
                    ptr++;
                    continue;
                case ANY_ALL:
                    if (ptr >= end) return A_FAIL;
                    ptr++;
                    continue;
                case IN:
                    if (ptr >= end || !SreCharset.contains(code, pc + 1, s[ptr])) return A_FAIL;
                    pc += code[pc];
                    ptr++;
                    continue;
                case LITERAL_IGNORE:
                    if (ptr >= end || SreUnicode.lowerAscii(s[ptr]) != code[pc]) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case LITERAL_UNI_IGNORE:
                    if (ptr >= end || SreUnicode.lowerUnicode(s[ptr]) != code[pc]) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case LITERAL_LOC_IGNORE:
                    if (ptr >= end || !locIgnore(code[pc], s[ptr])) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case NOT_LITERAL_IGNORE:
                    if (ptr >= end || SreUnicode.lowerAscii(s[ptr]) == code[pc]) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case NOT_LITERAL_UNI_IGNORE:
                    if (ptr >= end || SreUnicode.lowerUnicode(s[ptr]) == code[pc]) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case NOT_LITERAL_LOC_IGNORE:
                    if (ptr >= end || locIgnore(code[pc], s[ptr])) return A_FAIL;
                    pc++;
                    ptr++;
                    continue;
                case IN_IGNORE:
                    if (ptr >= end || !SreCharset.contains(code, pc + 1, SreUnicode.lowerAscii(s[ptr]))) return A_FAIL;
                    pc += code[pc];
                    ptr++;
                    continue;
                case IN_UNI_IGNORE:
                    if (ptr >= end || !SreCharset.contains(code, pc + 1, SreUnicode.lowerUnicode(s[ptr]))) return A_FAIL;
                    pc += code[pc];
                    ptr++;
                    continue;
                case IN_LOC_IGNORE:
                    if (ptr >= end || !charsetLocIgnore(pc + 1, s[ptr])) return A_FAIL;
                    pc += code[pc];
                    ptr++;
                    continue;
                case JUMP: case INFO:
                    pc += code[pc];
                    continue;
                case FAILURE:
                    return A_FAIL;
                case GROUPREF: case GROUPREF_IGNORE: case GROUPREF_UNI_IGNORE: case GROUPREF_LOC_IGNORE: {
                    int op = code[pc - 1];
                    int g = code[pc] * 2;
                    if (g >= lastmark) return A_FAIL;
                    int p = mark[g], e = mark[g + 1];
                    if (p < 0 || e < 0 || e < p) return A_FAIL;
                    for (; p < e; p++, ptr++) {
                        if (ptr >= end) return A_FAIL;
                        int a = s[ptr], b = s[p];
                        if (a != b) {
                            switch (op) {
                                case GROUPREF_IGNORE: if (SreUnicode.lowerAscii(a) != SreUnicode.lowerAscii(b)) return A_FAIL; break;
                                case GROUPREF_UNI_IGNORE: if (SreUnicode.lowerUnicode(a) != SreUnicode.lowerUnicode(b)) return A_FAIL; break;
                                case GROUPREF_LOC_IGNORE: if (SreUnicode.lowerAscii(a) != SreUnicode.lowerAscii(b)) return A_FAIL; break;
                                default: return A_FAIL;
                            }
                        }
                    }
                    pc++;
                    continue;
                }
                case GROUPREF_EXISTS: {
                    int g = code[pc] * 2;
                    if (g >= lastmark) {
                        pc += code[pc + 1];
                        continue;
                    }
                    int p = mark[g], e = mark[g + 1];
                    if (p < 0 || e < 0 || e < p) {
                        pc += code[pc + 1];
                        continue;
                    }
                    pc += 2;
                    continue;
                }
                default: {
                    this.pc = pc;
                    this.lp = ptr;
                    int a = dispatchOps(code[pc - 1]);
                    if (a != A_CONT) return a;
                    pc = this.pc;
                    ptr = this.lp;
                }
            }
        }
    }

    /** The operators that need helper methods (backtracking and repeats). */
    private int dispatchOps(int op) {
        switch (op) {
            case BRANCH: return opBranch();
            case REPEAT_ONE: return opRepeatOne();
            case MIN_REPEAT_ONE: return opMinRepeatOne();
            case POSSESSIVE_REPEAT_ONE: return opPossessiveRepeatOne();
            case REPEAT: return opRepeat();
            case MAX_UNTIL: return opMaxUntil();
            case MIN_UNTIL: return opMinUntil();
            case POSSESSIVE_REPEAT: return opPossessiveRepeat();
            case ATOMIC_GROUP: return opAtomicGroup();
            case ASSERT: return opAssert();
            case ASSERT_NOT: return opAssertNot();
            default: throw new PyException("RuntimeError", "internal error in regular expression engine");
        }
    }

    // ---- SRE(search) ------------------------------------------------------------------------------

    /** Search from {@code start} (state->start/ptr) for the first position at which the pattern matches. */
    int search() {
        final int[] code = this.code;
        final int[] s = this.str;
        int ptr = start;
        int end = this.end;
        int status;
        int prefixLen = 0, prefixSkip = 0, prefix = 0, overlap = 0, charset = -1, flags = 0;
        int pattern = 0;
        if (ptr > end) return 0;
        if (code[0] == INFO) {
            flags = code[2];
            int min = code[3];
            if (min != 0 && end - ptr < min) return 0;
            if (min > 1) {
                end -= min - 1;
                if (end <= ptr) end = ptr;
            }
            if ((flags & INFO_PREFIX) != 0) {
                prefixLen = code[5];
                prefixSkip = code[6];
                prefix = 7;
                overlap = prefix + prefixLen - 1;
            } else if ((flags & INFO_CHARSET) != 0) {
                charset = 5;
            }
            pattern = 1 + code[1];
        }
        if (prefixLen == 1) {
            int c = code[prefix];
            end = this.end;
            mustAdvance = false;
            while (ptr < end) {
                while (s[ptr] != c) {
                    if (++ptr >= end) return 0;
                }
                start = ptr;
                sptr = ptr + prefixSkip;
                if ((flags & INFO_LITERAL) != 0) return 1;
                status = match(pattern + 2 * prefixSkip, 0);
                if (status != 0) return status;
                ++ptr;
                resetCapture();
            }
            return 0;
        }
        if (prefixLen > 1) {
            int i;
            end = this.end;
            if (prefixLen > end - ptr) return 0;
            while (ptr < end) {
                int c = code[prefix];
                while (s[ptr++] != c) {
                    if (ptr >= end) return 0;
                }
                if (ptr >= end) return 0;
                i = 1;
                mustAdvance = false;
                do {
                    if (s[ptr] == code[prefix + i]) {
                        if (++i != prefixLen) {
                            if (++ptr >= end) return 0;
                            continue;
                        }
                        start = ptr - (prefixLen - 1);
                        sptr = ptr - (prefixLen - prefixSkip - 1);
                        if ((flags & INFO_LITERAL) != 0) return 1;
                        status = match(pattern + 2 * prefixSkip, 0);
                        if (status != 0) return status;
                        if (++ptr >= end) return 0;
                        resetCapture();
                    }
                    i = code[overlap + i];
                } while (i != 0);
            }
            return 0;
        }
        if (charset >= 0) {
            end = this.end;
            mustAdvance = false;
            for (;;) {
                while (ptr < end && !SreCharset.contains(code, charset, s[ptr])) ptr++;
                if (ptr >= end) return 0;
                start = ptr;
                sptr = ptr;
                status = match(pattern, 0);
                if (status != 0) break;
                ptr++;
                resetCapture();
            }
            return status;
        }
        start = sptr = ptr;
        status = match(pattern, 1);
        mustAdvance = false;
        if (status == 0 && code[pattern] == AT && (code[pattern + 1] == 0 || code[pattern + 1] == 2)) {
            start = sptr = ptr = end;
            return 0;
        }
        while (status == 0 && ptr < end) {
            ptr++;
            resetCapture();
            start = sptr = ptr;
            status = match(pattern, 0);
        }
        return status;
    }
}
