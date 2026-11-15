// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Win32 calls through the Foreign Function and Memory API for what java.nio does not expose: file identity (volume
 * serial, file index, link count, change time, reparse tag), final and full path names, the console size and the
 * registry. Only touched on Windows. Calls that report failure through GetLastError capture it per call
 * ({@code Linker.Option.captureCallState}), so the JVM's own Win32 calls cannot overwrite it.
 */
final class WindowsNative {
    private WindowsNative() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("GetLastError");

    static final long INVALID_HANDLE = -1L;

    static final int FILE_READ_ATTRIBUTES = 0x80, FILE_SHARE_ALL = 7, OPEN_EXISTING = 3;
    static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000, FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    static final int FILE_ATTRIBUTE_READONLY = 0x1, FILE_ATTRIBUTE_DIRECTORY = 0x10, FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    static final int IO_REPARSE_TAG_SYMLINK = 0xA000000C, IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003;
    static final int FILE_TYPE_DISK = 1, FILE_TYPE_CHAR = 2, FILE_TYPE_PIPE = 3;

    private static MethodHandle k32(String name, FunctionDescriptor fd) { return LINKER.downcallHandle(KERNEL32.findOrThrow(name), fd, CAPTURE); }
    private static MethodHandle adv(String name, FunctionDescriptor fd) { return LINKER.downcallHandle(ADVAPI32.findOrThrow(name), fd); }

    private static final MethodHandle CREATE_FILE = k32("CreateFileW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE_HANDLE = k32("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FILE_INFO = k32("GetFileInformationByHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FILE_INFO_EX = k32("GetFileInformationByHandleEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle FINAL_PATH = k32("GetFinalPathNameByHandleW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle FULL_PATH = k32("GetFullPathNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FIND_FIRST = k32("FindFirstFileW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FIND_CLOSE = k32("FindClose", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle STD_HANDLE = k32("GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle FILE_TYPE = k32("GetFileType", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CONSOLE_INFO = k32("GetConsoleScreenBufferInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle EXPAND_ENV = k32("ExpandEnvironmentStringsW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle FORMAT_MESSAGE = LINKER.downcallHandle(KERNEL32.findOrThrow("FormatMessageW"),
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    // ---- errors ----------------------------------------------------------------------------------

    private static int lastError(MemorySegment state) { return (int) LAST_ERROR.get(state, 0L); }

    /** FormatMessageW's text for a Win32 error code, trimmed of trailing white space and dots as CPython does. */
    static String message(int code) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(2048 * 2L);
            int n = (int) FORMAT_MESSAGE.invokeExact(0x1000 | 0x200, MemorySegment.NULL, code, 0, buf, 2048, MemorySegment.NULL);
            if (n == 0) return "Windows Error 0x" + Integer.toHexString(code);
            String s = wideString(buf, n);
            int end = s.length();
            while (end > 0 && (s.charAt(end - 1) <= ' ' || s.charAt(end - 1) == '.')) end--;
            return s.substring(0, end);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** CPython's winerror_to_errno (PC/errmap.h), generated from CPython 3.13/3.15: {@code OSError(None, '', None, w).errno}. */
    static int errnoOf(int winerror) {
        if (winerror >= 10000 && winerror < 12000) return winerror;    // Winsock codes are errno values themselves
        return switch (winerror) {
            case 2, 3, 15, 18, 53, 67, 161, 206 -> 2;                                                       // ENOENT
            case 10 -> 7;                                                                                   // E2BIG
            case 11, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202 -> 8;        // ENOEXEC
            case 6, 114, 130 -> 9;                                                                          // EBADF
            case 128, 129 -> 10;                                                                            // ECHILD
            case 89, 164, 215 -> 11;                                                                        // EAGAIN
            case 7, 8, 9, 1816 -> 12;                                                                       // ENOMEM
            case 5, 16, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 65, 82, 83, 108, 132, 158, 167 -> 13; // EACCES
            case 80, 183 -> 17;                                                                             // EEXIST
            case 17 -> 18;                                                                                  // EXDEV
            case 267 -> 20;                                                                                 // ENOTDIR
            case 4 -> 24;                                                                                   // EMFILE
            case 112 -> 28;                                                                                 // ENOSPC
            case 109, 232 -> 32;                                                                            // EPIPE
            case 145 -> 41;                                                                                 // ENOTEMPTY
            case 1113 -> 42;                                                                                // EILSEQ
            case 258 -> 138;                                                                                // MSVC ETIMEDOUT
            default -> 22;                                                                                  // EINVAL
        };
    }

    /** CPython's errno -> OSError subclass map on Windows (Objects/exceptions.c, MSVC and Winsock values). */
    static String errorType(int errno) {
        return switch (errno) {
            case 2 -> "FileNotFoundError";
            case 1, 13 -> "PermissionError";
            case 17 -> "FileExistsError";
            case 20 -> "NotADirectoryError";
            case 21 -> "IsADirectoryError";
            case 32, 10058 -> "BrokenPipeError";
            case 10 -> "ChildProcessError";
            case 3 -> "ProcessLookupError";
            case 4 -> "InterruptedError";
            case 11, 10035, 10036, 10037 -> "BlockingIOError";
            case 138, 10060 -> "TimeoutError";
            case 10061 -> "ConnectionRefusedError";
            case 10053 -> "ConnectionAbortedError";
            case 10054 -> "ConnectionResetError";
            default -> "OSError";
        };
    }

    /** {@code OSError} with errno, strerror, filename and winerror, typed by errno as CPython's OSError constructor does. */
    static PyException winError(int code, String filename) {
        int errno = errnoOf(code);
        String type = errorType(errno);
        String msg = message(code);
        LinkedHashMap<String,Object> attrs = new LinkedHashMap<>();
        attrs.put("errno", (long) errno); attrs.put("strerror", msg); attrs.put("filename", filename); attrs.put("winerror", (long) code);
        return new PyException(type, new PyExcArgs(NativeTypes.tuple((long) errno, msg, filename, (long) code), null, attrs));
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    static MemorySegment wide(Arena a, String s) { return a.allocateFrom(s, StandardCharsets.UTF_16LE); }

    static String wideString(MemorySegment buf, int chars) {
        byte[] b = buf.asSlice(0, chars * 2L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        return new String(b, StandardCharsets.UTF_16LE);
    }

    // ---- files -----------------------------------------------------------------------------------

    /** What GetFileInformationByHandle(Ex) report for a path; times are ns since the Unix epoch. */
    static final class FileInfo {
        int attributes, reparseTag;
        long volume, index, indexHigh, links, size, created, accessed, written, changed;
    }

    /** FILETIME (100 ns ticks since 1601) to ns since 1970. */
    static long filetimeNs(long ticks) { return (ticks - 116_444_736_000_000_000L) * 100L; }

    private static long open(Arena a, String path, boolean follow, MemorySegment state) throws Throwable {
        int flags = FILE_FLAG_BACKUP_SEMANTICS | (follow ? 0 : FILE_FLAG_OPEN_REPARSE_POINT);
        MemorySegment h = (MemorySegment) CREATE_FILE.invokeExact(state, wide(a, path), FILE_READ_ATTRIBUTES, FILE_SHARE_ALL,
            MemorySegment.NULL, OPEN_EXISTING, flags, MemorySegment.NULL);
        return h.address();
    }

    /**
     * The file's identity and attributes, or a {@code winError} naming {@code shown}. Without {@code follow} a reparse
     * point that is not a name surrogate (a symbolic link or junction) is followed anyway, as CPython's lstat does.
     */
    static FileInfo fileInfo(String path, boolean follow, String shown) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            long h = open(a, path, follow, state);
            if (h == INVALID_HANDLE) throw winError(lastError(state), shown);
            FileInfo fi;
            try {
                fi = query(a, h, state, shown);
            } finally {
                int ok = (int) CLOSE_HANDLE.invokeExact(state, MemorySegment.ofAddress(h));
            }
            if (!follow && (fi.attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0 && (fi.reparseTag & 0x20000000) == 0)
                return fileInfo(path, true, shown);
            return fi;
        } catch (Throwable t) { throw rethrow(t); }
    }

    private static FileInfo query(Arena a, long h, MemorySegment state, String shown) throws Throwable {
        MemorySegment handle = MemorySegment.ofAddress(h);
        // BY_HANDLE_FILE_INFORMATION: attributes, 3 FILETIMEs, volume serial, size high/low, links, index high/low
        MemorySegment bh = a.allocate(52, 4);
        if ((int) FILE_INFO.invokeExact(state, handle, bh) == 0) throw winError(lastError(state), shown);
        FileInfo fi = new FileInfo();
        fi.attributes = bh.get(JAVA_INT, 0);
        fi.volume = Integer.toUnsignedLong(bh.get(JAVA_INT, 28));
        fi.size = (Integer.toUnsignedLong(bh.get(JAVA_INT, 32)) << 32) | Integer.toUnsignedLong(bh.get(JAVA_INT, 36));
        fi.links = Integer.toUnsignedLong(bh.get(JAVA_INT, 40));
        fi.index = (Integer.toUnsignedLong(bh.get(JAVA_INT, 44)) << 32) | Integer.toUnsignedLong(bh.get(JAVA_INT, 48));
        // FILE_BASIC_INFO (class 0): creation, last access, last write, change times as LARGE_INTEGERs
        MemorySegment basic = a.allocate(40, 8);
        if ((int) FILE_INFO_EX.invokeExact(state, handle, 0, basic, 40) == 0) throw winError(lastError(state), shown);
        fi.created = filetimeNs(basic.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0));
        fi.accessed = filetimeNs(basic.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8));
        fi.written = filetimeNs(basic.get(java.lang.foreign.ValueLayout.JAVA_LONG, 16));
        fi.changed = filetimeNs(basic.get(java.lang.foreign.ValueLayout.JAVA_LONG, 24));
        // FILE_ID_INFO (class 18): 64-bit volume serial and 128-bit file id, which CPython 3.12+ reports when available
        MemorySegment id = a.allocate(24, 8);
        if ((int) FILE_INFO_EX.invokeExact(state, handle, 18, id, 24) != 0) {
            fi.volume = id.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
            fi.index = id.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8);
            fi.indexHigh = id.get(java.lang.foreign.ValueLayout.JAVA_LONG, 16);
        }
        if ((fi.attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            // FILE_ATTRIBUTE_TAG_INFO (class 9): attributes, reparse tag
            MemorySegment tag = a.allocate(8, 4);
            if ((int) FILE_INFO_EX.invokeExact(state, handle, 9, tag, 8) == 0) throw winError(lastError(state), shown);
            fi.reparseTag = tag.get(JAVA_INT, 4);
        }
        return fi;
    }

    /** {@link #fileInfo} for an open handle (a standard stream on disk). */
    static FileInfo handleInfo(long handle) {
        try (Arena a = Arena.ofConfined()) {
            return query(a, handle, a.allocate(CALL_STATE), null);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** GetFinalPathNameByHandleW (VOLUME_NAME_DOS): the {@code \\?\}-prefixed path with links resolved. */
    static String finalPath(String path, String shown) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            long h = open(a, path, true, state);
            if (h == INVALID_HANDLE) throw winError(lastError(state), shown);
            try {
                int size = 512;
                while (true) {
                    MemorySegment buf = a.allocate(size * 2L);
                    int n = (int) FINAL_PATH.invokeExact(state, MemorySegment.ofAddress(h), buf, size, 0);
                    if (n == 0) throw winError(lastError(state), shown);
                    if (n < size) return wideString(buf, n);
                    size = n + 1;
                }
            } finally {
                int ok = (int) CLOSE_HANDLE.invokeExact(state, MemorySegment.ofAddress(h));
            }
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** GetFullPathNameW: an absolute, normalised path (relative paths resolve against the process directory). */
    static String fullPath(String path, String shown) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            MemorySegment in = wide(a, path);
            int size = 512;
            while (true) {
                MemorySegment buf = a.allocate(size * 2L);
                int n = (int) FULL_PATH.invokeExact(state, in, size, buf, MemorySegment.NULL);
                if (n == 0) throw winError(lastError(state), shown);
                if (n < size) return wideString(buf, n);
                size = n;
            }
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** FindFirstFileW: the directory entry's name as stored (its case), for ntpath.realpath. */
    static String findFirstFile(String path, String shown) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            MemorySegment data = a.allocate(592, 4);       // WIN32_FIND_DATAW; cFileName at 44, 260 WCHARs
            MemorySegment h = (MemorySegment) FIND_FIRST.invokeExact(state, wide(a, path), data);
            if (h.address() == INVALID_HANDLE) throw winError(lastError(state), shown);
            int ok = (int) FIND_CLOSE.invokeExact(state, h);
            MemorySegment name = data.asSlice(44, 520);
            int len = 0;
            while (len < 260 && name.get(JAVA_SHORT, len * 2L) != 0) len++;
            return wideString(name, len);
        } catch (Throwable t) { throw rethrow(t); }
    }

    private static final MethodHandle SKIP_ROOT = LINKER.downcallHandle(
        SymbolLookup.libraryLookup("kernelbase", Arena.global()).findOrThrow("PathCchSkipRoot"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * nt._path_splitroot (Modules/posixmodule.c): PathCchSkipRoot on a copy with '/' turned into '\', split at the root's
     * end; ("", path) when the call fails, (path, "") when there is no root.
     */
    static PyTuple splitRoot(String path) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = wide(a, path.replace('/', '\\'));
            MemorySegment end = a.allocate(ADDRESS);
            int hr = (int) SKIP_ROOT.invokeExact(buf, end);
            if (hr < 0) return NativeTypes.tuple("", path);
            int rootLen = (int) ((end.get(ADDRESS, 0).address() - buf.address()) / 2);
            if (rootLen == 0) return NativeTypes.tuple(path, "");
            return NativeTypes.tuple(path.substring(0, rootLen), path.substring(rootLen));
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** GetStdHandle for fd 0, 1, 2 as a raw handle value. */
    static long stdHandle(int fd) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            MemorySegment h = (MemorySegment) STD_HANDLE.invokeExact(state, -10 - fd);
            if (h.address() == INVALID_HANDLE) throw winError(lastError(state), null);
            return h.address();
        } catch (Throwable t) { throw rethrow(t); }
    }

    static int fileType(long handle) {
        try (Arena a = Arena.ofConfined()) {
            return (int) FILE_TYPE.invokeExact(a.allocate(CALL_STATE), MemorySegment.ofAddress(handle));
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** The visible window of the console behind {@code handle}: {columns, lines}. */
    static long[] consoleSize(long handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            MemorySegment info = a.allocate(22, 2);    // CONSOLE_SCREEN_BUFFER_INFO; srWindow (left, top, right, bottom) at 10
            if ((int) CONSOLE_INFO.invokeExact(state, MemorySegment.ofAddress(handle), info) == 0) throw winError(lastError(state), null);
            long cols = info.get(JAVA_SHORT, 14) - info.get(JAVA_SHORT, 10) + 1, lines = info.get(JAVA_SHORT, 16) - info.get(JAVA_SHORT, 12) + 1;
            return new long[]{cols, lines};
        } catch (Throwable t) { throw rethrow(t); }
    }

    static String expandEnvironmentStrings(String s) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(CALL_STATE);
            MemorySegment in = wide(a, s);
            int size = Math.max(64, s.length() + 1);
            while (true) {
                MemorySegment buf = a.allocate(size * 2L);
                int n = (int) EXPAND_ENV.invokeExact(state, in, buf, size);
                if (n == 0) throw winError(lastError(state), null);
                if (n <= size) return wideString(buf, n - 1);
                size = n;
            }
        } catch (Throwable t) { throw rethrow(t); }
    }

    // ---- registry (status codes are returned, not left in GetLastError) --------------------------

    static final MethodHandle REG_OPEN = adv("RegOpenKeyExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle REG_CREATE = adv("RegCreateKeyExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle REG_CLOSE = adv("RegCloseKey", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle REG_CONNECT = adv("RegConnectRegistryW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle REG_QUERY_VALUE = adv("RegQueryValueExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle REG_SET_VALUE = adv("RegSetValueExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle REG_ENUM_KEY = adv("RegEnumKeyExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle REG_ENUM_VALUE = adv("RegEnumValueW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle REG_QUERY_INFO = adv("RegQueryInfoKeyW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle REG_DELETE_KEY_EX = adv("RegDeleteKeyExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle REG_DELETE_VALUE = adv("RegDeleteValueW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle REG_DELETE_TREE = adv("RegDeleteTreeW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle REG_FLUSH = adv("RegFlushKey", FunctionDescriptor.of(JAVA_INT, ADDRESS));
}
