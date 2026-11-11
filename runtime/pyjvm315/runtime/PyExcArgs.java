// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * Payload of an exception value that does not fit the single-argument model: the full ``args`` tuple of an
 * exception built with several positional arguments (or one ``None``), and for ``BaseExceptionGroup`` the
 * validated ``exceptions`` tuple.
 */
final class PyExcArgs {
    final PyTuple args;
    final PyTuple group;
    /** Keyword-only attributes (ImportError.name/path/name_from); null for most exceptions. */
    final java.util.Map<String,Object> attrs;
    PyExcArgs(PyTuple args, PyTuple group) { this(args, group, null); }
    PyExcArgs(PyTuple args, PyTuple group, java.util.Map<String,Object> attrs) { this.args = args; this.group = group; this.attrs = attrs; }

    PyTuple copyArgs() { PyTuple out = new PyTuple(); out.items.addAll(args.items); return out; }

    String str() {
        if (group != null) {
            int n = group.items.size();
            return PyRuntime.pyStr(args.items.get(0)) + " (" + n + " sub-exception" + (n == 1 ? "" : "s") + ")";
        }
        if (attrs != null && attrs.containsKey(CodecErrors.KIND)) return CodecErrors.describe(attrs);
        if (attrs != null && attrs.containsKey("errno") && attrs.get("strerror") != null && args.items.size() >= 2)
            return "[Errno " + PyRuntime.pyStr(attrs.get("errno")) + "] " + PyRuntime.pyStr(attrs.get("strerror"))
                + (attrs.get("filename") == null ? "" : ": " + PyRuntime.pyRepr(attrs.get("filename")));
        if (args.items.isEmpty()) return "";
        if (args.items.size() == 1) return PyRuntime.pyStr(args.items.get(0));
        return args.pyRepr();
    }

    @Override public String toString() { return str(); }
}
