// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Argument handling shared by the builtin digest modules (_md5, _sha1, _sha2, _sha3, _blake2). */
final class HashArgs {
    private HashArgs() {}

    /** CPython 3.14+ exposes {@code _GIL_MINSIZE = 2048} on every hash module. */
    static void gilMinsize(PyModule m) { if (LangVersion.atLeast(3, 14)) m.attrs.put("_GIL_MINSIZE", 2048L); }

    /**
     * The {@code data} argument of a constructor {@code name([data], *, usedforsecurity=True, string=None)}. {@code rest} is a
     * mutable copy of the keywords; {@code data}, {@code string} and {@code usedforsecurity} are removed from it. Returns
     * {@link NativeArgs#MISSING} when no data was given.
     */
    static Object dataArg(String fname, List<Object> args, Map<Object, Object> rest) { return dataArg(fname, args, rest, 1L); }

    /** {@code stacklevel} is 2 when the caller is a native {@code __new__} (its frame counts). */
    static Object dataArg(String fname, List<Object> args, Map<Object, Object> rest, long stacklevel) {
        if (args.size() > 1) throw new PyException("TypeError", fname + "() takes at most 1 positional argument (" + args.size() + " given)");
        rest.remove("usedforsecurity");
        Object data = args.isEmpty() ? NativeArgs.MISSING : args.get(0);
        if (rest.containsKey("data")) {
            if (data != NativeArgs.MISSING) throw new PyException("TypeError", "argument for " + fname + "() given by name ('data') and position (1)");
            data = rest.remove("data");
        }
        if (rest.containsKey("string")) {
            Object s = rest.remove("string");
            {   // string=None is an ordinary (rejected) value, not "absent"
                if (data != NativeArgs.MISSING) throw new PyException("TypeError", "'data' and 'string' are mutually exclusive and support for 'string' keyword parameter is slated for removal in a future version.");
                data = s;
                if (LangVersion.atLeast(3, 15)) {
                    Object warnings = ImportSupport.importBuiltin(new ArrayList<Object>(List.of("warnings")), new LinkedHashMap<Object, Object>());
                    PyRuntime.callMethod(warnings, "warn", new Object[]{"the 'string' keyword parameter is deprecated since Python 3.15 and slated for removal in Python 3.19; use the 'data' keyword parameter or pass the data to hash as a positional argument instead", PyRuntime.builtinType("DeprecationWarning"), stacklevel});
                }
            }
        }
        return data;
    }

    static void noUnknown(String fname, Map<Object, Object> rest) {
        if (!rest.isEmpty()) throw new PyException("TypeError", fname + "() got an unexpected keyword argument '" + rest.keySet().iterator().next() + "'");
    }

    /** The bytes of a data argument, with CPython's two messages. */
    static byte[] bytes(Object obj) {
        if (obj instanceof String) throw new PyException("TypeError", "Strings must be encoded before hashing");
        try { return IoUtil.bytesLike(obj); }
        catch (PyException e) { throw new PyException("TypeError", "object supporting the buffer API required"); }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }
}
