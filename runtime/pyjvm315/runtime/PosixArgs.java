// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.math.BigInteger;

/**
 * Argument converters shared by the N2 POSIX modules ({@code fcntl}, {@code termios}; buffer helpers are added by the
 * ticket that needs them). No FFM here. {@link #fd} is CPython's {@code PyObject_AsFileDescriptor}; its texts are
 * measured on the three lanes (n2_plan 1.3, 3.8). The {@code os} functions of the terminal ticket take a plain C int
 * ({@code PosixOsProc.cInt}) instead.
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
        if (isInt(u)) return checkFd(u);
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

    private static boolean isInt(Object u) { return u instanceof Long || u instanceof BigInteger || u instanceof Boolean; }

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
