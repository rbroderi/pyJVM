// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code _winapi}: the Win32 process and handle calls Lib/subprocess.py uses (CreateProcess with STARTUPINFO and an
 * inherit list, CreatePipe, DuplicateHandle, the wait/exit-code/terminate calls, GetStdHandle, GetFileType), plus
 * OpenProcess, WaitForMultipleObjects, NeedCurrentDirectoryForExePath (shutil.which), GetVersion, GetACP, GetOEMCP and
 * GetTickCount64. Handles are ints, as in CPython (unsigned: -1 is 2**64-1). The overlapped-I/O, named-pipe, event,
 * mutex, file-mapping, CopyFile2 and LCMapStringEx parts of CPython's module are absent.
 */
final class WindowsWinApiModule {
    private WindowsWinApiModule() {}

    private static final Object[] CONSTANTS = {
        "ABOVE_NORMAL_PRIORITY_CLASS", 32768L, "BELOW_NORMAL_PRIORITY_CLASS", 16384L, "HIGH_PRIORITY_CLASS", 128L, "IDLE_PRIORITY_CLASS", 64L,
        "NORMAL_PRIORITY_CLASS", 32L, "REALTIME_PRIORITY_CLASS", 256L, "CREATE_BREAKAWAY_FROM_JOB", 16777216L, "CREATE_DEFAULT_ERROR_MODE", 67108864L,
        "CREATE_NEW_CONSOLE", 16L, "CREATE_NEW_PROCESS_GROUP", 512L, "CREATE_NO_WINDOW", 134217728L, "DETACHED_PROCESS", 8L,
        "DUPLICATE_CLOSE_SOURCE", 1L, "DUPLICATE_SAME_ACCESS", 2L, "FILE_TYPE_CHAR", 2L, "FILE_TYPE_DISK", 1L, "FILE_TYPE_PIPE", 3L,
        "FILE_TYPE_REMOTE", 32768L, "FILE_TYPE_UNKNOWN", 0L, "INFINITE", 4294967295L, "NULL", 0L,
        "STARTF_FORCEOFFFEEDBACK", 128L, "STARTF_FORCEONFEEDBACK", 64L, "STARTF_PREVENTPINNING", 8192L, "STARTF_RUNFULLSCREEN", 32L,
        "STARTF_TITLEISAPPID", 4096L, "STARTF_TITLEISLINKNAME", 2048L, "STARTF_UNTRUSTEDSOURCE", 32768L, "STARTF_USECOUNTCHARS", 8L,
        "STARTF_USEFILLATTRIBUTE", 16L, "STARTF_USEHOTKEY", 512L, "STARTF_USEPOSITION", 4L, "STARTF_USESHOWWINDOW", 1L, "STARTF_USESIZE", 2L,
        "STARTF_USESTDHANDLES", 256L, "STD_ERROR_HANDLE", 4294967284L, "STD_INPUT_HANDLE", 4294967286L, "STD_OUTPUT_HANDLE", 4294967285L,
        "STILL_ACTIVE", 259L, "SW_HIDE", 0L, "SYNCHRONIZE", 1048576L, "WAIT_ABANDONED_0", 128L, "WAIT_OBJECT_0", 0L, "WAIT_TIMEOUT", 258L,
        "PROCESS_ALL_ACCESS", 2097151L, "PROCESS_DUP_HANDLE", 64L, "GENERIC_READ", 2147483648L, "GENERIC_WRITE", 1073741824L,
        "ERROR_ACCESS_DENIED", 5L, "ERROR_BROKEN_PIPE", 109L, "ERROR_MORE_DATA", 234L, "ERROR_NO_DATA", 232L, "ERROR_PRIVILEGE_NOT_HELD", 1314L};

    static synchronized PyModule create() {
        PyModule m = new PyModule("_winapi", WindowsWinApiModule.class.getName());
        for (int i = 0; i < CONSTANTS.length; i += 2) m.attrs.put((String) CONSTANTS[i], CONSTANTS[i + 1]);
        m.attrs.put("INVALID_HANDLE_VALUE", unsigned(-1L));
        fn(m, "CloseHandle", (a, k) -> { WindowsHandles.closeHandle(handle(a.get(0))); return null; });
        fn(m, "CreatePipe", (a, k) -> createPipe(a.get(0), a.get(1)));
        fn(m, "DuplicateHandle", (a, k) -> unsigned(WindowsHandles.duplicateHandle(handle(a.get(0)), handle(a.get(1)), handle(a.get(2)),
            (int) IoUtil.asLong(a.get(3)), PyRuntime.truth(a.get(4)), a.size() > 5 ? (int) IoUtil.asLong(a.get(5)) : 0)));
        fn(m, "GetCurrentProcess", (a, k) -> unsigned(WindowsHandles.currentProcess()));
        fn(m, "GetExitCodeProcess", (a, k) -> Integer.toUnsignedLong(WindowsHandles.exitCode(handle(a.get(0)))));
        fn(m, "GetFileType", (a, k) -> (long) WindowsHandles.fileType(handle(a.get(0))));
        fn(m, "GetStdHandle", (a, k) -> {
            long h = WindowsHandles.stdHandle((int) IoUtil.asLong(a.get(0)));
            return h == 0 ? null : unsigned(h);
        });
        fn(m, "TerminateProcess", (a, k) -> { WindowsHandles.terminate(handle(a.get(0)), (int) IoUtil.asLong(a.get(1))); return null; });
        fn(m, "WaitForSingleObject", (a, k) -> Integer.toUnsignedLong(WindowsHandles.waitForSingleObject(handle(a.get(0)), (int) IoUtil.asLong(a.get(1)))));
        fn(m, "WaitForMultipleObjects", (a, k) -> waitMany(a.get(0), a.get(1), a.size() > 2 ? a.get(2) : k.getOrDefault("milliseconds", 0xFFFFFFFFL)));
        fn(m, "OpenProcess", (a, k) -> unsigned(WindowsHandles.openProcess((int) IoUtil.asLong(a.get(0)), PyRuntime.truth(a.get(1)), (int) IoUtil.asLong(a.get(2)))));
        fn(m, "NeedCurrentDirectoryForExePath", (a, k) -> WindowsHandles.needCurrentDirectoryForExePath(string(a.get(0), "NeedCurrentDirectoryForExePath")));
        fn(m, "GetVersion", (a, k) -> Integer.toUnsignedLong(WindowsHandles.version()));
        fn(m, "GetACP", (a, k) -> (long) WindowsHandles.acp());
        if (LangVersion.atLeast(3, 15)) fn(m, "GetOEMCP", (a, k) -> (long) WindowsHandles.oemcp());
        fn(m, "GetTickCount64", (a, k) -> unsigned(WindowsHandles.tickCount64()));
        fn(m, "CreateProcess", (a, k) -> createProcess(a));
        return m;
    }

    private static Object unsigned(long v) { return v >= 0 ? (Object) v : new BigInteger(Long.toUnsignedString(v)); }

    /** A handle argument: an int (unsigned or signed), None meaning NULL. */
    private static long handle(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o == null) return 0;
        if (o instanceof Long l) return l;
        if (o instanceof BigInteger b) return b.longValue();
        if (o instanceof Boolean b) return b ? 1 : 0;
        throw new PyException("TypeError", "an integer is required, not " + PyRuntime.typeName(o));
    }

    private static String string(Object o, String fn) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof String s) return s;
        throw new PyException("TypeError", fn + "() argument must be str, not " + PyRuntime.typeName(o));
    }

    private static String optionalString(Object o, String fn) { return o == null ? null : string(o, fn); }

    private static Object createPipe(Object attributes, Object size) {
        if (attributes != null) throw new PyException("ValueError", "CreatePipe: pipe_attrs must be None");
        long[] ends = WindowsHandles.createPipe((int) IoUtil.asLong(size));
        return tuple(unsigned(ends[0]), unsigned(ends[1]));
    }

    private static Object waitMany(Object handles, Object waitAll, Object millis) {
        List<?> items = handles instanceof PyTuple t ? t.items : handles instanceof List<?> l ? l : null;
        if (items == null) throw new PyException("TypeError", "handle_seq must be a tuple or list of handles");
        if (items.size() > 64) throw new PyException("ValueError", "need at most 64 handles");
        long[] hs = new long[items.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = handle(items.get(i));
        int r = WindowsHandles.waitForMultipleObjects(hs, PyRuntime.truth(waitAll), (int) IoUtil.asLong(millis));
        return Integer.toUnsignedLong(r);
    }

    /** {@code CreateProcess(app_name, command_line, proc_attrs, thread_attrs, inherit_handles, creation_flags, env_mapping, current_directory, startup_info)}. */
    private static Object createProcess(List<Object> a) {
        if (a.size() != 9) throw new PyException("TypeError", "CreateProcess expected 9 arguments, got " + a.size());
        if (a.get(2) != null || a.get(3) != null) throw new PyException("ValueError", "CreateProcess: proc_attrs and thread_attrs must be None");
        String app = optionalString(a.get(0), "CreateProcess"), cmd = optionalString(a.get(1), "CreateProcess");
        String cwd = a.get(7) == null ? PosixModule.cwd() : PosixModule.resolve(string(a.get(7), "CreateProcess")).toAbsolutePath().toString();
        long[] r = WindowsHandles.createProcess(app, cmd, PyRuntime.truth(a.get(4)), (int) IoUtil.asLong(a.get(5)), environment(a.get(6)), cwd, startInfo(a.get(8)));
        return tuple(unsigned(r[0]), unsigned(r[1]), r[2], r[3]);
    }

    /** An environment block ("k=v\0k=v\0"), names sorted case-insensitively as CreateProcess documents, or null to inherit. */
    private static String environment(Object mapping) {
        if (mapping == null) return null;
        if (!(mapping instanceof Map<?,?> map)) throw new PyException("TypeError", "environment must be a mapping or None");
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<?,?> e : map.entrySet()) {
            String key = string(e.getKey(), "CreateProcess"), value = string(e.getValue(), "CreateProcess");
            if (key.indexOf('\0') >= 0 || value.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character");
            if (key.indexOf('=', 1) >= 0) throw new PyException("ValueError", "illegal environment variable name");
            pairs.add(new String[]{key, value});
        }
        pairs.sort((x, y) -> x[0].toUpperCase(java.util.Locale.ROOT).compareTo(y[0].toUpperCase(java.util.Locale.ROOT)));
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) sb.append(p[0]).append('=').append(p[1]).append('\0');
        return pairs.isEmpty() ? "\0" : sb.toString();
    }

    private static WindowsHandles.StartInfo startInfo(Object o) {
        WindowsHandles.StartInfo si = new WindowsHandles.StartInfo();
        if (o == null) return si;
        si.flags = (int) IoUtil.asLong(attr(o, "dwFlags", 0L));
        si.stdIn = handle(attr(o, "hStdInput", null));
        si.stdOut = handle(attr(o, "hStdOutput", null));
        si.stdErr = handle(attr(o, "hStdError", null));
        si.showWindow = (int) IoUtil.asLong(attr(o, "wShowWindow", 0L));
        Object attrs = attr(o, "lpAttributeList", null);
        if (attrs != null) {
            if (!(attrs instanceof Map<?,?> map)) throw new PyException("TypeError", "startup_info.lpAttributeList must be a dict");
            Object list = map.get("handle_list");
            if (list != null) {
                List<?> items = list instanceof PyTuple t ? t.items : list instanceof List<?> l ? l : null;
                if (items == null) throw new PyException("TypeError", "startup_info.lpAttributeList['handle_list'] must be a list");
                if (!items.isEmpty()) {
                    si.handleList = new long[items.size()];
                    for (int i = 0; i < si.handleList.length; i++) si.handleList[i] = handle(items.get(i));
                }
            }
        }
        return si;
    }

    private static Object attr(Object o, String name, Object fallback) { return PyRuntime.getattrDefault(o, name, fallback); }
}
