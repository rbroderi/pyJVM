// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.List;
import java.util.Map;

/** Argument parsing for native functions, with the diagnostics CPython's argument clinic produces. */
final class NativeArgs {
    private NativeArgs() {}

    /** Stands for an optional parameter that was not passed (None is a value). */
    static final Object MISSING = new Object();

    /**
     * Binds {@code args}/{@code kwargs} to {@code names}; the first {@code posOnly} names accept no keyword, the first
     * {@code required} names must be given. Missing optional entries are null.
     */
    static Object[] parse(String fname, List<Object> args, Map<Object, Object> kwargs, String[] names, int required, int posOnly) {
        if (args.size() > names.length)
            throw new PyException("TypeError", fname + "() takes at most " + names.length + " argument" + (names.length == 1 ? "" : "s") + " (" + args.size() + " given)");
        Object[] out = new Object[names.length];
        boolean[] set = new boolean[names.length];
        java.util.Arrays.fill(out, MISSING);
        for (int i = 0; i < args.size(); i++) { out[i] = args.get(i); set[i] = true; }
        if (kwargs != null) {
            for (Map.Entry<Object, Object> e : kwargs.entrySet()) {
                String key = e.getKey() instanceof String s ? s : null;
                int idx = -1;
                for (int i = 0; i < names.length && key != null; i++) if (names[i].equals(key)) idx = i;
                if (idx < 0 || idx < posOnly) {
                    if (idx >= 0) throw new PyException("TypeError", fname + "() got some positional-only arguments passed as keyword arguments: '" + key + "'");
                    throw new PyException("TypeError", fname + "() got an unexpected keyword argument '" + PyRuntime.pyStr(e.getKey()) + "'");
                }
                if (set[idx]) throw new PyException("TypeError", "argument for " + fname + "() given by name ('" + key + "') and position (" + (idx + 1) + ")");
                out[idx] = e.getValue(); set[idx] = true;
            }
        }
        for (int i = 0; i < required; i++)
            if (!set[i]) {
                if (args.size() < posOnly && i < posOnly)
                    throw new PyException("TypeError", fname + "() takes at least " + posOnly + " positional argument" + (posOnly == 1 ? "" : "s") + " (" + args.size() + " given)");
                throw new PyException("TypeError", fname + "() missing required argument '" + names[i] + "' (pos " + (i + 1) + ")");
            }
        return out;
    }

    /** {@code name() takes exactly one argument (n given)} / {@code takes no keyword arguments}. */
    static Object one(String fname, List<Object> args, Map<Object, Object> kwargs) {
        if (kwargs != null && !kwargs.isEmpty()) throw new PyException("TypeError", fname + "() takes no keyword arguments");
        if (args.size() != 1) throw new PyException("TypeError", fname + "() takes exactly one argument (" + args.size() + " given)");
        return args.get(0);
    }
}
