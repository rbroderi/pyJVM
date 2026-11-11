// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * {@code _struct}: format strings, {@code Struct}, and the module-level pack/unpack functions, for every format
 * character and the byte-order/alignment modes {@code @ = < > !}. Native mode follows the LP64 Linux layout
 * (long and size_t are 8 bytes). Error messages are CPython's.
 */
public final class StructModule {
    private StructModule() {}

    static PyClass structClass, errorClass, iterClass;

    /** One run of a format: {@code count} items (or bytes, for x/s/p) of format character {@code ch} at {@code offset}. */
    private record Code(char ch, int size, int count, long offset) {}

    /** A compiled format. */
    static final class Fmt {
        String source;
        boolean nat, little;
        final ArrayList<Code> codes = new ArrayList<>();
        long size;
        int items;
    }

    private static final HashMap<String, Fmt> CACHE = new HashMap<>();

    static PyException err(String msg) { return PyRuntime.userException(PyRuntime.instantiate(errorClass, new Object[]{msg})); }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_struct", "pyjvm315.runtime.StructModule");
        errorClass = (PyClass) PyRuntime.classCreate("error", "struct");
        PyRuntime.classAddBase(errorClass, PyRuntime.builtinType("Exception"));
        finish(errorClass);
        m.attrs.put("error", errorClass);
        Class<?> c = StructModule.class;
        structClass = define("_struct", "Struct", null);
        PyRuntime.classAddMethodEx(structClass, "__new__", c, "stNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(structClass, "__new__", structClass.methods.get("__new__").function);
        methodVarKw(structClass, c, "__init__", "stInit", "self", "args", "kwargs", new PyDict());
        methodVarKw(structClass, c, "pack", "stPack", "self", "args", "kwargs", new PyDict());
        methodVarKw(structClass, c, "unpack", "stUnpack", "self", "args", "kwargs", new PyDict());
        methodVarKw(structClass, c, "unpack_from", "stUnpackFrom", "self", "args", "kwargs", new PyDict());
        methodVarKw(structClass, c, "pack_into", "stPackInto", "self", "args", "kwargs", new PyDict());
        methodVarKw(structClass, c, "iter_unpack", "stIterUnpack", "self", "args", "kwargs", new PyDict());
        method(structClass, c, "__repr__", "stRepr", "self");
        method(structClass, c, "__sizeof__", "stSizeof", "self");
        PyRuntime.classAddProperty(structClass, "format", c.getName(), "stFormat", null);
        PyRuntime.classAddProperty(structClass, "size", c.getName(), "stSize", null);
        PyRuntime.setattr(structClass, "__doc__", "Struct(fmt) --> compiled struct object\n\n");
        finish(structClass);
        m.attrs.put("Struct", structClass);
        iterClass = define("_struct", "unpack_iterator", null);
        finish(iterClass);
        fn(m, "calcsize", (a, k) -> calcsize(a, k));
        fn(m, "pack", (a, k) -> pack(a, k));
        fn(m, "unpack", (a, k) -> unpack(a, k));
        fn(m, "unpack_from", (a, k) -> unpackFrom(a, k));
        fn(m, "pack_into", (a, k) -> packInto(a, k));
        fn(m, "iter_unpack", (a, k) -> iterUnpack(a, k));
        fn(m, "_clearcache", (a, k) -> { synchronized (CACHE) { CACHE.clear(); } return null; });
        m.attrs.put("__doc__", "Functions to convert between Python values and C structs.\nPython bytes objects are used to hold the data representing the C struct\nand also as format strings (explained below) to describe the layout of data\nin the C struct.\n");
        return m;
    }

    // ---- format compilation ------------------------------------------------------------------------------

    private static boolean complexOk() { return LangVersion.atLeast(3, 14); }

    /** Size of one item of {@code ch}, or -1 when the character is not valid in this mode. */
    private static int itemSize(char ch, boolean nat) {
        switch (ch) {
            case 'x': case 'c': case 'b': case 'B': case '?': case 's': case 'p': return 1;
            case 'h': case 'H': case 'e': return 2;
            case 'i': case 'I': case 'f': return 4;
            case 'l': case 'L': return nat ? 8 : 4;
            case 'q': case 'Q': case 'd': return 8;
            case 'n': case 'N': case 'P': return nat ? 8 : -1;
            case 'F': return complexOk() ? 8 : -1;
            case 'D': return complexOk() ? 16 : -1;
            default: return -1;
        }
    }

    private static int itemAlign(char ch, int size) {
        switch (ch) {
            case 'x': case 'c': case 'b': case 'B': case '?': case 's': case 'p': return 1;
            case 'D': return 8;
            case 'F': return 4;
            default: return size;
        }
    }

    static Fmt compile(Object formatObj) {
        String key;
        if (formatObj instanceof String s) key = s;
        else if (NativeSubclass.unwrap(formatObj) instanceof PyByteSequence b && !(NativeSubclass.unwrap(formatObj) instanceof PyMemoryView)) {
            byte[] raw = b.toByteArray();
            StringBuilder sb = new StringBuilder();
            for (byte x : raw) sb.append((char) (x & 0xff));
            key = sb.toString();
        } else if (NativeSubclass.unwrap(formatObj) instanceof String s) key = s;
        else throw new PyException("TypeError", "Struct() argument 1 must be a str or bytes object, not " + PyRuntime.typeName(formatObj));
        synchronized (CACHE) {
            Fmt hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch >= 128 && formatObj instanceof String) {
                if (LangVersion.atLeast(3, 14)) throw new PyException("ValueError", "non-ASCII character in struct format");
                PyRuntime.callMethod(key, "encode", new Object[]{"ascii"});
            }
        }
        Fmt f = parse(key);
        synchronized (CACHE) { if (CACHE.size() >= 100) CACHE.clear(); CACHE.put(key, f); }
        return f;
    }

    private static Fmt parse(String s) {
        Fmt f = new Fmt();
        f.source = s;
        f.nat = true; f.little = true;
        int i = 0;
        if (!s.isEmpty()) {
            switch (s.charAt(0)) {
                case '@': i = 1; break;
                case '=': case '<': f.nat = false; i = 1; break;
                case '>': case '!': f.nat = false; f.little = false; i = 1; break;
                default: break;
            }
        }
        if (s.indexOf('\0') >= 0) throw err("embedded null character");
        long size = 0;
        long limit = Long.MAX_VALUE;
        while (i < s.length()) {
            char ch = s.charAt(i++);
            if (ch == ' ' || (ch >= '\t' && ch <= '\r')) continue;
            long num = 1;
            if (ch >= '0' && ch <= '9') {
                num = ch - '0';
                while (true) {
                    if (i >= s.length()) throw err("repeat count given without format specifier");
                    ch = s.charAt(i++);
                    if (ch < '0' || ch > '9') break;
                    if (num > (limit - (ch - '0')) / 10) throw err("total struct size too long");
                    num = num * 10 + (ch - '0');
                }
            }
            int isz = itemSize(ch, f.nat);
            if (isz < 0) throw err("bad char in struct format");
            int align = f.nat ? itemAlign(ch, isz) : 1;
            if (align > 1) {
                long rem = size % align;
                if (rem != 0) {
                    long pad = align - rem;
                    if (size > limit - pad) throw err("total struct size too long");
                    size += pad;
                }
            }
            boolean bytesRun = ch == 's' || ch == 'p' || ch == 'x';
            long runBytes = bytesRun ? num : num * isz;
            if (!bytesRun && isz > 0 && num > limit / isz) throw err("total struct size too long");
            if (size > limit - runBytes) throw err("total struct size too long");
            if (num > Integer.MAX_VALUE) throw err("total struct size too long");
            f.codes.add(new Code(ch, isz, (int) num, size));
            size += runBytes;
            if (ch == 's' || ch == 'p') f.items += 1;
            else if (ch != 'x') f.items += (int) Math.min(num, Integer.MAX_VALUE - f.items);
        }
        f.size = size;
        return f;
    }

    private static int checkedSize(Fmt f) {
        if (f.size > Integer.MAX_VALUE - 16) throw new PyException("MemoryError", null);
        return (int) f.size;
    }

    // ---- packing -----------------------------------------------------------------------------------------

    private static void putBits(byte[] buf, int pos, int size, boolean little, long bits) {
        for (int i = 0; i < size; i++) {
            int shift = 8 * (little ? i : size - 1 - i);
            buf[pos + i] = (byte) (shift >= 64 ? (bits < 0 ? -1 : 0) : bits >>> shift);
        }
    }

    private static BigInteger getInt(Object v) {
        Object u = NativeSubclass.unwrap(v);
        if (PyRuntime.isIntLike(u)) return PyRuntime.bigInt(u);
        if (v instanceof PyInstance pi && pi.cls.lookupMethod("__index__") != null) return BuiltinsExtra.rangeIndex(v);
        throw err("required argument is not an integer");
    }

    private static final BigInteger TWO64 = BigInteger.ONE.shiftLeft(64);

    private static long packInt(Object v, char ch, int size) {
        BigInteger b = getInt(v);
        boolean signed = "bhilqn".indexOf(ch) >= 0;
        if (ch == 'P') {
            if (b.signum() < 0 ? b.bitLength() > 63 : b.bitLength() > 64) throw err("int too large to convert");
            return b.longValue();
        }
        BigInteger min, max;
        if (signed) { min = BigInteger.ONE.shiftLeft(size * 8 - 1).negate(); max = BigInteger.ONE.shiftLeft(size * 8 - 1).subtract(BigInteger.ONE); }
        else { min = BigInteger.ZERO; max = BigInteger.ONE.shiftLeft(size * 8).subtract(BigInteger.ONE); }
        if (b.compareTo(min) < 0 || b.compareTo(max) > 0)
            throw err("'" + ch + "' format requires " + min + " <= number <= " + max);
        return b.longValue();
    }

    private static double getDouble(Object v) {
        try {
            Object u = NativeSubclass.unwrap(v);
            if (u instanceof Double d) return d;
            if (u instanceof Long l) return l.doubleValue();
            if (u instanceof Boolean b) return b ? 1.0 : 0.0;
            if (u instanceof BigInteger b) {
                double d = b.doubleValue();
                if (Double.isInfinite(d)) throw new PyException("OverflowError", "int too large to convert to float");
                return d;
            }
            if (v instanceof PyInstance pi) {
                if (pi.cls.lookupMethod("__float__") != null || pi.cls.lookupMethod("__index__") != null) {
                    return (Double) PyRuntime.float_(v);
                }
            }
        } catch (PyException e) { /* any failure becomes struct.error below */ }
        throw err("required argument is not a float");
    }

    /** IEEE binary16 bits of {@code x}, rounding half to even. */
    static int halfBits(double x) {
        long raw = Double.doubleToRawLongBits(x);
        int sign = (int) (raw >>> 63);
        int e;
        int bits;
        if (Double.isInfinite(x)) { e = 0x1f; bits = 0; }
        else if (Double.isNaN(x)) { e = 0x1f; bits = 512; }
        else if (x == 0.0) { e = 0; bits = 0; }
        else {
            double ax = Math.abs(x);
            int exp = Math.getExponent(ax);
            double f;
            if (exp < Double.MIN_EXPONENT) { double s = ax * 0x1p54; exp = Math.getExponent(s); f = s / Math.scalb(1.0, exp) / 2; exp = exp - 54 + 1; }
            else { f = ax / Math.scalb(1.0, exp) / 2; exp = exp + 1; }
            e = exp;      // ax = f * 2**e with 0.5 <= f < 1
            f *= 2.0; e--;
            if (e >= 16) throw new PyException("OverflowError", "float too large to pack with e format");
            else if (e < -25) { f = 0.0; e = 0; }
            else if (e < -14) { f = Math.scalb(f, 14 + e); e = 0; }
            else { e += 15; f -= 1.0; }
            f *= 1024.0;
            bits = (int) f;
            if ((f - bits > 0.5) || ((f - bits == 0.5) && (bits % 2 == 1))) {
                ++bits;
                if (bits == 1024) { bits = 0; ++e; if (e == 31) throw new PyException("OverflowError", "float too large to pack with e format"); }
            }
        }
        return bits | (e << 10) | (sign << 15);
    }

    static double halfValue(int bits) {
        int sign = (bits >> 15) & 1, e = (bits >> 10) & 0x1f, f = bits & 0x3ff;
        double x;
        if (e == 0x1f) x = f == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        else if (e == 0) x = Math.scalb((double) f, -24);
        else x = Math.scalb((double) (f | 0x400), e - 25);
        return sign != 0 ? -x : x;
    }

    private static void packOne(Fmt fm, char ch, int size, Object v, byte[] buf, int pos) {
        boolean little = fm.little;
        switch (ch) {
            case 'c': {
                Object u = NativeSubclass.unwrap(v);
                if (!(u instanceof PyBytes b) || b.data.length != 1) throw err("char format requires a bytes object of length 1");
                buf[pos] = b.data[0];
                return;
            }
            case '?': buf[pos] = (byte) (PyRuntime.truth(v) ? 1 : 0); return;
            case 'e': {
                double d = getDouble(v);
                if (Math.abs(d) >= 65520.0 && PyRuntime.isIntLike(NativeSubclass.unwrap(v))) throw err("int too large to convert");
                putBits(buf, pos, 2, little, halfBits(d));
                return;
            }
            case 'f': {
                double d = getDouble(v);
                float f = (float) d;
                if (Float.isInfinite(f) && !Double.isInfinite(d)) throw new PyException("OverflowError", "float too large to pack with f format");
                putBits(buf, pos, 4, little, Float.floatToRawIntBits(f));
                return;
            }
            case 'd': putBits(buf, pos, 8, little, Double.doubleToRawLongBits(getDouble(v))); return;
            case 'F': case 'D': {
                double[] parts = getComplex(v);
                int half = size / 2;
                if (ch == 'F') {
                    float re = (float) parts[0], im = (float) parts[1];
                    if ((Float.isInfinite(re) && !Double.isInfinite(parts[0])) || (Float.isInfinite(im) && !Double.isInfinite(parts[1])))
                        throw new PyException("OverflowError", "float too large to pack with F format");
                    putBits(buf, pos, half, little, Float.floatToRawIntBits(re));
                    putBits(buf, pos + half, half, little, Float.floatToRawIntBits(im));
                } else {
                    putBits(buf, pos, half, little, Double.doubleToRawLongBits(parts[0]));
                    putBits(buf, pos + half, half, little, Double.doubleToRawLongBits(parts[1]));
                }
                return;
            }
            default: putBits(buf, pos, size, little, packInt(v, ch, size));
        }
    }

    private static double[] getComplex(Object v) {
        try {
            Object u = NativeSubclass.unwrap(v);
            if (u instanceof PyComplex c) return new double[]{c.real, c.imag};
            return new double[]{getDouble(v), 0.0};
        } catch (PyException e) { throw err("required argument is not a complex"); }
    }

    private static PyException countError(String fname, Fmt f, int got) {
        return err(fname + " expected " + f.items + " items for packing (got " + got + ")");
    }

    private static void packInto(Fmt f, List<Object> vals, int from, byte[] buf, int base, String fname) {
        int n = vals.size() - from;
        if (n != f.items) throw countError(fname, f, n);
        int at = from;
        for (Code c : f.codes) {
            int pos = base + (int) c.offset();
            switch (c.ch()) {
                case 'x': break;
                case 's': case 'p': {
                    Object v = NativeSubclass.unwrap(vals.get(at++));
                    if (!(v instanceof PyBytes || v instanceof PyByteArray)) throw err("argument for '" + c.ch() + "' must be a bytes object");
                    byte[] src = ((PyByteSequence) v).toByteArray();
                    int len = src.length;
                    if (c.ch() == 's') {
                        System.arraycopy(src, 0, buf, pos, Math.min(len, c.count()));
                    } else if (c.count() > 0) {
                        int room = c.count() - 1;
                        if (len > room) len = room;
                        buf[pos] = (byte) Math.min(len, 255);
                        System.arraycopy(src, 0, buf, pos + 1, len);
                    }
                    break;
                }
                default:
                    for (int i = 0; i < c.count(); i++) packOne(f, c.ch(), c.size(), vals.get(at++), buf, pos + i * c.size());
            }
        }
    }

    // ---- unpacking ---------------------------------------------------------------------------------------

    private static long getBits(byte[] buf, int pos, int size, boolean little) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            int b = buf[pos + (little ? size - 1 - i : i)] & 0xff;
            v = (v << 8) | b;
        }
        return v;
    }

    private static Object unpackOne(Fmt f, char ch, int size, byte[] buf, int pos) {
        boolean little = f.little;
        switch (ch) {
            case 'c': return new PyBytes(new byte[]{buf[pos]});
            case '?': return buf[pos] != 0;
            case 'e': return halfValue((int) getBits(buf, pos, 2, little));
            case 'f': return (double) Float.intBitsToFloat((int) getBits(buf, pos, 4, little));
            case 'd': return Double.longBitsToDouble(getBits(buf, pos, 8, little));
            case 'F': return new PyComplex((double) Float.intBitsToFloat((int) getBits(buf, pos, 4, little)), (double) Float.intBitsToFloat((int) getBits(buf, pos + 4, 4, little)));
            case 'D': return new PyComplex(Double.longBitsToDouble(getBits(buf, pos, 8, little)), Double.longBitsToDouble(getBits(buf, pos + 8, 8, little)));
            default: {
                long bits = getBits(buf, pos, size, little);
                boolean signed = "bhilqn".indexOf(ch) >= 0;
                if (signed) {
                    int shift = 64 - size * 8;
                    return (bits << shift) >> shift;
                }
                if (size == 8 && bits < 0) return PyRuntime.compact(new BigInteger(Long.toUnsignedString(bits)));
                return bits;
            }
        }
    }

    private static PyTuple unpackAt(Fmt f, byte[] buf, int base) {
        PyTuple out = new PyTuple();
        for (Code c : f.codes) {
            int pos = base + (int) c.offset();
            switch (c.ch()) {
                case 'x': break;
                case 's': {
                    byte[] s = new byte[c.count()];
                    System.arraycopy(buf, pos, s, 0, s.length);
                    out.items.add(new PyBytes(s));
                    break;
                }
                case 'p': {
                    int n = c.count();
                    int len = 0;
                    if (n > 0) { len = buf[pos] & 0xff; if (len >= n) len = n - 1; }
                    byte[] s = new byte[len];
                    if (len > 0) System.arraycopy(buf, pos + 1, s, 0, len);
                    out.items.add(new PyBytes(s));
                    break;
                }
                default:
                    for (int i = 0; i < c.count(); i++) out.items.add(unpackOne(f, c.ch(), c.size(), buf, pos + i * c.size()));
            }
        }
        return out;
    }

    // ---- shared buffer helpers ---------------------------------------------------------------------------

    private static long ssize(Object o) { return ssize(o, false); }

    private static long ssize(Object o, boolean indexError) {
        BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (b.bitLength() > 63) throw indexError && !LangVersion.atLeast(3, 14) ? new PyException("IndexError", "cannot fit 'int' into an index-sized integer")
            : new PyException("OverflowError", "Python int too large to convert to C ssize_t");
        return b.longValue();
    }

    private static Object[] writable(Object buffer, String fname, int argPos) {
        Object u = NativeSubclass.unwrap(buffer);
        if (u instanceof PyByteArray a) return new Object[]{a, a.byteSize()};
        if (u instanceof PyMemoryView mv && !mv.readonly()) return new Object[]{mv, mv.byteSize()};
        Object wb = ArrayModule.writableBuffer(u);
        if (wb != null) return new Object[]{wb, ((PyByteSequence) wb).byteSize()};
        String got = PyRuntime.typeName(buffer);
        throw new PyException("TypeError", LangVersion.atLeast(3, 14)
            ? fname + "() argument " + argPos + " must be read-write bytes-like object, not " + got
            : "argument must be read-write bytes-like object, not " + got);
    }

    private static void writeBack(Object target, int offset, byte[] src) {
        if (target instanceof PyByteArray a) { for (int i = 0; i < src.length; i++) a.data.set(offset + i, src[i]); }
        else if (target instanceof PyMemoryView mv) { for (int i = 0; i < src.length; i++) mv.setUnsigned(offset + i, src[i] & 0xff); }
        else ArrayModule.writeBytes(target, offset, src);
    }

    // ---- Struct objects ----------------------------------------------------------------------------------

    public static Object stNew(Object cls, Object args, Object kwargs) { return new PyInstance((PyClass) cls); }

    private static Fmt st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof Fmt f) return f;
        if (self instanceof PyInstance i && i.cls.mro.contains(structClass)) throw new PyException("RuntimeError", "Struct object is not initialized");
        throw new PyException("TypeError", "descriptor requires a '_struct.Struct' object but received '" + PyRuntime.typeName(self) + "'");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> items(Object args) { return ((PyTuple) args).items; }
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> kw(Object kwargs) { return (Map<Object, Object>) kwargs; }

    public static Object stInit(Object self, Object args, Object kwargs) {
        Object[] a = NativeArgs.parse("Struct", items(args), kw(kwargs), new String[]{"format"}, 1, 0);
        setState(self, compile(a[0]));
        return null;
    }

    public static Object stRepr(Object self) { return "Struct(" + PyRuntime.pyRepr(st(self).source) + ")"; }
    public static Object stSizeof(Object self) { Fmt f = st(self); return (long) (56 + 24 * f.codes.size()); }
    public static Object stFormat(Object self) { return st(self).source; }
    public static Object stSize(Object self) { return st(self).size; }

    public static Object stPack(Object self, Object args, Object kwargs) {
        Fmt f = st(self);
        if (!kw(kwargs).isEmpty()) throw new PyException("TypeError", "Struct.pack() takes no keyword arguments");
        return doPack(f, items(args), 0);
    }

    private static Object doPack(Fmt f, List<Object> vals, int from) {
        byte[] buf = new byte[checkedSize(f)];
        packInto(f, vals, from, buf, 0, "pack");
        return new PyBytes(buf);
    }

    public static Object stUnpack(Object self, Object args, Object kwargs) {
        Fmt f = st(self);
        if (!kw(kwargs).isEmpty()) throw new PyException("TypeError", "Struct.unpack() takes no keyword arguments");
        if (items(args).size() != 1) throw new PyException("TypeError", "Struct.unpack() takes exactly one argument (" + items(args).size() + " given)");
        return doUnpack(f, items(args).get(0));
    }

    private static Object doUnpack(Fmt f, Object buffer) {
        byte[] data = IoUtil.bytesLike(buffer);
        if (data.length != f.size) throw err("unpack requires a buffer of " + f.size + " bytes");
        return unpackAt(f, data, 0);
    }

    public static Object stUnpackFrom(Object self, Object args, Object kwargs) {
        Fmt f = st(self);
        Object[] a = NativeArgs.parse("unpack_from", items(args), kw(kwargs), new String[]{"buffer", "offset"}, 1, 0);
        return doUnpackFrom(f, a[0], a[1]);
    }

    private static Object doUnpackFrom(Fmt f, Object buffer, Object offsetObj) {
        long offset = offsetObj == NativeArgs.MISSING ? 0 : ssize(offsetObj);
        byte[] data = IoUtil.bytesLike(buffer);
        if (offset < 0) {
            if (offset + f.size > 0) throw err("not enough data to unpack " + f.size + " bytes at offset " + offset);
            if (offset + data.length < 0) throw err("offset " + offset + " out of range for " + data.length + "-byte buffer");
            offset += data.length;
        }
        if (data.length - offset < f.size)
            throw err("unpack_from requires a buffer of at least " + (f.size + offset) + " bytes for unpacking " + f.size + " bytes at offset " + offset + " (actual buffer size is " + data.length + ")");
        return unpackAt(f, data, (int) offset);
    }

    public static Object stPackInto(Object self, Object args, Object kwargs) {
        Fmt f = st(self);
        if (!kw(kwargs).isEmpty()) throw new PyException("TypeError", "Struct.pack_into() takes no keyword arguments");
        List<Object> a = items(args);
        if (a.size() < 2) throw LangVersion.atLeast(3, 14) ? new PyException("TypeError", "pack_into expected at least 2 arguments, got " + a.size())
            : err(a.isEmpty() ? "pack_into expected buffer argument" : "pack_into expected offset argument");
        return doPackInto(f, a, 0);
    }

    private static Object doPackInto(Fmt f, List<Object> a, int first) {
        Object[] w = writable(a.get(first), "pack_into", first + 1);
        int len = ((PyByteSequence) w[0]).byteSize();
        long offset = ssize(a.get(first + 1), true);
        if (offset < 0) {
            if (offset + f.size > 0) throw err("no space to pack " + f.size + " bytes at offset " + offset);
            if (offset + len < 0) throw err("offset " + offset + " out of range for " + len + "-byte buffer");
            offset += len;
        }
        if (len - offset < f.size)
            throw err("pack_into requires a buffer of at least " + (f.size + offset) + " bytes for packing " + f.size + " bytes at offset " + offset + " (actual buffer size is " + len + ")");
        byte[] tmp = new byte[checkedSize(f)];
        packInto(f, a, first + 2, tmp, 0, "pack_into");
        writeBack(w[0], (int) offset, tmp);
        return null;
    }

    public static Object stIterUnpack(Object self, Object args, Object kwargs) {
        Fmt f = st(self);
        if (!kw(kwargs).isEmpty()) throw new PyException("TypeError", "Struct.iter_unpack() takes no keyword arguments");
        if (items(args).size() != 1) throw new PyException("TypeError", "Struct.iter_unpack() takes exactly one argument (" + items(args).size() + " given)");
        return makeIter(f, items(args).get(0));
    }

    private static Object makeIter(Fmt f, Object buffer) {
        if (f.size == 0) throw err("cannot iteratively unpack with a struct of length 0");
        byte[] data = IoUtil.bytesLike(buffer);
        if (data.length % f.size != 0) throw err("iterative unpacking requires a buffer of a multiple of " + f.size + " bytes");
        int[] at = {0};
        return new PyLazyIterator() {
            @Override String pyTypeName() { return "_struct.unpack_iterator"; }
            @Override Object compute() {
                if (at[0] >= data.length) throw new NoSuchElementException();
                Object r = unpackAt(f, data, at[0]);
                at[0] += (int) f.size;
                return r;
            }
        };
    }

    // ---- module functions --------------------------------------------------------------------------------

    private static Object calcsize(List<Object> a, Map<Object, Object> k) {
        return compile(NativeArgs.one("_struct.calcsize", a, k)).size;
    }

    private static Object pack(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_struct.pack() takes no keyword arguments");
        if (a.isEmpty()) throw new PyException("TypeError", LangVersion.atLeast(3, 14) ? "pack expected at least 1 argument, got 0" : "missing format argument");
        return doPack(compile(a.get(0)), a, 1);
    }

    private static Object unpack(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_struct.unpack() takes no keyword arguments");
        if (a.size() != 2) throw new PyException("TypeError", "unpack expected 2 arguments, got " + a.size());
        return doUnpack(compile(a.get(0)), a.get(1));
    }

    private static Object unpackFrom(List<Object> a, Map<Object, Object> k) {
        Object[] p = NativeArgs.parse("unpack_from", a, k, new String[]{"format", "buffer", "offset"}, 2, 1);
        return doUnpackFrom(compile(p[0]), p[1], p[2]);
    }

    private static Object iterUnpack(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_struct.iter_unpack() takes no keyword arguments");
        if (a.size() != 2) throw new PyException("TypeError", "iter_unpack expected 2 arguments, got " + a.size());
        return makeIter(compile(a.get(0)), a.get(1));
    }

    private static Object packInto(List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "_struct.pack_into() takes no keyword arguments");
        if (a.size() < 3) {
            if (LangVersion.atLeast(3, 14)) throw new PyException("TypeError", "pack_into expected at least 3 arguments, got " + a.size());
            if (a.isEmpty()) throw new PyException("TypeError", "missing format argument");
            throw err(a.size() < 2 ? "pack_into expected buffer argument" : "pack_into expected offset argument");
        }
        return doPackInto(compile(a.get(0)), a, 1);
    }
}
