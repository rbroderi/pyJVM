// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.ArrayModule.*;
import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The named methods of {@code array.array}, and the pickle helpers. */
final class ArrayMethods {
    private ArrayMethods() {}

    private static final Object NONE = NativeArgs.MISSING;

    static void define(PyClass cls) {
        Class<?> c = ArrayMethods.class;
        method(cls, c, "append", "append", "self,v");
        method(cls, c, "extend", "extend", "self,bb");
        method(cls, c, "insert", "insert", "self,i,v");
        method(cls, c, "pop", "pop", "self,i", defaults("i", -1L));
        method(cls, c, "remove", "remove", "self,v");
        method(cls, c, "index", "index", "self,v,start,stop", defaults("start", 0L, "stop", NONE));
        method(cls, c, "count", "count", "self,v");
        method(cls, c, "reverse", "reverse", "self");
        method(cls, c, "byteswap", "byteswap", "self");
        method(cls, c, "clear", "clear", "self");
        method(cls, c, "tolist", "tolist", "self");
        method(cls, c, "tobytes", "tobytes", "self");
        method(cls, c, "frombytes", "frombytes", "self,buffer");
        method(cls, c, "fromlist", "fromlist", "self,list");
        method(cls, c, "tounicode", "tounicode", "self");
        method(cls, c, "fromunicode", "fromunicode", "self,ustr");
        method(cls, c, "tofile", "tofile", "self,f");
        method(cls, c, "fromfile", "fromfile", "self,f,n");
        method(cls, c, "buffer_info", "bufferInfo", "self");
        method(cls, c, "__copy__", "copy", "self");
        method(cls, c, "__deepcopy__", "deepcopy", "self,memo");
        method(cls, c, "__sizeof__", "sizeof", "self");
        method(cls, c, "__reduce_ex__", "reduceEx", "self,protocol");
    }

    // ---- element-level --------------------------------------------------------------------------------------

    public static Object append(Object self, Object v) { self(self).append(v); return null; }

    static void extendFrom(ArrayData a, ArrayData b) {
        int old = a.n;
        byte[] add = b.buf.length == 0 ? b.buf : Arrays.copyOf(b.buf, b.n * b.isz);
        a.ensure(old + b.n);
        System.arraycopy(add, 0, a.buf, old * a.isz, add.length);
        a.n = old + b.n;
    }

    public static Object extend(Object self, Object bb) {
        ArrayData a = self(self);
        ArrayData b = asArray(bb);
        if (b != null) {
            if (b.tc != a.tc) throw new PyException("TypeError", "can only extend with array of same kind");
            extendFrom(a, b);
            return null;
        }
        for (Object x : PyRuntime.iterable(bb)) a.append(x);
        return null;
    }

    public static Object insert(Object self, Object iObj, Object v) {
        ArrayData a = self(self);
        BigInteger b = big(iObj);
        long i = b.bitLength() > 62 ? (b.signum() < 0 ? -1L << 62 : 1L << 62) : b.longValue();
        if (i < 0) { i += a.n; if (i < 0) i = 0; }
        if (i > a.n) i = a.n;
        a.check(v);
        a.append(v);
        int at = (int) i;
        if (at < a.n - 1) {
            byte[] last = Arrays.copyOfRange(a.buf, (a.n - 1) * a.isz, a.n * a.isz);
            System.arraycopy(a.buf, at * a.isz, a.buf, (at + 1) * a.isz, (a.n - 1 - at) * a.isz);
            System.arraycopy(last, 0, a.buf, at * a.isz, a.isz);
        }
        return null;
    }

    public static Object pop(Object self, Object iObj) {
        ArrayData a = self(self);
        if (a.n == 0) throw new PyException("IndexError", "pop from empty array");
        BigInteger b = big(iObj);
        if (b.signum() < 0) b = b.add(BigInteger.valueOf(a.n));
        if (b.signum() < 0 || b.compareTo(BigInteger.valueOf(a.n)) >= 0) throw new PyException("IndexError", "pop index out of range");
        int i = b.intValue();
        Object v = a.get(i);
        System.arraycopy(a.buf, (i + 1) * a.isz, a.buf, i * a.isz, (a.n - i - 1) * a.isz);
        a.n--;
        return v;
    }

    public static Object remove(Object self, Object v) {
        ArrayData a = self(self);
        for (int i = 0; i < a.n; i++) {
            if (PyRuntime.truth(PyRuntime.eq(a.get(i), v))) {
                System.arraycopy(a.buf, (i + 1) * a.isz, a.buf, i * a.isz, (a.n - i - 1) * a.isz);
                a.n--;
                return null;
            }
        }
        throw new PyException("ValueError", LangVersion.atLeast(3, 14) ? "array.remove(x): x not in array" : "array.remove(x): x not in array");
    }

    private static long clamp(Object o, int n, long dflt) {
        if (o == NONE) return dflt;
        BigInteger b = big(o);
        if (b.signum() < 0) { b = b.add(BigInteger.valueOf(n)); if (b.signum() < 0) return 0; }
        return b.compareTo(BigInteger.valueOf(n)) > 0 ? n : b.longValue();
    }

    public static Object index(Object self, Object v, Object start, Object stop) {
        ArrayData a = self(self);
        long from = clamp(start, a.n, 0), to = clamp(stop, a.n, a.n);
        for (long i = from; i < to; i++) if (PyRuntime.truth(PyRuntime.eq(a.get((int) i), v))) return i;
        throw new PyException("ValueError", "array.index(x): x not in array");
    }

    public static Object count(Object self, Object v) {
        ArrayData a = self(self);
        long c = 0;
        for (int i = 0; i < a.n; i++) if (PyRuntime.truth(PyRuntime.eq(a.get(i), v))) c++;
        return c;
    }

    public static Object reverse(Object self) {
        ArrayData a = self(self);
        byte[] tmp = new byte[a.isz];
        for (int i = 0, j = a.n - 1; i < j; i++, j--) {
            System.arraycopy(a.buf, i * a.isz, tmp, 0, a.isz);
            System.arraycopy(a.buf, j * a.isz, a.buf, i * a.isz, a.isz);
            System.arraycopy(tmp, 0, a.buf, j * a.isz, a.isz);
        }
        return null;
    }

    public static Object byteswap(Object self) {
        ArrayData a = self(self);
        int unit = a.tc == 'F' ? 4 : a.tc == 'D' ? 8 : a.isz;
        if (unit == 1) return null;
        for (int off = 0; off < a.n * a.isz; off += unit) {
            for (int i = off, j = off + unit - 1; i < j; i++, j--) { byte t = a.buf[i]; a.buf[i] = a.buf[j]; a.buf[j] = t; }
        }
        return null;
    }

    public static Object clear(Object self) {
        ArrayData a = self(self);
        a.buf = new byte[0];
        a.n = 0;
        return null;
    }

    // ---- conversions ----------------------------------------------------------------------------------------

    static Object tolist(ArrayData a) {
        ArrayList<Object> out = new ArrayList<>(a.n);
        for (int i = 0; i < a.n; i++) out.add(a.get(i));
        return out;
    }

    public static Object tolist(Object self) { return tolist(self(self)); }

    public static Object tobytes(Object self) { return new PyBytes(self(self).toByteArray()); }

    static void frombytes(ArrayData a, byte[] data) {
        if (data.length % a.isz != 0) throw new PyException("ValueError", "bytes length not a multiple of item size");
        if (a.unicode()) validateCodePoints(a, data);
        int old = a.n;
        a.ensure(old + data.length / a.isz);
        System.arraycopy(data, 0, a.buf, old * a.isz, data.length);
        a.n = old + data.length / a.isz;
    }

    private static void validateCodePoints(ArrayData a, byte[] data) {
        for (int i = 0; i + 3 < data.length; i += 4) {
            long cp = (data[i] & 0xffL) | (data[i + 1] & 0xffL) << 8 | (data[i + 2] & 0xffL) << 16 | (data[i + 3] & 0xffL) << 24;
            if (cp > 0x10ffff) {
                if (a.tc == 'u') throw new PyException("ValueError", "character U+" + Long.toHexString(cp) + " is not in range [U+0000; U+10ffff]");
                throw new PyException("UnicodeDecodeError", "'utf-32-le' codec can't decode bytes in position " + i + "-" + (i + 3) + ": code point not in range(0x110000)");
            }
        }
    }

    public static Object frombytes(Object self, Object buffer) {
        ArrayData a = self(self);
        frombytes(a, IoUtil.bytesLike(buffer));
        return null;
    }

    public static Object fromlist(Object self, Object list) {
        ArrayData a = self(self);
        if (!(NativeSubclass.unwrap(list) instanceof List<?> l)) throw new PyException("TypeError", "arg must be list");
        int old = a.n;
        try { for (Object x : new ArrayList<Object>(l)) a.append(x); }
        catch (RuntimeException e) { a.n = old; throw e; }
        return null;
    }

    static void appendString(ArrayData a, String s) {
        s.codePoints().forEach(cp -> a.append(new String(Character.toChars(cp))));
    }

    static Object tounicode(ArrayData a) {
        StringBuilder sb = new StringBuilder(a.n);
        for (int i = 0; i < a.n; i++) sb.appendCodePoint((int) a.raw(i, 4));
        return sb.toString();
    }

    public static Object tounicode(Object self) {
        ArrayData a = self(self);
        if (!a.unicode()) throw new PyException("ValueError", "tounicode() may only be called on unicode type arrays ('u' or 'w')");
        return tounicode(a);
    }

    public static Object fromunicode(Object self, Object ustr) {
        ArrayData a = self(self);
        Object u = NativeSubclass.unwrap(ustr);
        if (!(u instanceof String s)) throw new PyException("TypeError", "fromunicode() argument must be str, not " + PyRuntime.typeName(ustr));
        if (!a.unicode()) throw new PyException("ValueError", "fromunicode() may only be called on unicode type arrays ('u' or 'w')");
        appendString(a, s);
        return null;
    }

    public static Object tofile(Object self, Object f) {
        ArrayData a = self(self);
        byte[] all = a.toByteArray();
        int block = 64 * 1024;
        for (int off = 0; off < all.length || off == 0 && all.length == 0; off += block) {
            if (all.length == 0) break;
            PyRuntime.callMethod(f, "write", new Object[]{new PyBytes(Arrays.copyOfRange(all, off, Math.min(all.length, off + block)))});
        }
        return null;
    }

    public static Object fromfile(Object self, Object f, Object nObj) {
        ArrayData a = self(self);
        BigInteger n = big(nObj);
        if (n.signum() < 0) throw new PyException("ValueError", LangVersion.atLeast(3, 15) ? "n cannot be negative" : "negative count");
        BigInteger nbytes = n.multiply(BigInteger.valueOf(a.isz));
        if (nbytes.bitLength() > 31) throw new PyException("MemoryError", "");
        Object r = PyRuntime.callMethod(f, "read", new Object[]{compactLong(nbytes)});
        Object u = NativeSubclass.unwrap(r);
        if (!(u instanceof PyBytes b)) throw new PyException("TypeError", "read() didn't return bytes");
        boolean notEnough = b.data.length != nbytes.intValue();
        frombytes(a, b.data);
        if (notEnough) throw new PyException("EOFError", "read() didn't return enough bytes");
        return null;
    }

    private static Object compactLong(BigInteger b) { return b.longValue(); }

    public static Object bufferInfo(Object self) {
        ArrayData a = self(self);
        return tuple((long) System.identityHashCode(a) * 16L, (long) a.n);
    }

    public static Object copy(Object self) { return ArrayOps.like(self, self(self).copyOf()); }

    public static Object deepcopy(Object self, Object memo) { return copy(self); }

    public static Object sizeof(Object self) { ArrayData a = self(self); return 64L + a.buf.length; }

    // ---- pickling ---------------------------------------------------------------------------------------------

    private static final String CODES = "BbHhIiLlQqfdUUUU";

    /** The machine format code {@code tc} has on this (little-endian, LP64) machine, or -1. */
    static int mformat(char tc) {
        return switch (tc) {
            case 'B' -> 0; case 'b' -> 1; case 'H' -> 2; case 'h' -> 4; case 'I' -> 6; case 'i' -> 8;
            case 'L', 'Q' -> 10; case 'l', 'q' -> 12; case 'f' -> 14; case 'd' -> 16; case 'u', 'w' -> 20;
            case 'F' -> 22; case 'D' -> 24; case 'e' -> 26;
            default -> -1;
        };
    }

    public static Object reduceEx(Object self, Object protocol) {
        ArrayData a = self(self);
        PyInstance inst = (PyInstance) self;
        long proto = IoUtil.asLong(protocol);
        Object dict = inst.cls == arrayClass ? null : new PyDict(inst.fields);
        int mf = mformat(a.tc);
        if (proto < 3 || mf < 0) {
            Object items = a.unicode() ? tounicode(a) : tolist(a);
            return tuple(inst.cls, tuple(a.code(), items), dict);
        }
        return tuple(reconstructor, tuple(inst.cls, a.code(), (long) mf, new PyBytes(a.toByteArray())), dict);
    }

    @SuppressWarnings("unchecked")
    static Object reconstruct(List<Object> args, Map<Object, Object> kw) {
        if (args.size() != 4 || !kw.isEmpty()) throw new PyException("TypeError", "_array_reconstructor expected 4 arguments, got " + args.size());
        Object type = args.get(0);
        if (!(type instanceof PyClass pc) || !pc.mro.contains(arrayClass)) {
            if (type instanceof PyClass || type instanceof PyBuiltinType) throw new PyException("TypeError", PyRuntime.pyStr(PyRuntime.getattr(type, "__name__")) + " is not a subtype of array.array");
            throw new PyException("TypeError", "first argument must be a type object, not " + PyRuntime.typeName(type));
        }
        if (!(args.get(1) instanceof String tc) || !validCode(tc)) throw new PyException("ValueError", "second argument must be a valid type code");
        long mf = IoUtil.asLong(args.get(2));
        if (mf < 0 || mf > (LangVersion.atLeast(3, 15) ? 27 : 21)) throw new PyException("ValueError", "third argument must be a valid machine format code.");
        Object it = NativeSubclass.unwrap(args.get(3));
        if (!(it instanceof PyBytes bytes)) throw new PyException("TypeError", "fourth argument should be bytes, not " + PyRuntime.typeName(it));
        char code = tc.equals("Zf") ? 'F' : tc.equals("Zd") ? 'D' : tc.charAt(0);
        if (mf <= 13) code = integerCode((int) mf);
        ArrayData d = new ArrayData(code);
        PyInstance out = newInstance(pc, d);
        if (mf == mformat(code) && mf >= 0) { frombytes(d, bytes.data); return out; }
        decodeForeign(d, (int) mf, bytes.data);
        return out;
    }

    /** The array typecode CPython picks for an integer machine format (size and signedness decide). */
    private static char integerCode(int mf) {
        if (mf <= 1) return mf == 0 ? 'B' : 'b';
        int size = 2 << ((mf - 2) / 4);
        boolean signed = ((mf - 2) / 2) % 2 == 1;
        return switch (size) {
            case 2 -> signed ? 'h' : 'H';
            case 4 -> signed ? 'i' : 'I';
            default -> signed ? 'q' : 'Q';
        };
    }

    private static void decodeForeign(ArrayData d, int mf, byte[] data) {
        boolean big = mf % 2 == 1 && mf >= 2;
        int size, kind;     // kind: 0 unsigned, 1 signed, 2 float, 3 utf16, 4 utf32
        if (mf <= 1) { size = 1; kind = mf == 0 ? 0 : 1; big = false; }
        else if (mf <= 13) { size = 2 << ((mf - 2) / 4); kind = ((mf - 2) / 2) % 2 == 0 ? 0 : 1; }
        else if (mf <= 17) { size = mf <= 15 ? 4 : 8; kind = 2; }
        else if (mf <= 19) { size = 2; kind = 3; }
        else if (mf <= 21) { size = 4; kind = 4; }
        else if (mf <= 25) { size = mf <= 23 ? 8 : 16; kind = 5; big = mf % 2 == 1; }
        else { size = 2; kind = 6; big = mf % 2 == 1; }
        if (data.length % size != 0) throw new PyException("ValueError", "string length not a multiple of item size");
        if (kind == 3) {
            String text = new String(data, big ? java.nio.charset.StandardCharsets.UTF_16BE : java.nio.charset.StandardCharsets.UTF_16LE);
            appendString(d, text);
            return;
        }
        for (int off = 0; off < data.length; off += size) {
            long v = 0;
            for (int k = 0; k < size; k++) {
                int idx = big ? off + k : off + size - 1 - k;
                v = (v << 8) | (data[idx] & 0xff);
            }
            Object x;
            if (kind == 5) { x = complexAt(data, off, size / 2, big); d.append(x); continue; }
            if (kind == 6) { x = StructModule.halfValue((int) v); d.append(x); continue; }
            if (kind == 2) x = size == 4 ? (Object) (double) Float.intBitsToFloat((int) v) : (Object) Double.longBitsToDouble(v);
            else if (kind == 1) { int sh = 64 - 8 * size; x = (v << sh) >> sh; }
            else if (kind == 0) x = size == 8 && v < 0 ? PyRuntime.compact(new BigInteger(Long.toUnsignedString(v))) : (Object) v;
            else x = new String(new int[]{(int) v}, 0, 1);
            d.append(x);
        }
    }

    private static Object complexAt(byte[] data, int off, int half, boolean big) {
        double[] parts = new double[2];
        for (int p = 0; p < 2; p++) {
            long v = 0;
            for (int k = 0; k < half; k++) {
                int idx = big ? off + p * half + k : off + p * half + half - 1 - k;
                v = (v << 8) | (data[idx] & 0xff);
            }
            parts[p] = half == 4 ? (double) Float.intBitsToFloat((int) v) : Double.longBitsToDouble(v);
        }
        return new PyComplex(parts[0], parts[1]);
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
}
