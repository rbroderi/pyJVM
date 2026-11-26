// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.math.BigInteger;

/**
 * Argument converters shared by the N2 POSIX modules ({@code fcntl}, {@code termios}; buffer helpers are added by the
 * ticket that needs them: the third argument of {@code fcntl}/{@code ioctl} below). No FFM here. {@link #fd} is
 * CPython's {@code PyObject_AsFileDescriptor}; its texts are measured on the three lanes (n2_plan 1.3, 3.8). Of the
 * {@code os} terminal functions, {@code grantpt}, {@code unlockpt} and {@code ptsname} use {@link #fd} too (CPython
 * converts them with it); {@code ttyname} and {@code posix_openpt} take a plain C int ({@code PosixOsProc.cInt}).
 */
final class PosixArgs {
    private PosixArgs() {}

    private static final Object MISSING = new Object();

    /**
     * A descriptor argument: an int (bool allowed), else the result of {@code fileno()}. No {@code fileno} attribute:
     * {@code TypeError: argument must be an int, or have a fileno() method.}; a {@code fileno()} result that is not an
     * int: the lane's text (3.15 names the object's class); a value beyond a C int: OverflowError; negative: ValueError.
     */
    static int fd(Object o) {
        Object u = NativeSubclass.unwrap(o);
        if (isInt(u)) {
            if (u instanceof Boolean) warnBoolFd();   // PyObject_AsFileDescriptor warns for a bool argument (not for a bool returned by fileno())
            return checkFd(u);
        }
        Object method = PyRuntime.getattrDefault(o, "fileno", MISSING);   // looked up once, the fetched bound method is called
        if (method == MISSING) throw new PyException("TypeError", "argument must be an int, or have a fileno() method.");
        Object r = NativeSubclass.unwrap(PyRuntime.callFunction(method, new java.util.ArrayList<Object>(), new java.util.LinkedHashMap<Object, Object>()));
        if (!isInt(r)) {
            throw new PyException("TypeError", LangVersion.atLeast(3, 15)
                ? PyRuntime.typeName(o) + ".fileno() must return an int, not " + PyRuntime.typeName(r)
                : "fileno() returned a non-integer");
        }
        return checkFd(r);
    }

    /** {@code RuntimeWarning: bool is used as a file descriptor}, through the Python {@code warnings} module; stacklevel 1 is the Python frame that called the native function (measured against CPython's location). */
    private static void warnBoolFd() {
        PyRuntime.callMethod(ImportSupport.importBuiltin(new java.util.ArrayList<Object>(java.util.List.of("warnings")), new java.util.LinkedHashMap<Object, Object>()), "warn",
            new Object[]{"bool is used as a file descriptor", PyRuntime.builtinType("RuntimeWarning"), 1L});
    }

    private static boolean isInt(Object u) { return u instanceof Long || u instanceof BigInteger || u instanceof Boolean; }

    // ---- the third argument of fcntl.fcntl / fcntl.ioctl (n2_plan 1.4, 3.8) --------------------------------------

    /**
     * CPython's {@code PyIndex_Check}: an int (bool included) or an object whose class defines {@code __index__}. The
     * class is asked before any unwrapping, so a bytes/bytearray/str subclass with {@code __index__} counts (measured:
     * on 3.14+ {@code fcntl} then takes the integer form; 3.13 tries the buffer first, which its caller handles).
     */
    static boolean isIndex(Object o) {
        if (o instanceof PyInstance w && w.cls.lookupMethod("__index__") != null) return true;
        Object u = NativeSubclass.indexValue(NativeSubclass.unwrap(o));
        return isInt(u) || u instanceof PyInstance i && i.cls.lookupMethod("__index__") != null;
    }

    /**
     * The {@code I} format: any integer ({@code __index__} accepted, else "'X' object cannot be interpreted as an integer")
     * masked to its low 32 bits, no OverflowError. 3.15 warns {@code DeprecationWarning: integer value out of range} for a
     * value outside {@code [INT_MIN, UINT_MAX]} (measured: 2**31 and -1 are silent, 2**32 and 2**64 warn).
     */
    static int uintMask(Object o) {
        BigInteger v = BuiltinsExtra.rangeIndex(o);
        if (LangVersion.atLeast(3, 15) && (v.compareTo(INT_MIN) < 0 || v.compareTo(UINT_MAX) > 0)) warnOutOfRange();
        return v.intValue();
    }

    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE), UINT_MAX = BigInteger.valueOf(0xffffffffL);

    /** Raised through the Python {@code warnings} module (the technique of {@code PosixOsProc.openMode}), so {@code catch_warnings} sees it. */
    static void warnOutOfRange() {
        PyRuntime.callMethod(ImportSupport.importBuiltin(new java.util.ArrayList<Object>(java.util.List.of("warnings")), new java.util.LinkedHashMap<Object, Object>()), "warn",
            new Object[]{"integer value out of range", PyRuntime.builtinType("DeprecationWarning"), 2L});
    }

    /**
     * A read-only buffer argument as bytes, or null when {@code o} is not one on this lane. 3.13 ({@code s#}): a str
     * (UTF-8; one that cannot be encoded is "not a buffer", so the caller's integer conversion reports it) or bytes;
     * bytearray, memoryview and array are not accepted. 3.14+ ({@code s*}): a str (UTF-8, an encoding error propagates)
     * or any bytes-like object (a non-contiguous memoryview is a BufferError). The array returned may be the object's
     * own storage: callers copy it and never write to it.
     */
    static byte[] readBuffer(Object o) {
        Object u = NativeSubclass.unwrap(o);
        boolean v314 = LangVersion.atLeast(3, 14);
        if (u instanceof String s) {
            if (v314) return Codecs.encode(s, "utf-8", "strict");
            try { return Codecs.encode(s, "utf-8", "strict"); }
            catch (PyException e) { if ("UnicodeEncodeError".equals(e.typeName)) return null; throw e; }
        }
        if (!v314) return u instanceof PyBytes b ? b.data : null;
        if (u instanceof PyByteSequence || ArrayModule.asArray(u) != null) return IoUtil.bytesLike(u);
        return null;
    }

    /**
     * The length limit of a read-only buffer argument of {@code fcntl}/{@code ioctl} ({@code func}): 1024 bytes on 3.13
     * ({@code ValueError: fcntl string arg too long}) and 3.14 ({@code ValueError: fcntl argument 3 is too long}); none on
     * 3.15. The native buffer is never smaller than 1024 bytes in any case (the kernel may write a whole struct).
     */
    static void checkBufferLength(int len, String func) {
        if (len <= 1024 || LangVersion.atLeast(3, 15)) return;
        throw new PyException("ValueError", LangVersion.atLeast(3, 14) ? func + " argument 3 is too long" : func + " string arg too long");
    }

    /**
     * 3.14+: {@code TypeError: <func>() argument 3 must be an integer, a bytes-like object, or a string, not <type>}, the
     * plain type name (measured: a class {@code U} defined in {@code __main__} is {@code not U}).
     */
    static PyException badArg3(String func, Object o) {
        return new PyException("TypeError", func + "() argument 3 must be an integer, a bytes-like object, or a string, not " + PyRuntime.typeName(o));
    }

    private static int checkFd(Object u) {
        long v;
        if (u instanceof Boolean b) v = b ? 1 : 0;
        else if (u instanceof Long l) v = l;
        else {
            BigInteger b = (BigInteger) u;
            if (b.bitLength() > 31) throw new PyException("OverflowError", "Python int too large to convert to C int");
            v = b.longValue();
        }
        if (v != (int) v) throw new PyException("OverflowError", "Python int too large to convert to C int");
        if (v < 0) throw new PyException("ValueError", "file descriptor cannot be a negative integer (" + v + ")");
        return (int) v;
    }
}
