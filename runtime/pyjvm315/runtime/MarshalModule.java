// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

/**
 * {@code marshal}: present because importlib's external bootstrap imports it, but pyJVM code objects are JVM class
 * files with no marshal format and CPython bytecode cannot run here, so every function raises
 * NotImplementedError. (pyJVM never writes .pyc files: sys.implementation.cache_tag is None.)
 */
public final class MarshalModule {
    private MarshalModule() {}

    static PyModule create() {
        PyModule m = new PyModule("marshal", "pyjvm315.runtime.MarshalModule");
        m.attrs.put("version", 5L);
        for (String name : new String[]{"loads", "dumps", "load", "dump"})
            fn(m, name, (a, k) -> { throw new PyException("NotImplementedError", "marshal." + name + ": pyJVM has no marshal format for code objects and cannot execute CPython bytecode"); });
        return m;
    }
}
