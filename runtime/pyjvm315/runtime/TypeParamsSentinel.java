// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.List;
import java.util.Map;

/**
 * The Python 3.15 builtin {@code sentinel(name, /, *, repr=None)}: a unique, truthy, identity-compared marker.
 * Compiled call sites pass their module name as the hidden keyword {@code __pyjvm_module__} (a builtin has no
 * caller frame to ask); {@code __module__} can be reassigned or deleted like in CPython.
 */
final class TypeParamsSentinel extends TypeParamsObject {
    static final String MODULE_KEYWORD = "__pyjvm_module__";

    private final String name;
    private final String customRepr;
    private Object module;
    private boolean hasModule;

    private TypeParamsSentinel(String name, String customRepr, Object module) {
        this.name = name;
        this.customRepr = customRepr;
        this.module = module;
        this.hasModule = module != null;
    }

    static Object construct(List<Object> args, Map<Object, Object> kwargs) {
        if (args.size() != 1) {
            throw new PyException("TypeError", args.isEmpty() ? "sentinel expected 1 argument, got 0" : "sentinel expected at most 1 argument, got " + args.size());
        }
        Object name = args.get(0);
        String repr = null;
        Object module = null;
        for (Map.Entry<Object, Object> entry : kwargs.entrySet()) {
            Object key = entry.getKey();
            if ("repr".equals(key)) {
                Object value = entry.getValue();
                if (value != null && !(value instanceof String)) throw new PyException("TypeError", "sentinel repr must be str or None, not " + PyRuntime.typeName(value));
                repr = (String) value;
            } else if (MODULE_KEYWORD.equals(key)) {
                module = entry.getValue();
            } else {
                throw new PyException("TypeError", "sentinel() got an unexpected keyword argument '" + key + "'");
            }
        }
        if (!(name instanceof String text)) throw new PyException("TypeError", "sentinel name must be str, not " + PyRuntime.typeName(name));
        return new TypeParamsSentinel(text, repr, module);
    }

    String typeName() { return "sentinel"; }
    String repr() { return customRepr != null ? customRepr : name; }

    Object getattr(String attr) {
        switch (attr) {
            case "__name__": return name;
            case "__module__":
                if (!hasModule) throw noAttribute(attr);
                return module;
            default: return super.getattr(attr);
        }
    }

    void setattr(String attr, Object value) {
        if (attr.equals("__module__")) { module = value; hasModule = true; return; }
        if (attr.equals("__name__")) throw new PyException("AttributeError", "readonly attribute");
        throw new PyException("AttributeError", "'sentinel' object has no attribute '" + attr + "' and no __dict__ for setting new attributes");
    }

    void delattr(String attr) {
        if (attr.equals("__module__") && hasModule) { module = null; hasModule = false; return; }
        if (attr.equals("__name__")) throw new PyException("AttributeError", "readonly attribute");
        throw noAttribute(attr);
    }
}
