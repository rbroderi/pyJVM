// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** X | Y for types, None, type parameters, generic aliases and sentinels (types.UnionType / typing.Union). */
final class TypeParamsUnion extends TypeParamsObject {
    final ArrayList<Object> args;

    private TypeParamsUnion(ArrayList<Object> args) { this.args = args; }

    private static final PyBuiltinType NONE_TYPE = (PyBuiltinType) PyRuntime.builtinType("NoneType");

    private static boolean isNone(Object value) { return value == null || value == NONE_TYPE || NONE_TYPE.equals(value); }

    private static boolean unionable(Object value) {
        if (value == null || value instanceof PyBuiltinType || value instanceof PyClass) return true;
        if (value instanceof TypeParamsVar v) return v.kind != TypeParamsVar.TYPEVARTUPLE;
        return value instanceof TypeParamsGeneric || value instanceof TypeParamsUnion || value instanceof TypeParamsAlias
            || value instanceof TypeParamsSentinel;
    }

    /** a | b when both are type-like (at least one a native type-system object, or both classes/None), else null. */
    static Object tryOr(Object a, Object b) {
        if (!unionable(a) || !unionable(b)) return null;
        if (a == null && b == null) return null;
        return of(List.of(wrap(a), wrap(b)));
    }

    private static Object wrap(Object value) { return value == null ? NONE_TYPE : value; }

    /** The union of the arguments: flattened, duplicates removed, a single survivor returned as itself. */
    static Object of(List<Object> items) {
        ArrayList<Object> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof TypeParamsUnion u) {
                for (Object inner : u.args) add(out, inner);
            } else {
                add(out, wrap(item));
            }
        }
        if (out.size() == 1) return out.get(0);
        return new TypeParamsUnion(out);
    }

    private static void add(List<Object> out, Object item) {
        for (Object existing : out) if (existing == item || existing.equals(item)) return;
        out.add(item);
    }

    String typeName() { return LangVersion.atLeast(3, 14) ? "Union" : "UnionType"; }

    String repr() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < args.size(); i++) out.append(i == 0 ? "" : " | ").append(isNone(args.get(i)) ? "None" : TypeParamsGeneric.reprItem(args.get(i)));
        return out.toString();
    }

    Object getattr(String name) {
        switch (name) {
            case "__args__": return tuple(args);
            case "__parameters__": { ArrayList<Object> out = new ArrayList<>(); TypeParamsGeneric.collect(args, out); return tuple(out); }
            default: return super.getattr(name);
        }
    }

    Object getitem(Object key) {
        ArrayList<Object> free = new ArrayList<>();
        TypeParamsGeneric.collect(args, free);
        if (free.isEmpty()) throw new PyException("TypeError", "Union[...] is not a generic class");
        List<Object> given = key instanceof PyTuple t ? t.items : List.of(key);
        if (given.size() != free.size()) throw new PyException("TypeError", "Too " + (given.size() > free.size() ? "many" : "few") + " arguments for " + repr());
        IdentityHashMap<Object, Object> map = new IdentityHashMap<>();
        for (int i = 0; i < free.size(); i++) map.put(free.get(i), given.get(i));
        return of(TypeParamsGeneric.substitute(args, map).items);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TypeParamsUnion u) || u.args.size() != args.size()) return false;
        for (Object a : args) if (!u.args.contains(a)) return false;
        return true;
    }
    @Override public int hashCode() { int h = 0; for (Object a : args) h += a.hashCode(); return h; }
    Object pyHash() { return (long) hashCode(); }
}
