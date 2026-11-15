// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static pyjvm315.runtime.WindowsNative.CALL_STATE;
import static pyjvm315.runtime.WindowsNative.k32;
import static pyjvm315.runtime.WindowsNative.lastError;
import static pyjvm315.runtime.WindowsNative.rethrow;
import static pyjvm315.runtime.WindowsNative.wide;
import static pyjvm315.runtime.WindowsNative.winError;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Win32 handles through FFM: what {@code _winapi}, {@code msvcrt} and the HANDLE-backed entries of {@link IoFd} (pipe
 * ends, {@code msvcrt.open_osfhandle}) need: pipes, duplication, blocking ReadFile/WriteFile, waiting, and process
 * creation with an inherit list (STARTUPINFOEX). Errors from the descriptor-level calls are reported the way the C
 * runtime's {@code _read}/{@code _write}/{@code _close} do (errno, no winerror); {@code _winapi} functions use
 * {@link WindowsNative#winError}. Only touched on Windows.
 */
final class WindowsHandles {
    private WindowsHandles() {}

    static final int ERROR_INVALID_HANDLE = 6, ERROR_BROKEN_PIPE = 109, ERROR_HANDLE_EOF = 38;
    static final int WAIT_FAILED = 0xFFFFFFFF;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x80000, CREATE_UNICODE_ENVIRONMENT = 0x400;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x20002L;
    private static final int PIPE_CHUNK = 65536;

    private static final MethodHandle CREATE_PIPE = k32("CreatePipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CLOSE = k32("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DUPLICATE = k32("DuplicateHandle",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle CURRENT_PROCESS = k32("GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle READ_FILE = k32("ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WRITE_FILE = k32("WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle STD_HANDLE = k32("GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle FILE_TYPE = k32("GetFileType", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle WAIT_ONE = k32("WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle WAIT_MANY = k32("WaitForMultipleObjects", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle EXIT_CODE = k32("GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle TERMINATE = k32("TerminateProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle OPEN_PROCESS = k32("OpenProcess", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle NEED_CURDIR = k32("NeedCurrentDirectoryForExePathW", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CREATE_FILE = k32("CreateFileW",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle SET_FILE_POINTER = k32("SetFilePointerEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_ERROR_MODE = k32("GetErrorMode", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle SET_ERROR_MODE = k32("SetErrorMode", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_VERSION = k32("GetVersion", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_ACP = k32("GetACP", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_OEMCP = k32("GetOEMCP", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle TICK_COUNT = k32("GetTickCount64", FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle INIT_ATTRS = k32("InitializeProcThreadAttributeList", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle UPDATE_ATTR = k32("UpdateProcThreadAttribute",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle DELETE_ATTRS = k32("DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CREATE_PROCESS = k32("CreateProcessW",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    private static MemorySegment h(long handle) { return MemorySegment.ofAddress(handle); }

    /** An error from a descriptor-level call: the C runtime's errno mapping, without winerror. */
    static PyException crtError(int winerror) {
        int errno = WindowsNative.errnoOf(winerror);
        return PosixModule.osError(WindowsNative.errorType(errno), errno, WindowsNtModule.strerror(errno), null);
    }

    /**
     * What _read/_write/_close report: ERROR_ACCESS_DENIED is EBADF (a read on a write end), and a write to a pipe whose
     * reader is gone (ERROR_NO_DATA) comes out as EINVAL, as CPython on Windows shows.
     */
    static PyException crtIoError(int winerror) {
        if (winerror == 5) return crtError(6);
        if (winerror == 232) return PosixModule.osError("OSError", 22, WindowsNtModule.strerror(22), null);
        return crtError(winerror);
    }

    // ---- pipes and descriptor I/O ------------------------------------------------------------------

    /** {read, write}: an anonymous pipe whose ends are not inheritable. */
    static long[] createPipe(int size) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), r = a.allocate(8), w = a.allocate(8);
            if ((int) CREATE_PIPE.invokeExact(st, r, w, MemorySegment.NULL, size) == 0) throw winError(lastError(st), null);
            return new long[]{r.get(JAVA_LONG, 0), w.get(JAVA_LONG, 0)};
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void closeHandle(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            if ((int) CLOSE.invokeExact(st, h(handle)) == 0) throw winError(lastError(st), null);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** {@code _close}: a failure is an errno-style OSError. */
    static void closeFd(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            if ((int) CLOSE.invokeExact(st, h(handle)) == 0) throw crtIoError(lastError(st));
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** Up to {@code n} bytes (one ReadFile of at most 64 KiB); empty at end of stream (the writer closed). */
    static byte[] read(long handle, int n) {
        int len = Math.min(n, PIPE_CHUNK);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), buf = a.allocate(len), cnt = a.allocate(4);
            if ((int) READ_FILE.invokeExact(st, h(handle), buf, len, cnt, MemorySegment.NULL) == 0) {
                int e = lastError(st);
                if (e == ERROR_BROKEN_PIPE || e == ERROR_HANDLE_EOF) return new byte[0];
                throw crtIoError(e);
            }
            return buf.asSlice(0, cnt.get(JAVA_INT, 0)).toArray(JAVA_BYTE);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int write(long handle, byte[] b, int off, int len) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), buf = a.allocate(Math.max(1, Math.min(len, PIPE_CHUNK))), cnt = a.allocate(4);
            int done = 0;
            while (done < len) {
                int chunk = Math.min(len - done, PIPE_CHUNK);
                buf.asSlice(0, chunk).copyFrom(MemorySegment.ofArray(b).asSlice(off + done, chunk));
                if ((int) WRITE_FILE.invokeExact(st, h(handle), buf, chunk, cnt, MemorySegment.NULL) == 0) throw crtIoError(lastError(st));
                int w = cnt.get(JAVA_INT, 0);
                if (w <= 0) throw crtError(28);       // ERROR_DISK_FULL, as _write reports a short write
                done += w;
            }
            return len;
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** GetFileType; UNKNOWN (0) is an error only for an invalid handle. */
    static int fileType(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            int type = (int) FILE_TYPE.invokeExact(st, h(handle));
            if (type == 0 && lastError(st) == ERROR_INVALID_HANDLE) throw winError(ERROR_INVALID_HANDLE, null);
            return type;
        } catch (Throwable t) { throw rethrow(t); }
    }

    /**
     * A second handle on the file behind a FileChannel-backed descriptor (java.nio does not expose the channel's own
     * handle), positioned at the channel's offset. Not inheritable; closed with the descriptor.
     */
    static long shadowHandle(String path, boolean read, boolean write, boolean append, long position) {
        int access = (read ? 0x80000000 : 0) | (append ? 0x4 : write ? 0x40000000 : 0);   // GENERIC_READ, FILE_APPEND_DATA, GENERIC_WRITE
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment hnd = (MemorySegment) CREATE_FILE.invokeExact(st, wide(a, path), access, 7, MemorySegment.NULL, 3, 0x80, MemorySegment.NULL);
            if (hnd.address() == WindowsNative.INVALID_HANDLE) throw winError(lastError(st), path);
            if (!append && position > 0 && (int) SET_FILE_POINTER.invokeExact(st, hnd, position, MemorySegment.NULL, 0) == 0) {
                int e = lastError(st);
                int ok = (int) CLOSE.invokeExact(st, hnd);
                throw winError(e, path);
            }
            return hnd.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- _winapi -----------------------------------------------------------------------------------

    static long currentProcess() {
        try (Arena a = Arena.ofConfined()) { return ((MemorySegment) CURRENT_PROCESS.invokeExact(a.allocate(CALL_STATE))).address(); } catch (Throwable t) { throw rethrow(t); }
    }

    static long duplicateHandle(long srcProcess, long src, long dstProcess, int access, boolean inherit, int options) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), out = a.allocate(8);
            if ((int) DUPLICATE.invokeExact(st, h(srcProcess), h(src), h(dstProcess), out, access, inherit ? 1 : 0, options) == 0)
                throw winError(lastError(st), null);
            return out.get(JAVA_LONG, 0);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** GetStdHandle's raw result (0 when the process has no such handle, INVALID_HANDLE_VALUE on failure). */
    static long stdHandle(int which) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment r = (MemorySegment) STD_HANDLE.invokeExact(st, which);
            if (r.address() == WindowsNative.INVALID_HANDLE) throw winError(lastError(st), null);
            return r.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int waitForSingleObject(long handle, int millis) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            int r = (int) WAIT_ONE.invokeExact(st, h(handle), millis);
            if (r == WAIT_FAILED) throw winError(lastError(st), null);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int waitForMultipleObjects(long[] handles, boolean all, int millis) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), arr = a.allocate(8L * Math.max(1, handles.length), 8);
            for (int i = 0; i < handles.length; i++) arr.set(JAVA_LONG, 8L * i, handles[i]);
            int r = (int) WAIT_MANY.invokeExact(st, handles.length, arr, all ? 1 : 0, millis);
            if (r == WAIT_FAILED) throw winError(lastError(st), null);
            return r;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int exitCode(long process) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE), out = a.allocate(4);
            if ((int) EXIT_CODE.invokeExact(st, h(process), out) == 0) throw winError(lastError(st), null);
            return out.get(JAVA_INT, 0);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static void terminate(long process, int code) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            if ((int) TERMINATE.invokeExact(st, h(process), code) == 0) throw winError(lastError(st), null);
        } catch (Throwable t) { throw rethrow(t); }
    }

    static long openProcess(int access, boolean inherit, int pid) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            MemorySegment r = (MemorySegment) OPEN_PROCESS.invokeExact(st, access, inherit ? 1 : 0, pid);
            if (r.address() == 0) throw winError(lastError(st), null);
            return r.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    static boolean needCurrentDirectoryForExePath(String exe) {
        try (Arena a = Arena.ofConfined()) {
            return (int) NEED_CURDIR.invokeExact(a.allocate(CALL_STATE), wide(a, exe)) != 0;
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int errorMode() { try (Arena a = Arena.ofConfined()) { return (int) GET_ERROR_MODE.invokeExact(a.allocate(CALL_STATE)); } catch (Throwable t) { throw rethrow(t); } }

    static int setErrorMode(int mode) { try (Arena a = Arena.ofConfined()) { return (int) SET_ERROR_MODE.invokeExact(a.allocate(CALL_STATE), mode); } catch (Throwable t) { throw rethrow(t); } }

    static int version() { try (Arena a = Arena.ofConfined()) { return (int) GET_VERSION.invokeExact(a.allocate(CALL_STATE)); } catch (Throwable t) { throw rethrow(t); } }

    static int acp() { try (Arena a = Arena.ofConfined()) { return (int) GET_ACP.invokeExact(a.allocate(CALL_STATE)); } catch (Throwable t) { throw rethrow(t); } }

    static int oemcp() { try (Arena a = Arena.ofConfined()) { return (int) GET_OEMCP.invokeExact(a.allocate(CALL_STATE)); } catch (Throwable t) { throw rethrow(t); } }

    static long tickCount64() { try (Arena a = Arena.ofConfined()) { return (long) TICK_COUNT.invokeExact(a.allocate(CALL_STATE)); } catch (Throwable t) { throw rethrow(t); } }

    /** What CreateProcess reads from a {@code subprocess.STARTUPINFO}. */
    static final class StartInfo {
        int flags, showWindow;
        long stdIn, stdOut, stdErr;
        long[] handleList;           // the inherit list (PROC_THREAD_ATTRIBUTE_HANDLE_LIST), or null
    }

    /**
     * CreateProcessW. {@code env} is a complete block ("k=v\0k=v\0", no final terminator) or null to inherit; the new
     * process always gets a Unicode environment. Returns {hProcess, hThread, pid, tid}.
     */
    static long[] createProcess(String app, String cmd, boolean inherit, int flags, String env, String cwd, StartInfo si) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(CALL_STATE);
            boolean ex = si.handleList != null;
            MemorySegment info = a.allocate(ex ? 112 : 104, 8);          // STARTUPINFO(EX)W, zeroed
            info.set(JAVA_INT, 0, ex ? 112 : 104);
            info.set(JAVA_INT, 60, si.flags);
            info.set(JAVA_SHORT, 64, (short) si.showWindow);
            info.set(JAVA_LONG, 80, si.stdIn); info.set(JAVA_LONG, 88, si.stdOut); info.set(JAVA_LONG, 96, si.stdErr);
            boolean listed = false;
            MemorySegment list = null;
            try {
                if (ex) {
                    MemorySegment size = a.allocate(8);
                    int ignored = (int) INIT_ATTRS.invokeExact(st, MemorySegment.NULL, 1, 0, size);      // fails, reporting the size needed
                    list = a.allocate(size.get(JAVA_LONG, 0), 16);
                    if ((int) INIT_ATTRS.invokeExact(st, list, 1, 0, size) == 0) throw winError(lastError(st), null);
                    listed = true;
                    MemorySegment hs = a.allocate(8L * si.handleList.length, 8);
                    for (int i = 0; i < si.handleList.length; i++) hs.set(JAVA_LONG, 8L * i, si.handleList[i]);
                    if ((int) UPDATE_ATTR.invokeExact(st, list, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, hs, 8L * si.handleList.length,
                        MemorySegment.NULL, MemorySegment.NULL) == 0) throw winError(lastError(st), null);
                    info.set(ADDRESS, 104, list);
                    flags |= EXTENDED_STARTUPINFO_PRESENT;
                }
                MemorySegment pi = a.allocate(24, 8);
                MemorySegment appSeg = app == null ? MemorySegment.NULL : wide(a, app);
                MemorySegment cmdSeg = cmd == null ? MemorySegment.NULL : wide(a, cmd);
                MemorySegment envSeg = env == null ? MemorySegment.NULL : wide(a, env + "\0");
                MemorySegment cwdSeg = cwd == null ? MemorySegment.NULL : wide(a, cwd);
                int ok = (int) CREATE_PROCESS.invokeExact(st, appSeg, cmdSeg, MemorySegment.NULL, MemorySegment.NULL, inherit ? 1 : 0,
                    flags | CREATE_UNICODE_ENVIRONMENT, envSeg, cwdSeg, info, pi);
                if (ok == 0) throw winError(lastError(st), null);
                return new long[]{pi.get(JAVA_LONG, 0), pi.get(JAVA_LONG, 8), Integer.toUnsignedLong(pi.get(JAVA_INT, 16)), Integer.toUnsignedLong(pi.get(JAVA_INT, 20))};
            } finally {
                if (listed) DELETE_ATTRS.invokeExact(st, list);
            }
        } catch (Throwable t) { throw rethrow(t); }
    }
}
