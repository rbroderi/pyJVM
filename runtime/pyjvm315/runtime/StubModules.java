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

    static PyModule resource() {
        PyModule m = module("resource");
        Object[] c = {"RLIMIT_CPU", 0L, "RLIMIT_FSIZE", 1L, "RLIMIT_DATA", 2L, "RLIMIT_STACK", 3L, "RLIMIT_CORE", 4L, "RLIMIT_NOFILE", 7L,
            "RLIMIT_AS", 9L, "RLIM_INFINITY", -1L, "RUSAGE_SELF", 0L, "RUSAGE_CHILDREN", -1L};
        for (int i = 0; i < c.length; i += 2) m.attrs.put((String) c[i], c[i + 1]);
        NativeTypes.fn(m, "getrlimit", (a, k) -> NativeTypes.tuple(8388608L, -1L));
        NativeTypes.fn(m, "setrlimit", (a, k) -> null);
        NativeTypes.fn(m, "getpagesize", (a, k) -> 4096L);
        unsupported(m, "getrusage");
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        return m;
    }

    /** Enough of {@code _socket} for {@code socket}, {@code selectors} and {@code asyncio} to import; sockets cannot be created. */
    static PyModule socket() {
        PyModule m = module("_socket");
        PyClass sock = NativeTypes.define("_socket", "socket", null);
        NativeTypes.method(sock, StubModules.class, "__init__", "sockInit", "self,family,type,proto,fileno", NativeTypes.defaults("family", -1L, "type", -1L, "proto", -1L, "fileno", null));
        NativeTypes.finish(sock);
        m.attrs.put("socket", sock);
        m.attrs.put("SocketType", sock);
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        m.attrs.put("timeout", PyRuntime.builtinType("TimeoutError"));
        for (String n : new String[]{"gaierror", "herror"}) {
            PyClass c = NativeTypes.define("socket", n, (PyClass) PyRuntime.builtinType("OSError"));
            NativeTypes.finish(c);
            m.attrs.put(n, c);
        }
        Object[] c = {"AF_UNSPEC", 0L, "AF_UNIX", 1L, "AF_INET", 2L, "AF_INET6", 10L, "SOCK_STREAM", 1L, "SOCK_DGRAM", 2L, "SOCK_RAW", 3L,
            "SOCK_NONBLOCK", 2048L, "SOCK_CLOEXEC", 524288L, "SOL_SOCKET", 1L, "SOL_IP", 0L, "SOL_TCP", 6L, "SOL_UDP", 17L,
            "SO_REUSEADDR", 2L, "SO_TYPE", 3L, "SO_ERROR", 4L, "SO_BROADCAST", 6L, "SO_SNDBUF", 7L, "SO_RCVBUF", 8L, "SO_KEEPALIVE", 9L,
            "SO_REUSEPORT", 15L, "IPPROTO_IP", 0L, "IPPROTO_TCP", 6L, "IPPROTO_UDP", 17L, "IPPROTO_IPV6", 41L, "TCP_NODELAY", 1L,
            "SOMAXCONN", 4096L, "SHUT_RD", 0L, "SHUT_WR", 1L, "SHUT_RDWR", 2L, "MSG_PEEK", 2L, "MSG_DONTWAIT", 64L, "MSG_OOB", 1L,
            "AI_PASSIVE", 1L, "AI_CANONNAME", 2L, "AI_NUMERICHOST", 4L, "NI_NUMERICHOST", 1L, "NI_NUMERICSERV", 2L, "EAI_NONAME", -2L,
            "INADDR_ANY", 0L, "IPV6_V6ONLY", 26L, "has_ipv6", true};
        for (int i = 0; i < c.length; i += 2) m.attrs.put((String) c[i], c[i + 1]);
        NativeTypes.fn(m, "gethostname", (a, k) -> "localhost");
        NativeTypes.fn(m, "getdefaulttimeout", (a, k) -> null);
        NativeTypes.fn(m, "setdefaulttimeout", (a, k) -> null);
        NativeTypes.fn(m, "htons", (a, k) -> Long.reverseBytes(IoUtil.asLong(a.get(0)) & 0xffff) >>> 48);
        NativeTypes.fn(m, "ntohs", (a, k) -> Long.reverseBytes(IoUtil.asLong(a.get(0)) & 0xffff) >>> 48);
        NativeTypes.fn(m, "htonl", (a, k) -> Long.reverseBytes(IoUtil.asLong(a.get(0)) & 0xffffffffL) >>> 32);
        NativeTypes.fn(m, "ntohl", (a, k) -> Long.reverseBytes(IoUtil.asLong(a.get(0)) & 0xffffffffL) >>> 32);
        unsupported(m, "getaddrinfo", "gethostbyname", "getnameinfo", "socketpair", "dup", "close", "inet_aton", "inet_pton", "inet_ntop");
        return m;
    }

    public static Object sockInit(Object self, Object family, Object type, Object proto, Object fileno) {
        throw new PyException("OSError", "sockets are not supported by pyJVM");
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
