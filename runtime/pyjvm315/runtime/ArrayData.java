// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Storage and element conversion of an {@code array.array}: the elements live in a little-endian byte buffer
 * (the layout {@code tobytes()} exposes), so slicing, concatenation, {@code frombytes} and buffer sharing are plain
 * byte copies.  Element values are converted on access exactly as CPython's array module does, including its
 * per-typecode overflow messages.
 */
final class ArrayData implements PyByteSequence {
    final char tc;          // typecode; 'F' stands for 'Zf' and 'D' for 'Zd'
    final int isz;          // itemsize
    byte[] buf;
    int n;

    ArrayData(char tc) {
        this.tc = tc;
        this.isz = sizeOf(tc);
        this.buf = new byte[0];
    }

    static int sizeOf(char tc) {
        return switch (tc) {
            case 'b', 'B' -> 1;
            case 'h', 'H', 'e' -> 2;
            case 'i', 'I', 'f', 'u', 'w' -> 4;
            case 'l', 'L', 'q', 'Q', 'd', 'F' -> 8;
            case 'D' -> 16;
            default -> 0;
        };
    }

    /** The typecode as Python spells it ('Zf' / 'Zd' for the complex ones). */
    String code() { return tc == 'F' ? "Zf" : tc == 'D' ? "Zd" : String.valueOf(tc); }

    boolean unicode() { return tc == 'u' || tc == 'w'; }

    // ---- PyByteSequence ------------------------------------------------------------------------------------
    public int byteSize() { return n * isz; }
    public int unsignedAt(int index) { return buf[index] & 0xff; }
    public byte[] toByteArray() { return Arrays.copyOf(buf, n * isz); }
    public Iterator<Object> iterator() {
        return new Iterator<>() {
            int i = 0;
            public boolean hasNext() { return i < n * isz; }
            public Object next() { return (long) (buf[i++] & 0xff); }
        };
    }

    // ---- sizing ----------------------------------------------------------------------------------------------
    void ensure(int items) {
        long need = (long) items * isz;
        if (need > Integer.MAX_VALUE - 16) throw new PyException("MemoryError", "");
        if (need > buf.length) buf = Arrays.copyOf(buf, (int) Math.max(need, Math.min(Integer.MAX_VALUE - 16, (long) buf.length * 3 / 2 + 16)));
    }

    void setLength(int items) {
        if (items > n) { ensure(items); Arrays.fill(buf, n * isz, items * isz, (byte) 0); }
        n = items;
    }

    ArrayData copyOf() {
        ArrayData c = new ArrayData(tc);
        c.buf = Arrays.copyOf(buf, n * isz);
        c.n = n;
        return c;
    }

    // ---- raw little-endian access ----------------------------------------------------------------------------
    long raw(int index, int size) {
        long v = 0;
        int base = index * isz;
        for (int k = size - 1; k >= 0; k--) v = (v << 8) | (buf[base + k] & 0xff);
        return v;
    }

    void putRaw(int index, int off, int size, long v) {
        int base = index * isz + off;
        for (int k = 0; k < size; k++) { buf[base + k] = (byte) v; v >>>= 8; }
    }

    // ---- element access --------------------------------------------------------------------------------------
    Object get(int i) {
        switch (tc) {
            case 'b': return (long) buf[i];
            case 'B': return (long) (buf[i] & 0xff);
            case 'h': return (long) (short) raw(i, 2);
            case 'H': return raw(i, 2);
            case 'i': return (long) (int) raw(i, 4);
            case 'I': return raw(i, 4);
            case 'l': case 'q': return raw(i, 8);
            case 'L': case 'Q': {
                long v = raw(i, 8);
                return v >= 0 ? (Object) v : PyRuntime.compact(new BigInteger(Long.toUnsignedString(v)));
            }
            case 'f': return (double) Float.intBitsToFloat((int) raw(i, 4));
            case 'd': return Double.longBitsToDouble(raw(i, 8));
            case 'e': return StructModule.halfValue((int) raw(i, 2));
            case 'u': case 'w': return new String(new int[]{(int) raw(i, 4)}, 0, 1);
            case 'F': {
                int base = i * isz;
                return new PyComplex((double) Float.intBitsToFloat((int) rawAt(base, 4)), (double) Float.intBitsToFloat((int) rawAt(base + 4, 4)));
            }
            default: {
                int base = i * isz;
                return new PyComplex(Double.longBitsToDouble(rawAt(base, 8)), Double.longBitsToDouble(rawAt(base + 8, 8)));
            }
        }
    }

    private long rawAt(int base, int size) {
        long v = 0;
        for (int k = size - 1; k >= 0; k--) v = (v << 8) | (buf[base + k] & 0xff);
        return v;
    }

    /** Store an already validated element. */
    void set(int i, Object value) {
        Object v = NativeSubclass.unwrap(value);
        switch (tc) {
            case 'f': putRaw(i, 0, 4, Float.floatToRawIntBits((float) toDouble(v))); return;
            case 'd': putRaw(i, 0, 8, Double.doubleToRawLongBits(toDouble(v))); return;
            case 'e': putRaw(i, 0, 2, StructModule.halfBits(toDouble(v))); return;
            case 'u': case 'w': putRaw(i, 0, 4, codePoint(v)); return;
            case 'F': case 'D': {
                double re, im;
                if (v instanceof PyComplex c) { re = c.real; im = c.imag; }
                else { re = toDouble(v); im = 0.0; }
                if (tc == 'F') { putRaw(i, 0, 4, Float.floatToRawIntBits((float) re)); putRaw(i, 4, 4, Float.floatToRawIntBits((float) im)); }
                else { putRaw(i, 0, 8, Double.doubleToRawLongBits(re)); putRaw(i, 8, 8, Double.doubleToRawLongBits(im)); }
                return;
            }
            default: putRaw(i, 0, isz, toLong(v));
        }
    }

    void append(Object v) {
        ensure(n + 1);
        n++;
        try { set(n - 1, v); } catch (RuntimeException e) { n--; throw e; }
    }

    // ---- conversions -----------------------------------------------------------------------------------------
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE), LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private static PyException overflow(String msg) { return new PyException("OverflowError", msg); }

    private long asCLong(BigInteger b) {
        if (b.compareTo(LONG_MIN) < 0 || b.compareTo(LONG_MAX) > 0) throw overflow("Python int too large to convert to C long");
        return b.longValue();
    }

    private static BigInteger asInteger(Object v) {
        if (PyRuntime.isIntLike(v)) return PyRuntime.bigInt(v);
        return BuiltinsExtra.rangeIndex(v);
    }

    private long toLong(Object v) {
        BigInteger b = asInteger(v);
        switch (tc) {
            case 'b': {
                long x = asCLong(b);
                if (x < -32768) throw overflow("signed short integer is less than minimum");
                if (x > 32767) throw overflow("signed short integer is greater than maximum");
                if (x < -128) throw overflow("signed char is less than minimum");
                if (x > 127) throw overflow("signed char is greater than maximum");
                return x;
            }
            case 'B': {
                long x = asCLong(b);
                if (x < 0) throw overflow("unsigned byte integer is less than minimum");
                if (x > 255) throw overflow("unsigned byte integer is greater than maximum");
                return x;
            }
            case 'h': {
                long x = asCLong(b);
                if (x < -32768) throw overflow("signed short integer is less than minimum");
                if (x > 32767) throw overflow("signed short integer is greater than maximum");
                return x;
            }
            case 'H': {
                long x = asInt(b);
                if (x < 0) throw overflow("unsigned short is less than minimum");
                if (x > 65535) throw overflow("unsigned short is greater than maximum");
                return x;
            }
            case 'i': return asInt(b);
            case 'I': {
                long x = asUnsigned(b);
                if (Long.compareUnsigned(x, 0xffffffffL) > 0) throw overflow("unsigned int is greater than maximum");
                return x;
            }
            case 'l': return asCLong(b);
            case 'L': return asUnsigned(b);
            case 'q': {
                if (b.compareTo(LONG_MIN) < 0 || b.compareTo(LONG_MAX) > 0) throw overflow("int too big to convert");
                return b.longValue();
            }
            default: {
                if (b.signum() < 0) throw overflow("can't convert negative int to unsigned");
                if (b.compareTo(U64_MAX) > 0) throw overflow("int too big to convert");
                return b.longValue();
            }
        }
    }

    private long asInt(BigInteger b) {
        long x = asCLong(b);
        if (x < Integer.MIN_VALUE) throw overflow("signed integer is less than minimum");
        if (x > Integer.MAX_VALUE) throw overflow("signed integer is greater than maximum");
        return x;
    }

    private static long asUnsigned(BigInteger b) {
        if (b.signum() < 0) throw overflow("can't convert negative value to unsigned int");
        if (b.compareTo(U64_MAX) > 0) throw overflow("Python int too large to convert to C unsigned long");
        return b.longValue();
    }

    static double toDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof Long l) return l.doubleValue();
        if (v instanceof BigInteger b) {
            double d = b.doubleValue();
            if (Double.isInfinite(d)) throw overflow("int too large to convert to float");
            return d;
        }
        if (v instanceof PyInstance pi && (pi.cls.lookupMethod("__float__") != null || pi.cls.lookupMethod("__index__") != null)) return (Double) PyRuntime.float_(v);
        throw new PyException("TypeError", "must be real number, not " + PyRuntime.typeName(v));
    }

    private int codePoint(Object v) {
        if (v instanceof String s) {
            int len = s.codePointCount(0, s.length());
            if (len == 1) return s.codePointAt(0);
            if (!LangVersion.atLeast(3, 14)) throw new PyException("TypeError", "array item must be unicode character");
            throw new PyException("TypeError", "array item must be a unicode character, not a string of length " + len);
        }
        if (!LangVersion.atLeast(3, 14)) throw new PyException("TypeError", "array item must be unicode character");
        throw new PyException("TypeError", "array item must be a unicode character, not " + PyRuntime.typeName(v));
    }

    /** Validate and convert without storing (used to fail before mutating). */
    Object check(Object v) {
        ArrayData probe = new ArrayData(tc);
        probe.ensure(1);
        probe.n = 1;
        probe.set(0, v);
        return probe.get(0);
    }
}
