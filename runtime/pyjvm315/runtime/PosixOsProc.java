// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.List;
import java.util.Map;

/**
 * The {@code posix} process functions of N1, registered over the shared ones by {@code PosixOsFds.install} (kernel
 * descriptors only). Never loaded on Windows.
 */
final class PosixOsProc {
    private PosixOsProc() {}

    static void install(PyModule m) {
        m.attrs.put("sysconf_names", nameDict(PosixConfNames.SYSCONF_NAMES, PosixConfNames.SYSCONF_VALUES));
        m.attrs.put("confstr_names", nameDict(PosixConfNames.CONFSTR_NAMES, PosixConfNames.CONFSTR_VALUES));
        fn(m, "sysconf", (a, k) -> {
            one("sysconf", a, k);
            return PosixProc.sysconf(confName(m, a.get(0), "sysconf_names"));
        });
        fn(m, "confstr", (a, k) -> {
            one("confstr", a, k);
            return PosixProc.confstr(confName(m, a.get(0), "confstr_names"));
        });
    }

    /** A plain dict, new on every module creation, built from the generated arrays. */
    private static PyDict nameDict(String[] names, int[] values) {
        PyDict d = new PyDict();
        for (int i = 0; i < names.length; i++) d.put(names[i], (long) values[i]);
        return d;
    }

    /** CPython's argument-count texts for a positional-only, one-argument function. */
    private static void one(String name, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "posix." + name + "() takes no keyword arguments");
        if (a.size() != 1) throw new PyException("TypeError", "posix." + name + "() takes exactly one argument (" + a.size() + " given)");
    }

    /** A C int argument with CPython's texts: a non-integer is a TypeError, a value outside 32 bits an OverflowError. */
    static int cInt(Object o) { return (int) PosixFds.cfd(PosixFiles.fd(o)); }

    /**
     * {@code conv_confname}: a str is looked up in the module's own {@code sysconf_names}/{@code confstr_names} dict at call
     * time (CPython does this: the table is a real, mutable object, and a change to it changes later lookups); an int is the
     * number itself. The table is read with a generic {@code table[name]} (any object, a dict subclass's {@code __getitem__}
     * honoured), and any failure of that lookup is the ValueError; only a missing attribute is the AttributeError. An object
     * with {@code __index__} is accepted from 3.14 on (measured: TypeError on 3.13).
     */
    private static int confName(PyModule m, Object o, String table) {
        Object u = NativeSubclass.unwrap(o);
        if (u instanceof String s) {
            Object t = m.attrs.get(table);
            if (t == null) throw new PyException("AttributeError", "module 'posix' has no attribute '" + table + "'");
            Object v;
            try { v = PyRuntime.getitem(t, o); }
            catch (PyException e) { throw new PyException("ValueError", "unrecognized configuration name"); }
            u = NativeSubclass.unwrap(v);     // the value goes through the same check as a direct argument
        }
        if (PyRuntime.isIntLike(u)) return cInt(u);
        if (LangVersion.atLeast(3, 14) && !(u instanceof String)) {
            java.math.BigInteger n;
            try { n = BuiltinsExtra.rangeIndex(u); }
            catch (PyException e) {
                if (!e.typeName.equals("TypeError")) throw e;      // an exception raised by __index__ itself propagates
                throw new PyException("TypeError", "configuration names must be strings or integers");
            }
            return cInt(n.bitLength() < 64 ? (Object) n.longValue() : n);
        }
        throw new PyException("TypeError", "configuration names must be strings or integers");
    }
}
