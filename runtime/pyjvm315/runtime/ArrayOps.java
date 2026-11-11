// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.ArrayModule.*;
import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.Arrays;

/** The sequence protocol of {@code array.array}: len, indexing, slicing, concatenation, repetition, comparison, repr. */
final class ArrayOps {
    private ArrayOps() {}

    static void define(PyClass cls) {
        Class<?> c = ArrayOps.class;
        method(cls, c, "__len__", "len", "self");
        method(cls, c, "__getitem__", "getitem", "self,index");
        method(cls, c, "__setitem__", "setitem", "self,index,value");
        method(cls, c, "__delitem__", "delitem", "self,index");
        method(cls, c, "__contains__", "contains", "self,value");
        method(cls, c, "__iter__", "iter", "self");
        method(cls, c, "__add__", "add", "self,other");
        method(cls, c, "__iadd__", "iadd", "self,other");
        method(cls, c, "__mul__", "mul", "self,count");
        method(cls, c, "__rmul__", "mul", "self,count");
        method(cls, c, "__imul__", "imul", "self,count");
        method(cls, c, "__eq__", "eq", "self,other");
        method(cls, c, "__ne__", "ne", "self,other");
        method(cls, c, "__lt__", "lt", "self,other");
        method(cls, c, "__le__", "le", "self,other");
        method(cls, c, "__gt__", "gt", "self,other");
        method(cls, c, "__ge__", "ge", "self,other");
        method(cls, c, "__repr__", "repr", "self");
    }

    static Object like(Object self, ArrayData d) { return newInstance(arrayClass, d); }

    public static Object len(Object self) { return (long) self(self).n; }

    public static Object iter(Object self) { return ArrayModule.iterator(self(self)); }

    // ---- indexing ----------------------------------------------------------------------------------------------

    private static boolean indexable(Object i) {
        Object u = NativeSubclass.unwrap(i);
        return PyRuntime.isIntLike(u) || (i instanceof PyInstance pi && pi.cls.lookupMethod("__index__") != null);
    }

    /** Resolved slice: first index, step and number of elements. */
    static long[] slice(PySlice s, int len) {
        BigInteger step = s.step == null ? BigInteger.ONE : big(s.step);
        if (step.signum() == 0) throw new PyException("ValueError", "slice step cannot be zero");
        boolean neg = step.signum() < 0;
        long stp = step.bitLength() > 62 ? (neg ? -(1L << 62) : (1L << 62)) : step.longValue();
        long lower = neg ? -1 : 0, upper = neg ? len - 1 : len;
        long start = bound(s.start, neg ? upper : lower, len, lower, upper);
        long stop = bound(s.stop, neg ? lower : upper, len, lower, upper);
        long count = 0;
        if (neg) { if (stop < start) count = (start - stop - 1) / (-stp) + 1; }
        else if (start < stop) count = (stop - start - 1) / stp + 1;
        return new long[]{start, stp, count, stop};
    }

    private static long bound(Object v, long dflt, int len, long lower, long upper) {
        if (v == null) return dflt;
        BigInteger b = big(v);
        if (b.signum() < 0) { b = b.add(BigInteger.valueOf(len)); if (b.signum() < 0) return lower; }
        if (b.compareTo(BigInteger.valueOf(upper)) > 0) return upper;
        return b.longValue();
    }

    static int index(Object idx, int len, String what) {
        if (!indexable(idx)) throw new PyException("TypeError", "array indices must be integers");
        BigInteger b = big(idx);
        if (b.bitLength() > 63) throw new PyException("IndexError", "cannot fit 'int' into an index-sized integer");
        if (b.signum() < 0) b = b.add(BigInteger.valueOf(len));
        if (b.signum() < 0 || b.compareTo(BigInteger.valueOf(len)) >= 0) throw new PyException("IndexError", what);
        return b.intValue();
    }

    public static Object getitem(Object self, Object idx) {
        ArrayData a = self(self);
        Object u = NativeSubclass.unwrap(idx);
        if (u instanceof PySlice s) {
            long[] r = slice(s, a.n);
            ArrayData out = new ArrayData(a.tc);
            int cnt = (int) r[2];
            out.ensure(cnt);
            out.n = cnt;
            if (r[1] == 1) System.arraycopy(a.buf, (int) r[0] * a.isz, out.buf, 0, cnt * a.isz);
            else for (int k = 0; k < cnt; k++) System.arraycopy(a.buf, (int) (r[0] + k * r[1]) * a.isz, out.buf, k * a.isz, a.isz);
            return like(self, out);
        }
        return a.get(index(idx, a.n, "array index out of range"));
    }

    public static Object setitem(Object self, Object idx, Object value) {
        ArrayData a = self(self);
        Object u = NativeSubclass.unwrap(idx);
        if (u instanceof PySlice s) { assignSlice(a, s, value); return null; }
        int i = index(idx, a.n, "array assignment index out of range");
        a.set(i, value);
        return null;
    }

    private static void assignSlice(ArrayData a, PySlice s, Object value) {
        ArrayData v = asArray(value);
        if (v == null) throw new PyException("TypeError", "can only assign array (not \"" + PyRuntime.typeName(value) + "\") to array slice");
        if (v.tc != a.tc) throw new PyException("TypeError", "bad argument type for built-in operation");
        if (v == a) v = a.copyOf();
        long[] r = slice(s, a.n);
        int cnt = (int) r[2];
        if (r[1] == 1) {
            int start = (int) r[0];
            int stop = (int) Math.max(r[0], r[3]);
            replaceRange(a, start, stop, v);
            return;
        }
        if (v.n != cnt) throw new PyException("ValueError", "attempt to assign array of size " + v.n + " to extended slice of size " + cnt);
        for (int k = 0; k < cnt; k++) System.arraycopy(v.buf, k * a.isz, a.buf, (int) (r[0] + k * r[1]) * a.isz, a.isz);
    }

    static void replaceRange(ArrayData a, int start, int stop, ArrayData v) {
        int tail = a.n - stop;
        int newLen = start + v.n + tail;
        byte[] out = new byte[Math.max(newLen * a.isz, 0)];
        System.arraycopy(a.buf, 0, out, 0, start * a.isz);
        System.arraycopy(v.buf, 0, out, start * a.isz, v.n * a.isz);
        System.arraycopy(a.buf, stop * a.isz, out, (start + v.n) * a.isz, tail * a.isz);
        a.buf = out;
        a.n = newLen;
    }

    public static Object delitem(Object self, Object idx) {
        ArrayData a = self(self);
        Object u = NativeSubclass.unwrap(idx);
        if (u instanceof PySlice s) {
            long[] r = slice(s, a.n);
            int cnt = (int) r[2];
            if (cnt == 0) return null;
            if (r[1] == 1) { replaceRange(a, (int) r[0], (int) r[0] + cnt, new ArrayData(a.tc)); return null; }
            long step = r[1];
            long first = step > 0 ? r[0] : r[0] + (cnt - 1) * step;
            long stride = Math.abs(step);
            boolean[] drop = new boolean[a.n];
            for (int k = 0; k < cnt; k++) drop[(int) (first + k * stride)] = true;
            compact(a, drop);
            return null;
        }
        int i = index(idx, a.n, "array assignment index out of range");
        System.arraycopy(a.buf, (i + 1) * a.isz, a.buf, i * a.isz, (a.n - i - 1) * a.isz);
        a.n--;
        return null;
    }

    private static void compact(ArrayData a, boolean[] drop) {
        int w = 0;
        for (int i = 0; i < a.n; i++) {
            if (drop[i]) continue;
            if (w != i) System.arraycopy(a.buf, i * a.isz, a.buf, w * a.isz, a.isz);
            w++;
        }
        a.n = w;
    }

    public static Object contains(Object self, Object value) {
        ArrayData a = self(self);
        for (int i = 0; i < a.n; i++) if (PyRuntime.truth(PyRuntime.eq(a.get(i), value))) return true;
        return false;
    }

    // ---- arithmetic ----------------------------------------------------------------------------------------------

    private static ArrayData operand(Object other, String verb) {
        ArrayData b = asArray(other);
        if (b == null) throw new PyException("TypeError", "can only " + verb + " array (not \"" + PyRuntime.typeName(other) + "\") to array");
        return b;
    }

    public static Object add(Object self, Object other) {
        ArrayData a = self(self);
        ArrayData b = asArray(other);
        if (b == null) throw new PyException("TypeError", "can only append array (not \"" + PyRuntime.typeName(other) + "\") to array");
        if (a.tc != b.tc) throw new PyException("TypeError", "bad argument type for built-in operation");
        ArrayData out = new ArrayData(a.tc);
        out.buf = new byte[(a.n + b.n) * a.isz];
        System.arraycopy(a.buf, 0, out.buf, 0, a.n * a.isz);
        System.arraycopy(b.buf, 0, out.buf, a.n * a.isz, b.n * a.isz);
        out.n = a.n + b.n;
        return like(self, out);
    }

    public static Object iadd(Object self, Object other) {
        ArrayData a = self(self);
        ArrayData b = asArray(other);
        if (b == null) throw new PyException("TypeError", "can only extend array with array (not \"" + PyRuntime.typeName(other) + "\")");
        if (a.tc != b.tc) throw new PyException("TypeError", "can only extend with array of same kind");
        ArrayMethods.extendFrom(a, b);
        return self;
    }

    private static long count(Object c) {
        if (!indexable(c)) return Long.MIN_VALUE;
        BigInteger b = big(c);
        if (b.signum() <= 0) return 0;
        return b.bitLength() > 62 ? Long.MAX_VALUE : b.longValue();
    }

    private static byte[] repeat(ArrayData a, long times) {
        long total = (long) a.n * a.isz * times;
        if (a.n != 0 && times > Integer.MAX_VALUE / Math.max(1, (long) a.n * a.isz)) throw new PyException("MemoryError", "");
        byte[] out = new byte[(int) total];
        int unit = a.n * a.isz;
        for (long k = 0; k < times; k++) System.arraycopy(a.buf, 0, out, (int) (k * unit), unit);
        return out;
    }

    public static Object mul(Object self, Object count) {
        ArrayData a = self(self);
        long t = count(count);
        if (t == Long.MIN_VALUE) throw new PyException("TypeError", "can't multiply sequence by non-int of type '" + PyRuntime.typeName(count) + "'");
        ArrayData out = new ArrayData(a.tc);
        if (a.n > 0 && t > 0) { out.buf = repeat(a, t); out.n = (int) (a.n * t); }
        return like(self, out);
    }

    public static Object imul(Object self, Object count) {
        ArrayData a = self(self);
        long t = count(count);
        if (t == Long.MIN_VALUE) big(count);
        if (a.n == 0 || t == 1) return self;
        if (t <= 0) { a.buf = new byte[0]; a.n = 0; return self; }
        a.buf = repeat(a, t);
        a.n = (int) (a.n * t);
        return self;
    }

    // ---- comparison and repr -----------------------------------------------------------------------------------

    private static Object compare(Object self, Object other, String op) {
        ArrayData a = self(self);
        ArrayData b = asArray(other);
        if (b == null) return PyRuntime.notImplemented();
        boolean eqOp = op.equals("eq"), neOp = op.equals("ne");
        if ((eqOp || neOp) && a.n != b.n) return neOp;
        int m = Math.min(a.n, b.n);
        int i = 0;
        for (; i < m; i++) {
            if (!PyRuntime.truth(PyRuntime.eq(a.get(i), b.get(i)))) break;
        }
        if (i >= m) {
            int cmp = Integer.compare(a.n, b.n);
            return switch (op) {
                case "eq" -> cmp == 0;
                case "ne" -> cmp != 0;
                case "lt" -> cmp < 0;
                case "le" -> cmp <= 0;
                case "gt" -> cmp > 0;
                default -> cmp >= 0;
            };
        }
        if (eqOp) return false;
        if (neOp) return true;
        Object x = a.get(i), y = b.get(i);
        return switch (op) {
            case "lt" -> PyRuntime.lt(x, y);
            case "le" -> PyRuntime.le(x, y);
            case "gt" -> PyRuntime.gt(x, y);
            default -> PyRuntime.ge(x, y);
        };
    }

    public static Object eq(Object s, Object o) { return compare(s, o, "eq"); }
    public static Object ne(Object s, Object o) { return compare(s, o, "ne"); }
    public static Object lt(Object s, Object o) { return compare(s, o, "lt"); }
    public static Object le(Object s, Object o) { return compare(s, o, "le"); }
    public static Object gt(Object s, Object o) { return compare(s, o, "gt"); }
    public static Object ge(Object s, Object o) { return compare(s, o, "ge"); }

    public static Object repr(Object self) {
        ArrayData a = self(self);
        String name = ((PyInstance) self).cls.name;
        if (a.n == 0) return name + "('" + a.code() + "')";
        Object body = a.unicode() ? ArrayMethods.tounicode(a) : ArrayMethods.tolist(a);
        return name + "('" + a.code() + "', " + PyRuntime.pyRepr(body) + ")";
    }

    static byte[] range(ArrayData a, int from, int to) { return Arrays.copyOfRange(a.buf, from * a.isz, to * a.isz); }
}
