// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The globals mapping of a statically compiled module. The module keeps its names in static fields
 * ({@code __py_global_x}, {@code __py_function_x}, {@code __py_class_x}), so this view reads and writes
 * those fields; names the module never binds statically live in the module's {@code __py_extras} dict,
 * which compiled code consults for names it cannot resolve at compile time.
 */
final class NamespacesModuleView extends AbstractMap<Object,Object> {
    private static final ConcurrentHashMap<String,NamespacesModuleView> VIEWS = new ConcurrentHashMap<>();
    private static final String[] PREFIXES = {"__py_global_", "__py_function_", "__py_class_"};

    private final Class<?> cls;
    private final Map<Object,Object> extras;
    private final ConcurrentHashMap<String,Field[]> fieldCache = new ConcurrentHashMap<>();

    static NamespacesModuleView of(String className) {
        NamespacesModuleView view = VIEWS.get(className);
        if (view != null) return view;
        try {
            NamespacesModuleView created = new NamespacesModuleView(Class.forName(className));
            NamespacesModuleView raced = VIEWS.putIfAbsent(className, created);
            return raced != null ? raced : created;
        } catch (ClassNotFoundException e) {
            throw new PyException("RuntimeError", "globals(): module class " + className + " is not loaded");
        }
    }

    @SuppressWarnings("unchecked")
    private NamespacesModuleView(Class<?> cls) {
        this.cls = cls;
        Map<Object,Object> found = null;
        try { found = (Map<Object,Object>) cls.getField("__py_extras").get(null); }
        catch (ReflectiveOperationException ignored) { }
        this.extras = found != null ? found : new LinkedHashMap<Object,Object>();
        if (!extras.containsKey("__builtins__")) extras.put("__builtins__", Namespaces.builtinsFor(get("__name__")));
    }

    /** The static fields that can hold {@code name}, in lookup order. */
    private Field[] fieldsFor(String name) {
        return fieldCache.computeIfAbsent(name, n -> {
            Field[] out = new Field[PREFIXES.length];
            for (int i = 0; i < PREFIXES.length; i++) {
                try { out[i] = cls.getField(PREFIXES[i] + n); } catch (NoSuchFieldException e) { out[i] = null; }
            }
            return out;
        });
    }

    private static Object read(Field f) {
        try { return f.get(null); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }

    @Override public Object get(Object key) {
        if (key instanceof String name) {
            for (Field f : fieldsFor(name)) {
                if (f == null) continue;
                Object value = read(f);
                if (value != PyRuntime.UNBOUND) return value;
            }
        }
        return extras.get(key);
    }

    @Override public boolean containsKey(Object key) {
        if (key instanceof String name) {
            for (Field f : fieldsFor(name)) if (f != null && read(f) != PyRuntime.UNBOUND) return true;
        }
        return extras.containsKey(key);
    }

    @Override public Object put(Object key, Object value) {
        if (key instanceof String name) {
            Field target = null;
            for (Field f : fieldsFor(name)) if (f != null && read(f) != PyRuntime.UNBOUND) { target = f; break; }
            if (target == null) for (Field f : fieldsFor(name)) if (f != null) { target = f; break; }
            if (target != null) {
                Object old = read(target);
                try { target.set(null, value); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
                return old == PyRuntime.UNBOUND ? null : old;
            }
        }
        return extras.put(key, value);
    }

    @Override public Object remove(Object key) {
        if (key instanceof String name) {
            for (Field f : fieldsFor(name)) {
                if (f == null) continue;
                Object old = read(f);
                if (old == PyRuntime.UNBOUND) continue;
                try { f.set(null, PyRuntime.UNBOUND); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
                return old;
            }
        }
        return extras.remove(key);
    }

    private LinkedHashMap<Object,Object> snapshot() {
        LinkedHashMap<Object,Object> out = new LinkedHashMap<>();
        for (Field f : cls.getFields()) {
            String field = f.getName();
            for (String prefix : PREFIXES) {
                if (!field.startsWith(prefix)) continue;
                Object value = read(f);
                if (value != PyRuntime.UNBOUND) out.putIfAbsent(field.substring(prefix.length()), value);
            }
        }
        out.putAll(extras);
        return out;
    }

    @Override public Set<Map.Entry<Object,Object>> entrySet() { return snapshot().entrySet(); }
    @Override public int size() { return snapshot().size(); }
    @Override public void clear() { for (Object key : snapshot().keySet()) remove(key); }
    @Override public String toString() { return PyRuntime.pyRepr(new PyDict(snapshot())); }
}
