// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * Importable shells of the POSIX extension modules that {@code subprocess}, {@code tempfile} and the test suite import:
 * {@code _posixsubprocess}, {@code fcntl}, {@code pwd} and {@code grp}. Importing succeeds; the operations that need a
 * real process, descriptor control or the account database raise {@code NotImplementedError} or {@code KeyError}.
 */
final class StubModules {
    private StubModules() {}

    private static PyModule module(String name) { return new PyModule(name, StubModules.class.getName()); }

    private static void unsupported(PyModule m, String... names) {
        for (String n : names) NativeTypes.fn(m, n, (a, k) -> { throw new PyException("NotImplementedError", m.name + "." + n + " is not supported by pyJVM"); });
    }

    static PyModule posixsubprocess() {
        PyModule m = module("_posixsubprocess");
        unsupported(m, "fork_exec");
        return m;
    }

    static PyModule fcntl() {
        PyModule m = module("fcntl");
        Object[] c = {"F_DUPFD", 0L, "F_GETFD", 1L, "F_SETFD", 2L, "F_GETFL", 3L, "F_SETFL", 4L, "F_GETLK", 5L, "F_SETLK", 6L, "F_SETLKW", 7L,
            "FD_CLOEXEC", 1L, "F_RDLCK", 0L, "F_WRLCK", 1L, "F_UNLCK", 2L, "F_DUPFD_CLOEXEC", 1030L,
            "LOCK_SH", 1L, "LOCK_EX", 2L, "LOCK_NB", 4L, "LOCK_UN", 8L};
        for (int i = 0; i < c.length; i += 2) m.attrs.put((String) c[i], c[i + 1]);
        unsupported(m, "fcntl", "ioctl", "flock", "lockf");
        return m;
    }

    static PyModule pwd() {
        PyModule m = module("pwd");
        NativeTypes.fn(m, "getpwuid", (a, k) -> { throw new PyException("KeyError", "getpwuid(): uid not found"); });
        NativeTypes.fn(m, "getpwnam", (a, k) -> { throw new PyException("KeyError", "getpwnam(): name not found"); });
        NativeTypes.fn(m, "getpwall", (a, k) -> new java.util.ArrayList<Object>());
        return m;
    }

    static PyModule grp() {
        PyModule m = module("grp");
        NativeTypes.fn(m, "getgrgid", (a, k) -> { throw new PyException("KeyError", "getgrgid(): gid not found"); });
        NativeTypes.fn(m, "getgrnam", (a, k) -> { throw new PyException("KeyError", "getgrnam(): name not found"); });
        NativeTypes.fn(m, "getgrall", (a, k) -> new java.util.ArrayList<Object>());
        return m;
    }
}
