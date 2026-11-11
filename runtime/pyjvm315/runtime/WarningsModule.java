// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code _warnings}: the warning filter machinery the import system calls ({@code warn} with ImportWarning and
 * friends). Filters, the once registry and per-module {@code __warningregistry__} follow CPython; the regular
 * expressions inside filters are whatever objects the filter holds (anything with a {@code match} method), so
 * filters installed through the pure-Python {@code warnings} module work once {@code re} is importable.
 * Output goes to {@code sys.stderr} in CPython's {@code file:line: Category: message} form.
 */
public final class WarningsModule {
    private WarningsModule() {}

    static final ArrayList<Object> FILTERS = new ArrayList<>();
    static final PyDict ONCE_REGISTRY = new PyDict();
    static String defaultAction = "default";

    private static PyTuple filter(String action, String category, String module) {
        return tuple(action, null, PyRuntime.builtinType(category), module, 0L);
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_warnings", "pyjvm315.runtime.WarningsModule");
        if (FILTERS.isEmpty()) {
            FILTERS.add(filter("default", "DeprecationWarning", "__main__"));
            FILTERS.add(filter("ignore", "DeprecationWarning", null));
            FILTERS.add(filter("ignore", "PendingDeprecationWarning", null));
            FILTERS.add(filter("ignore", "ImportWarning", null));
            FILTERS.add(filter("ignore", "ResourceWarning", null));
        }
        m.attrs.put("filters", FILTERS);
        m.attrs.put("_onceregistry", ONCE_REGISTRY);
        m.attrs.put("_defaultaction", defaultAction);
        fn(m, "_filters_mutated", (a, k) -> null);
        fn(m, "_filters_mutated_lock_held", (a, k) -> null);
        fn(m, "_acquire_lock", (a, k) -> null);
        fn(m, "_release_lock", (a, k) -> null);
        fn(m, "warn", (a, k) -> warn(a, k));
        fn(m, "warn_explicit", (a, k) -> warnExplicit(a, k));
        return m;
    }

    private static Object arg(List<Object> a, Map<Object,Object> k, int i, String name, Object fallback) {
        if (i < a.size()) return a.get(i);
        return k.containsKey(name) ? k.get(name) : fallback;
    }

    private static Object warn(List<Object> a, Map<Object,Object> k) {
        Object message = arg(a, k, 0, "message", null);
        if (message == null && a.isEmpty() && !k.containsKey("message")) throw new PyException("TypeError", "warn() missing 1 required positional argument: 'message'");
        Object category = arg(a, k, 1, "category", null);
        long stacklevel = ((Number) arg(a, k, 2, "stacklevel", 1L)).longValue();
        ActiveFrame frame = frameAt(Math.max(1, stacklevel));
        String filename = frame == null ? "<sys>" : frame.filename;
        long lineno = frame == null ? 0 : frame.line;
        Object globals = frame == null || frame.owner == null ? null : Namespaces.globalsOfOwner(frame.owner);
        Object module = globals instanceof Map<?,?> g ? g.get("__name__") : null;
        Object registry = null;
        if (globals instanceof Map<?,?> g) {
            @SuppressWarnings("unchecked") Map<Object,Object> gm = (Map<Object,Object>) g;
            registry = gm.get("__warningregistry__");
            if (registry == null) { registry = new PyDict(); gm.put("__warningregistry__", registry); }
        }
        return explicit(message, category, filename, lineno, module == null ? "<string>" : module, registry);
    }

    private static ActiveFrame frameAt(long level) {
        long n = 0;
        for (ActiveFrame f : PyRuntime.LOGICAL_FRAMES.get()) {
            if (++n == level) return f;
        }
        return null;
    }

    private static Object warnExplicit(List<Object> a, Map<Object,Object> k) {
        Object message = arg(a, k, 0, "message", null), category = arg(a, k, 1, "category", null);
        Object filename = arg(a, k, 2, "filename", null), lineno = arg(a, k, 3, "lineno", null);
        Object module = arg(a, k, 4, "module", null), registry = arg(a, k, 5, "registry", null);
        if (!(filename instanceof String) || !(lineno instanceof Number))
            throw new PyException("TypeError", "warn_explicit() missing required filename/lineno arguments");
        if (module == null) {
            String f = (String) filename;
            module = f.endsWith(".py") ? f.substring(0, f.length() - 3) : f;
        }
        return explicit(message, category, (String) filename, ((Number) lineno).longValue(), module, registry);
    }

    @SuppressWarnings("unchecked")
    private static Object explicit(Object message, Object category, String filename, long lineno, Object module, Object registry) {
        String text;
        if (message instanceof PyInstance || message instanceof PyExceptionValue) {
            if (category == null) category = PyRuntime.typeOf(message);
            text = PyRuntime.pyStr(message);
        } else {
            text = PyRuntime.pyStr(message);
            if (category == null) category = PyRuntime.builtinType("UserWarning");
        }
        if (!PyRuntime.truth(PyRuntime.isSubclass(category, PyRuntime.builtinType("Warning"))))
            throw new PyException("TypeError", "category must be a Warning subclass, not '" + PyRuntime.typeName(category) + "'");
        PyTuple key = tuple(text, category, lineno);
        Map<Object,Object> reg = registry instanceof Map<?,?> ? (Map<Object,Object>) registry : null;
        if (reg != null && PyRuntime.truth(reg.get(key))) return null;
        String action = null;
        for (Object f : new ArrayList<>(FILTERS)) {
            PyTuple t = (PyTuple) f;
            Object msgRe = t.items.get(1), cat = t.items.get(2), modRe = t.items.get(3);
            long ln = ((Number) t.items.get(4)).longValue();
            boolean msgOk = matched(msgRe, text);
            boolean catOk = PyRuntime.truth(PyRuntime.isSubclass(category, cat));
            boolean modOk = matched(modRe, module);
            if (msgOk && catOk && modOk && (ln == 0 || lineno == ln)) { action = (String) t.items.get(0); break; }
        }
        if (action == null) action = defaultAction;
        if (action.equals("error")) {
            ArrayList<Object> args = new ArrayList<>(); args.add(text);
            PyRuntime.raiseObject(PyRuntime.callFunction(category, args, new LinkedHashMap<Object,Object>()));
            return null;
        }
        if (action.equals("ignore")) return null;
        if (action.equals("once")) {
            if (PyRuntime.truth(ONCE_REGISTRY.get(tuple(text, category)))) return null;
            ONCE_REGISTRY.put(tuple(text, category), true);
            if (reg != null) reg.put(key, true);
        } else if (action.equals("always")) {
            // never recorded
        } else if (action.equals("module")) {
            if (reg != null) { reg.put(key, true); PyTuple modKey = tuple(text, category, 0L); if (PyRuntime.truth(reg.get(modKey))) return null; reg.put(modKey, true); }
        } else if (action.equals("default")) {
            if (reg != null) reg.put(key, true);
        } else {
            throw new PyException("RuntimeError", "Unrecognized action ('" + action + "') in warnings.filters:\n " + ((Object) null));
        }
        show(filename, lineno, category, text);
        return null;
    }

    /** CPython's check_matched: None matches everything, a str must be equal, anything else is a compiled pattern. */
    private static boolean matched(Object pattern, Object text) {
        if (pattern == null) return true;
        if (pattern instanceof String p) return p.equals(text);
        return PyRuntime.truth(PyRuntime.callMethod(pattern, "match", new Object[]{text}));
    }

    private static void show(String filename, long lineno, Object category, String text) {
        String name = category instanceof PyBuiltinType b ? b.name : category instanceof PyClass c ? c.name : PyRuntime.pyStr(category);
        StringBuilder sb = new StringBuilder(filename).append(':').append(lineno).append(": ").append(name).append(": ").append(text).append('\n');
        try {
            List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(filename));
            if (lineno >= 1 && lineno <= lines.size() && !lines.get((int) lineno - 1).isBlank()) sb.append("  ").append(lines.get((int) lineno - 1).strip()).append('\n');
        } catch (java.io.IOException | RuntimeException e) { /* no source line available */ }
        Object stderr = BuiltinsExtra.currentStream("stderr");
        if (stderr == null) { System.err.print(sb); System.err.flush(); }
        else PyRuntime.callMethod(stderr, "write", new Object[]{sb.toString()});
    }
}
