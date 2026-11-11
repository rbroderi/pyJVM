// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code zlib} module on top of {@code java.util.zip}: checksums, one-shot {@code compress}/{@code decompress},
 * and the streaming {@code Compress}/{@code Decompress}/{@code _ZlibDecompressor} objects (see {@link ZlibStreams}).
 * Not available from the JDK: the Z_RLE/Z_FIXED strategies, Z_BLOCK/Z_TREES flushes and a configurable window size
 * (output always uses a 32 KiB window).
 */
final class ZlibModule {
    private ZlibModule() {}

    private static final String ME = ZlibModule.class.getName();
    static PyClass errorClass, compClass, decompClass, zdecClass;

    static PyException error(String msg) { return PyRuntime.userException(PyRuntime.instantiate(errorClass, new Object[]{msg})); }

    static synchronized PyModule create() {
        PyModule m = new PyModule("zlib", ME);
        errorClass = (PyClass) PyRuntime.classCreate("error", "zlib");
        PyRuntime.classAddBase(errorClass, PyRuntime.builtinType("Exception"));
        finish(errorClass);
        m.attrs.put("error", errorClass);
        String[] names = {"DEFLATED", "DEF_BUF_SIZE", "DEF_MEM_LEVEL", "MAX_WBITS", "Z_BEST_COMPRESSION", "Z_BEST_SPEED", "Z_BLOCK",
            "Z_DEFAULT_COMPRESSION", "Z_DEFAULT_STRATEGY", "Z_FILTERED", "Z_FINISH", "Z_FIXED", "Z_FULL_FLUSH", "Z_HUFFMAN_ONLY",
            "Z_NO_COMPRESSION", "Z_NO_FLUSH", "Z_PARTIAL_FLUSH", "Z_RLE", "Z_SYNC_FLUSH", "Z_TREES"};
        long[] vals = {8, 16384, 8, 15, 9, 1, 5, -1, 0, 1, 4, 4, 3, 2, 0, 0, 1, 3, 2, 6};
        for (int i = 0; i < names.length; i++) m.attrs.put(names[i], vals[i]);
        m.attrs.put("ZLIB_VERSION", "1.3.1");
        m.attrs.put("ZLIB_RUNTIME_VERSION", "1.3.1");
        if (!LangVersion.atLeast(3, 15)) m.attrs.put("__version__", "1.0");
        fn(m, "crc32", (a, k) -> checksum("crc32", a, k, 0));
        fn(m, "adler32", (a, k) -> checksum("adler32", a, k, 1));
        if (LangVersion.atLeast(3, 15)) {
            fn(m, "crc32_combine", (a, k) -> combine("crc32_combine", a, k, true));
            fn(m, "adler32_combine", (a, k) -> combine("adler32_combine", a, k, false));
        }
        fn(m, "compress", ZlibModule::compressFn);
        fn(m, "decompress", ZlibModule::decompressFn);
        fn(m, "compressobj", ZlibModule::compressobj);
        fn(m, "decompressobj", ZlibModule::decompressobj);
        defineClasses(m);
        return m;
    }

    private static void defineClasses(PyModule m) {
        Class<?> c = ZlibModule.class;
        compClass = define("zlib", "Compress", null);
        method(compClass, c, "compress", "cCompress", "self,data");
        method(compClass, c, "flush", "cFlush", "self,mode", defaults("mode", 4L));
        method(compClass, c, "copy", "cCopy", "self");
        method(compClass, c, "__copy__", "cCopy", "self");
        method(compClass, c, "__deepcopy__", "cDeepCopy", "self,memo");
        finish(compClass);
        decompClass = define("zlib", "Decompress", null);
        method(decompClass, c, "decompress", "dDecompress", "self,data,max_length", defaults("max_length", 0L));
        method(decompClass, c, "flush", "dFlush", "self,length", defaults("length", 16384L));
        method(decompClass, c, "copy", "dCopy", "self");
        method(decompClass, c, "__copy__", "dCopy", "self");
        method(decompClass, c, "__deepcopy__", "dDeepCopy", "self,memo");
        PyRuntime.classAddProperty(decompClass, "unused_data", ME, "dUnused", null);
        PyRuntime.classAddProperty(decompClass, "unconsumed_tail", ME, "dTail", null);
        PyRuntime.classAddProperty(decompClass, "eof", ME, "dEof", null);
        finish(decompClass);
        zdecClass = define("zlib", "_ZlibDecompressor", null);
        PyRuntime.classAddMethodEx(zdecClass, "__new__", c, "zdNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(zdecClass, "__new__", zdecClass.methods.get("__new__").function);
        methodVarKw(zdecClass, c, "__init__", "zdInit", "self", "args", "kwargs", new PyDict());
        method(zdecClass, c, "decompress", "zdDecompress", "self,data,max_length", defaults("max_length", -1L));
        PyRuntime.classAddProperty(zdecClass, "unused_data", ME, "zdUnused", null);
        PyRuntime.classAddProperty(zdecClass, "eof", ME, "zdEof", null);
        PyRuntime.classAddProperty(zdecClass, "needs_input", ME, "zdNeeds", null);
        finish(zdecClass);
        m.attrs.put("Compress", compClass);
        m.attrs.put("Decompress", decompClass);
        m.attrs.put("_ZlibDecompressor", zdecClass);
    }

    // ---------------------------------------------------------------- checksums

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
            CRC_TABLE[n] = c;
        }
    }

    static int crc32(int crc, byte[] b, int off, int len) {
        if (crc == 0) {
            java.util.zip.CRC32 j = new java.util.zip.CRC32();
            j.update(b, off, len);
            return (int) j.getValue();
        }
        int c = ~crc;
        for (int i = off; i < off + len; i++) c = CRC_TABLE[(c ^ b[i]) & 0xff] ^ (c >>> 8);
        return ~c;
    }

    static int adler32(int adler, byte[] b, int off, int len) {
        long a = adler & 0xffff, s = (adler >>> 16) & 0xffff;
        int i = off, end = off + len;
        while (i < end) {
            int stop = Math.min(end, i + 5552);
            for (; i < stop; i++) { a += b[i] & 0xff; s += a; }
            a %= 65521; s %= 65521;
        }
        return (int) ((s << 16) | a);
    }

    /** An unsigned 32-bit checksum argument: any int is reduced modulo 2**32 (3.15 warns when it was out of range). */
    private static int u32(Object v) {
        BigInteger b = BuiltinsExtra.rangeIndex(v);
        if (LangVersion.atLeast(3, 15) && (b.signum() < 0 || b.bitLength() > 32))
            WarningsModule.warn(List.of("integer value out of range", PyRuntime.builtinType("DeprecationWarning"), 2L), new LinkedHashMap<>());
        return b.intValue();
    }

    private static Object checksum(String fname, List<Object> args, Map<Object, Object> kw, int initial) {
        Object[] p = NativeArgs.parse(fname, args, kw, new String[]{"data", "value"}, 1, 2);
        byte[] data = IoUtil.bytesLike(p[0]);
        int seed = p[1] == NativeArgs.MISSING ? initial : u32(p[1]);
        int r = initial == 0 ? crc32(seed, data, 0, data.length) : adler32(seed, data, 0, data.length);
        return (long) r & 0xffffffffL;
    }

    private static Object combine(String fname, List<Object> args, Map<Object, Object> kw, boolean crc) {
        Object[] p = NativeArgs.parse(fname, args, kw, new String[]{"a", "b", "len"}, 3, 3);
        long a = u32(p[0]) & 0xffffffffL, b = u32(p[1]) & 0xffffffffL;
        BigInteger len = BuiltinsExtra.rangeIndex(p[2]);
        long n = len.bitLength() > 62 ? (len.signum() < 0 ? -1 : Long.MAX_VALUE) : len.longValue();
        return crc ? crcCombine(a, b, n) : adlerCombine(a, b, n);
    }

    private static long gf2Times(long[] mat, long vec) {
        long sum = 0;
        for (int i = 0; vec != 0; i++, vec >>= 1) if ((vec & 1) != 0) sum ^= mat[i];
        return sum;
    }

    private static void gf2Square(long[] sq, long[] mat) { for (int n = 0; n < 32; n++) sq[n] = gf2Times(mat, mat[n]); }

    static long crcCombine(long crc1, long crc2, long len2) {
        if (len2 < 0) return 0;
        if (len2 == 0) return crc1 ^ crc2;
        long[] even = new long[32], odd = new long[32];
        odd[0] = 0xedb88320L;
        long row = 1;
        for (int n = 1; n < 32; n++) { odd[n] = row; row <<= 1; }
        gf2Square(even, odd);
        gf2Square(odd, even);
        do {
            gf2Square(even, odd);
            if ((len2 & 1) != 0) crc1 = gf2Times(even, crc1);
            len2 >>= 1;
            if (len2 == 0) break;
            gf2Square(odd, even);
            if ((len2 & 1) != 0) crc1 = gf2Times(odd, crc1);
            len2 >>= 1;
        } while (len2 != 0);
        return crc1 ^ crc2;
    }

    static long adlerCombine(long adler1, long adler2, long len2) {
        final long base = 65521;
        long rem = len2 % base;
        long sum1 = adler1 & 0xffff;
        long sum2 = (rem * sum1) % base;
        sum1 += (adler2 & 0xffff) + base - 1;
        sum2 += ((adler1 >> 16) & 0xffff) + ((adler2 >> 16) & 0xffff) + base - rem;
        if (sum1 >= base) sum1 -= base;
        if (sum1 >= base) sum1 -= base;
        if (sum2 >= (base << 1)) sum2 -= (base << 1);
        if (sum2 >= base) sum2 -= base;
        return sum1 | (sum2 << 16);
    }

    // ---------------------------------------------------------------- argument handling

    private static int intArg(Object o) { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, IoUtil.asLong(o))); }

    /** zlib's deflateInit2 window rules: 8..15 zlib, -9..-15 raw, 25..31 gzip. Returns the wrapper mode or -1. */
    private static int compressMode(int wbits) {
        if (wbits >= 8 && wbits <= 15) return 0;
        if (wbits >= -15 && wbits <= -9) return 1;
        if (wbits >= 25 && wbits <= 31) return 2;
        return -1;
    }

    private static boolean validDecompressWbits(int wbits) {
        if (wbits < 0) return wbits >= -15 && wbits <= -8;
        if (wbits >= 48) return false;
        int w = wbits & 15;
        return w == 0 || w >= 8;
    }

    private static byte[] dictArg(Object zdict) {
        if (zdict == NativeArgs.MISSING || zdict == null) return null;
        byte[] b;
        try { b = IoUtil.bytesLike(zdict); }
        catch (PyException e) { throw new PyException("TypeError", "zdict argument must support the buffer protocol"); }
        return b.length == 0 ? null : b;
    }

    private static PyException negative(String what) {
        return new PyException("ValueError", what + (LangVersion.atLeast(3, 15) ? " cannot be negative" : " must be non-negative"));
    }

    // ---------------------------------------------------------------- one-shot functions

    private static Object compressFn(List<Object> args, Map<Object, Object> kw) {
        Object[] p = NativeArgs.parse("compress", args, kw, new String[]{"data", "level", "wbits"}, 1, 1);
        byte[] data = IoUtil.bytesLike(p[0]);
        int level = p[1] == NativeArgs.MISSING ? -1 : intArg(p[1]);
        int wbits = p[2] == NativeArgs.MISSING ? 15 : intArg(p[2]);
        int mode = compressMode(wbits);
        if (level < -1 || level > 9 || mode < 0) throw error("Bad compression level");
        ZlibStreams.Comp c = new ZlibStreams.Comp(mode, level, 0, null);
        return new PyBytes(ZlibStreams.cat(c.compress(data), c.flush(4)));
    }

    private static Object decompressFn(List<Object> args, Map<Object, Object> kw) {
        Object[] p = NativeArgs.parse("decompress", args, kw, new String[]{"data", "wbits", "bufsize"}, 1, 1);
        byte[] data = IoUtil.bytesLike(p[0]);
        int wbits = p[1] == NativeArgs.MISSING ? 15 : intArg(p[1]);
        if (p[2] != NativeArgs.MISSING && IoUtil.asLong(p[2]) < 0) throw negative("bufsize");
        if (!validDecompressWbits(wbits)) throw ZlibStreams.zerr(-2, "inconsistent stream state", "preparing to decompress data");
        ZlibStreams.Decomp d = new ZlibStreams.Decomp(wbits, null);
        byte[] out = d.run(data, -1, "decompressing data");
        if (!d.eof) throw ZlibStreams.zerr(-5, "incomplete or truncated stream", "decompressing data");
        return new PyBytes(out);
    }

    // ---------------------------------------------------------------- compressobj / decompressobj

    private static Object compressobj(List<Object> args, Map<Object, Object> kw) {
        Object[] p = NativeArgs.parse("compressobj", args, kw, new String[]{"level", "method", "wbits", "memLevel", "strategy", "zdict"}, 0, 0);
        int level = p[0] == NativeArgs.MISSING ? -1 : intArg(p[0]);
        int method = p[1] == NativeArgs.MISSING ? 8 : intArg(p[1]);
        int wbits = p[2] == NativeArgs.MISSING ? 15 : intArg(p[2]);
        int memLevel = p[3] == NativeArgs.MISSING ? 8 : intArg(p[3]);
        int strategy = p[4] == NativeArgs.MISSING ? 0 : intArg(p[4]);
        byte[] zdict = dictArg(p[5]);
        int mode = compressMode(wbits);
        if (level < -1 || level > 9 || method != 8 || mode < 0 || memLevel < 1 || memLevel > 9 || strategy < 0 || strategy > 4)
            throw new PyException("ValueError", "Invalid initialization option");
        if (strategy > 2) throw error("compression strategies Z_RLE and Z_FIXED are not supported by the JVM zlib");
        return newInstance(compClass, new ZlibStreams.Comp(mode, level, strategy, zdict));
    }

    private static Object decompressobj(List<Object> args, Map<Object, Object> kw) {
        Object[] p = NativeArgs.parse("decompressobj", args, kw, new String[]{"wbits", "zdict"}, 0, 0);
        int wbits = p[0] == NativeArgs.MISSING ? 15 : intArg(p[0]);
        if (!validDecompressWbits(wbits)) throw new PyException("ValueError", "Invalid initialization option");
        return newInstance(decompClass, new ZlibStreams.Decomp(wbits, dictArg(p[1])));
    }

    private static ZlibStreams.Comp comp(Object self) { return state(self, ZlibStreams.Comp.class, "zlib.Compress"); }
    private static ZlibStreams.Decomp dec(Object self) { return state(self, ZlibStreams.Decomp.class, "zlib.Decompress"); }

    public static Object cCompress(Object self, Object data) { return new PyBytes(comp(self).compress(IoUtil.bytesLike(data))); }
    public static Object cFlush(Object self, Object mode) { return new PyBytes(comp(self).flush(intArg(mode))); }
    public static Object cCopy(Object self) { return newInstance(compClass, comp(self).copy()); }
    public static Object cDeepCopy(Object self, Object memo) { return cCopy(self); }

    public static Object dDecompress(Object self, Object data, Object maxLength) {
        ZlibStreams.Decomp d = dec(self);
        byte[] in = IoUtil.bytesLike(data);
        long max = IoUtil.asLong(maxLength);
        if (max < 0) throw negative("max_length");
        return new PyBytes(d.run(in, max == 0 ? -1 : (int) Math.min(max, Integer.MAX_VALUE), "decompressing data"));
    }

    public static Object dFlush(Object self, Object length) {
        ZlibStreams.Decomp d = dec(self);
        if (IoUtil.asLong(length) <= 0) throw new PyException("ValueError", "length must be greater than zero");
        return new PyBytes(d.flush());
    }

    public static Object dCopy(Object self) { return newInstance(decompClass, dec(self).copy()); }
    public static Object dDeepCopy(Object self, Object memo) { return dCopy(self); }
    public static Object dUnused(Object self) { return new PyBytes(dec(self).unused); }
    public static Object dTail(Object self) { return new PyBytes(dec(self).tail); }
    public static Object dEof(Object self) { return dec(self).eof; }

    // ---------------------------------------------------------------- _ZlibDecompressor

    private static final class Zd {
        ZlibStreams.Decomp d;
        byte[] pending = ZlibStreams.EMPTY;
        boolean needsInput = true;
    }

    public static Object zdNew(Object cls, Object args, Object kwargs) { return newInstance((PyClass) cls, new Zd()); }

    @SuppressWarnings("unchecked")
    public static Object zdInit(Object self, Object args, Object kwargs) {
        Object[] p = NativeArgs.parse("_ZlibDecompressor", ((PyTuple) args).items, (Map<Object, Object>) kwargs, new String[]{"wbits", "zdict"}, 0, 0);
        int wbits = p[0] == NativeArgs.MISSING ? 15 : intArg(p[0]);
        if (!validDecompressWbits(wbits)) throw new PyException("ValueError", "Invalid initialization option");
        zd(self).d = new ZlibStreams.Decomp(wbits, dictArg(p[1]));
        return null;
    }

    private static Zd zd(Object self) {
        Zd z = state(self, Zd.class, "zlib._ZlibDecompressor");
        return z;
    }

    private static Zd ready(Object self) {
        Zd z = zd(self);
        if (z.d == null) throw new PyException("RuntimeError", "_ZlibDecompressor object is not initialized");
        return z;
    }

    public static Object zdDecompress(Object self, Object data, Object maxLength) {
        Zd z = ready(self);
        if (z.d.eof) throw new PyException("EOFError", "End of stream already reached");
        byte[] in = ZlibStreams.cat(z.pending, IoUtil.bytesLike(data));
        long max = IoUtil.asLong(maxLength);
        z.pending = ZlibStreams.EMPTY;
        byte[] out = z.d.run(in, max < 0 ? -1 : (int) Math.min(max, Integer.MAX_VALUE), "decompressing data");
        if (z.d.eof) z.needsInput = false;
        else if (z.d.limitHit) { z.pending = z.d.tail; z.needsInput = false; }
        else z.needsInput = true;
        return new PyBytes(out);
    }

    public static Object zdUnused(Object self) { return new PyBytes(ready(self).d.unused); }
    public static Object zdEof(Object self) { return ready(self).d.eof; }
    public static Object zdNeeds(Object self) { return ready(self).needsInput; }
}
