// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.List;

/**
 * Runtime half of type-parameter lowering (pyjvm315/typeparams.py): the {@code __pyjvm_tp_*} helpers lowered code
 * calls, and the entry points PyRuntime uses to reach the native type-system objects.
 */
final class TypeParamsSupport {
    private TypeParamsSupport() {}

    static boolean isHelper(String name) { return name.startsWith("__pyjvm_tp_"); }

    private static Object optional(Object thunk) { return thunk; }

    private static void arity(String name, List<Object> args, int count) {
        if (args.size() != count) throw new PyException("TypeError", name + "() takes exactly " + count + " arguments (" + args.size() + " given)");
    }

    static Object callHelper(String name, List<Object> args) {
        switch (name) {
            case "__pyjvm_tp_typevar__":
                arity(name, args, 4);
                return new TypeParamsVar(TypeParamsVar.TYPEVAR, (String) args.get(0), optional(args.get(1)), optional(args.get(2)), optional(args.get(3)));
            case "__pyjvm_tp_typevartuple__":
                arity(name, args, 2);
                return new TypeParamsVar(TypeParamsVar.TYPEVARTUPLE, (String) args.get(0), null, null, optional(args.get(1)));
            case "__pyjvm_tp_paramspec__":
                arity(name, args, 2);
                return new TypeParamsVar(TypeParamsVar.PARAMSPEC, (String) args.get(0), null, null, optional(args.get(1)));
            case "__pyjvm_tp_alias__":
                arity(name, args, 3);
                return new TypeParamsAlias((String) args.get(0), (PyTuple) args.get(1), args.get(2));
            case "__pyjvm_tp_generic__":
                arity(name, args, 1);
                return TypeParamsGeneric.genericBase((PyTuple) args.get(0));
            case "__pyjvm_tp_bases__":
                arity(name, args, 1);
                return TypeParamsGeneric.resolveBases(args.get(0));
            case "__pyjvm_tp_unpack__":
                arity(name, args, 1);
                return new TypeParamsObject.Unpack(args.get(0));
            default:
                throw new PyException("NameError", "name '" + name + "' is not defined");
        }
    }

    // ---- entry points for PyRuntime --------------------------------------------------------------

    /** value[key] for classes and native type-system objects. */
    static Object subscript(Object value, Object key) {
        if (value instanceof TypeParamsObject object) return object.getitem(key);
        if (value instanceof PyClass cls) return TypeParamsGeneric.subscriptClass(cls, key);
        return TypeParamsGeneric.subscriptBuiltin((PyBuiltinType) value, key);
    }

    /** __type_params__ of a function or class that was not declared generic. */
    static Object emptyTypeParams() { return new PyTuple(); }
}
