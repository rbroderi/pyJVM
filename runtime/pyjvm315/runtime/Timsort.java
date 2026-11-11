// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.List;

/**
 * Port of CPython's list.sort (Objects/listobject.c): powersort-scheduled Timsort with
 * binary-insertion for short runs and galloping merges, using only {@code <} so the
 * sequence of comparisons matches CPython for the same input.
 */
final class Timsort {
    private static final int MIN_GALLOP = 7;

    private final Object[] k;   // keys being compared
    private final Object[] v;   // parallel values (null when sorting the elements themselves)
    private final int size;
    private int minGallop = MIN_GALLOP;
    private final int[] pBase = new int[96], pLen = new int[96], pPower = new int[96];
    private int pn;
    private Object[] tk, tv;
    private int dst, pa, pb, ca, cb;

    private Timsort(Object[] keys, Object[] values) { k = keys; v = values; size = keys.length; }

    /** list.sort(key=keyfn, reverse=reverse), in place, with CPython's emptied-list/modification semantics. */
    static void sortList(List<Object> list, Object keyfn, boolean reverse) {
        Object[] saved = list.toArray();
        int n = saved.length;
        list.clear();
        Object[] keys = saved;
        Object[] vals = null;
        try {
            if (keyfn != null) {
                keys = new Object[n];
                vals = saved;
                for (int i = 0; i < n; i++) keys[i] = PyRuntime.unaryCall(keyfn, saved[i]);
            }
        } catch (RuntimeException | Error e) {
            list.clear();
            list.addAll(java.util.Arrays.asList(saved));
            throw e;
        }
        RuntimeException failure = null;
        try {
            if (reverse && n > 1) { reverse(keys, 0, n); if (vals != null) reverse(vals, 0, n); }
            if (n > 1) new Timsort(keys, vals).run();
        } catch (RuntimeException e) {
            failure = e;
        }
        boolean modified = !list.isEmpty();
        if (reverse && n > 1) reverse(saved, 0, n);
        list.clear();
        list.addAll(java.util.Arrays.asList(saved));
        if (failure != null) throw failure;
        if (modified) throw new PyException("ValueError", "list modified during sort");
    }

    static void reverse(Object[] a, int from, int to) {
        for (int i = from, j = to - 1; i < j; i++, j--) { Object t = a[i]; a[i] = a[j]; a[j] = t; }
    }

    private static boolean lt(Object a, Object b) { return PyRuntime.truth(PyRuntime.lt(a, b)); }

    private void put(int d, Object[] ak, Object[] av, int i) { k[d] = ak[i]; if (v != null) v[d] = av[i]; }

    private void move(Object[] ak, Object[] av, int from, int to, int len) {
        System.arraycopy(ak, from, k, to, len);
        if (v != null) System.arraycopy(av, from, v, to, len);
    }

    private void swapRange(int from, int to) { reverse(k, from, to); if (v != null) reverse(v, from, to); }

    static int computeMinrun(int n) {
        int r = 0;
        while (n >= 64) { r |= n & 1; n >>= 1; }
        return LangVersion.atLeast(3, 14) ? n : n + r;
    }

    private int runEnd(int lo, int minrun) {
        int shift = 0;
        for (int t = size; t >= 64; t >>= 1) shift++;
        for (int j = 1; ; j++) {
            int b = (int) (((long) j * size) >> shift);
            if (b >= lo + minrun || b >= size) return Math.min(b, size);
        }
    }

    private void run() {
        int nremaining = size, lo = 0;
        int minrun = computeMinrun(nremaining);
        do {
            int n = countRun(lo, nremaining);
            if (n < minrun) {
                int force = Math.min(nremaining, LangVersion.atLeast(3, 14) ? runEnd(lo, minrun) - lo : minrun);
                binarysort(lo, lo + force, lo + n);
                n = force;
            }
            foundNewRun(n);
            pBase[pn] = lo; pLen[pn] = n; pn++;
            lo += n; nremaining -= n;
        } while (nremaining > 0);
        while (pn > 1) {
            int n = pn - 2;
            if (n > 0 && pLen[n - 1] < pLen[n + 1]) n--;
            mergeAt(n);
        }
    }

    private int countRun(int lo, int nremaining) {
        int n;
        for (n = 1; n < nremaining; n++) if (lt(k[lo + n], k[lo + n - 1])) break;
        if (n == nremaining) return n;
        if (n > 1) {
            if (lt(k[lo], k[lo + n - 1])) return n;
            swapRange(lo, lo + n);
        }
        n++;
        int neq = 0;
        for (; n < nremaining; n++) {
            if (lt(k[lo + n], k[lo + n - 1])) {
                if (neq > 0) { swapRange(lo + n - neq - 1, lo + n); neq = 0; }
            } else if (lt(k[lo + n - 1], k[lo + n])) {
                break;
            } else {
                neq++;
            }
        }
        if (neq > 0) swapRange(lo + n - neq - 1, lo + n);
        swapRange(lo, lo + n);
        // The reversed run is ascending; extend it while the following elements keep ascending.
        for (; n < nremaining; n++) if (lt(k[lo + n], k[lo + n - 1])) break;
        return n;
    }

    private void binarysort(int lo, int hi, int start) {
        for (; start < hi; start++) {
            int l = lo, r = start;
            Object pk = k[start], pv = v == null ? null : v[start];
            do {
                int p = l + ((r - l) >> 1);
                if (lt(pk, k[p])) r = p; else l = p + 1;
            } while (l < r);
            System.arraycopy(k, l, k, l + 1, start - l);
            if (v != null) System.arraycopy(v, l, v, l + 1, start - l);
            k[l] = pk;
            if (v != null) v[l] = pv;
        }
    }

    private static int powerloop(int s1, int n1, int n2, int n) {
        int result = 0;
        long a = 2L * s1 + n1, b = a + n1 + n2;
        for (;;) {
            result++;
            if (a >= n) { a -= n; b -= n; }
            else if (b >= n) break;
            a <<= 1; b <<= 1;
        }
        return result;
    }

    private void foundNewRun(int n2) {
        if (pn > 0) {
            int power = powerloop(pBase[pn - 1], pLen[pn - 1], n2, size);
            while (pn > 1 && pPower[pn - 2] > power) mergeAt(pn - 2);
            pPower[pn - 1] = power;
        }
    }

    private void mergeAt(int i) {
        int sa = pBase[i], na = pLen[i], sb = pBase[i + 1], nb = pLen[i + 1];
        pLen[i] = na + nb;
        if (i == pn - 3) { pBase[i + 1] = pBase[i + 2]; pLen[i + 1] = pLen[i + 2]; pPower[i + 1] = pPower[i + 2]; }
        pn--;
        int kk = gallopRight(k[sb], k, sa, na, 0);
        sa += kk; na -= kk;
        if (na == 0) return;
        nb = gallopLeft(k[sa + na - 1], k, sb, nb, nb - 1);
        if (nb == 0) return;
        if (na <= nb) mergeLo(sa, na, sb, nb); else mergeHi(sa, na, sb, nb);
    }

    private static int gallopLeft(Object key, Object[] a, int base, int n, int hint) {
        int lastofs = 0, ofs = 1;
        int h = base + hint;
        if (lt(a[h], key)) {
            int maxofs = n - hint;
            while (ofs < maxofs) {
                if (lt(a[h + ofs], key)) { lastofs = ofs; ofs = (ofs << 1) + 1; } else break;
            }
            if (ofs > maxofs) ofs = maxofs;
            lastofs += hint; ofs += hint;
        } else {
            int maxofs = hint + 1;
            while (ofs < maxofs) {
                if (lt(a[h - ofs], key)) break;
                lastofs = ofs; ofs = (ofs << 1) + 1;
            }
            if (ofs > maxofs) ofs = maxofs;
            int t = lastofs; lastofs = hint - ofs; ofs = hint - t;
        }
        lastofs++;
        while (lastofs < ofs) {
            int m = lastofs + ((ofs - lastofs) >> 1);
            if (lt(a[base + m], key)) lastofs = m + 1; else ofs = m;
        }
        return ofs;
    }

    private static int gallopRight(Object key, Object[] a, int base, int n, int hint) {
        int lastofs = 0, ofs = 1;
        int h = base + hint;
        if (lt(key, a[h])) {
            int maxofs = hint + 1;
            while (ofs < maxofs) {
                if (lt(key, a[h - ofs])) { lastofs = ofs; ofs = (ofs << 1) + 1; } else break;
            }
            if (ofs > maxofs) ofs = maxofs;
            int t = lastofs; lastofs = hint - ofs; ofs = hint - t;
        } else {
            int maxofs = n - hint;
            while (ofs < maxofs) {
                if (lt(key, a[h + ofs])) break;
                lastofs = ofs; ofs = (ofs << 1) + 1;
            }
            if (ofs > maxofs) ofs = maxofs;
            lastofs += hint; ofs += hint;
        }
        lastofs++;
        while (lastofs < ofs) {
            int m = lastofs + ((ofs - lastofs) >> 1);
            if (lt(key, a[base + m])) ofs = m; else lastofs = m + 1;
        }
        return ofs;
    }

    private void mergeLo(int sa, int na, int sb, int nb) {
        tk = java.util.Arrays.copyOfRange(k, sa, sa + na);
        tv = v == null ? null : java.util.Arrays.copyOfRange(v, sa, sa + na);
        dst = sa; pa = 0; ca = na; pb = sb; cb = nb;
        boolean copyB;
        try { copyB = runLo(); }
        catch (RuntimeException e) { move(tk, tv, pa, dst, ca); throw e; }
        if (copyB) {
            move(k, v, pb, dst, cb);
            k[dst + cb] = tk[pa];
            if (v != null) v[dst + cb] = tv[pa];
        } else {
            move(tk, tv, pa, dst, ca);
        }
    }

    /** Returns true for CopyB, false for Succeed. */
    private boolean runLo() {
        put(dst++, k, v, pb++);
        if (--cb == 0) return false;
        if (ca == 1) return true;
        int mg = minGallop;
        for (;;) {
            int acount = 0, bcount = 0;
            for (;;) {
                if (lt(k[pb], tk[pa])) {
                    put(dst++, k, v, pb++); bcount++; acount = 0;
                    if (--cb == 0) return false;
                    if (bcount >= mg) break;
                } else {
                    put(dst++, tk, tv, pa++); acount++; bcount = 0;
                    if (--ca == 1) return true;
                    if (acount >= mg) break;
                }
            }
            mg++;
            do {
                if (mg > 1) mg--;
                minGallop = mg;
                int kk = gallopRight(k[pb], tk, pa, ca, 0);
                acount = kk;
                if (kk > 0) {
                    move(tk, tv, pa, dst, kk);
                    dst += kk; pa += kk; ca -= kk;
                    if (ca == 1) return true;
                    if (ca == 0) return false;
                }
                put(dst++, k, v, pb++);
                if (--cb == 0) return false;
                kk = gallopLeft(tk[pa], k, pb, cb, 0);
                bcount = kk;
                if (kk > 0) {
                    move(k, v, pb, dst, kk);
                    dst += kk; pb += kk; cb -= kk;
                    if (cb == 0) return false;
                }
                put(dst++, tk, tv, pa++);
                if (--ca == 1) return true;
            } while (acount >= MIN_GALLOP || bcount >= MIN_GALLOP);
            mg++;
            minGallop = mg;
        }
    }

    private void mergeHi(int sa, int na, int sb, int nb) {
        tk = java.util.Arrays.copyOfRange(k, sb, sb + nb);
        tv = v == null ? null : java.util.Arrays.copyOfRange(v, sb, sb + nb);
        dst = sb + nb - 1; pa = sa + na - 1; ca = na; pb = nb - 1; cb = nb;
        boolean copyA;
        try { copyA = runHi(sa); }
        catch (RuntimeException e) { if (cb > 0) move(tk, tv, 0, dst - (cb - 1), cb); throw e; }
        if (copyA) {
            dst -= ca; pa -= ca;
            move(k, v, pa + 1, dst + 1, ca);
            k[dst] = tk[pb];
            if (v != null) v[dst] = tv[pb];
        } else if (cb > 0) {
            move(tk, tv, 0, dst - (cb - 1), cb);
        }
    }

    /** Returns true for CopyA, false for Succeed. */
    private boolean runHi(int basea) {
        put(dst--, k, v, pa--);
        if (--ca == 0) return false;
        if (cb == 1) return true;
        int mg = minGallop;
        for (;;) {
            int acount = 0, bcount = 0;
            for (;;) {
                if (lt(tk[pb], k[pa])) {
                    put(dst--, k, v, pa--); acount++; bcount = 0;
                    if (--ca == 0) return false;
                    if (acount >= mg) break;
                } else {
                    put(dst--, tk, tv, pb--); bcount++; acount = 0;
                    if (--cb == 1) return true;
                    if (bcount >= mg) break;
                }
            }
            mg++;
            do {
                if (mg > 1) mg--;
                minGallop = mg;
                int kk = gallopRight(tk[pb], k, basea, ca, ca - 1);
                kk = ca - kk;
                acount = kk;
                if (kk > 0) {
                    dst -= kk; pa -= kk;
                    move(k, v, pa + 1, dst + 1, kk);
                    ca -= kk;
                    if (ca == 0) return false;
                }
                put(dst--, tk, tv, pb--);
                if (--cb == 1) return true;
                kk = gallopLeft(k[pa], tk, 0, cb, cb - 1);
                kk = cb - kk;
                bcount = kk;
                if (kk > 0) {
                    dst -= kk; pb -= kk;
                    move(tk, tv, pb + 1, dst + 1, kk);
                    cb -= kk;
                    if (cb == 1) return true;
                    if (cb == 0) return false;
                }
                put(dst--, k, v, pa--);
                if (--ca == 0) return false;
            } while (acount >= MIN_GALLOP || bcount >= MIN_GALLOP);
            mg++;
            minGallop = mg;
        }
    }
}
