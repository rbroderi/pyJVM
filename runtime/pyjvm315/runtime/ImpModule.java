// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code _imp}: what importlib's bootstrap asks of the interpreter. Built-in modules are the Java-implemented
 * ones ({@link ImportSupport#NATIVE_NAMES}); there are no frozen modules and no C extension modules, so the
 * frozen and extension entry points report that honestly (is_frozen is False, extension_suffixes is empty and
 * create_dynamic is absent, so importlib registers no extension loader).
 */
public final class ImpModule {
    private ImpModule() {}

    private static final ReentrantLock IMPORT_LOCK = new ReentrantLock();

    static PyModule create() {
        PyModule m = new PyModule("_imp", "pyjvm315.runtime.ImpModule");
        fn(m, "lock_held", (a, k) -> IMPORT_LOCK.isLocked());
        fn(m, "acquire_lock", (a, k) -> { IMPORT_LOCK.lock(); return null; });
        fn(m, "release_lock", (a, k) -> {
            if (!IMPORT_LOCK.isHeldByCurrentThread()) throw new PyException("RuntimeError", "not holding the import lock");
            IMPORT_LOCK.unlock();
            return null;
        });
        fn(m, "is_builtin", (a, k) -> ImportSupport.NATIVE_NAMES.contains(name(a)) ? 1L : 0L);
        fn(m, "create_builtin", (a, k) -> {
            String name = (String) PyRuntime.getattr(a.get(0), "name");
            if (!ImportSupport.NATIVE_NAMES.contains(name)) throw new PyException("ImportError", "no built-in module named " + name);
            return PyRuntime.importNative(name);
        });
        fn(m, "exec_builtin", (a, k) -> 0L);
        fn(m, "is_frozen", (a, k) -> false);
        fn(m, "is_frozen_package", (a, k) -> { throw frozenMissing(name(a)); });
        fn(m, "find_frozen", (a, k) -> null);
        fn(m, "get_frozen_object", (a, k) -> { throw frozenMissing(name(a)); });
        fn(m, "init_frozen", (a, k) -> { throw frozenMissing(name(a)); });
        fn(m, "extension_suffixes", (a, k) -> new ArrayList<Object>());
        fn(m, "_set_lazy_attributes", (a, k) -> null);
        fn(m, "source_hash", (a, k) -> { throw new PyException("NotImplementedError", "_imp.source_hash: hash-based .pyc files are not supported"); });
        fn(m, "_fix_co_filename", (a, k) -> { throw new PyException("NotImplementedError", "_imp._fix_co_filename: pyJVM has no CPython code objects to patch"); });
        m.attrs.put("pyc_magic_number_token", 0x0a0d7079L);
        m.attrs.put("check_hash_based_pycs", "never");
        return m;
    }

    private static String name(java.util.List<Object> a) {
        if (a.isEmpty() || !(a.get(0) instanceof String s)) throw new PyException("TypeError", "name must be str");
        return s;
    }

    private static PyException frozenMissing(String name) {
        return new PyException("ImportError", "No such frozen object named '" + name + "'");
    }
}
