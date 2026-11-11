// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Unicode error exceptions and the codec error-handler registry ({@code codecs.register_error} /
 * {@code lookup_error}). The exception values carry CPython's attributes ({@code encoding}, {@code object},
 * {@code start}, {@code end}, {@code reason}); positions are code point indexes as in CPython.
 */
final class CodecErrors {
    private CodecErrors() {}

    static final String KIND = "\u0000unicode";
    private static final Map<String,Object> HANDLERS = new ConcurrentHashMap<>();
    static final List<String> BUILTIN = List.of("strict", "ignore", "replace", "xmlcharrefreplace", "backslashreplace",
        "namereplace", "surrogatepass", "surrogateescape");

    // ---- exception construction ----------------------------------------------------------------

    static PyExceptionValue decodeValue(String enc, byte[] data, long start, long end, String reason) {
        return value("UnicodeDecodeError", "decode", tuple(enc, new PyBytes(data), start, end, reason));
    }

    static PyExceptionValue encodeValue(String enc, String obj, long start, long end, String reason) {
        return value("UnicodeEncodeError", "encode", tuple(enc, obj, start, end, reason));
    }

    static PyExceptionValue translateValue(String obj, long start, long end, String reason) {
        return value("UnicodeTranslateError", "translate", tuple(obj, start, end, reason));
    }

    /** The exception value for a Unicode error built from its constructor arguments, else null for other exception types. */
    static PyExceptionValue fromArgs(String type, List<Object> args) {
        boolean decode = type.equals("UnicodeDecodeError"), encode = type.equals("UnicodeEncodeError");
        if (!decode && !encode && !type.equals("UnicodeTranslateError")) return null;
        int want = type.equals("UnicodeTranslateError") ? 4 : 5;
        if (args.size() != want) throw new PyException("TypeError", type + " expected " + want + " arguments, got " + args.size());
        int o = want - 4;
        if (o == 1 && !(args.get(0) instanceof String)) throw new PyException("TypeError", "argument 1 must be str, not " + PyRuntime.typeName(args.get(0)));
        Object obj = args.get(o);
        if (decode && !(obj instanceof PyByteSequence)) throw new PyException("TypeError", "argument 2 must be bytes-like object, not '" + PyRuntime.typeName(obj) + "'");
        if (!decode && !(obj instanceof String)) throw new PyException("TypeError", "argument " + (o + 1) + " must be str, not " + PyRuntime.typeName(obj));
        for (int i = o + 1; i < o + 3; i++)
            if (!(args.get(i) instanceof Long || args.get(i) instanceof java.math.BigInteger))
                throw new PyException("TypeError", "'" + PyRuntime.typeName(args.get(i)) + "' object cannot be interpreted as an integer");
        if (!(args.get(o + 3) instanceof String)) throw new PyException("TypeError", "argument " + (o + 4) + " must be str, not " + PyRuntime.typeName(args.get(o + 3)));
        PyTuple all = new PyTuple(); all.items.addAll(args);
        if (decode && !(obj instanceof PyBytes)) all.items.set(1, new PyBytes(((PyByteSequence) obj).toByteArray()));
        return value(type, decode ? "decode" : encode ? "encode" : "translate", all);
    }

    private static PyExceptionValue value(String type, String kind, PyTuple args) {
        LinkedHashMap<String,Object> attrs = new LinkedHashMap<>();
        List<Object> a = args.items;
        int o = a.size() == 4 ? 0 : 1;
        if (o == 1) attrs.put("encoding", a.get(0));
        attrs.put("object", a.get(o)); attrs.put("start", a.get(o + 1)); attrs.put("end", a.get(o + 2)); attrs.put("reason", a.get(o + 3));
        attrs.put(KIND, kind);
        return new PyExceptionValue(type, new PyExcArgs(args, null, attrs));
    }

    static PyException raise(PyExceptionValue v) { return new PyException(v.typeName, v.value); }

    static PyException decodeError(String enc, byte[] data, int start, int end, String reason) {
        return raise(decodeValue(enc, data, start, end, reason));
    }

    /** {@code start}/{@code end} are UTF-16 indexes into {@code s}. */
    static PyException encodeError(String enc, String s, int start, int end, String reason) {
        return raise(encodeValue(enc, s, s.codePointCount(0, start), s.codePointCount(0, end), reason));
    }

    /** CPython's {@code UnicodeError.__str__}, from the attributes. */
    static String describe(Map<String,Object> a) {
        Object kind = a.get(KIND);
        String reason = String.valueOf(a.get("reason"));
        long start = ((Number) a.get("start")).longValue(), end = ((Number) a.get("end")).longValue();
        Object obj = a.get("object");
        int size = obj instanceof PyByteSequence b ? b.byteSize() : obj instanceof String s ? s.codePointCount(0, s.length()) : 0;
        boolean one = end == start + 1 && start >= 0 && start < size;
        if (kind.equals("decode")) {
            if (one) return "'" + a.get("encoding") + "' codec can't decode byte 0x" + String.format("%02x", ((PyByteSequence) obj).unsignedAt((int) start)) + " in position " + start + ": " + reason;
            return "'" + a.get("encoding") + "' codec can't decode bytes in position " + start + "-" + (end - 1) + ": " + reason;
        }
        String head = kind.equals("encode") ? "'" + a.get("encoding") + "' codec can't encode" : "can't translate";
        if (one) {
            String s = (String) obj;
            int cp = s.codePointAt(s.offsetByCodePoints(0, (int) start));
            return head + " character '" + (cp < 0x100 ? String.format("\\x%02x", cp) : cp < 0x10000 ? String.format("\\u%04x", cp) : String.format("\\U%08x", cp))
                + "' in position " + start + ": " + reason;
        }
        return head + " characters in position " + start + "-" + (end - 1) + ": " + reason;
    }

    // ---- registry ----------------------------------------------------------------------------

    static Object lookup(String name) {
        Object h = HANDLERS.get(name);
        if (h != null) return h;
        synchronized (CodecErrors.class) {
            if (HANDLERS.isEmpty()) for (String n : BUILTIN) HANDLERS.put(n, builtin(n));
        }
        h = HANDLERS.get(name);
        if (h == null) throw new PyException("LookupError", "unknown error handler name '" + name + "'");
        return h;
    }

    static void register(String name, Object handler) {
        lookup("strict");
        if (!PyRuntime.truth(PyRuntime.callable_(handler))) throw new PyException("TypeError", "handler must be callable");
        HANDLERS.put(name, handler);
    }

    /** True when {@code name} is one of the builtin handlers (and has not been replaced), which the encoders implement inline. */
    static boolean isBuiltin(String name) { return BUILTIN.contains(name) && HANDLERS.getOrDefault(name, null) instanceof NativeFunction; }

    private static Object builtin(String name) {
        return new NativeFunction(name + "_errors", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", name + "_errors() takes exactly one argument (" + a.size() + " given)");
            return apply(name, a.get(0));
        });
    }

    static boolean isUnicodeError(Object o) {
        return o instanceof PyExceptionValue v && v.value instanceof PyExcArgs p && p.attrs != null && p.attrs.containsKey(KIND);
    }

    private static Map<String,Object> attrs(Object exc) { return ((PyExcArgs) ((PyExceptionValue) exc).value).attrs; }

    /** The builtin handler {@code name} applied to a Unicode error value: returns (replacement, newpos) or raises. */
    static Object apply(String name, Object exc) {
        if (!isUnicodeError(exc)) {
            if (exc instanceof PyExceptionValue || exc instanceof PyException || exc instanceof PyInstance)
                throw new PyException("TypeError", "don't know how to handle " + PyRuntime.typeName(exc) + " in error callback");
            throw new PyException("TypeError", "codec must pass exception instance");
        }
        Map<String,Object> a = attrs(exc);
        String kind = (String) a.get(KIND);
        long start = ((Number) a.get("start")).longValue(), end = ((Number) a.get("end")).longValue();
        PyExceptionValue ev = (PyExceptionValue) exc;
        switch (name) {
            case "strict", "surrogatepass": throw raise(ev);
            case "ignore": return tuple("", end);
            case "replace": {
                if (kind.equals("encode")) return tuple("?".repeat((int) Math.max(0, end - start)), end);
                return tuple(kind.equals("decode") ? "�" : "�".repeat((int) Math.max(0, end - start)), end);
            }
            default: break;
        }
        return applyOther(name, ev, a, kind, start, end);
    }

    private static Object applyOther(String name, PyExceptionValue ev, Map<String,Object> a, String kind, long start, long end) {
        if (name.equals("surrogateescape")) return surrogateEscape(ev, a, kind, start, end);
        if (kind.equals("decode")) {
            if (name.equals("xmlcharrefreplace") || name.equals("namereplace"))
                throw new PyException("TypeError", "don't know how to handle UnicodeDecodeError in error callback");
            if (!name.equals("backslashreplace")) throw raise(ev);
            PyByteSequence b = (PyByteSequence) a.get("object");
            StringBuilder sb = new StringBuilder();
            for (long i = start; i < end; i++) sb.append(String.format("\\x%02x", b.unsignedAt((int) i)));
            return tuple(sb.toString(), end);
        }
        String s = (String) a.get("object");
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (long i = start; i < end && i < cps.length; i++) {
            int c = cps[(int) i];
            switch (name) {
                case "backslashreplace" -> sb.append(c < 0x100 ? String.format("\\x%02x", c) : c < 0x10000 ? String.format("\\u%04x", c) : String.format("\\U%08x", c));
                case "xmlcharrefreplace" -> sb.append("&#").append(c).append(';');
                case "namereplace" -> {
                    String n = Character.getName(c);
                    if (n == null) throw raise(ev);
                    sb.append("\\N{").append(n).append('}');
                }
                default -> throw raise(ev);
            }
        }
        if (kind.equals("translate") && !name.equals("backslashreplace")) throw raise(ev);
        return tuple(sb.toString(), end);
    }

    private static Object surrogateEscape(PyExceptionValue ev, Map<String,Object> a, String kind, long start, long end) {
        if (kind.equals("decode")) {
            PyByteSequence b = (PyByteSequence) a.get("object");
            StringBuilder sb = new StringBuilder();
            for (long i = start; i < end; i++) {
                int v = b.unsignedAt((int) i);
                if (v < 128) throw raise(ev);
                sb.append((char) (0xDC00 + v));
            }
            return tuple(sb.toString(), end);
        }
        if (!kind.equals("encode")) throw raise(ev);
        int[] cps = ((String) a.get("object")).codePoints().toArray();
        byte[] out = new byte[(int) Math.max(0, end - start)];
        for (long i = start; i < end; i++) {
            int c = cps[(int) i];
            if (c < 0xDC80 || c > 0xDCFF) throw raise(ev);
            out[(int) (i - start)] = (byte) (c - 0xDC00);
        }
        return tuple(new PyBytes(out), end);
    }
}
