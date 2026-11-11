// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The operator methods of {@code weakref.ProxyType} and {@code weakref.CallableProxyType}. A proxy is a PyInstance
 * whose class has {@link PyClass#weakProxy} set; every operation unwraps the referent (raising ReferenceError when it
 * has been collected) and applies the ordinary runtime operation to it, so a proxy behaves exactly like the object
 * it stands for. Attribute access, assignment, deletion and isinstance are forwarded from PyRuntime.
 */
final class WeakrefProxy {
    private WeakrefProxy() {}

    private static final String[][] OPERATORS = {
        {"__add__", "b_add", "s,o"},
        {"__radd__", "r_add", "s,o"},
        {"__sub__", "b_sub", "s,o"},
        {"__rsub__", "r_sub", "s,o"},
        {"__mul__", "b_mul", "s,o"},
        {"__rmul__", "r_mul", "s,o"},
        {"__matmul__", "b_matmul", "s,o"},
        {"__rmatmul__", "r_matmul", "s,o"},
        {"__truediv__", "b_truediv", "s,o"},
        {"__rtruediv__", "r_truediv", "s,o"},
        {"__floordiv__", "b_floordiv", "s,o"},
        {"__rfloordiv__", "r_floordiv", "s,o"},
        {"__mod__", "b_mod", "s,o"},
        {"__rmod__", "r_mod", "s,o"},
        {"__divmod__", "b_divmod", "s,o"},
        {"__rdivmod__", "r_divmod", "s,o"},
        {"__lshift__", "b_lshift", "s,o"},
        {"__rlshift__", "r_lshift", "s,o"},
        {"__rshift__", "b_rshift", "s,o"},
        {"__rrshift__", "r_rshift", "s,o"},
        {"__and__", "b_and", "s,o"},
        {"__rand__", "r_and", "s,o"},
        {"__or__", "b_or", "s,o"},
        {"__ror__", "r_or", "s,o"},
        {"__xor__", "b_xor", "s,o"},
        {"__rxor__", "r_xor", "s,o"},
        {"__iadd__", "i_add", "s,o"},
        {"__isub__", "i_sub", "s,o"},
        {"__imul__", "i_mul", "s,o"},
        {"__imatmul__", "i_matmul", "s,o"},
        {"__iand__", "i_and", "s,o"},
        {"__ior__", "i_or", "s,o"},
        {"__ixor__", "i_xor", "s,o"},
        {"__neg__", "u_neg", "s"},
        {"__pos__", "u_pos", "s"},
        {"__abs__", "u_abs", "s"},
        {"__invert__", "u_invert", "s"},
        {"__bool__", "u_bool", "s"},
        {"__int__", "u_int", "s"},
        {"__float__", "u_float", "s"},
        {"__index__", "u_index", "s"},
        {"__len__", "u_len", "s"},
        {"__iter__", "u_iter", "s"},
        {"__next__", "u_next", "s"},
        {"__reversed__", "u_reversed", "s"},
        {"__str__", "u_str", "s"},
        {"__bytes__", "u_bytes", "s"},
        {"__eq__", "c_eq", "s,o"},
        {"__ne__", "c_ne", "s,o"},
        {"__lt__", "c_lt", "s,o"},
        {"__le__", "c_le", "s,o"},
        {"__gt__", "c_gt", "s,o"},
        {"__ge__", "c_ge", "s,o"},
        {"__contains__", "c_contains", "s,o"},
        {"__getitem__", "c_getitem", "s,o"},
        {"__setitem__", "c_setitem", "s,k,v"},
        {"__delitem__", "c_delitem", "s,k"}
    };

    static void install(PyClass cls, boolean callable) {
        for (String[] op : OPERATORS) method(cls, WeakrefProxy.class, op[0], op[1], op[2].replace("s", "self").replace("o", "other").replace("k", "key").replace("v", "value"));
        method(cls, WeakrefProxy.class, "__pow__", "b_pow", "self,other,mod", defaults("mod", null));
        method(cls, WeakrefProxy.class, "__rpow__", "r_pow", "self,other,mod", defaults("mod", null));
        method(cls, WeakrefProxy.class, "__hash__", "hash", "self");
        method(cls, WeakrefProxy.class, "__repr__", "repr", "self");
        if (callable) methodVarKw(cls, WeakrefProxy.class, "__call__", "call", "self", "args", "kwargs", new PyDict());
        cls.weakProxy = true;
    }

    // ---- unwrapping ------------------------------------------------------------------------------

    static Object referent(PyInstance proxy) {
        Object o = NativeTypes.state(proxy, WeakrefModule.RefState.class, "weakref.ProxyType").get();
        if (o == null) throw new PyException("ReferenceError", "weakly-referenced object no longer exists");
        return o;
    }

    private static Object ref(Object self) { return referent((PyInstance) self); }

    /** An operand that is itself a proxy stands for its referent, as in CPython. */
    private static Object un(Object o) { return o instanceof PyInstance i && i.cls.weakProxy ? referent(i) : o; }

    private static Object divmod(Object a, Object b) {
        return PyRuntime.callBuiltin("divmod", new ArrayList<Object>(List.of(a, b)), new LinkedHashMap<Object, Object>());
    }

    private static Object index(Object a) {
        return PyRuntime.callMethod(a, "__index__", new Object[0]);
    }

    private static Object bytes(Object a) {
        return PyRuntime.callBuiltin("bytes", new ArrayList<Object>(List.of(a)), new LinkedHashMap<Object, Object>());
    }

    // ---- attribute forwarding (called from PyRuntime.getattr/setattr/delattr) --------------------

    static Object getattr(PyInstance proxy, String name) { return PyRuntime.getattr(referent(proxy), name); }

    static void setattr(PyInstance proxy, String name, Object value) { PyRuntime.setattr(referent(proxy), name, value); }

    static void delattr(PyInstance proxy, String name) { PyRuntime.delattr(referent(proxy), name); }

    // ---- methods that are not plain forwards -----------------------------------------------------

    public static Object b_pow(Object s, Object o, Object mod) {
        if (mod == null) return PyRuntime.pow(ref(s), un(o));
        return PyRuntime.callBuiltin("pow", new ArrayList<Object>(List.of(ref(s), un(o), un(mod))), new LinkedHashMap<Object, Object>());
    }

    public static Object r_pow(Object s, Object o, Object mod) {
        if (mod == null) return PyRuntime.pow(un(o), ref(s));
        return PyRuntime.callBuiltin("pow", new ArrayList<Object>(List.of(un(o), ref(s), un(mod))), new LinkedHashMap<Object, Object>());
    }

    public static Object hash(Object s) {
        throw new PyException("TypeError", "unhashable type: 'weakref." + (((PyInstance) s).cls.name) + "'");
    }

    public static Object repr(Object s) {
        WeakrefModule.RefState st = NativeTypes.state(s, WeakrefModule.RefState.class, "weakref.ProxyType");
        Object o = st.get();
        String at = "0x" + Long.toHexString((Long) PyRuntime.id(s));
        if (o == null) return "<weakproxy at " + at + "; dead>";
        return "<weakproxy at " + at + "; to '" + PyRuntime.typeName(o) + "' at 0x" + Long.toHexString((Long) PyRuntime.id(o)) + ">";
    }

    public static Object call(Object s, Object args, Object kwargs) {
        @SuppressWarnings("unchecked") Map<Object, Object> kw = (Map<Object, Object>) kwargs;
        return PyRuntime.callFunction(ref(s), new ArrayList<Object>(((PyTuple) args).items), new LinkedHashMap<Object, Object>(kw));
    }

    // ---- plain forwards --------------------------------------------------------------------------

    public static Object b_add(Object s, Object o) { return PyRuntime.add(ref(s),un(o)); }
    public static Object r_add(Object s, Object o) { return PyRuntime.add(un(o),ref(s)); }
    public static Object b_sub(Object s, Object o) { return PyRuntime.sub(ref(s),un(o)); }
    public static Object r_sub(Object s, Object o) { return PyRuntime.sub(un(o),ref(s)); }
    public static Object b_mul(Object s, Object o) { return PyRuntime.mul(ref(s),un(o)); }
    public static Object r_mul(Object s, Object o) { return PyRuntime.mul(un(o),ref(s)); }
    public static Object b_matmul(Object s, Object o) { return BuiltinsExtra.matmul(ref(s),un(o)); }
    public static Object r_matmul(Object s, Object o) { return BuiltinsExtra.matmul(un(o),ref(s)); }
    public static Object b_truediv(Object s, Object o) { return PyRuntime.truediv(ref(s),un(o)); }
    public static Object r_truediv(Object s, Object o) { return PyRuntime.truediv(un(o),ref(s)); }
    public static Object b_floordiv(Object s, Object o) { return PyRuntime.floordiv(ref(s),un(o)); }
    public static Object r_floordiv(Object s, Object o) { return PyRuntime.floordiv(un(o),ref(s)); }
    public static Object b_mod(Object s, Object o) { return PyRuntime.mod(ref(s),un(o)); }
    public static Object r_mod(Object s, Object o) { return PyRuntime.mod(un(o),ref(s)); }
    public static Object b_divmod(Object s, Object o) { return divmod(ref(s),un(o)); }
    public static Object r_divmod(Object s, Object o) { return divmod(un(o),ref(s)); }
    public static Object b_lshift(Object s, Object o) { return PyRuntime.lshift(ref(s),un(o)); }
    public static Object r_lshift(Object s, Object o) { return PyRuntime.lshift(un(o),ref(s)); }
    public static Object b_rshift(Object s, Object o) { return PyRuntime.rshift(ref(s),un(o)); }
    public static Object r_rshift(Object s, Object o) { return PyRuntime.rshift(un(o),ref(s)); }
    public static Object b_and(Object s, Object o) { return PyRuntime.bitAnd(ref(s),un(o)); }
    public static Object r_and(Object s, Object o) { return PyRuntime.bitAnd(un(o),ref(s)); }
    public static Object b_or(Object s, Object o) { return PyRuntime.bitOr(ref(s),un(o)); }
    public static Object r_or(Object s, Object o) { return PyRuntime.bitOr(un(o),ref(s)); }
    public static Object b_xor(Object s, Object o) { return PyRuntime.bitXor(ref(s),un(o)); }
    public static Object r_xor(Object s, Object o) { return PyRuntime.bitXor(un(o),ref(s)); }
    public static Object i_add(Object s, Object o) { return PyRuntime.iadd(ref(s), un(o)); }
    public static Object i_sub(Object s, Object o) { return PyRuntime.isub(ref(s), un(o)); }
    public static Object i_mul(Object s, Object o) { return PyRuntime.imul(ref(s), un(o)); }
    public static Object i_matmul(Object s, Object o) { return BuiltinsExtra.imatmul(ref(s), un(o)); }
    public static Object i_and(Object s, Object o) { return PyRuntime.ibitAnd(ref(s), un(o)); }
    public static Object i_or(Object s, Object o) { return PyRuntime.ibitOr(ref(s), un(o)); }
    public static Object i_xor(Object s, Object o) { return PyRuntime.ibitXor(ref(s), un(o)); }
    public static Object u_neg(Object s) { return PyRuntime.neg(ref(s)); }
    public static Object u_pos(Object s) { return PyRuntime.pos(ref(s)); }
    public static Object u_abs(Object s) { return PyRuntime.abs(ref(s)); }
    public static Object u_invert(Object s) { return PyRuntime.invert(ref(s)); }
    public static Object u_bool(Object s) { return (Object) Boolean.valueOf(PyRuntime.truth(ref(s))); }
    public static Object u_int(Object s) { return PyRuntime.int_(ref(s)); }
    public static Object u_float(Object s) { return PyRuntime.float_(ref(s)); }
    public static Object u_index(Object s) { return index(ref(s)); }
    public static Object u_len(Object s) { return PyRuntime.len(ref(s)); }
    public static Object u_iter(Object s) { return PyRuntime.iter(ref(s)); }
    public static Object u_next(Object s) { return PyRuntime.next_(ref(s)); }
    public static Object u_reversed(Object s) { return PyRuntime.reversed(ref(s)); }
    public static Object u_str(Object s) { return PyRuntime.str_(ref(s)); }
    public static Object u_bytes(Object s) { return bytes(ref(s)); }
    public static Object c_eq(Object s, Object o) { return PyRuntime.eq(ref(s), un(o)); }
    public static Object c_ne(Object s, Object o) { return PyRuntime.ne(ref(s), un(o)); }
    public static Object c_lt(Object s, Object o) { return PyRuntime.lt(ref(s), un(o)); }
    public static Object c_le(Object s, Object o) { return PyRuntime.le(ref(s), un(o)); }
    public static Object c_gt(Object s, Object o) { return PyRuntime.gt(ref(s), un(o)); }
    public static Object c_ge(Object s, Object o) { return PyRuntime.ge(ref(s), un(o)); }
    public static Object c_contains(Object s, Object o) { return PyRuntime.contains(ref(s), o); }
    public static Object c_getitem(Object s, Object o) { return PyRuntime.getitem(ref(s), o); }
    public static Object c_setitem(Object s, Object k, Object v) { PyRuntime.setitem(ref(s), k, v); return null; }
    public static Object c_delitem(Object s, Object k) { PyRuntime.delitem(ref(s), k); return null; }
}
