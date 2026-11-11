// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User classes that derive from a builtin value type (int, str, float, complex, bytes, bytearray, tuple, list, dict,
 * set, frozenset). An instance is an ordinary {@link PyInstance} (own class, {@code __dict__}, slots) whose
 * {@link PyInstance#payload} holds the native value (Long/BigInteger, String, ArrayList, PyDict, ...). Attribute
 * lookup and the special-method protocol go through the class MRO first; whatever the class does not define is
 * answered by the payload, exactly as CPython's inherited tp_* slots would answer. This file holds the shared
 * pieces: kinds, allocation and construction, the native special-method table used both for unbound calls
 * ({@code int.__add__(c, 1)}, {@code super().__getitem__(k)}) and as the fallback of operators and builtins.
 */
final class NativeSubclass {
    private NativeSubclass() {}

    static final Set<String> KINDS = Set.of("int", "str", "float", "complex", "bytes", "bytearray", "tuple", "list", "dict", "set", "frozenset");
    private static final Set<String> MUTABLE = Set.of("bytearray", "list", "dict", "set");

    static boolean isKind(String name) { return KINDS.contains(name); }
    static boolean mutable(String kind) { return MUTABLE.contains(kind); }

    /** The native value behind a builtin-backed instance, else the value itself. */
    static Object unwrap(Object value) {
        return value instanceof PyInstance p && p.payload != null ? p.payload : value;
    }
    /** Method arguments with builtin-backed instances replaced by their native values (the array itself when there are none). */
    static Object[] unwrapArgs(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof PyInstance p && p.payload != null) {
                Object[] out = args.clone();
                for (int j = i; j < out.length; j++) out[j] = unwrap(out[j]);
                return out;
            }
        }
        return args;
    }
    /** dict.__getitem__ on a subclass instance: missing keys go to the class's __missing__ hook. */
    static Object dictGet(PyInstance w, Object key) {
        Map<?, ?> m = (Map<?, ?>) w.payload;
        if (m.containsKey(key)) return m.get(key);
        PyMethod missing = w.cls.lookupMethod("__missing__");
        if (missing != null) return invoke(w, missing, new Object[]{key});
        throw new PyException("KeyError", key);
    }
    static boolean hasPayload(Object value) { return value instanceof PyInstance p && p.payload != null; }

    /** Native kind of a plain builtin value, or null. */
    static String kindOf(Object o) {
        if (o instanceof Long || o instanceof BigInteger || o instanceof Boolean) return "int";
        if (o instanceof String) return "str";
        if (o instanceof Double) return "float";
        if (o instanceof PyTuple) return "tuple";
        if (o instanceof List<?>) return "list";
        if (o instanceof PyDict d) return d.isFrozen() ? null : "dict";
        if (o instanceof java.util.Map<?, ?>) return "dict";
        if (o instanceof PyFrozenSet) return "frozenset";
        if (o instanceof Set<?>) return "set";
        if (o instanceof PyBytes) return "bytes";
        if (o instanceof PyByteArray) return "bytearray";
        if (o instanceof PyComplex) return "complex";
        return null;
    }
    /** Kind for plain values and for builtin-backed instances. */
    static String kindOfAny(Object o) {
        if (o instanceof PyInstance p) return p.payload == null ? null : p.cls.nativeKind;
        return kindOf(o);
    }

    // ---------- class creation ----------

    /** Called while a class is finalized: find the builtin value type among its bases and reject layout conflicts. */
    static String resolveKind(PyClass cls) {
        String kind = null;
        for (PyClass c : cls.mro) {
            String b = c.builtinBaseName;
            if (b == null) continue;
            String k = isKind(b) ? b : null;
            if (k == null && c.exceptionBase != null) k = "exception";
            if (k == null) continue;
            if (kind != null && !kind.equals(k)) throw new PyException("TypeError", "multiple bases have instance lay-out conflict");
            kind = k;
        }
        return kind == null || kind.equals("exception") ? null : kind;
    }

    /** classAddBase for a builtin value type used as a base. */
    static void addBase(PyClass target, PyBuiltinType base) {
        if (target.builtinBaseName != null && !target.builtinBaseName.equals("object") && !target.builtinBaseName.equals(base.name))
            throw new PyException("TypeError", "multiple bases have instance lay-out conflict");
        for (PyClass b : target.bases) {
            for (PyClass c : b.mro) if (c.builtinBaseName != null && isKind(c.builtinBaseName) && !c.builtinBaseName.equals(base.name))
                throw new PyException("TypeError", "multiple bases have instance lay-out conflict");
        }
        target.builtinBaseName = base.name;
    }

    // ---------- allocation / construction ----------

    static Object emptyPayload(String kind) {
        return switch (kind) {
            case "list" -> new ArrayList<Object>();
            case "dict" -> new PyDict();
            case "set" -> new PySet();
            case "bytearray" -> new PyByteArray();
            default -> throw new IllegalStateException(kind);
        };
    }

    /** {@code kind.__new__(cls, *args)} for a subclass (cls a PyClass) or the exact type. */
    static Object allocate(Object cls, String kind, Object[] args, Map<Object, Object> kwargs) {
        if (!(cls instanceof PyClass pc)) return mutable(kind) ? emptyPayload(kind) : construct(kind, args, kwargs);
        if (!kind.equals(pc.nativeKind)) throw new PyException("TypeError", kind + ".__new__(" + pc.name + "): " + pc.name + " is not a subtype of " + kind);
        PyInstance inst = new PyInstance(pc);
        inst.payload = mutable(kind) ? emptyPayload(kind) : construct(kind, args, kwargs);
        return inst;
    }

    /** The native constructor call {@code kind(*args, **kwargs)} for an immutable kind. */
    static Object construct(String kind, Object[] args, Map<Object, Object> kwargs) {
        List<Object> list = new ArrayList<>(Arrays.asList(args));
        Object first = list.isEmpty() ? null : list.get(0);
        switch (kind) {
            case "int": return constructInt(list, kwargs);
            case "str": return constructStr(list, kwargs);
            case "float":
                noKeywords("float", kwargs); atMost("float", list, 1);
                return list.isEmpty() ? (Object) 0.0 : float_(unwrap(first));
            case "complex": return complexConstructor(unwrapAll(list), kwargs);
            case "bytes": case "bytearray": return constructBytes(kind, list, kwargs);
            case "tuple": noKeywords("tuple", kwargs); atMost("tuple", list, 1); return list.isEmpty() ? new PyTuple() : tupleFrom(first);
            case "frozenset": noKeywords("frozenset", kwargs); atMost("frozenset", list, 1);
                return list.isEmpty() ? new PyFrozenSet(List.of()) : first instanceof PyFrozenSet ? first : new PyFrozenSet(first);
            case "list": case "dict": case "set": {
                Object out = emptyPayload(kind);
                initPayload(kind, out, args, kwargs);
                return out;
            }
            default: throw new PyException("TypeError", "unsupported builtin constructor " + kind);
        }
    }

    static void noKeywords(String name, Map<Object, Object> kwargs) {
        if (!kwargs.isEmpty()) throw new PyException("TypeError", name + "() takes no keyword arguments");
    }
    static void atMost(String name, List<Object> args, int n) {
        if (args.size() > n) throw new PyException("TypeError", name + " expected at most " + n + " argument" + (n == 1 ? "" : "s") + ", got " + args.size());
    }
    static List<Object> unwrapAll(List<Object> in) {
        ArrayList<Object> out = new ArrayList<>();
        for (Object o : in) out.add(unwrap(o));
        return out;
    }

    private static Object constructInt(List<Object> args, Map<Object, Object> kwargs) {
        Object base = null;
        for (Map.Entry<Object, Object> e : kwargs.entrySet()) {
            if (e.getKey().equals("base") && args.size() == 1) base = e.getValue();
            else if (e.getKey().equals("x") && args.isEmpty()) args = new ArrayList<>(List.of(e.getValue()));
            else throw new PyException("TypeError", "int() got an unexpected keyword argument '" + e.getKey() + "'");
        }
        if (args.size() > 2) throw new PyException("TypeError", "int() takes at most 2 arguments (" + args.size() + " given)");
        if (args.size() == 2) base = args.get(1);
        if (args.isEmpty()) return 0L;
        Object x = unwrap(args.get(0));
        if (base == null) {
            if (x instanceof Double d) {
                if (Double.isNaN(d)) throw new PyException("ValueError", "cannot convert float NaN to integer");
                if (Double.isInfinite(d)) throw new PyException("OverflowError", "cannot convert float infinity to integer");
                return compact(new java.math.BigDecimal(d).toBigInteger());
            }
            if (x instanceof String || x instanceof PyByteSequence) return parseInt(x, 10);
            return int_(x);
        }
        if (!(x instanceof String || x instanceof PyByteSequence)) throw new PyException("TypeError", "int() can't convert non-string with explicit base");
        return parseInt(x, (int) asIndex(unwrap(base)));
    }

    static Object parseInt(Object source, int base) {
        String text = source instanceof String s ? s : new String(((PyByteSequence) source).toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (base != 0 && (base < 2 || base > 36)) throw new PyException("ValueError", "int() base must be >= 2 and <= 36, or 0");
        String t = text.strip();
        String body = t;
        boolean negative = false;
        if (body.startsWith("-") || body.startsWith("+")) { negative = body.startsWith("-"); body = body.substring(1); }
        int radix = base;
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("0x") && (base == 16 || base == 0)) { radix = 16; body = body.substring(2); }
        else if (lower.startsWith("0o") && (base == 8 || base == 0)) { radix = 8; body = body.substring(2); }
        else if (lower.startsWith("0b") && (base == 2 || base == 0)) { radix = 2; body = body.substring(2); }
        else if (base == 0) radix = 10;
        boolean valid = !body.isEmpty() && !body.startsWith("_") && !body.endsWith("_") && !body.contains("__");
        if (valid) {
            String digits = body.replace("_", "");
            for (int i = 0; i < digits.length(); i++) if (Character.digit(digits.charAt(i), radix) < 0) { valid = false; break; }
            if (valid && base == 0 && radix == 10 && digits.length() > 1 && digits.charAt(0) == '0' && !digits.chars().allMatch(c -> c == '0')) valid = false;
            if (valid) {
                BigInteger v = new BigInteger(digits, radix);
                return compact(negative ? v.negate() : v);
            }
        }
        throw new PyException("ValueError", "invalid literal for int() with base " + (base == 0 ? 10 : base) + ": " + pyRepr(source));
    }

    private static Object constructStr(List<Object> args, Map<Object, Object> kwargs) {
        Object encoding = null, errors = null, obj = null;
        boolean haveObj = false;
        for (Map.Entry<Object, Object> e : kwargs.entrySet()) {
            switch ((String) e.getKey()) {
                case "object": obj = e.getValue(); haveObj = true; break;
                case "encoding": encoding = e.getValue(); break;
                case "errors": errors = e.getValue(); break;
                default: throw new PyException("TypeError", "str() got an unexpected keyword argument '" + e.getKey() + "'");
            }
        }
        if (args.size() > 3) throw new PyException("TypeError", "str() takes at most 3 arguments (" + args.size() + " given)");
        if (!args.isEmpty()) { obj = args.get(0); haveObj = true; }
        if (args.size() > 1) encoding = args.get(1);
        if (args.size() > 2) errors = args.get(2);
        if (!haveObj) return "";
        if (encoding == null && errors == null) return str_(obj);
        Object raw = unwrap(obj);
        if (!(raw instanceof PyByteSequence seq)) throw new PyException("TypeError", "decoding to str: need a bytes-like object, " + typeName(obj) + " found");
        String enc = encoding == null ? "utf-8" : (String) unwrap(encoding);
        return new String(seq.toByteArray(), pyCharset(enc));
    }

    private static Object constructBytes(String kind, List<Object> args, Map<Object, Object> kwargs) {
        List<Object> a = unwrapAll(args);
        for (Map.Entry<Object, Object> e : kwargs.entrySet()) {
            String key = (String) e.getKey();
            if (key.equals("source") && a.isEmpty()) a.add(e.getValue());
            else if (key.equals("encoding") && a.size() == 1) a.add(e.getValue());
            else if (key.equals("errors") && a.size() == 2) a.add(e.getValue());
            else throw new PyException("TypeError", kind + "() got an unexpected keyword argument '" + key + "'");
        }
        boolean isBytes = kind.equals("bytes");
        if (a.isEmpty()) return isBytes ? bytes0() : bytearray0();
        if (a.size() == 1) {
            if (a.get(0) instanceof String) throw new PyException("TypeError", "string argument without an encoding");
            return isBytes ? bytes1(a.get(0)) : bytearray1(a.get(0));
        }
        if (!(a.get(0) instanceof String text)) throw new PyException("TypeError", "encoding without a string argument");
        byte[] data = text.getBytes(pyCharset((String) a.get(1)));
        return isBytes ? new PyBytes(data) : new PyByteArray(data);
    }

    /** {@code kind.__init__(self, *args, **kwargs)} on a mutable payload: list/set rebuild, dict update, bytearray reset. */
    static void initPayload(String kind, Object payload, Object[] args, Map<Object, Object> kwargs) {
        @SuppressWarnings("unchecked") Map<Object, Object> kw = kwargs;
        switch (kind) {
            case "list": {
                noKeywords("list", kw); atMost("list", Arrays.asList(args), 1);
                @SuppressWarnings("unchecked") List<Object> list = (List<Object>) payload;
                ArrayList<Object> items = new ArrayList<>();
                if (args.length == 1) for (Object x : iterable(args[0])) items.add(x);
                list.clear(); list.addAll(items);
                return;
            }
            case "set": {
                noKeywords("set", kw); atMost("set", Arrays.asList(args), 1);
                PySet set = (PySet) payload;
                PySet items = args.length == 1 ? new PySet(args[0]) : new PySet();
                set.entries.clear(); set.entries.putAll(items.entries);
                return;
            }
            case "dict": {
                if (args.length > 1) throw new PyException("TypeError", "dict expected at most 1 argument, got " + args.length);
                if (args.length == 1) dictUpdate(payload, args[0]);
                @SuppressWarnings("unchecked") Map<Object, Object> dict = (Map<Object, Object>) payload;
                for (Map.Entry<Object, Object> e : kw.entrySet()) dict.put(e.getKey(), e.getValue());
                return;
            }
            case "bytearray": {
                PyByteArray array = (PyByteArray) payload;
                PyByteArray fresh = (PyByteArray) constructBytes("bytearray", new ArrayList<>(Arrays.asList(args)), kw);
                array.data.clear(); array.data.addAll(fresh.data);
                return;
            }
            default: return;
        }
    }

    /** Default {@code __init__} of a builtin-backed instance whose class does not define one. */
    static void defaultInit(PyInstance inst, Object[] args, Map<Object, Object> kwargs) {
        if (mutable(inst.cls.nativeKind)) initPayload(inst.cls.nativeKind, inst.payload, args, kwargs);
    }

    // ---------- instance protocol fallbacks (used by PyRuntime when the class defines no override) ----------

    static String repr(PyInstance inst) {
        Object p = inst.payload;
        String kind = inst.cls.nativeKind;
        if (kind.equals("set") || kind.equals("frozenset")) {
            Set<?> s = (Set<?>) p;
            return inst.cls.name + "(" + (s.isEmpty() ? "" : seqRepr(s, "{", "}")) + ")";
        }
        if (kind.equals("bytearray")) return inst.cls.name + "(" + bytesRepr(((PyByteArray) p).toByteArray()) + ")";
        return pyRepr(p);
    }

    static Object hash(PyInstance inst) {
        if (inst.cls.nativeKind.equals("list") || inst.cls.nativeKind.equals("dict") || inst.cls.nativeKind.equals("set") || inst.cls.nativeKind.equals("bytearray"))
            throw new PyException("TypeError", "unhashable type: '" + inst.cls.name + "'");
        return PyRuntime.hash(inst.payload);
    }

    /** The int payload of an int-subclass instance (CPython uses the C value for subclasses and ignores __index__), else the value itself. */
    static Object indexValue(Object value) {
        return value instanceof PyInstance w && w.payload != null && isIntLike(w.payload) ? w.payload : value;
    }

    /** int(x) for an instance: __int__/__index__/__trunc__ overrides first, then the native value. */
    static Object intOf(PyInstance w) {
        PyMethod m = w.cls.lookupMethod("__int__");
        if (m == null) m = w.cls.lookupMethod("__index__");
        if (m == null) m = w.cls.lookupMethod("__trunc__");
        if (m != null) {
            Object r = unwrap(invoke(w, m, new Object[0]));
            if (!isIntLike(r)) throw new PyException("TypeError", "__int__ returned non-int (type " + typeName(r) + ")");
            return compact(bigInt(r));
        }
        if (w.payload != null) return int_(w.payload);
        throw typeError("int() argument must be a string or a number", w);
    }

    /** float(x) for an instance: __float__/__index__ overrides first, then the native value. */
    static Object floatOf(PyInstance w) {
        PyMethod m = w.cls.lookupMethod("__float__");
        if (m != null) {
            Object r = unwrap(invoke(w, m, new Object[0]));
            if (!(r instanceof Double)) throw new PyException("TypeError", "__float__ returned non-float (type " + typeName(r) + ")");
            return r;
        }
        m = w.cls.lookupMethod("__index__");
        if (m != null) return float_(unwrap(invoke(w, m, new Object[0])));
        if (w.payload != null) return float_(w.payload);
        throw typeError("float() argument must be a string or a real number", w);
    }

    /** Arguments of a builtin constructor call with a builtin-backed first argument replaced by its payload where CPython reads the C layout. */
    static List<Object> nativeArgs(String type, List<Object> args) {
        PyInstance w = (PyInstance) args.get(0);
        PyClass c = w.cls;
        boolean unwrap = switch (type) {
            case "list", "tuple", "set", "frozenset", "bytes", "bytearray", "dict" -> c.lookupMethod("__iter__") == null;
            case "int" -> c.lookupMethod("__int__") == null && c.lookupMethod("__index__") == null && c.lookupMethod("__trunc__") == null;
            case "float" -> c.lookupMethod("__float__") == null && c.lookupMethod("__index__") == null;
            case "complex" -> c.lookupMethod("__complex__") == null && c.lookupMethod("__float__") == null && c.lookupMethod("__index__") == null;
            default -> false;
        };
        if (!unwrap) return args;
        ArrayList<Object> out = new ArrayList<>(args);
        out.set(0, w.payload);
        return out;
    }

    /** Result of a unary operator on a builtin-backed instance; MISSING when the instance has no payload. */
    static Object unary(Object a, String op) {
        if (!(a instanceof PyInstance inst)) return MISSING;
        PyMethod m = inst.cls.lookupMethod(op);
        if (m != null) return invoke(inst, m, new Object[0]);
        if (inst.payload == null) return MISSING;
        return switch (op) {
            case "__neg__" -> neg(inst.payload);
            case "__pos__" -> pos(inst.payload);
            case "__abs__" -> abs(inst.payload);
            case "__invert__" -> invert(inst.payload);
            default -> MISSING;
        };
    }

    /** Native evaluation of a binary operator once no user method answered; NOT_IMPLEMENTED if neither side has a payload. */
    static Object binary(String name, Object a, Object b) {
        if (!hasPayload(a) && !hasPayload(b)) return NOT_IMPLEMENTED;
        Object x = unwrap(a), y = unwrap(b);
        if (x instanceof PyInstance || y instanceof PyInstance) return NOT_IMPLEMENTED;
        return switch (name) {
            case "__add__" -> add(x, y);
            case "__sub__" -> sub(x, y);
            case "__mul__" -> mul(x, y);
            case "__truediv__" -> truediv(x, y);
            case "__floordiv__" -> floordiv(x, y);
            case "__mod__" -> mod(x, y);
            case "__pow__" -> pow(x, y);
            case "__divmod__" -> BuiltinsExtra.divmod(x, y);
            case "__and__" -> bitAnd(x, y);
            case "__or__" -> bitOr(x, y);
            case "__xor__" -> bitXor(x, y);
            case "__lshift__" -> lshift(x, y);
            case "__rshift__" -> rshift(x, y);
            case "__eq__" -> eq(x, y);
            case "__ne__" -> ne(x, y);
            case "__lt__" -> lt(x, y);
            case "__le__" -> le(x, y);
            case "__gt__" -> gt(x, y);
            case "__ge__" -> ge(x, y);
            default -> NOT_IMPLEMENTED;
        };
    }

    /** In-place operators: run on the payload and keep the wrapper as the result. */
    static Object inplace(PyInstance inst, String op, Object other) {
        Object p = inst.payload;
        other = unwrap(other);
        Object result = switch (op) {
            case "__iadd__" -> iadd(p, other);
            case "__imul__" -> imul(p, other);
            case "__ior__" -> ibitOr(p, other);
            case "__iand__" -> ibitAnd(p, other);
            case "__ixor__" -> ibitXor(p, other);
            case "__isub__" -> isub(p, other);
            default -> NOT_IMPLEMENTED;
        };
        return result == p ? inst : result;
    }
}
