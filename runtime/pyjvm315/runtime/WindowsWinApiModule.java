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
 * GetTickCount64, plus CreateNamedPipe, CreateFile, ConnectNamedPipe (also overlapped=True, returning a
 * {@code _winapi.Overlapped}), CreateEventW, SetEvent and ResetEvent. Handles are ints, as in CPython (unsigned: -1 is
 * 2**64-1); security attributes must be 0. Absent: overlapped ReadFile/WriteFile, PeekNamedPipe, WaitNamedPipe,
 * SetNamedPipeHandleState, BatchedWaitForMultipleObjects, mutex, file-mapping, CopyFile2 and LCMapStringEx.
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
        "ERROR_ACCESS_DENIED", 5L, "ERROR_BROKEN_PIPE", 109L, "ERROR_MORE_DATA", 234L, "ERROR_NO_DATA", 232L, "ERROR_PRIVILEGE_NOT_HELD", 1314L,
        "FILE_FLAG_OVERLAPPED", 1073741824L, "FILE_FLAG_FIRST_PIPE_INSTANCE", 524288L, "PIPE_ACCESS_DUPLEX", 3L, "PIPE_ACCESS_INBOUND", 1L,
        "PIPE_READMODE_MESSAGE", 2L, "PIPE_TYPE_MESSAGE", 4L, "PIPE_WAIT", 0L, "PIPE_UNLIMITED_INSTANCES", 255L, "NMPWAIT_WAIT_FOREVER", 4294967295L,
        "OPEN_EXISTING", 3L, "ERROR_PIPE_BUSY", 231L, "ERROR_IO_PENDING", 997L, "ERROR_OPERATION_ABORTED", 995L, "ERROR_NETNAME_DELETED", 64L,
        "ERROR_SEM_TIMEOUT", 121L, "ERROR_PIPE_CONNECTED", 535L, "ERROR_ALREADY_EXISTS", 183L, "FILE_GENERIC_READ", 1179785L, "FILE_GENERIC_WRITE", 1179926L};

    static synchronized PyModule create() {
        PyModule m = new PyModule("_winapi", WindowsWinApiModule.class.getName());
        for (int i = 0; i < CONSTANTS.length; i += 2) m.attrs.put((String) CONSTANTS[i], CONSTANTS[i + 1]);
        m.attrs.put("INVALID_HANDLE_VALUE", unsigned(-1L));
        fn(m, "CloseHandle", (a, k) -> { WindowsHandles.closeHandle(handle(a.get(0))); return null; });
        fn(m, "CreatePipe", (a, k) -> createPipe(a.get(0), a.get(1)));
        fn(m, "DuplicateHandle", (a, k) -> duplicateHandle(a));
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
        fn(m, "CreateNamedPipe", (a, k) -> {
            List<Object> p = exact("CreateNamedPipe", a, k, 8);
            String name = wstr(p.get(0), "CreateNamedPipe", 1);
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) v[i] = dw(p.get(i + 1), "CreateNamedPipe", i + 2);
            securityNull(p.get(7), "CreateNamedPipe", 8);
            return handleResult(WindowsIocp.createNamedPipe(name, v[0], v[1], v[2], v[3], v[4], v[5]));
        });
        fn(m, "CreateFile", (a, k) -> {
            List<Object> p = exact("CreateFile", a, k, 7);
            String name = wstr(p.get(0), "CreateFile", 1);
            int access = dw(p.get(1), "CreateFile", 2), share = dw(p.get(2), "CreateFile", 3);
            securityNull(p.get(3), "CreateFile", 4);
            int disposition = dw(p.get(4), "CreateFile", 5), flags = dw(p.get(5), "CreateFile", 6);
            long template = ptr(p.get(6), "CreateFile", 7);
            return handleResult(WindowsIocp.createFile(name, access, share, disposition, flags, template, false));
        });
        fn(m, "ConnectNamedPipe", (a, k) -> {
            Object[] p = keywords("ConnectNamedPipe", a, k, 1, "handle", "overlapped");
            long h = WindowsOverlappedModule.pointer(p[0]);
            if (p[1] != null && PyRuntime.truth(p[1])) return WindowsOverlappedModule.connectNamedPipeOverlapped(h);
            WindowsIocp.connectNamedPipeBlocking(h);
            return null;
        });
        fn(m, "CreateEventW", (a, k) -> {
            Object[] p = keywords("CreateEventW", a, k, 4, "security_attributes", "manual_reset", "initial_state", "name");
            securityNull(p[0], "CreateEventW", 1);
            boolean manual = flag(p[1]), initial = flag(p[2]);
            Object n = NativeSubclass.unwrap(p[3]);
            if (n != null && !(n instanceof String)) throw new PyException("TypeError", "CreateEventW() argument 4 must be str or None, not " + PyRuntime.typeName(n));
            if (n instanceof String s && s.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character");
            return handleResult(WindowsIocp.createEvent(manual, initial, (String) n));
        });
        fn(m, "SetEvent", (a, k) -> { WindowsIocp.setEvent(WindowsOverlappedModule.pointer(keywords("SetEvent", a, k, 1, "event")[0])); return null; });
        fn(m, "ResetEvent", (a, k) -> { WindowsIocp.resetEvent(WindowsOverlappedModule.pointer(keywords("ResetEvent", a, k, 1, "event")[0])); return null; });
        m.attrs.put("Overlapped", WindowsOverlappedModule.winapiOverlapped());
        return m;
    }

    /** A HANDLE result: NULL is None. */
    private static Object handleResult(long h) { return h == 0 ? null : unsigned(h); }

    private static boolean flag(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof Boolean b) return b;
        if (o instanceof Long l) return l != 0;
        if (o instanceof BigInteger b) return b.signum() != 0;
        throw new PyException("TypeError", LangVersion.atLeast(3, 15) ? "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer" : "an integer is required");
    }

    /** All-positional-only arguments, as "f() takes exactly N arguments (M given)". */
    private static List<Object> exact(String fn, List<Object> a, Map<Object, Object> k, int n) {
        if (!k.isEmpty()) throw new PyException("TypeError", fn + "() takes no keyword arguments");
        if (a.size() != n) throw new PyException("TypeError", fn + "() takes exactly " + n + " argument" + (n == 1 ? "" : "s") + " (" + a.size() + " given)");
        return a;
    }

    /** Arguments that may be passed by keyword; the first {@code required} must be present, the others are null when absent. */
    private static Object[] keywords(String fn, List<Object> a, Map<Object, Object> k, int required, String... names) {
        if (a.size() > names.length) throw new PyException("TypeError", fn + "() takes " + (required == names.length ? "exactly " : "at most ") + names.length + " argument"
            + (names.length == 1 ? "" : "s") + " (" + (a.size() + k.size()) + " given)");
        Object[] out = new Object[names.length];
        boolean[] set = new boolean[names.length];
        for (int i = 0; i < a.size(); i++) { out[i] = a.get(i); set[i] = true; }
        for (Map.Entry<Object, Object> e : k.entrySet()) {
            int i = java.util.Arrays.asList(names).indexOf(e.getKey());
            if (i < 0) throw new PyException("TypeError", fn + "() got an unexpected keyword argument '" + e.getKey() + "'");
            if (set[i]) throw new PyException("TypeError", fn + "() argument for '" + names[i] + "' given by name and position");
            out[i] = e.getValue(); set[i] = true;
        }
        for (int i = 0; i < required; i++)
            if (!set[i]) throw new PyException("TypeError", fn + "() missing required argument '" + names[i] + "' (pos " + (i + 1) + ")");
        return out;
    }

    private static String wstr(Object o, String fn, int pos) {
        o = NativeSubclass.unwrap(o);
        if (!(o instanceof String s)) throw new PyException("TypeError", "argument must be str, not " + PyRuntime.typeName(o));
        if (s.indexOf('\0') >= 0) throw new PyException("ValueError", "embedded null character");
        return s;
    }

    /** An integer argument of the Argument Clinic 'k'/'K' kind: any int, taken modulo the C width; otherwise "must be int". */
    private static Object intArg(Object o, String fn, int pos) {
        Object v = NativeSubclass.unwrap(o);
        if (v instanceof Long || v instanceof BigInteger || v instanceof Boolean) return v;
        throw new PyException("TypeError", fn + "() argument " + pos + " must be int, not " + (v == null ? "None" : PyRuntime.typeName(v)));
    }

    private static int dw(Object o, String fn, int pos) {
        Object v = intArg(o, fn, pos);
        return v instanceof BigInteger b ? b.intValue() : v instanceof Boolean b ? (b ? 1 : 0) : (int) (long) (Long) v;
    }

    private static long ptr(Object o, String fn, int pos) {
        Object v = intArg(o, fn, pos);
        return v instanceof BigInteger b ? b.longValue() : v instanceof Boolean b ? (b ? 1 : 0) : (Long) v;
    }

    /** A LPSECURITY_ATTRIBUTES argument: an int, which must be 0 here (there is no way to build the structure). */
    private static void securityNull(Object o, String fn, int pos) {
        if (ptr(o, fn, pos) != 0) throw new PyException("ValueError", fn + ": security_attributes must be NULL");
    }

    static Object unsigned(long v) { return v >= 0 ? (Object) v : new BigInteger(Long.toUnsignedString(v)); }

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
        WindowsHandles.WINAPI_PIPE_ENDS.add(ends[0]);
        WindowsHandles.WINAPI_PIPE_ENDS.add(ends[1]);
        return tuple(unsigned(ends[0]), unsigned(ends[1]));
    }

    private static final int DUPLICATE_CLOSE_SOURCE = 1;

    private static Object duplicateHandle(List<Object> a) {
        long srcProcess = handle(a.get(0)), src = handle(a.get(1)), dstProcess = handle(a.get(2));
        boolean inherit = PyRuntime.truth(a.get(4));
        int options = a.size() > 5 ? (int) IoUtil.asLong(a.get(5)) : 0;
        long dup = WindowsHandles.duplicateHandle(srcProcess, src, dstProcess, (int) IoUtil.asLong(a.get(3)), inherit, options);
        long self = WindowsHandles.currentProcess();
        if (srcProcess == self && (options & DUPLICATE_CLOSE_SOURCE) != 0) {
            WindowsHandles.WINAPI_PIPE_ENDS.remove(src);
            WindowsHandles.DUPLICATED_PIPE_ENDS.remove(src);
        } else if (inherit && srcProcess == self && dstProcess == self && WindowsHandles.WINAPI_PIPE_ENDS.contains(src)) {
            WindowsHandles.DUPLICATED_PIPE_ENDS.add(src);
        }
        return unsigned(dup);
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
        // Lib/subprocess.py makes each pipe end inheritable by duplicating it and relies on reference counting to close
        // the original at once (Handle.__del__). Here those originals live until a collection; one still open in this
        // process keeps the pipe from ever reporting EOF (communicate() then waits forever). The JVM cannot collect
        // selected objects, so when such an original is still open, a full collection runs before the child starts:
        // it closes every unreachable Handle, which is the state CPython is in at this point. Spawns without pipes, or
        // whose originals were already closed, skip it.
        if (PyRuntime.truth(a.get(4)) && !WindowsHandles.DUPLICATED_PIPE_ENDS.isEmpty()) WeakrefDelivery.collect();
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
