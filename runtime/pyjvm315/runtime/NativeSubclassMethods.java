// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native special-method and method tables of the builtin value types: the descriptors reached as
 * {@code int.__add__}, {@code super().__getitem__}, {@code [].__len__} and as the fallback of the instance protocol
 * of classes that derive from a builtin type (see {@link NativeSubclass}).
 */
final class NativeSubclassMethods {
    private NativeSubclassMethods() {}

    private static final String BYTES_METHODS = "decode hex find rfind index rindex partition rpartition count startswith endswith replace split join translate strip lstrip rstrip lower upper capitalize title swapcase";
    private static final String SET_METHODS = "copy union intersection difference symmetric_difference issubset issuperset isdisjoint";
    private static final String ARITH = "add sub mul truediv floordiv mod pow divmod lshift rshift and or xor";
    private static final HashMap<String, Set<String>> MEMBERS = new HashMap<>();
    private static void kind(String kind, String... groups) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(List.of("__init__", "__new__", "__repr__", "__str__", "__eq__", "__ne__"));
        for (String group : groups) names.addAll(List.of(group.split(" ")));
        MEMBERS.put(kind, names);
    }
    private static String both(String ops) {
        StringBuilder out = new StringBuilder();
        for (String op : ops.split(" ")) out.append("__").append(op).append("__ __r").append(op).append("__ ");
        return out.toString();
    }
    private static String ordering = "__lt__ __le__ __gt__ __ge__";
    static {
        kind("int", both(ARITH), "__neg__ __pos__ __abs__ __invert__ __bool__ __int__ __float__ __index__ __hash__ __trunc__ __floor__ __ceil__", ordering,
            "bit_length bit_count to_bytes conjugate is_integer as_integer_ratio real imag numerator denominator");
        kind("float", both("add sub mul truediv floordiv mod pow divmod"), "__neg__ __pos__ __abs__ __bool__ __int__ __float__ __hash__ __trunc__ __floor__ __ceil__", ordering,
            "is_integer as_integer_ratio conjugate real imag hex fromhex");
        kind("complex", both("add sub mul truediv pow"), "__neg__ __pos__ __abs__ __bool__ __hash__ real imag conjugate");
        kind("str", "__add__ __mul__ __rmul__ __mod__ __len__ __getitem__ __contains__ __iter__ __hash__", ordering, String.join(" ", StringMethods.NAMES));
        kind("bytes", "__add__ __mul__ __rmul__ __len__ __getitem__ __contains__ __iter__ __hash__", ordering, BYTES_METHODS, "fromhex");
        kind("bytearray", "__add__ __mul__ __rmul__ __iadd__ __imul__ __len__ __getitem__ __setitem__ __delitem__ __contains__ __iter__", ordering, BYTES_METHODS,
            "append extend clear copy reverse pop remove insert fromhex");
        kind("tuple", "__add__ __mul__ __rmul__ __len__ __getitem__ __contains__ __iter__ __hash__", ordering, "index count");
        kind("list", "__add__ __mul__ __rmul__ __iadd__ __imul__ __len__ __getitem__ __setitem__ __delitem__ __contains__ __iter__ __reversed__", ordering,
            String.join(" ", ListMethods.NAMES));
        kind("dict", "__len__ __getitem__ __setitem__ __delitem__ __contains__ __iter__ __reversed__ __or__ __ror__ __ior__", String.join(" ", DICT_METHODS));
        kind("set", "__len__ __contains__ __iter__ __and__ __rand__ __or__ __ror__ __xor__ __rxor__ __sub__ __rsub__ __iand__ __ior__ __ixor__ __isub__", ordering,
            SET_METHODS + " add remove discard clear pop update intersection_update difference_update symmetric_difference_update");
        kind("frozenset", "__len__ __contains__ __iter__ __and__ __rand__ __or__ __ror__ __xor__ __rxor__ __sub__ __rsub__ __hash__", ordering, SET_METHODS);
        MEMBERS.get("dict").add("fromkeys");
        MEMBERS.get("int").add("from_bytes");
    }

    static boolean hasMember(String kind, String name) {
        Set<String> names = MEMBERS.get(kind);
        return names != null && names.contains(name);
    }

    static boolean isInstanceMethod(String kind, String name) {
        return hasMember(kind, name) && !name.equals("__new__") && !name.equals("fromkeys") && !name.equals("from_bytes") && !name.equals("fromhex");
    }

    /** Special methods (not plain methods) reachable on instances: {@code __new__} is type-level only. */
    static boolean hasInstanceDunder(String kind, String name) {
        return !name.equals("__new__") && name.startsWith("__") && hasMember(kind, name);
    }

    // ---------- hooks used by PyRuntime ----------

    /** callMethod on a plain native value for a special-method name; MISSING if the type has no such slot. */
    static Object callDunder(Object obj, String name, Object[] args) {
        String kind = NativeSubclass.kindOf(obj);
        if (kind == null || !hasInstanceDunder(kind, name)) return MISSING;
        return dunder(obj, obj, kind, name, args);
    }

    /** getattr(native value, special-method name): a bound builtin method, or MISSING. */
    static Object boundDunder(Object obj, String name) {
        String kind = NativeSubclass.kindOf(obj);
        return kind != null && hasInstanceDunder(kind, name) ? new BuiltinBoundMethod(obj, name) : MISSING;
    }

    /** getattr(instance, name) once the class MRO and instance dictionary did not answer. */
    static Object instanceAttribute(PyInstance inst, String name) {
        String kind = inst.cls.nativeKind;
        if (hasInstanceDunder(kind, name)) return new BuiltinBoundMethod(inst.payload, name);
        if (name.startsWith("__")) return MISSING;
        try { return getattr(inst.payload, name); }
        catch (PyException e) { if (e.typeName.equals("AttributeError")) return MISSING; throw e; }
    }

    /** getattr(cls, name) when the class derives from a builtin value type and its MRO did not answer. */
    static Object classAttribute(PyClass cls, String name) {
        String kind = cls.nativeKind;
        if (name.equals("__base__")) {
            for (PyClass base : cls.bases) if (kind == null || kind.equals(base.nativeKind)) return base;
            return builtinType(cls.builtinBaseName != null ? cls.builtinBaseName : "object");
        }
        if (!hasMember(kind, name)) return MISSING;
        if (name.equals("fromkeys") || name.equals("from_bytes") || name.equals("fromhex"))
            return new NativeFunction(name, (a, k) -> classConstructor(cls, kind, name, a));
        return new BuiltinBoundMethod(builtinType(kind), name);
    }

    private static Object classConstructor(PyClass cls, String kind, String name, List<Object> args) {
        if (name.equals("from_bytes")) {
            Object value = IntMethods.fromBytes(args.toArray());
            return instantiate(cls, new Object[]{value});
        }
        if (name.equals("fromhex")) {
            if (args.size() != 1 || !(args.get(0) instanceof String text)) throw new PyException("TypeError", "fromhex() argument must be str");
            return instantiate(cls, new Object[]{fromHex(kind, text)});
        }
        if (args.isEmpty() || args.size() > 2) throw new PyException("TypeError", "fromkeys expected 1 or 2 arguments");
        Object out = instantiate(cls, new Object[0]);
        for (Object key : iterable(args.get(0))) setitem(out, key, args.size() == 2 ? args.get(1) : null);
        return out;
    }

    /** {@code kind.name(self, *args)} reached through the type object; MISSING if the type has no such member. */
    static Object callUnbound(PyBuiltinType type, String name, Object[] args) {
        String kind = type.name;
        if (!NativeSubclass.isKind(kind) || !hasMember(kind, name)) return MISSING;
        if (name.equals("__new__")) {
            if (args.length == 0) throw new PyException("TypeError", kind + ".__new__(): not enough arguments");
            return NativeSubclass.allocate(args[0], kind, Arrays.copyOfRange(args, 1, args.length), new LinkedHashMap<>());
        }
        if (name.equals("fromhex")) {
            if (args.length != 1 || !(args[0] instanceof String text)) throw new PyException("TypeError", "fromhex() argument must be str");
            return fromHex(kind, text);
        }
        if (name.equals("fromkeys") || name.equals("from_bytes")) return MISSING;
        if (args.length == 0) throw new PyException("TypeError", "unbound method " + kind + "." + name + "() needs an argument");
        Object self = args[0], p = NativeSubclass.unwrap(self);
        String actual = NativeSubclass.kindOf(p);
        if (actual == null && p instanceof PyDict fd && fd.isFrozen() && kind.equals("dict")) actual = "dict";
        if (actual == null || !(actual.equals(kind))) {
            throw new PyException("TypeError", "descriptor '" + name + "' for '" + kind + "' objects doesn't apply to a '" + typeName(self) + "' object");
        }
        Object[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (name.startsWith("__")) return dunder(self, p, kind, name, rest);
        return callMethod(p, name, rest);
    }

    /** {@code kind.__init__}/{@code kind.__new__} reached with keyword arguments (super().__init__(**kw)). */
    static Object callInitNew(String kind, String name, List<Object> args, Map<Object, Object> kwargs) {
        if (name.equals("__new__")) {
            if (args.isEmpty()) throw new PyException("TypeError", kind + ".__new__(): not enough arguments");
            return NativeSubclass.allocate(args.get(0), kind, args.subList(1, args.size()).toArray(), kwargs);
        }
        if (args.isEmpty()) throw new PyException("TypeError", kind + ".__init__(): not enough arguments");
        Object self = args.get(0);
        Object[] rest = args.subList(1, args.size()).toArray();
        if (self instanceof PyInstance w && w.payload != null) NativeSubclass.defaultInit(w, rest, kwargs);
        else if (NativeSubclass.mutable(kind) && NativeSubclass.kindOf(self) != null) NativeSubclass.initPayload(kind, self, rest, kwargs);
        return null;
    }

    /** Members of plain float/tuple values that have no per-type method file. */
    static Object plainAttribute(Object obj, String name) {
        Object number = numberAttribute(obj, name);
        if (number != MISSING) return number;
        if (obj instanceof Double && (name.equals("hex") || name.equals("is_integer") || name.equals("conjugate") || name.equals("as_integer_ratio"))) return new BuiltinBoundMethod(obj, name);
        if (obj instanceof PyTuple && (name.equals("index") || name.equals("count"))) return new BuiltinBoundMethod(obj, name);
        if ((obj instanceof PyBytes || obj instanceof PyByteArray) && !name.startsWith("__") && hasMember(NativeSubclass.kindOf(obj), name)) return new BuiltinBoundMethod(obj, name);
        return boundDunder(obj, name);
    }

    static Object callFloatSlot(Object self, String name, Object[] args) {
        Object r = callDunder(self, name, args);
        return r != MISSING ? r : callFloat(self, name, args);
    }
    static Object callTupleSlot(Object self, String name, Object[] args) {
        Object r = callDunder(self, name, args);
        return r != MISSING ? r : callTuple(self, name, args);
    }

    // ---------- method slots missing from the per-type files ----------

    static Object callTuple(Object obj, String name, Object[] args) {
        PyTuple t = (PyTuple) obj;
        switch (name) {
            case "count": requireArgs(name, args, 1); { long n = 0; for (Object x : t.items) if (x == args[0] || truth(eq(x, args[0]))) n++; return n; }
            case "index": {
                if (args.length < 1 || args.length > 3) throw new PyException("TypeError", "index expected 1 to 3 arguments");
                int n = t.items.size();
                long from = args.length > 1 ? asIndex(args[1]) : 0, to = args.length > 2 ? asIndex(args[2]) : n;
                if (from < 0) from = Math.max(0, from + n);
                if (to < 0) to = Math.max(0, to + n);
                to = Math.min(to, n);
                for (int i = (int) from; i < to; i++) if (t.items.get(i) == args[0] || truth(eq(t.items.get(i), args[0]))) return (long) i;
                throw new PyException("ValueError", "tuple.index(x): x not in tuple");
            }
            default: throw new PyException("AttributeError", "'tuple' object has no attribute '" + name + "'");
        }
    }

    static Object repeatTuple(PyTuple t, BigInteger count) {
        PyTuple out = new PyTuple();
        if (count.signum() <= 0) return out;
        if (count.bitLength() > 31) throw new PyException("OverflowError", "cannot fit 'int' into an index-sized integer");
        for (int i = 0; i < count.intValue(); i++) out.items.addAll(t.items);
        return out;
    }

    /** list[slice] = iterable, including extended slices. */
    @SuppressWarnings("unchecked")
    static void listSetSlice(List<Object> list, PySlice sl, Object rhs) {
        ArrayList<Object> values = new ArrayList<>();
        for (Object x : iterable(rhs == list ? new ArrayList<Object>(list) : rhs)) values.add(x);
        int size = list.size();
        int step = sl.step == null ? 1 : asIndex(sl.step);
        if (step == 0) throw new PyException("ValueError", "slice step cannot be zero");
        int start = sl.start == null ? (step > 0 ? 0 : size - 1) : normalizeSliceIndex(asIndex(sl.start), size, step > 0);
        int stop = sl.stop == null ? (step > 0 ? size : -1) : normalizeSliceStop(asIndex(sl.stop), size, step > 0);
        if (step == 1) {
            if (stop < start) stop = start;
            List<Object> window = list.subList(start, stop);
            window.clear();
            list.addAll(start, values);
            return;
        }
        ArrayList<Integer> positions = new ArrayList<>();
        for (int i = start; step > 0 ? i < stop : i > stop; i += step) if (i >= 0 && i < size) positions.add(i);
        if (positions.size() != values.size())
            throw new PyException("ValueError", "attempt to assign sequence of size " + values.size() + " to extended slice of size " + positions.size());
        for (int i = 0; i < positions.size(); i++) list.set(positions.get(i), values.get(i));
    }

    static String floatHex(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0) return Double.doubleToRawLongBits(d) < 0 ? "-0x0.0p+0" : "0x0.0p+0";
        long bits = Double.doubleToRawLongBits(d);
        boolean negative = bits < 0;
        int exponent = (int) ((bits >>> 52) & 2047);
        long fraction = bits & 0xfffffffffffffL;
        String lead = exponent == 0 ? "0" : "1";
        int power = exponent == 0 ? -1022 : exponent - 1023;
        return (negative ? "-" : "") + "0x" + lead + "." + String.format("%013x", fraction) + "p" + (power < 0 ? "-" : "+") + Math.abs(power);
    }

    static Object fromHex(String kind, String text) {
        if (kind.equals("float")) return floatFromHex(text);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int pending = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == 0x0c) {
                if (pending >= 0) throw new PyException("ValueError", "non-hexadecimal number found in fromhex() arg at position " + i);
                continue;
            }
            int digit = Character.digit(c, 16);
            if (digit < 0 || c > 127) throw new PyException("ValueError", "non-hexadecimal number found in fromhex() arg at position " + i);
            if (pending < 0) pending = digit; else { out.write(pending * 16 + digit); pending = -1; }
        }
        if (pending >= 0) throw new PyException("ValueError", "fromhex() arg must contain an even number of hexadecimal digits");
        return kind.equals("bytes") ? new PyBytes(out.toByteArray()) : new PyByteArray(out.toByteArray());
    }

    static Object floatFromHex(String text) {
        String s = text.strip().toLowerCase();
        String body = s.startsWith("-") || s.startsWith("+") ? s.substring(1) : s;
        if (body.equals("inf") || body.equals("infinity") || body.equals("nan")) return float_(s);
        if (!body.startsWith("0x")) throw new PyException("ValueError", "invalid hexadecimal floating-point string");
        if (!body.contains("p")) s = s + "p0";
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { throw new PyException("ValueError", "invalid hexadecimal floating-point string"); }
    }

    static Object callFloat(Object obj, String name, Object[] args) {
        double d = (Double) obj;
        requireArgs(name, args, 0);
        switch (name) {
            case "hex": return floatHex(d);
            case "is_integer": return Double.isFinite(d) && d == Math.rint(d);
            case "conjugate": return obj;
            case "as_integer_ratio": {
                if (Double.isNaN(d)) throw new PyException("ValueError", "cannot convert NaN to integer ratio");
                if (Double.isInfinite(d)) throw new PyException("OverflowError", "cannot convert Infinity to integer ratio");
                java.math.BigDecimal exact = new java.math.BigDecimal(d);
                BigInteger num = exact.unscaledValue(), den = BigInteger.ONE;
                if (exact.scale() > 0) den = BigInteger.TEN.pow(exact.scale()); else num = num.multiply(BigInteger.TEN.pow(-exact.scale()));
                BigInteger g = num.gcd(den);
                if (g.signum() != 0) { num = num.divide(g); den = den.divide(g); }
                PyTuple out = new PyTuple();
                out.items.add(compact(num)); out.items.add(compact(den));
                return out;
            }
            default: throw new PyException("AttributeError", "'float' object has no attribute '" + name + "'");
        }
    }

    /** Attribute-style members of plain ints/floats (real, imag, numerator, denominator). */
    static Object numberAttribute(Object obj, String name) {
        if (NativeSubclass.kindOf(obj) == null) return MISSING;
        boolean isInt = obj instanceof Long || obj instanceof BigInteger || obj instanceof Boolean;
        if (isInt) {
            Object value = obj instanceof Boolean b ? (Object) (b ? 1L : 0L) : obj;
            return switch (name) { case "real", "numerator" -> value; case "imag" -> 0L; case "denominator" -> 1L; default -> MISSING; };
        }
        if (obj instanceof Double) return switch (name) { case "real" -> obj; case "imag" -> 0.0; default -> MISSING; };
        return MISSING;
    }

    // ---------- the special-method implementations ----------

    private static boolean sequenceKind(String kind) {
        return kind.equals("str") || kind.equals("bytes") || kind.equals("bytearray") || kind.equals("tuple") || kind.equals("list");
    }

    private static String family(String kind) {
        return switch (kind) {
            case "int", "float", "complex" -> "num";
            case "bytes", "bytearray" -> "bytes";
            case "set", "frozenset" -> "set";
            default -> kind;
        };
    }

    private static Object arithmetic(String base, boolean reflected, Object p, Object[] a, String kind) {
        if (a.length < 1 || a.length > (base.equals("pow") ? 2 : 1)) throw new PyException("TypeError", "expected 1 argument, got " + a.length);
        Object other = NativeSubclass.unwrap(a[0]);
        Object x = reflected ? other : p, y = reflected ? p : other;
        try {
            return switch (base) {
                case "add" -> add(x, y);
                case "sub" -> sub(x, y);
                case "mul" -> mul(x, y);
                case "truediv" -> truediv(x, y);
                case "floordiv" -> floordiv(x, y);
                case "mod" -> mod(x, y);
                case "pow" -> a.length == 2 && a[1] != null ? powMod(x, y, NativeSubclass.unwrap(a[1])) : pow(x, y);
                case "divmod" -> BuiltinsExtra.divmod(x, y);
                case "lshift" -> lshift(x, y);
                case "rshift" -> rshift(x, y);
                case "and" -> bitAnd(x, y);
                case "or" -> bitOr(x, y);
                default -> bitXor(x, y);
            };
        } catch (PyException e) {
            if (!e.typeName.equals("TypeError") || sequenceKind(kind)) throw e;
            return NOT_IMPLEMENTED;
        } catch (IllegalArgumentException | ClassCastException e) {
            if (sequenceKind(kind)) throw new PyException("TypeError", "unsupported operand type(s) for " + base + ": '" + kind + "' and '" + typeName(other) + "'");
            return NOT_IMPLEMENTED;
        }
    }

    private static Object compare(String name, Object p, Object[] a, String kind) {
        requireArgs(name, a, 1);
        Object other = NativeSubclass.unwrap(a[0]);
        String otherKind = NativeSubclass.kindOf(other);
        boolean eqOp = name.equals("__eq__") || name.equals("__ne__");
        boolean ok = otherKind != null && family(otherKind).equals(family(kind));
        if (!eqOp && (kind.equals("complex") || kind.equals("dict"))) ok = false;
        if (eqOp && kind.equals("bytes") && other instanceof PyMemoryView) ok = true;
        if (!ok) return NOT_IMPLEMENTED;
        return switch (name) {
            case "__eq__" -> eq(p, other);
            case "__ne__" -> ne(p, other);
            case "__lt__" -> lt(p, other);
            case "__le__" -> le(p, other);
            case "__gt__" -> gt(p, other);
            default -> ge(p, other);
        };
    }

    /** Implementation of special method {@code name} for {@code kind}; {@code self} may be the wrapper instance. */
    static Object dunder(Object self, Object p, String kind, String name, Object[] a) {
        switch (name) {
            case "__eq__": case "__ne__": case "__lt__": case "__le__": case "__gt__": case "__ge__":
                return compare(name, p, a, kind);
            case "__repr__": requireArgs(name, a, 0); return self instanceof PyInstance w && w.payload != null ? NativeSubclass.repr(w) : pyRepr(p);
            case "__str__": requireArgs(name, a, 0);
                if (kind.equals("str")) return p;
                return self instanceof PyInstance w && w.payload != null ? pyRepr(w) : pyStr(p);
            case "__hash__": requireArgs(name, a, 0); return PyRuntime.hash(p);
            case "__init__":
                if (self instanceof PyInstance w && w.payload != null) NativeSubclass.defaultInit(w, a, new LinkedHashMap<>());
                else if (NativeSubclass.mutable(kind)) NativeSubclass.initPayload(kind, p, a, new LinkedHashMap<>());
                return null;
            case "__new__": throw new PyException("TypeError", "__new__ must be called on the type");
            default: break;
        }
        if (name.length() > 4 && name.startsWith("__r") && !name.equals("__reversed__") && ARITH_OPS.contains(name.substring(3, name.length() - 2)))
            return arithmetic(name.substring(3, name.length() - 2), true, p, a, kind);
        if (ARITH_OPS.contains(name.substring(2, name.length() - 2))) return arithmetic(name.substring(2, name.length() - 2), false, p, a, kind);
        return containerOrUnary(self, p, kind, name, a);
    }
    private static final Set<String> ARITH_OPS = Set.of(ARITH.split(" "));

    private static Object containerOrUnary(Object self, Object p, String kind, String name, Object[] a) {
        switch (name) {
            case "__neg__": requireArgs(name, a, 0); return neg(p);
            case "__pos__": requireArgs(name, a, 0); return pos(p);
            case "__abs__": requireArgs(name, a, 0); return abs(p);
            case "__invert__": requireArgs(name, a, 0); return invert(p);
            case "__bool__": requireArgs(name, a, 0); return truth(p);
            case "__int__": requireArgs(name, a, 0); return NativeSubclass.construct("int", new Object[]{p}, new LinkedHashMap<>());
            case "__float__": requireArgs(name, a, 0); return float_(p);
            case "__index__": requireArgs(name, a, 0); return p instanceof Boolean b ? (Object) (b ? 1L : 0L) : p;
            case "__trunc__": case "__floor__": case "__ceil__": {
                requireArgs(name, a, 0);
                if (!(p instanceof Double d)) return p instanceof Boolean b ? (Object) (b ? 1L : 0L) : p;
                if (Double.isNaN(d)) throw new PyException("ValueError", "cannot convert float NaN to integer");
                if (Double.isInfinite(d)) throw new PyException("OverflowError", "cannot convert float infinity to integer");
                double r = name.equals("__trunc__") ? (d < 0 ? Math.ceil(d) : Math.floor(d)) : name.equals("__floor__") ? Math.floor(d) : Math.ceil(d);
                return compact(new java.math.BigDecimal(r).toBigInteger());
            }
            case "__len__": requireArgs(name, a, 0); return len(p);
            case "__getitem__": requireArgs(name, a, 1); return self instanceof PyInstance w && p instanceof Map<?, ?> ? NativeSubclass.dictGet(w, a[0]) : getitem(p, a[0]);
            case "__setitem__": requireArgs(name, a, 2); setitem(p, a[0], a[1]); return null;
            case "__delitem__": requireArgs(name, a, 1); delitem(p, a[0]); return null;
            case "__contains__": requireArgs(name, a, 1); return contains(p, kind.equals("str") || kind.startsWith("byte") ? NativeSubclass.unwrap(a[0]) : a[0]);
            case "__iter__": requireArgs(name, a, 0); return iter(p);
            case "__reversed__": requireArgs(name, a, 0); return reversed(p);
            case "__iadd__": case "__imul__": case "__ior__": case "__iand__": case "__ixor__": case "__isub__": {
                requireArgs(name, a, 1);
                Object result = self instanceof PyInstance w && w.payload != null ? NativeSubclass.inplace(w, name, a[0]) : inplacePlain(p, name, a[0]);
                return result;
            }
            default: throw new PyException("AttributeError", "'" + kind + "' object has no attribute '" + name + "'");
        }
    }

    private static Object inplacePlain(Object p, String op, Object other) {
        return switch (op) {
            case "__iadd__" -> iadd(p, other);
            case "__imul__" -> imul(p, other);
            case "__ior__" -> ibitOr(p, other);
            case "__iand__" -> ibitAnd(p, other);
            case "__ixor__" -> ibitXor(p, other);
            default -> isub(p, other);
        };
    }
}
