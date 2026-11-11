// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A subscripted generic: {@code Generic[T]}, {@code Box[int]}, {@code list[int]} or {@code Alias[int]}.
 * {@code builtinStyle} selects types.GenericAlias behaviour (list[int], aliases of the type statement) over the
 * typing._GenericAlias behaviour used for user classes deriving from Generic.
 */
final class TypeParamsGeneric extends TypeParamsObject {
    /** typing.Generic, appended to the bases of every PEP 695 generic class. */
    static final PyClass GENERIC = makeGeneric();

    final Object origin;
    final PyTuple args;
    final boolean builtinStyle;

    TypeParamsGeneric(Object origin, PyTuple args, boolean builtinStyle) {
        this.origin = origin;
        this.args = args;
        this.builtinStyle = builtinStyle;
    }

    private static PyClass makeGeneric() {
        PyClass cls = new PyClass("typing", "Generic");
        cls.finalized = true;
        return cls;
    }

    // ---- subscription of classes ---------------------------------------------------------------

    static boolean isGeneric(PyClass cls) { return cls.mro.contains(GENERIC); }

    private static PyTuple keyArgs(Object key) {
        if (key instanceof PyTuple t) return tuple(t.items);
        ArrayList<Object> one = new ArrayList<>();
        one.add(key);
        return tuple(one);
    }

    /** cls[key] for a user class: __class_getitem__ when defined, else the Generic machinery, else a TypeError. */
    static Object subscriptClass(PyClass cls, Object key) {
        Object hook = cls.lookupAttr("__class_getitem__");
        if (hook != PyRuntime.MISSING) {
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(key);
            return PyRuntime.callFunction(PyRuntime.descriptorGet(hook, null, cls), arguments, new LinkedHashMap<Object, Object>());
        }
        if (!isGeneric(cls)) throw new PyException("TypeError", "type '" + cls.name + "' is not subscriptable");
        if (cls == GENERIC) {
            PyTuple a = keyArgs(key);
            if (a.items.isEmpty()) throw new PyException("TypeError", "Parameter list to Generic[...] cannot be empty");
        }
        return new TypeParamsGeneric(cls, keyArgs(key), false);
    }

    private static final Set<String> SUBSCRIPTABLE = Set.of("list", "dict", "tuple", "set", "frozenset", "type");

    /** list[int] and friends. */
    static Object subscriptBuiltin(PyBuiltinType type, Object key) {
        if (!SUBSCRIPTABLE.contains(type.name)) throw new PyException("TypeError", "type '" + type.name + "' is not subscriptable");
        return new TypeParamsGeneric(type, keyArgs(key), true);
    }

    static Object genericBase(PyTuple params) { return new TypeParamsGeneric(GENERIC, params, false); }

    /** The class objects a class statement actually derives from (PEP 560 __mro_entries__). */
    static Object resolveBases(Object bases) {
        PyTuple out = new PyTuple();
        for (Object base : ((PyTuple) bases).items) {
            Object resolved = base instanceof TypeParamsGeneric g && g.origin instanceof PyClass ? g.origin : base;
            if (!out.items.contains(resolved)) out.items.add(resolved);
        }
        return out;
    }

    // ---- repr ----------------------------------------------------------------------------------

    static String reprItem(Object item) {
        if (item == null) return "None";
        if (item == PyRuntime.ELLIPSIS) return "...";
        if (item instanceof PyBuiltinType t) return t.name.equals("NoneType") ? "None" : t.name;
        if (item instanceof PyClass c) return c.moduleName.equals("builtins") ? c.qualname : c.moduleName + "." + c.qualname;
        if (item instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) out.append(i == 0 ? "" : ", ").append(reprItem(list.get(i)));
            return out.append("]").toString();
        }
        return PyRuntime.pyRepr(item);
    }

    private String originRepr() {
        if (origin instanceof TypeParamsAlias alias && LangVersion.atLeast(3, 14)) return PyRuntime.pyStr(alias.getattr("__module__")) + "." + alias.name;
        return reprItem(origin);
    }

    String typeName() { return builtinStyle ? "GenericAlias" : "_GenericAlias"; }

    String repr() {
        StringBuilder out = new StringBuilder(originRepr()).append("[");
        if (args.items.isEmpty()) out.append("()");
        for (int i = 0; i < args.items.size(); i++) out.append(i == 0 ? "" : ", ").append(reprItem(args.items.get(i)));
        return out.append("]").toString();
    }

    // ---- attributes ----------------------------------------------------------------------------

    /** The type parameters left free in the arguments, in order of first appearance. */
    PyTuple parameters() {
        ArrayList<Object> out = new ArrayList<>();
        collect(args.items, out);
        return tuple(out);
    }

    static void collect(List<Object> items, List<Object> out) {
        for (Object item : items) {
            if (item instanceof TypeParamsVar) {
                if (!out.contains(item)) out.add(item);
            } else if (item instanceof TypeParamsGeneric g) {
                collect(g.parameters().items, out);
            } else if (item instanceof TypeParamsUnion u) {
                collect(u.args, out);
            } else if (item instanceof Unpack u) {
                collect(List.of(u.inner), out);
            } else if (item instanceof List<?> nested) {
                @SuppressWarnings("unchecked") List<Object> typed = (List<Object>) nested;
                collect(typed, out);
            }
        }
    }

    Object getattr(String name) {
        switch (name) {
            case "__origin__": return origin;
            case "__args__": return args;
            case "__parameters__": return parameters();
            case "__unpacked__": return false;
            default: break;
        }
        if (!name.startsWith("__") || name.equals("__name__") || name.equals("__qualname__") || name.equals("__module__")) {
            if (!name.equals("__class__")) {
                try { return PyRuntime.getattr(origin, name); } catch (PyException e) { if (!e.typeName.equals("AttributeError")) throw e; }
            }
        }
        return super.getattr(name);
    }

    // ---- substitution --------------------------------------------------------------------------

    Object getitem(Object key) {
        PyTuple free = parameters();
        if (free.items.isEmpty()) throw new PyException("TypeError", reprItem(origin) + " is not a generic class");
        PyTuple given = keyArgs(key);
        if (given.items.size() != free.items.size())
            throw new PyException("TypeError", "Too " + (given.items.size() > free.items.size() ? "many" : "few") + " arguments for " + repr()
                + "; actual " + given.items.size() + ", expected " + free.items.size());
        IdentityHashMap<Object, Object> map = new IdentityHashMap<>();
        for (int i = 0; i < free.items.size(); i++) map.put(free.items.get(i), given.items.get(i));
        return new TypeParamsGeneric(origin, substitute(args.items, map), builtinStyle);
    }

    static PyTuple substitute(List<Object> items, IdentityHashMap<Object, Object> map) {
        ArrayList<Object> out = new ArrayList<>();
        for (Object item : items) out.add(substituteOne(item, map));
        return tuple(out);
    }

    static Object substituteOne(Object item, IdentityHashMap<Object, Object> map) {
        if (map.containsKey(item)) return map.get(item);
        if (item instanceof TypeParamsGeneric g) return new TypeParamsGeneric(g.origin, substitute(g.args.items, map), g.builtinStyle);
        if (item instanceof TypeParamsUnion u) return TypeParamsUnion.of(substitute(u.args, map).items);
        if (item instanceof List<?> nested) {
            @SuppressWarnings("unchecked") List<Object> typed = (List<Object>) nested;
            return new ArrayList<Object>(substitute(typed, map).items);
        }
        return item;
    }

    // ---- calling: Box[int](...) instantiates Box ------------------------------------------------

    Object call(List<Object> arguments, java.util.Map<Object, Object> keywords) {
        Object result = PyRuntime.callFunction(origin, arguments, keywords);
        if (!builtinStyle && result instanceof PyInstance instance && !instance.fields.containsKey("__orig_class__")) {
            instance.fields.put("__orig_class__", this);
        }
        return result;
    }

    @Override public boolean equals(Object other) {
        return other instanceof TypeParamsGeneric g && builtinStyle == g.builtinStyle && Objects.equals(origin, g.origin)
            && args.equals(g.args);
    }
    @Override public int hashCode() { return Objects.hashCode(origin) * 31 + args.hashCode(); }
    Object pyHash() { return (long) hashCode(); }
}
