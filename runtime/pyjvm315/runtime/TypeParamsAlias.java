// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.List;

/** typing.TypeAliasType: the object a {@code type X[T] = ...} statement binds. The value is evaluated on first use. */
final class TypeParamsAlias extends TypeParamsObject {
    final String name;
    final PyTuple typeParams;
    private final Object valueThunk;
    private Object value;
    private boolean valueDone;
    private Object module;
    private PyTuple parameters;

    TypeParamsAlias(String name, PyTuple typeParams, Object valueThunk) {
        this.name = name;
        this.typeParams = typeParams;
        this.valueThunk = valueThunk;
        Object module;
        try {
            module = PyRuntime.getattr(valueThunk, "__module__");
        } catch (PyException e) {
            module = "__main__";
        }
        this.module = module;
    }

    String typeName() { return "TypeAliasType"; }
    String repr() { return name; }

    private Object value() {
        if (!valueDone) {
            value = force(valueThunk);
            valueDone = true;
        }
        return value;
    }

    /** Like Generic.__parameters__: a TypeVarTuple appears unpacked. */
    private PyTuple parameters() {
        if (parameters == null) {
            ArrayList<Object> out = new ArrayList<>();
            for (Object p : typeParams.items) {
                out.add(p instanceof TypeParamsVar v && v.kind == TypeParamsVar.TYPEVARTUPLE ? new Unpack(p) : p);
            }
            parameters = tuple(out);
        }
        return parameters;
    }

    Object getattr(String attr) {
        switch (attr) {
            case "__name__", "__qualname__": return name;
            case "__module__": return module;
            case "__type_params__": return typeParams;
            case "__parameters__": return parameters();
            case "__value__": return value();
            default: return super.getattr(attr);
        }
    }

    void setattr(String attr, Object newValue) {
        if (attr.equals("__module__")) { module = newValue; return; }
        switch (attr) {
            case "__name__", "__qualname__", "__type_params__", "__parameters__", "__value__":
                throw new PyException("AttributeError", "readonly attribute");
            default:
                super.setattr(attr, newValue);
        }
    }

    void delattr(String attr) {
        switch (attr) {
            case "__name__", "__qualname__", "__type_params__", "__parameters__", "__value__", "__module__":
                throw new PyException("AttributeError", "readonly attribute");
            default:
                super.delattr(attr);
        }
    }

    Object getitem(Object key) {
        if (typeParams.items.isEmpty()) throw new PyException("TypeError", "Only generic type aliases are subscriptable");
        List<Object> items = key instanceof PyTuple t ? new ArrayList<>(t.items) : List.of(key);
        return new TypeParamsGeneric(this, tuple(items), true);
    }
}
