// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Base of the runtime-native objects behind PEP 695/696 (TypeVar, TypeVarTuple, ParamSpec, TypeAliasType, the
 * Generic[...] / list[int] aliases, unions, Unpack markers) and the 3.15 {@code sentinel}. PyRuntime delegates
 * attribute access, repr, hashing, subscription and method calls on these objects to the overridable hooks below.
 * Lowering lives in pyjvm315/typeparams.py; {@link TypeParamsSupport} holds the helpers the lowered code calls.
 */
abstract class TypeParamsObject {
    /** Name of the Python type, as type(x).__name__ reports it. */
    abstract String typeName();
    abstract String repr();

    /** Attribute lookup; unknown names raise AttributeError. */
    Object getattr(String name) {
        if (name.equals("__class__")) return PyRuntime.builtinType(typeName());
        throw noAttribute(name);
    }
    void setattr(String name, Object value) { throw new PyException("AttributeError", "'" + typeName() + "' object has no attribute '" + name + "' and no __dict__ for setting new attributes"); }
    void delattr(String name) { throw new PyException("AttributeError", "'" + typeName() + "' object has no attribute '" + name + "'"); }
    /** obj.name(*args); only names listed by {@link #methods()} are reachable. */
    Object callMethod(String name, Object[] args) { throw noAttribute(name); }
    java.util.Set<String> methods() { return java.util.Set.of(); }
    Object getitem(Object key) { throw new PyException("TypeError", "'" + typeName() + "' object is not subscriptable"); }
    Object pyHash() { return PyRuntime.id(this); }

    final PyException noAttribute(String name) { return new PyException("AttributeError", "'" + typeName() + "' object has no attribute '" + name + "'"); }
    final Object method(String name) { return new BuiltinBoundMethod(this, name); }
    final void noArgs(String name, Object[] args) {
        if (args.length != 0) throw new PyException("TypeError", name + "() takes no arguments (" + args.length + " given)");
    }

    @Override public String toString() { return repr(); }

    static PyTuple tuple(java.util.List<Object> items) {
        PyTuple out = new PyTuple();
        out.items.addAll(items);
        return out;
    }

    /** Calls a zero-argument Python callable (the lambdas lowering generates for lazy values). */
    static Object force(Object thunk) {
        return PyRuntime.callFunction(thunk, new ArrayList<Object>(), new LinkedHashMap<Object, Object>());
    }

    /** typing.NoDefault: the __default__ of a type parameter without a default. */
    static final TypeParamsObject NO_DEFAULT = new TypeParamsObject() {
        String typeName() { return "NoDefaultType"; }
        String repr() { return "typing.NoDefault"; }
    };

    /** A TypeVarTuple (or other starred type) in unpacked position: typing.Unpack[Ts], shown as *Ts. */
    static final class Unpack extends TypeParamsObject {
        final Object inner;
        Unpack(Object inner) { this.inner = inner; }
        String typeName() { return "_UnpackGenericAlias"; }
        String repr() { return "*" + TypeParamsGeneric.reprItem(inner); }
        Object getattr(String name) {
            if (name.equals("__origin__")) return inner;
            if (name.equals("__args__")) { PyTuple t = new PyTuple(); t.items.add(inner); return t; }
            return super.getattr(name);
        }
        @Override public boolean equals(Object o) { return o instanceof Unpack u && PyRuntime.truth(PyRuntime.eq(inner, u.inner)); }
        @Override public int hashCode() { return 31 + System.identityHashCode(inner); }
    }

    /** P.args / P.kwargs. */
    static final class SpecArgs extends TypeParamsObject {
        final Object origin; final boolean kwargs;
        SpecArgs(Object origin, boolean kwargs) { this.origin = origin; this.kwargs = kwargs; }
        String typeName() { return kwargs ? "ParamSpecKwargs" : "ParamSpecArgs"; }
        String repr() { return TypeParamsGeneric.reprItem(origin) + (kwargs ? ".kwargs" : ".args"); }
        Object getattr(String name) {
            if (name.equals("__origin__")) return origin;
            return super.getattr(name);
        }
    }
}
