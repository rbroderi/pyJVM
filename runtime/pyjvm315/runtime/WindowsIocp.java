// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static pyjvm315.runtime.WindowsNative.CALL_STATE;
import static pyjvm315.runtime.WindowsNative.LINKER;
import static pyjvm315.runtime.WindowsNative.k32;
import static pyjvm315.runtime.WindowsNative.lastError;
import static pyjvm315.runtime.WindowsNative.rethrow;
import static pyjvm315.runtime.WindowsNative.wide;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Win32 calls behind {@code _overlapped} and the overlapped parts of {@code _winapi}: I/O completion ports, events,
 * overlapped ReadFile/WriteFile/ConnectNamedPipe, CancelIoEx, GetOverlappedResult, thread-pool waits, named pipes.
 * Calls that can fail return the GetLastError code (0 on success) or throw {@link WindowsNative#winError}. Only touched
 * on Windows.
 *
 * <p>Waits: {@code RegisterWaitForSingleObject} with WT_EXECUTEINWAITTHREAD runs one global upcall stub on the Windows
 * wait thread; it posts the completion packet and nothing else (no Python code runs there).
 */
final class WindowsIocp {
    private WindowsIocp() {}

    static final int ERROR_IO_PENDING = 997, ERROR_NOT_FOUND = 1168, ERROR_PIPE_CONNECTED = 535, ERROR_MORE_DATA = 234;
    static final long STATUS_PENDING = 0x103L;
    static final int WT_EXECUTEINWAITTHREAD = 0x4, WT_EXECUTEONLYONCE = 0x8;

    private static MemorySegment h(long handle) { return MemorySegment.ofAddress(handle); }

    /** overlapped.c's SetFromWindowsErr: ERROR_CONNECTION_REFUSED and ERROR_CONNECTION_ABORTED get their own classes. */
    static final class OverlappedErrors {
        private OverlappedErrors() {}

        static PyException set(int err) {
            return WindowsNative.winError(err == 1225 ? "ConnectionRefusedError" : err == 1236 ? "ConnectionAbortedError" : null, err, null);
        }
    }

    private static final MethodHandle CIOCP = k32("CreateIoCompletionPort", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle GQCS = k32("GetQueuedCompletionStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle POST = k32("PostQueuedCompletionStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle CREATE_EVENT = k32("CreateEventW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle SET_EVENT = k32("SetEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle RESET_EVENT = k32("ResetEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE = k32("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle REGISTER_WAIT = k32("RegisterWaitForSingleObject",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle UNREGISTER_WAIT = k32("UnregisterWait", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle UNREGISTER_WAIT_EX = k32("UnregisterWaitEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CANCEL_IO_EX = k32("CancelIoEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_OV_RESULT = k32("GetOverlappedResult", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle READ_FILE = k32("ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WRITE_FILE = k32("WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CONNECT_PIPE = k32("ConnectNamedPipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_NAMED_PIPE = k32("CreateNamedPipeW",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CREATE_FILE = k32("CreateFileW",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle FORMAT_MESSAGE = k32("FormatMessageW",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CALLOC = LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow("calloc"),
        FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle FREE = LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow("free"), FunctionDescriptor.ofVoid(ADDRESS));

    // ---- memory ------------------------------------------------------------------------------------

    /** Zeroed native memory the kernel may keep using (an OVERLAPPED, an I/O buffer); 0 when out of memory. */
    static long calloc(long n) {
        try { return ((MemorySegment) CALLOC.invokeExact(1L, Math.max(1L, n))).address(); } catch (Throwable t) { throw rethrow(t); }
    }

    static void free(long p) {
        if (p == 0) return;
        try { FREE.invokeExact(h(p)); } catch (Throwable t) { throw rethrow(t); }
    }

    /** A view of {@code n} bytes of native memory. */
    static MemorySegment view(long p, long n) { return MemorySegment.ofAddress(p).reinterpret(n); }

    // ---- ports, events ---------------------------------------------------------------------------

    /** The new or existing port; NULL is a failure. */
    static long createIoCompletionPort(long file, long port, long key, int threads) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment r = (MemorySegment) CIOCP.invokeExact(st, h(file), h(port), key, threads);
            if (r.address() == 0) throw OverlappedErrors.set(lastError(st));
            return r.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** null on timeout, else {error, bytes, key, address}. */
    static long[] getQueuedCompletionStatus(long port, int millis) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), n = a.allocate(8), key = a.allocate(8), ov = a.allocate(8);
            int ok = (int) GQCS.invokeExact(st, h(port), n, key, ov, millis);
            int err = ok != 0 ? 0 : lastError(st);
            long addr = ov.get(JAVA_LONG, 0);
            if (addr == 0) {
                if (err == 258) return null;                      // WAIT_TIMEOUT
                throw OverlappedErrors.set(err);
            }
            return new long[]{err, Integer.toUnsignedLong(n.get(JAVA_INT, 0)), key.get(JAVA_LONG, 0), addr};
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void postQueuedCompletionStatus(long port, int bytes, long key, long address) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            if ((int) POST.invokeExact(st, h(port), bytes, key, h(address)) == 0) throw OverlappedErrors.set(lastError(st));
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** The new event, or a failure (NULL). */
    static long createEvent(boolean manual, boolean initial, String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment nm = name == null ? MemorySegment.NULL : wide(a, name);
            MemorySegment r = (MemorySegment) CREATE_EVENT.invokeExact(st, MemorySegment.NULL, manual ? 1 : 0, initial ? 1 : 0, nm);
            if (r.address() == 0) throw OverlappedErrors.set(lastError(st));
            return r.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void setEvent(long event) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            if ((int) SET_EVENT.invokeExact(st, h(event)) == 0) throw OverlappedErrors.set(lastError(st));
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void resetEvent(long event) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            if ((int) RESET_EVENT.invokeExact(st, h(event)) == 0) throw OverlappedErrors.set(lastError(st));
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** SetEvent ignoring failure. */
    static void setEventQuiet(long event) {
        try (Arena a = Arena.ofConfined()) { int ok = (int) SET_EVENT.invokeExact(a.allocate(CALL_STATE), h(event)); } catch (Throwable t) { throw rethrow(t); }
    }

    /** CloseHandle ignoring failure (finalizers). */
    static void closeQuiet(long handle) {
        try (Arena a = Arena.ofConfined()) { int ok = (int) CLOSE.invokeExact(a.allocate(CALL_STATE), h(handle)); } catch (Throwable t) { throw rethrow(t); }
    }

    /** FormatMessageW's text with trailing white space removed (not dots), or "unknown error code N". */
    static String formatMessage(int code) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), buf = a.allocate(4096 * 2L);
            int n = (int) FORMAT_MESSAGE.invokeExact(st, 0x1000 | 0x200, MemorySegment.NULL, code, 0x400, buf, 4096, MemorySegment.NULL);
            if (n == 0) return "unknown error code " + Integer.toUnsignedString(code);
            String s = WindowsNative.wideString(buf, n);
            int end = s.length();
            while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
            return s.substring(0, end);
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- overlapped I/O ----------------------------------------------------------------------------

    /** CancelIoEx: 0 on success, else the error. */
    static int cancelIoEx(long handle, long ov) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            return (int) CANCEL_IO_EX.invokeExact(st, h(handle), h(ov)) != 0 ? 0 : lastError(st);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** {error, transferred}; blocks when {@code wait}. */
    static long[] getOverlappedResult(long handle, long ov, boolean wait) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), n = a.allocate(4);
            int ok = (int) GET_OV_RESULT.invokeExact(st, h(handle), h(ov), n, wait ? 1 : 0);
            return new long[]{ok != 0 ? 0 : lastError(st), Integer.toUnsignedLong(n.get(JAVA_INT, 0))};
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** Overlapped ReadFile into native memory: 0 when it completed, else the error (ERROR_IO_PENDING when started). */
    static int readFile(long handle, long buf, int len, long ov) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), n = a.allocate(4);
            return (int) READ_FILE.invokeExact(st, h(handle), h(buf), len, n, h(ov)) != 0 ? 0 : lastError(st);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int writeFile(long handle, long buf, int len, long ov) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), n = a.allocate(4);
            return (int) WRITE_FILE.invokeExact(st, h(handle), h(buf), len, n, h(ov)) != 0 ? 0 : lastError(st);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int connectNamedPipe(long handle, long ov) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            return (int) CONNECT_PIPE.invokeExact(st, h(handle), h(ov)) != 0 ? 0 : lastError(st);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** Blocking ConnectNamedPipe (no OVERLAPPED), for {@code _winapi.ConnectNamedPipe(handle)}. */
    static void connectNamedPipeBlocking(long handle) {
        int err = connectNamedPipe(handle, 0);
        if (err != 0) throw WindowsNative.winError(err, null);
    }

    // ---- named pipes and files (_winapi) -----------------------------------------------------------

    /** The new pipe handle, or a plain {@code winError} (PyErr_SetFromWindowsErr). */
    static long createNamedPipe(String name, int openMode, int pipeMode, int maxInstances, int outSize, int inSize, int timeout) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment r = (MemorySegment) CREATE_NAMED_PIPE.invokeExact(st, wide(a, name), openMode, pipeMode, maxInstances, outSize, inSize,
                timeout, MemorySegment.NULL);
            if (r.address() == -1L) throw WindowsNative.winError(lastError(st), null);
            return r.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** CreateFileW; a failure is {@code SetFromWindowsErr} when {@code overlappedStyle}, else {@code PyErr_SetFromWindowsErr}. */
    static long createFile(String name, int access, int share, int disposition, int flags, long template, boolean overlappedStyle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment r = (MemorySegment) CREATE_FILE.invokeExact(st, wide(a, name), access, share, MemorySegment.NULL, disposition, flags, h(template));
            if (r.address() == -1L) throw overlappedStyle ? OverlappedErrors.set(lastError(st)) : WindowsNative.winError(lastError(st), null);
            return r.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- thread-pool waits -------------------------------------------------------------------------

    private static final AtomicLong WAIT_IDS = new AtomicLong(1);
    /** Registered waits that have not fired: id -> {port, overlapped address}. */
    private static final ConcurrentHashMap<Long, long[]> WAIT_CTX = new ConcurrentHashMap<>();
    /** Wait handle -> id. */
    private static final ConcurrentHashMap<Long, Long> WAIT_IDS_BY_HANDLE = new ConcurrentHashMap<>();
    private static volatile MemorySegment waitStub;

    /** Runs on a Windows wait thread: post the packet. An exception escaping an upcall would end the JVM, so none does. */
    private static void waitCallback(MemorySegment ctx, byte fired) {
        try {
            long[] c = WAIT_CTX.remove(ctx.address());
            if (c == null) return;                                // unregistered meanwhile
            try (Arena a = Arena.ofConfined()) {
                int ok = (int) POST.invokeExact(a.allocate(CALL_STATE), h(c[0]), fired != 0 ? 1 : 0, 0L, h(c[1]));
            }
        } catch (Throwable t) {
            // nothing to report to: the registration is lost, as when CPython's PostQueuedCompletionStatus fails
        }
    }

    private static MemorySegment waitStub() throws ReflectiveOperationException {
        MemorySegment s = waitStub;
        if (s == null) {
            synchronized (WindowsIocp.class) {
                if (waitStub == null) {
                    MethodHandle target = MethodHandles.lookup().findStatic(WindowsIocp.class, "waitCallback",
                        MethodType.methodType(void.class, MemorySegment.class, byte.class));
                    waitStub = LINKER.upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS, JAVA_BYTE), Arena.global());
                }
                s = waitStub;
            }
        }
        return s;
    }

    /** RegisterWaitWithQueue: the wait handle. */
    static long registerWait(long object, long port, long ov, int millis) {
        long id = WAIT_IDS.getAndIncrement();
        WAIT_CTX.put(id, new long[]{port, ov});
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), out = a.allocate(8);
            if ((int) REGISTER_WAIT.invokeExact(st, out, h(object), waitStub(), h(id), millis, WT_EXECUTEINWAITTHREAD | WT_EXECUTEONLYONCE) == 0) {
                WAIT_CTX.remove(id);
                throw OverlappedErrors.set(lastError(st));
            }
            long handle = out.get(JAVA_LONG, 0);
            WAIT_IDS_BY_HANDLE.put(handle, id);
            return handle;
        } catch (Throwable t) { WAIT_CTX.remove(id); throw rethrow(t); }
    }

    /** UnregisterWait(Ex) ({@code event} < 0: not Ex); a callback that has not run yet never will once this succeeds. */
    static void unregisterWait(long waitHandle, long event, boolean ex) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            int ok = ex ? (int) UNREGISTER_WAIT_EX.invokeExact(st, h(waitHandle), h(event)) : (int) UNREGISTER_WAIT.invokeExact(st, h(waitHandle));
            int err = ok != 0 ? 0 : lastError(st);
            Long id = WAIT_IDS_BY_HANDLE.get(waitHandle);
            if (ok != 0 && id != null) WAIT_CTX.remove(id);
            if (ok != 0 || err == ERROR_IO_PENDING) WAIT_IDS_BY_HANDLE.remove(waitHandle);
            if (ok == 0) throw OverlappedErrors.set(err);
        } catch (Throwable t) { throw rethrow(t); }
    }
}
