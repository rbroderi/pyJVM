// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code function.__defaults__} and {@code function.__kwdefaults__}: views of {@link PyFunction#defaults} (one map,
 * parameter name to default value). Assignment replaces the defaults of the positional or the keyword-only parameters
 * (collections.namedtuple installs its {@code defaults=} this way).
 */
final class FunctionDefaults {
    private FunctionDefaults() {}

    static boolean handles(String name) { return name.equals("__defaults__") || name.equals("__kwdefaults__"); }

    private static List<String> positional(PyFunction f) {
        ArrayList<String> names = new ArrayList<>(f.posonly);
        names.addAll(f.poskw);
        return names;
    }

    static Object get(PyFunction f, String name) {
        if (name.equals("__defaults__")) {
            PyTuple t = new PyTuple();
            for (String p : positional(f)) if (f.defaults.containsKey(p)) t.items.add(f.defaults.get(p));
            return t.items.isEmpty() ? null : t;
        }
        PyDict d = new PyDict();
        for (String p : f.kwonly) if (f.defaults.containsKey(p)) d.put(p, f.defaults.get(p));
        return d.isEmpty() ? null : d;
    }

    static void set(PyFunction f, String name, Object value) {
        value = NativeSubclass.unwrap(value);
        if (name.equals("__defaults__")) {
            if (value != null && !(value instanceof PyTuple)) throw new PyException("TypeError", "__defaults__ must be set to a tuple object");
            List<String> names = positional(f);
            List<Object> items = value == null ? List.of() : ((PyTuple) value).items;
            synchronized (f.defaults) {
                for (String p : names) f.defaults.remove(p);
                // the values belong to the last parameters; surplus leading values have no parameter to bind to
                int skip = Math.max(0, items.size() - names.size()), first = names.size() - (items.size() - skip);
                for (int k = skip; k < items.size(); k++) f.defaults.put(names.get(first + k - skip), items.get(k));
            }
            return;
        }
        if (value != null && !(value instanceof PyDict)) throw new PyException("TypeError", "__kwdefaults__ must be set to a dict object");
        synchronized (f.defaults) {
            for (String p : f.kwonly) f.defaults.remove(p);
            if (value != null) for (String p : f.kwonly) if (((PyDict) value).containsKey(p)) f.defaults.put(p, ((PyDict) value).get(p));
        }
    }
}
