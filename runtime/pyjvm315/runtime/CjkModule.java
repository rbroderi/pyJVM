// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * {@code _cjkcore}: the private native half of {@code _multibytecodec} (the Python half is cjk/_multibytecodec.py).
 * {@code encode}/{@code decode} run a {@link CjkCodec} over a whole buffer applying the error handler exactly as
 * CPython's multibytecodec.c does, and report how much input was consumed (the rest is the pending buffer) and the
 * codec state as the packed {@code state.c[8]} integer.
 */
final class CjkModule {
    private CjkModule() {}

    static PyModule create() {
        PyModule m = new PyModule("_cjkcore", CjkModule.class.getName());
        fn(m, "has", (a, k) -> CjkCodec.forName((String) a.get(0)) != null);
        fn(m, "decode", (a, k) -> decode((String) a.get(0), a.get(1), a.get(2), PyRuntime.truth(a.get(3)), state(a.get(4))));
        fn(m, "encode", (a, k) -> encode((String) a.get(0), (String) a.get(1), a.get(2), PyRuntime.truth(a.get(3)), state(a.get(4))));
        return m;
    }

    private static long state(Object o) { return o instanceof BigInteger b ? b.longValue() : ((Number) o).longValue(); }

    private static String errorName(Object o) { return o == null ? "strict" : (String) o; }

    private static Object call(String handler, PyExceptionValue exc) {
        return PyRuntime.callFunction(CodecErrors.lookup(handler), new ArrayList<Object>(List.of(exc)), new LinkedHashMap<Object, Object>());
    }

    /** Validates the (replacement, newpos) result of a handler and returns newpos made absolute. */
    private static int newPos(PyTuple t, int len) {
        long p = t.items.get(1) instanceof Long l ? l : t.items.get(1) instanceof BigInteger b ? b.longValue() : 0;
        if (p < 0) p += len;
        if (p < 0 || p > len) throw new PyException("IndexError", "position " + p + " from error handler out of bounds");
        return (int) p;
    }

    private static boolean isIntTuple(Object r, String what) {
        return r instanceof PyTuple t && t.items.size() == 2 && (t.items.get(0) instanceof String || what.equals("e") && t.items.get(0) instanceof PyBytes)
            && (t.items.get(1) instanceof Long || t.items.get(1) instanceof BigInteger);
    }

    static Object decode(String name, Object data, Object errorsObj, boolean fin, long st0) {
        CjkCodec c = CjkCodec.forName(name);
        byte[] d = data instanceof PyByteSequence b ? b.toByteArray() : ((PyBytes) data).toByteArray();
        String errors = errorName(errorsObj);
        long[] st = {st0};
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            CjkCodec.Res r = c.decode(d, pos, fin, st, out);
            if (r.reason == null) return tuple(out.toString(), (long) r.pos, st[0]);
            switch (errors) {
                case "strict" -> throw CodecErrors.decodeError(name, d, r.errStart, r.errEnd, r.reason);
                case "ignore" -> pos = r.errEnd;
                case "replace" -> { out.append('�'); pos = r.errEnd; }
                default -> {
                    Object res = call(errors, CodecErrors.decodeValue(name, d, r.errStart, r.errEnd, r.reason));
                    if (!isIntTuple(res, "d")) throw new PyException("TypeError", "decoding error handler must return (str, int) tuple");
                    PyTuple t = (PyTuple) res;
                    int np = newPos(t, d.length);
                    out.append((String) t.items.get(0));
                    pos = np;
                }
            }
        }
    }

    static Object encode(String name, String text, Object errorsObj, boolean fin, long st0) {
        CjkCodec c = CjkCodec.forName(name);
        int[] cps = text.codePoints().toArray();
        String errors = errorName(errorsObj);
        long[] st = {st0};
        CjkCodec.Bytes out = new CjkCodec.Bytes();
        int pos = 0;
        while (true) {
            CjkCodec.Res r = c.encode(cps, pos, fin, st, out);
            if (r.reason == null) return tuple(new PyBytes(out.toArray()), (long) r.pos, st[0]);
            switch (errors) {
                case "strict" -> throw CodecErrors.raise(CodecErrors.encodeValue(name, text, r.errStart, r.errEnd, r.reason));
                case "ignore" -> pos = r.errEnd;
                case "replace" -> { replacement(c, name, "?", out, st); pos = r.errEnd; }
                default -> {
                    Object res = call(errors, CodecErrors.encodeValue(name, text, r.errStart, r.errEnd, r.reason));
                    if (!isIntTuple(res, "e")) throw new PyException("TypeError", "encoding error handler must return (str, int) tuple");
                    PyTuple t = (PyTuple) res;
                    int np = newPos(t, cps.length);
                    if (t.items.get(0) instanceof PyBytes pb) for (byte b : pb.toByteArray()) out.put(b);
                    else replacement(c, name, (String) t.items.get(0), out, st);
                    pos = np;
                }
            }
        }
    }

    /** Encodes a handler's replacement text with the same codec; an unencodable character in it is an error of its own. */
    private static void replacement(CjkCodec c, String name, String repl, CjkCodec.Bytes out, long[] st) {
        int[] cps = repl.codePoints().toArray();
        CjkCodec.Res r = c.encode(cps, 0, true, st, out);
        if (r.reason != null) throw CodecErrors.raise(CodecErrors.encodeValue(name, repl, r.errStart, r.errEnd, r.reason));
    }
}
