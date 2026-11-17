// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The builtin module {@code _operator}: the functions that {@code operator.py} re-exports ({@code from _operator import *}),
 * {@code _compare_digest} (used by {@code hmac}), and the {@code itemgetter}/{@code attrgetter}/{@code methodcaller} types
 * ({@link OperatorGetters}). Docstrings and text signatures live in {@link OperatorDocs}; argument-count and error texts
 * were measured on CPython 3.13 / 3.14 / 3.15 (3.14 adds {@code is_none}/{@code is_not_none}; the {@code __length_hint__}
 * messages change in 3.15).
 */
final class OperatorModule {
    private OperatorModule() {}

    private static final String ME = OperatorModule.class.getName();

    private interface F1 { Object ap(Object a); }
    private interface F2 { Object ap(Object a, Object b); }

    private static boolean l14() { return LangVersion.atLeast(3, 14); }
    private static boolean l15() { return LangVersion.atLeast(3, 15); }
    private static PyException err(String type, String msg) { return new PyException(type, msg); }

    static PyModule create() {
        PyModule m = new PyModule("_operator", ME);
        m.attrs.put("__doc__", OperatorDocs.MODULE_DOC);
        Map<String, F1> unary = unary();
        Map<String, F2> binary = binary();
        for (String[] f : OperatorDocs.FUNCS) {
            String name = f[0];
            if (name.endsWith("_none") && !l14()) continue;
            NativeFunction.Body body;
            if (unary.containsKey(name)) body = methO(name, unary.get(name));
            else if (binary.containsKey(name)) body = fast(name, 2, 2, false, a -> binary.get(name).ap(a.get(0), a.get(1)));
            else body = other(name);
            NativeFunction nf = new NativeFunction(name, body);
            nf.attrs.put("__doc__", f[2]);
            nf.attrs.put("__text_signature__", f[1]);
            nf.attrs.put("__module__", "_operator");
            nf.attrs.put("__self__", m);
            m.attrs.put(name, nf);
        }
        OperatorGetters.install(m);
        return m;
    }

    // ------------------------------------------------------------------------------------------------ calling conventions

    private static void noKw(String name, Map<Object, Object> kw) {
        if (!kw.isEmpty()) throw err("TypeError", "_operator." + name + "() takes no keyword arguments");
    }

    /** {@code _PyArg_CheckPositional}; {@code max < 0} means unbounded. */
    private static void positional(String name, int n, int min, int max) {
        if (n >= min && (max < 0 || n <= max)) return;
        boolean low = n < min;
        int bound = low ? min : max;
        String qual = min == max ? "" : low ? "at least " : "at most ";
        throw err("TypeError", name + " expected " + qual + bound + " argument" + (bound == 1 ? "" : "s") + ", got " + n);
    }

    private static NativeFunction.Body methO(String name, F1 f) {
        return (a, k) -> {
            noKw(name, k);
            if (a.size() != 1) throw err("TypeError", "_operator." + name + "() takes exactly one argument (" + a.size() + " given)");
            return f.ap(a.get(0));
        };
    }

    private interface Fast { Object ap(List<Object> a); }

    private static NativeFunction.Body fast(String name, int min, int max, boolean kwOk, Fast f) {
        return (a, k) -> {
            if (!kwOk) noKw(name, k);
            positional(name, a.size(), min, max);
            return f.ap(a);
        };
    }

    private static NativeFunction.Body other(String name) {
        return switch (name) {
            case "setitem" -> fast(name, 3, 3, false, a -> { PyRuntime.setitem(a.get(0), a.get(1), a.get(2)); return null; });
            case "call" -> (a, k) -> {
                positional("call", a.size(), 1, -1);
                return PyRuntime.callFunction(a.get(0), new ArrayList<>(a.subList(1, a.size())), new LinkedHashMap<>(k));
            };
            case "length_hint" -> fast(name, 1, 2, false, OperatorModule::lengthHint);
            case "_compare_digest" -> fast(name, 2, 2, false, a -> compareDigest(a.get(0), a.get(1)));
            default -> throw new IllegalStateException("_operator." + name + " has no implementation");
        };
    }

    // ------------------------------------------------------------------------------------------------ operator tables

    private static Map<String, F1> unary() {
        Map<String, F1> u = new HashMap<>();
        u.put("abs", PyRuntime::abs);
        u.put("neg", PyRuntime::neg);
        u.put("pos", PyRuntime::pos);
        u.put("invert", PyRuntime::invert);
        u.put("inv", PyRuntime::invert);
        u.put("not_", a -> !PyRuntime.truth(a));
        u.put("truth", a -> PyRuntime.truth(a));
        u.put("index", a -> PyRuntime.compact(BuiltinsExtra.rangeIndex(a)));
        u.put("is_none", a -> a == null);
        u.put("is_not_none", a -> a != null);
        return u;
    }

    private static Map<String, F2> binary() {
        Map<String, F2> b = new HashMap<>();
        b.put("add", PyRuntime::add);
        b.put("sub", PyRuntime::sub);
        b.put("mul", PyRuntime::mul);
        b.put("truediv", PyRuntime::truediv);
        b.put("floordiv", PyRuntime::floordiv);
        b.put("mod", PyRuntime::mod);
        b.put("pow", PyRuntime::pow);
        b.put("lshift", PyRuntime::lshift);
        b.put("rshift", PyRuntime::rshift);
        b.put("and_", PyRuntime::bitAnd);
        b.put("or_", PyRuntime::bitOr);
        b.put("xor", PyRuntime::bitXor);
        b.put("matmul", BuiltinsExtra::matmul);
        b.put("eq", PyRuntime::eq);
        b.put("ne", PyRuntime::ne);
        b.put("lt", PyRuntime::lt);
        b.put("le", PyRuntime::le);
        b.put("gt", PyRuntime::gt);
        b.put("ge", PyRuntime::ge);
        b.put("is_", PyRuntime::is_);
        b.put("is_not", PyRuntime::is_not);
        b.put("concat", OperatorModule::concat);
        b.put("iconcat", OperatorModule::iconcat);
        b.put("contains", PyRuntime::contains);
        b.put("getitem", PyRuntime::getitem);
        b.put("delitem", (a, i) -> { PyRuntime.delitem(a, i); return null; });
        b.put("countOf", (a, x) -> search(a, x, false));
        b.put("indexOf", (a, x) -> search(a, x, true));
        b.put("iadd", ip("+", PyRuntime::iadd));
        b.put("isub", ip("-", PyRuntime::isub));
        b.put("imul", ip("*", PyRuntime::imul));
        b.put("ipow", ip("**", PyRuntime::ipow));
        b.put("iand", ip("&", PyRuntime::ibitAnd));
        b.put("ior", ip("|", PyRuntime::ibitOr));
        b.put("ixor", ip("^", PyRuntime::ibitXor));
        b.put("imatmul", ip("@", BuiltinsExtra::imatmul));
        b.put("ifloordiv", ip("//", special("__ifloordiv__", PyRuntime::floordiv)));
        b.put("itruediv", ip("/", special("__itruediv__", PyRuntime::truediv)));
        b.put("imod", ip("%", special("__imod__", PyRuntime::mod)));
        b.put("ilshift", ip("<<", special("__ilshift__", PyRuntime::lshift)));
        b.put("irshift", ip(">>", special("__irshift__", PyRuntime::rshift)));
        return b;
    }

    /** An in-place operator reports its own symbol ({@code +=}) where the plain one reports {@code +}. */
    private static F2 ip(String op, F2 f) {
        return (a, b) -> {
            try { return f.ap(a, b); }
            catch (PyException e) {
                String msg = e.getMessage();
                String prefix = "unsupported operand type(s) for " + op + ":";
                if ("TypeError".equals(e.typeName) && msg != null && msg.startsWith(prefix)) {
                    throw err("TypeError", "unsupported operand type(s) for " + op + "=:" + msg.substring(prefix.length()));
                }
                throw e;
            }
        };
    }

    /** An in-place dunder defined by the user's class, tried before the plain operator. */
    private static F2 special(String dunder, F2 f) {
        return (a, b) -> {
            if (a instanceof PyInstance i) {
                PyMethod method = i.cls.lookupMethod(dunder);
                if (method != null) {
                    Object v = PyRuntime.invoke(i, method, new Object[]{b});
                    if (v != PyRuntime.NOT_IMPLEMENTED) return v;
                }
            }
            return f.ap(a, b);
        };
    }

    // ------------------------------------------------------------------------------------------------ sequences

    private static final Set<String> SEQUENCE_BASES = Set.of("str", "bytes", "bytearray", "list", "tuple", "range", "memoryview");

    /** {@code PySequence_Check}: sequences, but not mappings. */
    private static boolean isSequence(Object o) {
        for (String base : SEQUENCE_BASES) {
            if (Boolean.TRUE.equals(PyRuntime.isInstance(o, PyRuntime.builtinType(base)))) return true;
        }
        String t = PyRuntime.typeName(o);
        if (t.equals("array") || t.equals("deque")) return true;
        if (o instanceof PyInstance i && i.cls.lookupMethod("__getitem__") != null) {
            return !Boolean.TRUE.equals(PyRuntime.isInstance(o, PyRuntime.builtinType("dict")));
        }
        return false;
    }

    private static Object concat(Object a, Object b) {
        if (!isSequence(a)) throw err("TypeError", "'" + PyRuntime.typeName(a) + "' object can't be concatenated");
        try { return PyRuntime.add(a, b); }
        catch (IllegalArgumentException e) { throw err("TypeError", "'" + PyRuntime.typeName(a) + "' object can't be concatenated"); }
    }

    private static Object iconcat(Object a, Object b) {
        if (!isSequence(a)) throw err("TypeError", "'" + PyRuntime.typeName(a) + "' object can't be concatenated");
        try { return PyRuntime.iadd(a, b); }
        catch (IllegalArgumentException e) { throw err("TypeError", "'" + PyRuntime.typeName(a) + "' object can't be concatenated"); }
    }

    /** {@code countOf} / {@code indexOf}: identity or equality of each item of an iterable. */
    private static Object search(Object seq, Object needle, boolean index) {
        Object it;
        try { it = PyRuntime.iter(seq); }
        catch (PyException e) {
            String type = PyRuntime.typeName(seq);
            if ("TypeError".equals(e.typeName) && ("'" + type + "' object is not iterable").equals(e.getMessage())) {
                throw err("TypeError", "argument of type '" + type + "' is not iterable");
            }
            throw e;
        }
        long count = 0, position = 0;
        while (true) {
            Object x;
            try { x = PyLazyIterator.pull(it); }
            catch (NoSuchElementException end) { break; }
            if (x == needle || PyRuntime.truth(PyRuntime.eq(x, needle))) {
                if (index) return position;
                count++;
            }
            position++;
        }
        if (index) throw err("ValueError", "sequence.index(x): x not in sequence");
        return count;
    }

    // ------------------------------------------------------------------------------------------------ length_hint

    private static Object lengthHint(List<Object> a) {
        BigInteger d = a.size() > 1 ? BuiltinsExtra.rangeIndex(a.get(1)) : BigInteger.ZERO;
        if (d.bitLength() > 63) throw err("OverflowError", "Python int too large to convert to C ssize_t");
        Object o = a.get(0);
        try { return PyRuntime.len(o); }
        catch (PyException e) { if (!"TypeError".equals(e.typeName)) throw e; }
        catch (IllegalArgumentException e) { /* generic runtime "object has no len()" */ }
        Object missing = new Object();
        Object hint = PyRuntime.getattrDefault(o, "__length_hint__", missing);
        if (hint == missing) return PyRuntime.compact(d);
        Object r = PyRuntime.callFunction(hint, new ArrayList<Object>(), new LinkedHashMap<Object, Object>());
        if (r == PyRuntime.NOT_IMPLEMENTED) return PyRuntime.compact(d);
        String type = PyRuntime.typeName(o);
        if (!PyRuntime.isIntLike(r)) {
            throw err("TypeError", l15() ? type + ".__length_hint__() must return an int, not " + PyRuntime.typeName(r)
                : "__length_hint__ must be an integer, not " + PyRuntime.typeName(r));
        }
        BigInteger n = PyRuntime.bigInt(r);
        if (n.bitLength() > 63) throw err("OverflowError", "Python int too large to convert to C ssize_t");
        if (n.signum() < 0) {
            throw err("ValueError", l15() ? type + ".__length_hint__() must return a non-negative int" : "__length_hint__() should return >= 0");
        }
        return PyRuntime.compact(n);
    }

    // ------------------------------------------------------------------------------------------------ _compare_digest

    private static Object compareDigest(Object a, Object b) {
        Object ua = NativeSubclass.unwrap(a), ub = NativeSubclass.unwrap(b);
        byte[] x, y;
        if (ua instanceof String sa && ub instanceof String sb) {
            if (!ascii(sa) || !ascii(sb)) throw err("TypeError", "comparing strings with non-ASCII characters is not supported");
            x = sa.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            y = sb.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        } else {
            PyException ea = null, eb = null;
            x = null; y = null;
            try { x = IoUtil.bytesLike(a); } catch (PyException e) { if (!"TypeError".equals(e.typeName)) throw e; ea = e; }
            try { y = IoUtil.bytesLike(b); } catch (PyException e) { if (!"TypeError".equals(e.typeName)) throw e; eb = e; }
            if (ea != null && eb != null) {
                throw err("TypeError", "unsupported operand types(s) or combination of types: '" + PyRuntime.typeName(a) + "' and '" + PyRuntime.typeName(b) + "'");
            }
            if (ea != null) throw ea;
            if (eb != null) throw eb;
        }
        // constant time in the length of the right operand, like CPython
        byte[] left = x.length == y.length ? x : y;
        int diff = x.length == y.length ? 0 : 1;
        for (int i = 0; i < y.length; i++) diff |= left[i] ^ y[i];
        return diff == 0;
    }

    private static boolean ascii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 128) return false;
        return true;
    }
}
