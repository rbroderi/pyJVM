package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime support for lowered {@code match} statements (see pyjvm315/patma.py).
 * Every helper takes and returns plain objects so generated code can call them directly.
 */
public final class PatternMatch {
    private PatternMatch() {}

    /** Returned by {@link #get} and {@link #attr} when a key/attribute is absent. */
    public static final Object MISSING = new Object() {
        @Override public String toString() { return "<missing>"; }
    };

    /** Types whose single positional sub-pattern matches the subject itself. */
    private static final Set<String> SELF_MATCHING = Set.of(
        "bool", "bytearray", "bytes", "dict", "float", "frozenset", "int", "list", "set", "str", "tuple");

    /** Sequence patterns match list, tuple and range, but never str, bytes or bytearray. */
    public static Object isSeq(Object x) {
        return x instanceof List<?> || x instanceof PyTuple || x instanceof PyRange;
    }

    public static Object isMap(Object x) { return x instanceof Map<?,?>; }

    /** {@code subject.get(key, MISSING)}, without triggering __missing__ or default factories. */
    public static Object get(Object subject, Object key) {
        return PyRuntime.callMethod(subject, "get", new Object[]{key, MISSING});
    }

    public static Object ok(Object value) { return value != MISSING; }

    /** The subject's mapping with the already-matched keys removed (a new dict). */
    public static Object rest(Object subject, Object keys) {
        LinkedHashMap<Object,Object> copy = new LinkedHashMap<>((Map<?,?>) subject);
        for (Object key : PyRuntime.iterable(keys)) PyRuntime.callMethod(copy, "pop", new Object[]{key, null});
        return copy;
    }

    public static Object isClass(Object subject, Object cls) {
        if (!(cls instanceof PyClass) && !(cls instanceof PyBuiltinType))
            throw new PyException("TypeError", "called match pattern must be a class");
        return PyRuntime.isInstance(subject, cls);
    }

    /** The i-th positional sub-pattern's value out of n, via {@code __match_args__}; MISSING if the attribute is absent. */
    public static Object pos(Object subject, Object cls, Object index, Object count) {
        int i = ((Long) index).intValue(), n = ((Long) count).intValue();
        String name = cls instanceof PyBuiltinType t ? t.name : ((PyClass) cls).name;
        if (cls instanceof PyBuiltinType t && SELF_MATCHING.contains(t.name)) {
            if (n > 1) throw new PyException("TypeError", name + "() accepts 1 positional sub-pattern (" + n + " given)");
            return subject;
        }
        Object margs = MISSING;
        try { margs = PyRuntime.getattr(cls, "__match_args__"); }
        catch (PyException e) { if (!e.typeName.equals("AttributeError")) throw e; }
        if (!(margs instanceof PyTuple tuple)) {
            if (margs == MISSING)
                throw new PyException("TypeError", name + "() accepts 0 positional sub-patterns (" + n + " given)");
            throw new PyException("TypeError", cls + ".__match_args__ must be a tuple (got " + PyRuntime.typeName(margs) + ")");
        }
        if (n > tuple.items.size())
            throw new PyException("TypeError", name + "() accepts " + tuple.items.size() + " positional sub-pattern"
                + (tuple.items.size() == 1 ? "" : "s") + " (" + n + " given)");
        Object attr = tuple.items.get(i);
        if (!(attr instanceof String s))
            throw new PyException("TypeError", "__match_args__ elements must be strings (got " + PyRuntime.typeName(attr) + ")");
        return attr(subject, s);
    }

    public static Object attr(Object subject, Object name) {
        try { return PyRuntime.getattr(subject, name); }
        catch (PyException e) { if (e.typeName.equals("AttributeError")) return MISSING; throw e; }
    }
}
