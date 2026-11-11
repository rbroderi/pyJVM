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
    PyExcArgs(PyTuple args, PyTuple group) { this.args = args; this.group = group; }

    PyTuple copyArgs() { PyTuple out = new PyTuple(); out.items.addAll(args.items); return out; }

    String str() {
        if (group != null) {
            int n = group.items.size();
            return PyRuntime.pyStr(args.items.get(0)) + " (" + n + " sub-exception" + (n == 1 ? "" : "s") + ")";
        }
        if (args.items.isEmpty()) return "";
        if (args.items.size() == 1) return PyRuntime.pyStr(args.items.get(0));
        return args.pyRepr();
    }

    @Override public String toString() { return str(); }
}
