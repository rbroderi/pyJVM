// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static pyjvm315.runtime.WindowsNative.CALL_STATE;
import static pyjvm315.runtime.WindowsNative.LINKER;
import static pyjvm315.runtime.WindowsNative.rethrow;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * The Winsock (ws2_32) calls behind {@code _socket}, {@code _overlapped}'s WSARecv/WSASend, {@code select.select} and the
 * signal wakeup socket. Every call captures {@code WSAGetLastError} per call (the JVM's own Win32 calls cannot overwrite
 * it); a failing call stores the code in {@code err[0]}. Sockets are raw SOCKET values (64 bit). Only touched on Windows.
 */
final class WindowsSockets {
    private WindowsSockets() {}

    static final long INVALID_SOCKET = -1L;
    static final int WSAEWOULDBLOCK = 10035, WSAENOTSOCK = 10038, WSAEINVAL = 10022, WSAECONNRESET = 10054, WSAEINTR = 10004;
    static final int SOL_SOCKET = 0xFFFF, SO_TYPE = 0x1008, SO_ERROR = 0x1007;
    private static final int FIONBIO = 0x8004667E;
    private static final int WSA_FLAG_OVERLAPPED = 0x1, WSA_FLAG_NO_HANDLE_INHERIT = 0x80;
    /** CPython's FD_SETSIZE for select on Windows. */
    static final int FD_SETSIZE = 512;

    private static final SymbolLookup WS2 = SymbolLookup.libraryLookup("ws2_32", Arena.global());
    private static final java.lang.foreign.Linker.Option CAPTURE = java.lang.foreign.Linker.Option.captureCallState("WSAGetLastError");
    private static final VarHandle WSA_ERROR = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("WSAGetLastError"));

    private static MethodHandle ws(String name, FunctionDescriptor fd) { return LINKER.downcallHandle(WS2.findOrThrow(name), fd, CAPTURE); }

    private static final MethodHandle STARTUP = LINKER.downcallHandle(WS2.findOrThrow("WSAStartup"), FunctionDescriptor.of(JAVA_INT, JAVA_SHORT, ADDRESS));
    private static final MethodHandle SOCKET = ws("WSASocketW", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle CLOSE = ws("closesocket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle BIND = ws("bind", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle LISTEN = ws("listen", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle CONNECT = ws("connect", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle ACCEPT = ws("accept", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle GETSOCKNAME = ws("getsockname", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle GETPEERNAME = ws("getpeername", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle IOCTL = ws("ioctlsocket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
    private static final MethodHandle SEND = ws("send", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle RECV = ws("recv", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle SELECT = ws("select", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SHUTDOWN = ws("shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle SETSOCKOPT = ws("setsockopt", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GETSOCKOPT = ws("getsockopt", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GETHOSTNAME = ws("gethostname", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle WSARECV = ws("WSARecv", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle WSASEND = ws("WSASend", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_HANDLE_INFO = WindowsNative.k32("SetHandleInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    private static volatile boolean started;

    private static int wsaError(MemorySegment st) { return (int) WSA_ERROR.get(st, 0L); }

    /** WSAStartup(2.2), once; importing {@code _socket} does this in CPython. */
    static synchronized void startup() {
        if (started) return;
        try (Arena a = Arena.ofConfined()) {
            int r = (int) STARTUP.invokeExact((short) 0x0202, a.allocate(512));
            if (r != 0) throw WindowsNative.winError(r, null);
            started = true;
        } catch (Throwable t) { throw rethrow(t); }
        SelectModule.wsaStarted = true;
    }

    /** WSASocketW with WSA_FLAG_OVERLAPPED | WSA_FLAG_NO_HANDLE_INHERIT (falling back without the latter, as CPython does). */
    static long socket(int family, int type, int proto, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            long s = (long) SOCKET.invokeExact(st, family, type, proto, MemorySegment.NULL, 0, WSA_FLAG_OVERLAPPED | WSA_FLAG_NO_HANDLE_INHERIT);
            if (s == INVALID_SOCKET && wsaError(st) == WSAEINVAL)
                s = (long) SOCKET.invokeExact(st, family, type, proto, MemorySegment.NULL, 0, WSA_FLAG_OVERLAPPED);
            if (s == INVALID_SOCKET) err[0] = wsaError(st);
            return s;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int closesocket(long s, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            int r = (int) CLOSE.invokeExact(st, s);
            if (r != 0) err[0] = wsaError(st);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int bind(long s, byte[] sa, int[] err) { return withAddress(BIND, s, sa, err); }

    static int connect(long s, byte[] sa, int[] err) { return withAddress(CONNECT, s, sa, err); }

    private static int withAddress(MethodHandle h, long s, byte[] sa, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), addr = a.allocate(Math.max(sa.length, 1));
            MemorySegment.copy(sa, 0, addr, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, sa.length);
            int r = (int) h.invokeExact(st, s, addr, sa.length);
            if (r != 0) err[0] = wsaError(st);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int listen(long s, int backlog, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            int r = (int) LISTEN.invokeExact(st, s, backlog);
            if (r != 0) err[0] = wsaError(st);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** The accepted socket (INVALID_SOCKET on failure); its peer address is stored in {@code peer[0]}. */
    static long accept(long s, byte[][] peer, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), sa = a.allocate(128), len = a.allocate(4);
            len.set(JAVA_INT, 0, 128);
            long n = (long) ACCEPT.invokeExact(st, s, sa, len);
            if (n == INVALID_SOCKET) { err[0] = wsaError(st); return n; }
            peer[0] = sa.asSlice(0, len.get(JAVA_INT, 0)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            int ok = (int) SET_HANDLE_INFO.invokeExact(st, MemorySegment.ofAddress(n), 1, 0);   // not inheritable, as CPython does
            return n;
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** getsockname / getpeername: the raw sockaddr, or null (error in {@code err[0]}). */
    static byte[] name(long s, boolean peer, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), sa = a.allocate(128), len = a.allocate(4);
            len.set(JAVA_INT, 0, 128);
            int r = (int) (peer ? GETPEERNAME : GETSOCKNAME).invokeExact(st, s, sa, len);
            if (r != 0) { err[0] = wsaError(st); return null; }
            return sa.asSlice(0, len.get(JAVA_INT, 0)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** ioctlsocket(FIONBIO). */
    static int setNonBlocking(long s, boolean nonBlocking, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), v = a.allocate(4);
            v.set(JAVA_INT, 0, nonBlocking ? 1 : 0);
            int r = (int) IOCTL.invokeExact(st, s, FIONBIO, v);
            if (r != 0) err[0] = wsaError(st);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** send: the byte count, or -1 (error in {@code err[0]}). */
    static int send(long s, byte[] data, int off, int len, int flags, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), buf = a.allocate(Math.max(len, 1));
            MemorySegment.copy(data, off, buf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, len);
            int r = (int) SEND.invokeExact(st, s, buf, len, flags);
            if (r < 0) err[0] = wsaError(st);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** recv into {@code dst}: the byte count, or -1 (error in {@code err[0]}). */
    static int recv(long s, byte[] dst, int len, int flags, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), buf = a.allocate(Math.max(len, 1));
            int r = (int) RECV.invokeExact(st, s, buf, len, flags);
            if (r < 0) { err[0] = wsaError(st); return r; }
            MemorySegment.copy(buf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, dst, 0, r);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int shutdown(long s, int how, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            int r = (int) SHUTDOWN.invokeExact(st, s, how);
            if (r != 0) err[0] = wsaError(st);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int setsockopt(long s, int level, int opt, byte[] value, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), v = a.allocate(Math.max(value.length, 1));
            MemorySegment.copy(value, 0, v, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, value.length);
            int r = (int) SETSOCKOPT.invokeExact(st, s, level, opt, v, value.length);
            if (r != 0) err[0] = wsaError(st);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** getsockopt into a buffer of {@code len} bytes: the bytes the system filled in, or null. */
    static byte[] getsockopt(long s, int level, int opt, int len, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), v = a.allocate(Math.max(len, 1)), n = a.allocate(4);
            n.set(JAVA_INT, 0, len);
            int r = (int) GETSOCKOPT.invokeExact(st, s, level, opt, v, n);
            if (r != 0) { err[0] = wsaError(st); return null; }
            return v.asSlice(0, Math.min(n.get(JAVA_INT, 0), len)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static String gethostname(int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), buf = a.allocate(1024);
            int r = (int) GETHOSTNAME.invokeExact(st, buf, 1024);
            if (r != 0) { err[0] = wsaError(st); return null; }
            return buf.getString(0);
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- select ------------------------------------------------------------------------------------

    private static MemorySegment fdSet(Arena a, long[] socks) {
        MemorySegment set = a.allocate(8 + 8L * Math.max(socks.length, 1), 8);
        set.set(JAVA_INT, 0, socks.length);
        for (int i = 0; i < socks.length; i++) set.set(JAVA_LONG, 8 + 8L * i, socks[i]);
        return set;
    }

    private static long[] ready(MemorySegment set, long[] asked) {
        int n = set.get(JAVA_INT, 0);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = set.get(JAVA_LONG, 8 + 8L * i);
        return out;
    }

    /**
     * select(): {readable, writable, exceptional} sockets, or null with the error in {@code err[0]}. A negative
     * {@code micros} waits without limit. An empty set is passed as NULL, so three empty sets fail with WSAEINVAL.
     */
    static long[][] select(long[] r, long[] w, long[] x, long micros, int[] err) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment rs = r.length == 0 ? MemorySegment.NULL : fdSet(a, r);
            MemorySegment wsx = w.length == 0 ? MemorySegment.NULL : fdSet(a, w);
            MemorySegment xs = x.length == 0 ? MemorySegment.NULL : fdSet(a, x);
            MemorySegment tv = MemorySegment.NULL;
            if (micros >= 0) {
                tv = a.allocate(8, 4);
                tv.set(JAVA_INT, 0, (int) (micros / 1_000_000L));
                tv.set(JAVA_INT, 4, (int) (micros % 1_000_000L));
            }
            int n = (int) SELECT.invokeExact(st, 0, rs, wsx, xs, tv);
            if (n < 0) { err[0] = wsaError(st); return null; }
            return new long[][]{r.length == 0 ? new long[0] : ready(rs, r), w.length == 0 ? new long[0] : ready(wsx, w),
                x.length == 0 ? new long[0] : ready(xs, x)};
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- overlapped (used by _overlapped.Overlapped) ----------------------------------------------

    /** WSARecv with one buffer on an OVERLAPPED: 0 when it started or completed, else the WSA error (997 when pending). */
    static int wsaRecv(long s, long buf, int len, int flags, long ov) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), wsabuf = a.allocate(16, 8), n = a.allocate(4), f = a.allocate(4);
            wsabuf.set(JAVA_INT, 0, len);
            wsabuf.set(JAVA_LONG, 8, buf);
            f.set(JAVA_INT, 0, flags);
            int r = (int) WSARECV.invokeExact(st, s, wsabuf, 1, n, f, MemorySegment.ofAddress(ov), MemorySegment.NULL);
            return r < 0 ? wsaError(st) : 0;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int wsaSend(long s, long buf, int len, int flags, long ov) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), wsabuf = a.allocate(16, 8), n = a.allocate(4);
            wsabuf.set(JAVA_INT, 0, len);
            wsabuf.set(JAVA_LONG, 8, buf);
            int r = (int) WSASEND.invokeExact(st, s, wsabuf, 1, n, flags, MemorySegment.ofAddress(ov), MemorySegment.NULL);
            return r < 0 ? wsaError(st) : 0;
        } catch (Throwable t) { throw rethrow(t); }
    }
}
