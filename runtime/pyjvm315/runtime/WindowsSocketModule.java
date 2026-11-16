// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;
import static pyjvm315.runtime.WindowsSockets.INVALID_SOCKET;
import static pyjvm315.runtime.WindowsSockets.WSAEWOULDBLOCK;

import java.lang.ref.Cleaner;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * {@code _socket} on Windows: real Winsock stream sockets (IPv4 and IPv6) with the methods {@code socket.py},
 * {@code asyncio}'s Proactor loop and {@code socket.socketpair()} (the loopback fallback) use: bind, listen, connect,
 * connect_ex, _accept, getsockname, getpeername, setblocking/settimeout, send, sendall, recv, recv_into, shutdown,
 * setsockopt/getsockopt, close, detach, fileno and the family/type/proto/timeout attributes. Everything CPython's
 * socket type has beyond that (datagram calls, ioctl, share, ...) and the name-resolution functions are absent, so
 * {@code getaddrinfo}-based callers ({@code create_connection}, {@code asyncio.create_server}) fail with AttributeError.
 * Host names in addresses are resolved with the JVM's resolver. The module constants are CPython's Windows values.
 *
 * <p>Timeouts follow socketmodule.c: None blocks in the system call, 0 sets the system non-blocking mode, a positive value
 * keeps the system non-blocking and waits with select. A socket object that is collected without close() has its handle
 * closed by a Cleaner (CPython does so in its destructor, with a ResourceWarning that is not emitted here).
 */
final class WindowsSocketModule {
    private WindowsSocketModule() {}

    private static final String ME = WindowsSocketModule.class.getName();
    private static final int AF_INET = 2, AF_INET6 = 23, SOCK_STREAM = 1;
    private static final Cleaner CLEANER = Cleaner.create(r -> { Thread t = new Thread(r, "pyjvm-socket-cleaner"); t.setDaemon(true); return t; });
    private static PyClass gaierror;
    private static PyClass sockClass;
    /** The default timeout in nanoseconds; negative for None. */
    private static volatile long defaultTimeout = -1;

    /** The Java state of one socket object. Never refers to the Python object (the Cleaner holds it). */
    static final class Sock {
        volatile long fd = INVALID_SOCKET;
        int family, type, proto;
        volatile long timeoutNs = -1;
        boolean registered;
    }

    private static final class Reaper implements Runnable {
        private final Sock s;
        Reaper(Sock s) { this.s = s; }
        @Override public void run() {
            try {
                long fd;
                synchronized (s) { fd = s.fd; s.fd = INVALID_SOCKET; }
                if (fd != INVALID_SOCKET) WindowsSockets.closesocket(fd, new int[1]);
            } catch (Throwable ignored) { /* a finalizer has nobody to report to */ }
        }
    }

    static synchronized PyModule create() {
        WindowsSockets.startup();
        PyModule m = new PyModule("_socket", ME);
        sockClass = define("_socket", "socket", null);
        method(sockClass, ME_CLASS, "__init__", "sockInit", "self,family,type,proto,fileno", defaults("family", -1L, "type", -1L, "proto", -1L, "fileno", null));
        method(sockClass, ME_CLASS, "close", "sockClose", "self");
        method(sockClass, ME_CLASS, "detach", "sockDetach", "self");
        method(sockClass, ME_CLASS, "fileno", "sockFileno", "self");
        method(sockClass, ME_CLASS, "bind", "sockBind", "self,address");
        method(sockClass, ME_CLASS, "listen", "sockListen", "self,backlog", defaults("backlog", null));
        method(sockClass, ME_CLASS, "connect", "sockConnect", "self,address");
        method(sockClass, ME_CLASS, "connect_ex", "sockConnectEx", "self,address");
        method(sockClass, ME_CLASS, "_accept", "sockAccept", "self");
        method(sockClass, ME_CLASS, "getsockname", "sockGetsockname", "self");
        method(sockClass, ME_CLASS, "getpeername", "sockGetpeername", "self");
        method(sockClass, ME_CLASS, "setblocking", "sockSetblocking", "self,flag");
        method(sockClass, ME_CLASS, "getblocking", "sockGetblocking", "self");
        method(sockClass, ME_CLASS, "settimeout", "sockSettimeout", "self,value");
        method(sockClass, ME_CLASS, "gettimeout", "sockGettimeout", "self");
        method(sockClass, ME_CLASS, "send", "sockSend", "self,data,flags", defaults("flags", 0L));
        method(sockClass, ME_CLASS, "sendall", "sockSendall", "self,data,flags", defaults("flags", 0L));
        method(sockClass, ME_CLASS, "recv", "sockRecv", "self,bufsize,flags", defaults("flags", 0L));
        method(sockClass, ME_CLASS, "recv_into", "sockRecvInto", "self,buffer,nbytes,flags", defaults("nbytes", 0L, "flags", 0L));
        method(sockClass, ME_CLASS, "shutdown", "sockShutdown", "self,how");
        methodVar(sockClass, ME_CLASS, "setsockopt", "sockSetsockopt", "self", "args");
        methodVar(sockClass, ME_CLASS, "getsockopt", "sockGetsockopt", "self", "args");
        property("family", "sockFamily");
        property("type", "sockType");
        property("proto", "sockProto");
        property("timeout", "sockTimeout");
        finish(sockClass);
        m.attrs.put("socket", sockClass);
        m.attrs.put("SocketType", sockClass);
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        m.attrs.put("timeout", PyRuntime.builtinType("TimeoutError"));
        PyClass herror = null;
        for (String n : new String[]{"herror", "gaierror"}) {
            PyClass c = (PyClass) PyRuntime.classCreate(n, "socket");
            PyRuntime.classAddBase(c, PyRuntime.builtinType("OSError"));
            finish(c);
            m.attrs.put(n, c);
            if (n.equals("gaierror")) gaierror = c;
        }
        for (String e : WindowsSocketConstants.TABLE.split(";")) {
            if (e.isEmpty()) continue;
            boolean only315 = e.charAt(0) == '*';
            if (only315 && !LangVersion.atLeast(3, 15)) continue;
            int eq = e.indexOf('=');
            String name = e.substring(only315 ? 1 : 0, eq);
            char kind = e.charAt(eq + 1);
            String v = e.substring(eq + 2);
            m.attrs.put(name, kind == 'i' ? (Object) Long.parseLong(v) : kind == 'b' ? (Object) Boolean.parseBoolean(v) : (Object) v);
        }
        fn(m, "getdefaulttimeout", (a, k) -> { noArgs("getdefaulttimeout", a, k); return defaultTimeout < 0 ? null : defaultTimeout / 1e9; });
        fn(m, "setdefaulttimeout", (a, k) -> { defaultTimeout = parseTimeout(arg1("setdefaulttimeout", a, k)); return null; });
        fn(m, "gethostname", (a, k) -> {
            noArgs("gethostname", a, k);
            int[] err = new int[1];
            String h = WindowsSockets.gethostname(err);
            if (h == null) throw sockErr(err[0]);
            return h;
        });
        fn(m, "close", (a, k) -> {
            long fd = socketArg(arg1("close", a, k));
            int[] err = new int[1];
            if (WindowsSockets.closesocket(fd, err) != 0 && err[0] != WindowsSockets.WSAECONNRESET) throw sockErr(err[0]);
            return null;
        });
        fn(m, "htons", (a, k) -> swap16(arg1("htons", a, k), "htons"));
        fn(m, "ntohs", (a, k) -> swap16(arg1("ntohs", a, k), "ntohs"));
        fn(m, "htonl", (a, k) -> swap32(arg1("htonl", a, k)));
        fn(m, "ntohl", (a, k) -> swap32(arg1("ntohl", a, k)));
        return m;
    }

    private static final Class<?> ME_CLASS = WindowsSocketModule.class;

    private static void property(String name, String getter) { sockClass.properties.put(name, new PyProperty(ME, getter, null)); }

    // ---- small helpers ----------------------------------------------------------------------------

    private static Object arg1(String fn, List<Object> a, java.util.Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", fn + "() takes no keyword arguments");
        if (a.size() != 1) throw new PyException("TypeError", fn + "() takes exactly one argument (" + a.size() + " given)");
        return a.get(0);
    }

    private static void noArgs(String fn, List<Object> a, java.util.Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", fn + "() takes no keyword arguments");
        if (!a.isEmpty()) throw new PyException("TypeError", fn + "() takes no arguments (" + a.size() + " given)");
    }

    private static Object swap16(Object o, String fn) {
        long v = IoUtil.asLong(o);
        if (v < 0) throw new PyException("OverflowError", fn + ": can't convert negative Python int to C 16-bit unsigned integer");
        if (v > 0xffff) throw new PyException("OverflowError", fn + ": Python int too large to convert to C 16-bit unsigned integer");
        return Long.reverseBytes(v) >>> 48;
    }

    private static Object swap32(Object o) {
        Object v = NativeSubclass.unwrap(o);
        BigInteger b = v instanceof BigInteger x ? x : BigInteger.valueOf(IoUtil.asLong(v));
        if (b.signum() < 0) throw new PyException("OverflowError", "can't convert negative value to unsigned int");
        if (b.bitLength() > 32) throw new PyException("OverflowError", "int larger than 32 bits");
        return Long.reverseBytes(b.longValue()) >>> 32;
    }

    /** A socket handle argument ({@code PyLong_AsSocket_t}). */
    private static long socketArg(Object o) {
        Object v = NativeSubclass.unwrap(o);
        if (v instanceof BigInteger b) {
            if (b.bitLength() > 64) throw new PyException("OverflowError", "Python int too large to convert to C unsigned long long");
            return b.longValue();
        }
        return IoUtil.asLong(v);
    }

    /** {@code set_error}: an OSError from a Winsock error code (typed by its errno, with winerror). */
    static PyException sockErr(int code) { return WindowsNative.winError(code, null); }

    private static PyException timedOut() { return new PyException("TimeoutError", "timed out"); }

    /** socket_parse_timeout: nanoseconds, negative for None. */
    private static long parseTimeout(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o == null) return -1;
        double d;
        if (o instanceof Double x) d = x;
        else if (o instanceof Long || o instanceof Boolean || o instanceof BigInteger) {
            Object v = o instanceof Boolean b ? (Object) (b ? 1L : 0L) : o;
            d = v instanceof BigInteger b ? b.doubleValue() : (Long) v;
        } else if (LangVersion.atLeast(3, 15)) throw new PyException("TypeError", "must be real number, not " + PyRuntime.typeName(o));
        else throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer");   // 3.13 falls back to __index__
        if (Double.isNaN(d)) throw new PyException("ValueError", "Invalid value NaN (not a number)");
        if (d < 0) throw new PyException("ValueError", "Timeout value out of range");
        double ns = Math.ceil(d * 1e9);
        if (ns >= 9.223372036854775807e18) throw new PyException("OverflowError", "timestamp too large to convert to C _PyTime_t");
        return (long) ns;
    }

    private static Sock sock(Object self) { return NativeTypes.state(self, Sock.class, "_socket.socket"); }

    // ---- construction -----------------------------------------------------------------------------

    public static Object sockInit(Object self, Object family, Object type, Object proto, Object fileno) {
        if (!(self instanceof PyInstance)) throw new PyException("TypeError", "descriptor '__init__' requires a '_socket.socket' object but received '" + PyRuntime.typeName(self) + "'");
        Sock s = new Sock();
        int fam = (int) IoUtil.asLong(family), typ = (int) IoUtil.asLong(type), pro = (int) IoUtil.asLong(proto);
        fileno = NativeSubclass.unwrap(fileno);
        int[] err = new int[1];
        long fd;
        if (fileno != null) {
            if (fileno instanceof PyBytes || fileno instanceof PyByteArray)
                throw new PyException("NotImplementedError", "creating a socket from socket.share() data is not supported by pyJVM");
            fd = socketArg(fileno);
            if (fd == INVALID_SOCKET) throw new PyException("ValueError", "negative file descriptor");
            // socketmodule.c: the family comes from getsockname (an unbound socket fails with WSAEINVAL, which is an error only
            // when the family has to be found out or the handle is not a socket), the type from SO_TYPE, and the protocol is
            // always 0 (Windows has no SO_PROTOCOL)
            byte[] addr = WindowsSockets.name(fd, false, err);
            if (addr != null) {
                if (fam == -1) fam = (addr[0] & 0xff) | (addr[1] & 0xff) << 8;
            } else if (fam == -1 || err[0] == WindowsSockets.WSAENOTSOCK) throw sockErr(err[0]);
            if (typ == -1) {
                byte[] tb = WindowsSockets.getsockopt(fd, WindowsSockets.SOL_SOCKET, WindowsSockets.SO_TYPE, 4, err);
                if (tb == null) throw sockErr(err[0]);
                typ = java.nio.ByteBuffer.wrap(tb).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0);
            }
            pro = 0;
        } else {
            if (fam == -1) fam = AF_INET;
            if (typ == -1) typ = SOCK_STREAM;
            if (pro == -1) pro = 0;
            fd = WindowsSockets.socket(fam, typ, pro, err);
            if (fd == INVALID_SOCKET) throw sockErr(err[0]);
        }
        s.fd = fd; s.family = fam; s.type = typ; s.proto = pro;
        s.timeoutNs = defaultTimeout;
        if (s.timeoutNs >= 0) setBlockingMode(s, false);
        NativeTypes.setState(self, s);
        CLEANER.register(self, new Reaper(s));
        return null;
    }

    private static void setBlockingMode(Sock s, boolean blocking) {
        int[] err = new int[1];
        if (WindowsSockets.setNonBlocking(s.fd, !blocking, err) != 0) throw sockErr(err[0]);
    }

    // ---- attributes -------------------------------------------------------------------------------

    public static Object sockFamily(Object self) { return (long) sock(self).family; }
    public static Object sockType(Object self) { return (long) sock(self).type; }
    public static Object sockProto(Object self) { return (long) sock(self).proto; }
    public static Object sockTimeout(Object self) { long t = sock(self).timeoutNs; return t < 0 ? null : t / 1e9; }

    public static Object sockFileno(Object self) { return sock(self).fd; }

    public static Object sockClose(Object self) {
        Sock s = sock(self);
        long fd;
        synchronized (s) { fd = s.fd; s.fd = INVALID_SOCKET; }
        if (fd != INVALID_SOCKET) {
            int[] err = new int[1];
            if (WindowsSockets.closesocket(fd, err) != 0 && err[0] != WindowsSockets.WSAECONNRESET) throw sockErr(err[0]);
        }
        return null;
    }

    public static Object sockDetach(Object self) {
        Sock s = sock(self);
        synchronized (s) { long fd = s.fd; s.fd = INVALID_SOCKET; return fd; }
    }

    public static Object sockSetblocking(Object self, Object flag) {
        Sock s = sock(self);
        boolean blocking = PyRuntime.truth(flag);
        s.timeoutNs = blocking ? -1 : 0;
        setBlockingMode(s, blocking);
        return null;
    }

    public static Object sockGetblocking(Object self) { return sock(self).timeoutNs != 0; }

    public static Object sockSettimeout(Object self, Object value) {
        Sock s = sock(self);
        long t = parseTimeout(value);
        s.timeoutNs = t;
        setBlockingMode(s, t < 0);
        return null;
    }

    public static Object sockGettimeout(Object self) { return sockTimeout(self); }

    // ---- waiting ----------------------------------------------------------------------------------

    /** Waits until {@code fd} is readable (or writable, also watching for a failed connect); false on timeout. */
    private static boolean waitReady(long fd, boolean writing, boolean connect, long nanos) {
        long micros = Math.max(1, (nanos + 999) / 1000);
        int[] err = new int[1];
        long[] one = {fd}, none = new long[0];
        long[][] r = WindowsSockets.select(writing ? none : one, writing ? one : none, connect ? one : none, micros, err);
        if (r == null) throw sockErr(err[0]);
        return r[0].length + r[1].length + r[2].length > 0;
    }

    private interface Op { long run(int[] err); }

    /** sock_call_ex: with a positive timeout wait for readiness, then run the operation, until it does not report would-block. */
    private static long call(Sock s, boolean writing, long deadline, Op op) {
        boolean timed = s.timeoutNs > 0;
        if (timed && deadline == 0) deadline = System.nanoTime() + s.timeoutNs;
        int[] err = new int[1];
        while (true) {
            if (timed) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || !waitReady(s.fd, writing, false, left)) throw timedOut();
            }
            err[0] = 0;
            long r = op.run(err);
            if (r >= 0) return r;
            if (err[0] == WSAEWOULDBLOCK && timed) continue;
            throw sockErr(err[0]);
        }
    }

    // ---- addresses --------------------------------------------------------------------------------

    private static PyException gaiError(int code, String msg) {
        return PyRuntime.userException(PyRuntime.instantiate(gaierror, new Object[]{(long) code, msg}));
    }

    /** idna_converter: str, bytes or bytearray. */
    private static String hostArg(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof String s) return s;
        if (o instanceof PyBytes || o instanceof PyByteArray) return new String(((PyByteSequence) o).toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        throw new PyException("TypeError", "str, bytes or bytearray expected, not " + PyRuntime.typeName(o));
    }

    /** setipaddr: the address bytes (4 or 16) for a host name of the family. */
    private static byte[] ipAddress(String host, int family) {
        if (host.isEmpty() || host.equals("<any>")) return new byte[family == AF_INET ? 4 : 16];
        if (host.equals("<broadcast>")) {
            if (family != AF_INET) throw gaiError(11001, "getaddrinfo failed");
            return new byte[]{-1, -1, -1, -1};
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (family == AF_INET && a instanceof Inet4Address) return a.getAddress();
                if (family == AF_INET6 && a instanceof Inet6Address) return a.getAddress();
            }
        } catch (UnknownHostException e) { /* falls through */ }
        throw gaiError(11001, "getaddrinfo failed");
    }

    private static long intPart(Object o, String caller, String what, long max) {
        Object v = NativeSubclass.unwrap(o);
        if (!(v instanceof Long || v instanceof Boolean || v instanceof BigInteger)) throw new PyException("TypeError", "'" + PyRuntime.typeName(v) + "' object cannot be interpreted as an integer");
        BigInteger b = v instanceof BigInteger x ? x : BigInteger.valueOf(IoUtil.asLong(v));
        if (b.signum() < 0 || b.compareTo(BigInteger.valueOf(max)) > 0) throw new PyException("OverflowError", caller + "(): " + what + " must be 0-" + max + ".");
        return b.longValue();
    }

    /** getsockaddrarg for AF_INET and AF_INET6 sockets. */
    private static byte[] sockaddr(Sock s, Object addr, String caller) {
        addr = NativeSubclass.unwrap(addr);
        if (s.family == AF_INET) {
            if (!(addr instanceof PyTuple t)) throw new PyException("TypeError", caller + "(): AF_INET address must be tuple, not " + PyRuntime.typeName(addr));
            if (t.items.size() != 2) throw new PyException("TypeError", "AF_INET address must be a pair (host, port)");
            byte[] ip = ipAddress(hostArg(t.items.get(0)), AF_INET);
            long port = intPart(t.items.get(1), caller, "port", 0xffff);
            byte[] sa = new byte[16];
            sa[0] = AF_INET;
            sa[2] = (byte) (port >> 8); sa[3] = (byte) port;
            System.arraycopy(ip, 0, sa, 4, 4);
            return sa;
        }
        if (s.family == AF_INET6) {
            if (!(addr instanceof PyTuple t)) throw new PyException("TypeError", caller + "(): AF_INET6 address must be tuple, not " + PyRuntime.typeName(addr));
            int n = t.items.size();
            if (n < 2 || n > 4) throw new PyException("TypeError", "AF_INET6 address must be a tuple (host, port[, flowinfo[, scopeid]])");
            byte[] ip = ipAddress(hostArg(t.items.get(0)), AF_INET6);
            long port = intPart(t.items.get(1), caller, "port", 0xffff);
            long flow = n > 2 ? intPart(t.items.get(2), caller, "flowinfo", 0xfffff) : 0;
            long scope = n > 3 ? intPart(t.items.get(3), caller, "scope_id", 0xffffffffL) : 0;
            byte[] sa = new byte[28];
            sa[0] = AF_INET6;
            sa[2] = (byte) (port >> 8); sa[3] = (byte) port;
            sa[4] = (byte) (flow >> 24); sa[5] = (byte) (flow >> 16); sa[6] = (byte) (flow >> 8); sa[7] = (byte) flow;
            System.arraycopy(ip, 0, sa, 8, 16);
            sa[24] = (byte) scope; sa[25] = (byte) (scope >> 8); sa[26] = (byte) (scope >> 16); sa[27] = (byte) (scope >> 24);
            return sa;
        }
        throw new PyException("OSError", "bind(): bad family");
    }

    /** The compressed text form of an IPv6 address, as inet_ntop prints it. */
    static String ipv6Text(byte[] a) {
        int[] w = new int[8];
        for (int i = 0; i < 8; i++) w[i] = ((a[2 * i] & 0xff) << 8) | (a[2 * i + 1] & 0xff);
        boolean mapped = w[0] == 0 && w[1] == 0 && w[2] == 0 && w[3] == 0 && w[4] == 0 && w[5] == 0xffff;
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (w[i] != 0) { i++; continue; }
            int j = i;
            while (j < 8 && w[j] == 0) j++;
            if (j - i > bestLen) { bestStart = i; bestLen = j - i; }
            i = j;
        }
        if (bestLen < 2) bestStart = -1;
        StringBuilder sb = new StringBuilder();
        int limit = mapped ? 6 : 8;
        for (int i = 0; i < limit; i++) {
            if (i == bestStart) { sb.append("::"); i += bestLen - 1; continue; }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') sb.append(':');
            sb.append(Integer.toHexString(w[i]));
        }
        if (mapped) {
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != ':') sb.append(':');
            sb.append(a[12] & 0xff).append('.').append(a[13] & 0xff).append('.').append(a[14] & 0xff).append('.').append(a[15] & 0xff);
        }
        return sb.toString();
    }

    /** makesockaddr. */
    private static Object makeAddr(byte[] sa) {
        int family = (sa[0] & 0xff) | ((sa[1] & 0xff) << 8);
        if (family == AF_INET && sa.length >= 8) {
            long port = ((sa[2] & 0xff) << 8) | (sa[3] & 0xff);
            return tuple((sa[4] & 0xff) + "." + (sa[5] & 0xff) + "." + (sa[6] & 0xff) + "." + (sa[7] & 0xff), port);
        }
        if (family == AF_INET6 && sa.length >= 28) {
            long port = ((sa[2] & 0xff) << 8) | (sa[3] & 0xff);
            long flow = ((long) (sa[4] & 0xff) << 24) | ((sa[5] & 0xff) << 16) | ((sa[6] & 0xff) << 8) | (sa[7] & 0xff);
            long scope = (sa[24] & 0xffL) | ((sa[25] & 0xffL) << 8) | ((sa[26] & 0xffL) << 16) | ((sa[27] & 0xffL) << 24);
            byte[] ip = java.util.Arrays.copyOfRange(sa, 8, 24);
            return tuple(ipv6Text(ip), port, flow, scope);
        }
        return tuple((long) family, new PyBytes(java.util.Arrays.copyOfRange(sa, 2, Math.min(sa.length, 16))));
    }

    // ---- bind, listen, connect, accept ------------------------------------------------------------

    public static Object sockBind(Object self, Object address) {
        Sock s = sock(self);
        byte[] sa = sockaddr(s, address, "bind");
        int[] err = new int[1];
        if (WindowsSockets.bind(s.fd, sa, err) != 0) throw sockErr(err[0]);
        return null;
    }

    public static Object sockListen(Object self, Object backlog) {
        Sock s = sock(self);
        long b = backlog == null ? 128 : IoUtil.asLong(backlog);
        int[] err = new int[1];
        // CPython: a negative backlog becomes 0, and the backlog is capped at SOMAXCONN (0x7fffffff on Windows)
        int n = (int) Math.max(0, Math.min(b, 0x7fffffffL));
        if (WindowsSockets.listen(s.fd, n, err) != 0) throw sockErr(err[0]);
        return null;
    }

    /** internal_connect: 0 on success, else the Winsock error (thrown when {@code raise}). */
    private static int connect(Sock s, byte[] sa, boolean raise) {
        int[] err = new int[1];
        if (WindowsSockets.connect(s.fd, sa, err) == 0) return 0;
        if (!(err[0] == WSAEWOULDBLOCK && s.timeoutNs > 0)) {
            if (raise) throw sockErr(err[0]);
            return err[0];
        }
        long deadline = System.nanoTime() + s.timeoutNs;
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !waitReady(s.fd, true, true, left)) {
                if (raise) throw timedOut();
                return WSAEWOULDBLOCK;
            }
            byte[] v = WindowsSockets.getsockopt(s.fd, WindowsSockets.SOL_SOCKET, WindowsSockets.SO_ERROR, 4, err);
            if (v == null) { if (raise) throw sockErr(err[0]); return err[0]; }
            int code = java.nio.ByteBuffer.wrap(v).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            if (code == 0) return 0;
            if (code == WSAEWOULDBLOCK) continue;
            if (raise) throw sockErr(code);
            return code;
        }
    }

    public static Object sockConnect(Object self, Object address) {
        Sock s = sock(self);
        connect(s, sockaddr(s, address, "connect"), true);
        return null;
    }

    public static Object sockConnectEx(Object self, Object address) {
        Sock s = sock(self);
        return (long) connect(s, sockaddr(s, address, "connect_ex"), false);
    }

    public static Object sockAccept(Object self) {
        Sock s = sock(self);
        byte[][] peer = new byte[1][];
        long fd = call(s, false, 0, err -> {
            long n = WindowsSockets.accept(s.fd, peer, err);
            return n == INVALID_SOCKET ? -1 : n;
        });
        return tuple(fd, makeAddr(peer[0]));
    }

    public static Object sockGetsockname(Object self) { return name(self, false); }
    public static Object sockGetpeername(Object self) { return name(self, true); }

    private static Object name(Object self, boolean peer) {
        Sock s = sock(self);
        int[] err = new int[1];
        byte[] sa = WindowsSockets.name(s.fd, peer, err);
        if (sa == null) throw sockErr(err[0]);
        return makeAddr(sa);
    }

    // ---- data -------------------------------------------------------------------------------------

    private static int flagsArg(Object o) {
        long v = IoUtil.asLong(o);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new PyException("OverflowError", "Python int too large to convert to C int");
        return (int) v;
    }

    public static Object sockSend(Object self, Object data, Object flags) {
        Sock s = sock(self);
        byte[] b = IoUtil.bytesLike(data);
        int f = flagsArg(flags);
        return call(s, true, 0, err -> WindowsSockets.send(s.fd, b, 0, b.length, f, err));
    }

    public static Object sockSendall(Object self, Object data, Object flags) {
        Sock s = sock(self);
        byte[] b = IoUtil.bytesLike(data);
        int f = flagsArg(flags);
        long deadline = s.timeoutNs > 0 ? System.nanoTime() + s.timeoutNs : 0;
        int off = 0;
        while (off < b.length) {
            final int o = off;
            off += (int) call(s, true, deadline, err -> WindowsSockets.send(s.fd, b, o, b.length - o, f, err));
        }
        return null;
    }

    public static Object sockRecv(Object self, Object bufsize, Object flags) {
        Sock s = sock(self);
        long n = IoUtil.asLong(bufsize);
        if (n < 0) throw new PyException("ValueError", "negative buffersize in recv");
        if (n > Integer.MAX_VALUE) throw new PyException("OverflowError", "Python int too large to convert to C int");
        int f = flagsArg(flags);
        byte[] buf = new byte[(int) n];
        int got = (int) call(s, false, 0, err -> WindowsSockets.recv(s.fd, buf, buf.length, f, err));
        return new PyBytes(got == buf.length ? buf : java.util.Arrays.copyOf(buf, got));
    }

    public static Object sockRecvInto(Object self, Object buffer, Object nbytes, Object flags) {
        Sock s = sock(self);
        int size = IoUtil.bufferSize(buffer);
        long n = IoUtil.asLong(nbytes);
        if (n < 0) throw new PyException("ValueError", "negative buffersize in recv_into");
        if (n == 0) n = size;
        if (n > size) throw new PyException("ValueError", "buffer too small for requested bytes");
        int f = flagsArg(flags);
        byte[] buf = new byte[(int) n];
        int got = (int) call(s, false, 0, err -> WindowsSockets.recv(s.fd, buf, buf.length, f, err));
        IoUtil.fill(buffer, buf, 0, got);
        return (long) got;
    }

    public static Object sockShutdown(Object self, Object how) {
        Sock s = sock(self);
        int h = (int) IoUtil.asLong(how);
        int[] err = new int[1];
        if (WindowsSockets.shutdown(s.fd, h, err) != 0) throw sockErr(err[0]);
        return null;
    }

    public static Object sockSetsockopt(Object self, Object args) {
        Sock s = sock(self);
        List<Object> a = ((PyTuple) args).items;
        if (a.size() != 3) throw new PyException("TypeError", "setsockopt() takes exactly 3 arguments (" + a.size() + " given)");
        int level = (int) IoUtil.asLong(a.get(0)), opt = (int) IoUtil.asLong(a.get(1));
        Object v = NativeSubclass.unwrap(a.get(2));
        byte[] value;
        if (v instanceof Long || v instanceof Boolean || v instanceof BigInteger) {
            int x = (int) IoUtil.asLong(v);
            value = new byte[]{(byte) x, (byte) (x >> 8), (byte) (x >> 16), (byte) (x >> 24)};
        } else value = IoUtil.bytesLike(v);
        int[] err = new int[1];
        if (WindowsSockets.setsockopt(s.fd, level, opt, value, err) != 0) throw sockErr(err[0]);
        return null;
    }

    public static Object sockGetsockopt(Object self, Object args) {
        Sock s = sock(self);
        List<Object> a = ((PyTuple) args).items;
        if (a.size() < 2 || a.size() > 3) throw new PyException("TypeError", "getsockopt expected 2 or 3 arguments, got " + a.size());
        int level = (int) IoUtil.asLong(a.get(0)), opt = (int) IoUtil.asLong(a.get(1));
        int[] err = new int[1];
        if (a.size() == 2 || IoUtil.asLong(a.get(2)) == 0) {
            byte[] v = WindowsSockets.getsockopt(s.fd, level, opt, 4, err);
            if (v == null) throw sockErr(err[0]);
            return (long) java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(v, 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        }
        long len = IoUtil.asLong(a.get(2));
        if (len <= 0 || len > 1024) throw new PyException("OSError", "getsockopt buflen out of range");
        byte[] v = WindowsSockets.getsockopt(s.fd, level, opt, (int) len, err);
        if (v == null) throw sockErr(err[0]);
        return new PyBytes(v);
    }
}
