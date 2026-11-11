// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.Set;

/**
 * A type parameter created by PEP 695 syntax: TypeVar (def f[T]), TypeVarTuple (*Ts) or ParamSpec (**P).
 * The bound, constraints and default are lazily evaluated thunks (PEP 695/696): they run on first access, once.
 */
final class TypeParamsVar extends TypeParamsObject {
    static final int TYPEVAR = 0, TYPEVARTUPLE = 1, PARAMSPEC = 2;
    private static final Set<String> METHODS = Set.of("has_default");

    final int kind;
    final String name;
    private final Object boundThunk, constraintsThunk, defaultThunk;
    private Object bound, constraints, defaultValue;
    private boolean boundDone, constraintsDone, defaultDone;

    TypeParamsVar(int kind, String name, Object boundThunk, Object constraintsThunk, Object defaultThunk) {
        this.kind = kind;
        this.name = name;
        this.boundThunk = boundThunk;
        this.constraintsThunk = constraintsThunk;
        this.defaultThunk = defaultThunk;
    }

    String typeName() { return kind == TYPEVAR ? "TypeVar" : kind == TYPEVARTUPLE ? "TypeVarTuple" : "ParamSpec"; }
    String repr() { return name; }
    java.util.Set<String> methods() { return METHODS; }

    private Object bound() {
        if (!boundDone) {
            bound = boundThunk == null ? null : force(boundThunk);
            boundDone = true;
        }
        return bound;
    }

    private Object constraints() {
        if (!constraintsDone) {
            constraints = constraintsThunk == null ? new PyTuple() : force(constraintsThunk);
            constraintsDone = true;
        }
        return constraints;
    }

    private Object defaultValue() {
        if (!defaultDone) {
            defaultValue = defaultThunk == null ? NO_DEFAULT : force(defaultThunk);
            defaultDone = true;
        }
        return defaultValue;
    }

    Object getattr(String attr) {
        switch (attr) {
            case "__name__": return name;
            case "__module__": return "typing";
            case "__default__": return defaultValue();
            case "has_default": return method(attr);
            case "__bound__": if (kind != TYPEVARTUPLE) return bound(); break;
            case "__constraints__": if (kind == TYPEVAR) return constraints(); break;
            case "__covariant__", "__contravariant__": if (kind != TYPEVARTUPLE) return false; break;
            case "__infer_variance__": if (kind != TYPEVARTUPLE) return true; break;
            case "args": if (kind == PARAMSPEC) return new SpecArgs(this, false); break;
            case "kwargs": if (kind == PARAMSPEC) return new SpecArgs(this, true); break;
            default: break;
        }
        return super.getattr(attr);
    }

    Object callMethod(String method, Object[] args) {
        if (method.equals("has_default")) {
            noArgs(method, args);
            return defaultThunk != null;
        }
        return super.callMethod(method, args);
    }

    void setattr(String attr, Object value) {
        throw new PyException("AttributeError", "readonly attribute");
    }
}
