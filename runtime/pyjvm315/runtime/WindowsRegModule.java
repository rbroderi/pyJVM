// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static pyjvm315.runtime.NativeTypes.*;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code winreg}: the Windows registry through advapi32 (FFM), following PC/winreg.c: {@code PyHKEY} handle objects,
 * value conversion both ways (Py2Reg / Reg2Py) and errors as {@code OSError} with {@code winerror}. importlib's
 * bootstrap imports it on Windows.
 */
final class WindowsRegModule {
    private WindowsRegModule() {}

    private static final int ERROR_MORE_DATA = 234, ERROR_INVALID_DATA = 13;
    private static final int REG_SZ = 1, REG_EXPAND_SZ = 2, REG_DWORD = 4, REG_MULTI_SZ = 7, REG_QWORD = 11;
    private static final int KEY_READ = 131097, KEY_WRITE = 131078, KEY_WOW64_64KEY = 256, MAXIMUM_ALLOWED = 0x02000000;

    private static final Object[] CONSTANTS = {
        "KEY_ALL_ACCESS", 983103L, "KEY_CREATE_LINK", 32L, "KEY_CREATE_SUB_KEY", 4L, "KEY_ENUMERATE_SUB_KEYS", 8L, "KEY_EXECUTE", 131097L,
        "KEY_NOTIFY", 16L, "KEY_QUERY_VALUE", 1L, "KEY_READ", 131097L, "KEY_SET_VALUE", 2L, "KEY_WOW64_32KEY", 512L,
        "KEY_WOW64_64KEY", 256L, "KEY_WRITE", 131078L, "REG_BINARY", 3L, "REG_CREATED_NEW_KEY", 1L, "REG_DWORD", 4L,
        "REG_DWORD_BIG_ENDIAN", 5L, "REG_DWORD_LITTLE_ENDIAN", 4L, "REG_EXPAND_SZ", 2L, "REG_FULL_RESOURCE_DESCRIPTOR", 9L,
        "REG_LEGAL_CHANGE_FILTER", 268435471L, "REG_LEGAL_OPTION", 31L, "REG_LINK", 6L, "REG_MULTI_SZ", 7L, "REG_NONE", 0L,
        "REG_NOTIFY_CHANGE_ATTRIBUTES", 2L, "REG_NOTIFY_CHANGE_LAST_SET", 4L, "REG_NOTIFY_CHANGE_NAME", 1L, "REG_NOTIFY_CHANGE_SECURITY", 8L,
        "REG_NO_LAZY_FLUSH", 4L, "REG_OPENED_EXISTING_KEY", 2L, "REG_OPTION_BACKUP_RESTORE", 4L, "REG_OPTION_CREATE_LINK", 2L,
        "REG_OPTION_NON_VOLATILE", 0L, "REG_OPTION_OPEN_LINK", 8L, "REG_OPTION_RESERVED", 0L, "REG_OPTION_VOLATILE", 1L, "REG_QWORD", 11L,
        "REG_QWORD_LITTLE_ENDIAN", 11L, "REG_REFRESH_HIVE", 2L, "REG_RESOURCE_LIST", 8L, "REG_RESOURCE_REQUIREMENTS_LIST", 10L, "REG_SZ", 1L,
        "REG_WHOLE_HIVE_VOLATILE", 1L};

    private static PyClass hkeyClass;

    // the calls WindowsNative does not already bind (privileged hive files and WOW64 reflection)
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
    private static MethodHandle adv(String name, FunctionDescriptor fd) { return LINKER.downcallHandle(ADVAPI32.findOrThrow(name), fd); }
    private static final MethodHandle REG_LOAD = adv("RegLoadKeyW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_SAVE = adv("RegSaveKeyW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_DISABLE_REFLECTION = adv("RegDisableReflectionKey", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle REG_ENABLE_REFLECTION = adv("RegEnableReflectionKey", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle REG_QUERY_REFLECTION = adv("RegQueryReflectionKey", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    static synchronized PyModule create() {
        PyModule m = new PyModule("winreg", WindowsRegModule.class.getName());
        hkeyClass = define("winreg", "PyHKEY", null);
        method(hkeyClass, WindowsRegModule.class, "Close", "hkClose", "self");
        method(hkeyClass, WindowsRegModule.class, "Detach", "hkDetach", "self");
        method(hkeyClass, WindowsRegModule.class, "__enter__", "hkEnter", "self");
        methodVar(hkeyClass, WindowsRegModule.class, "__exit__", "hkExit", "self", "args");
        method(hkeyClass, WindowsRegModule.class, "__int__", "hkInt", "self");
        method(hkeyClass, WindowsRegModule.class, "__bool__", "hkBool", "self");
        method(hkeyClass, WindowsRegModule.class, "__str__", "hkStr", "self");
        PyRuntime.classAddProperty(hkeyClass, "handle", WindowsRegModule.class.getName(), "hkInt", null);
        finish(hkeyClass);
        m.attrs.put("HKEYType", hkeyClass);
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        String[] roots = {"HKEY_CLASSES_ROOT", "HKEY_CURRENT_USER", "HKEY_LOCAL_MACHINE", "HKEY_USERS", "HKEY_PERFORMANCE_DATA", "HKEY_CURRENT_CONFIG", "HKEY_DYN_DATA"};
        for (int i = 0; i < roots.length; i++) m.attrs.put(roots[i], unsigned(0xFFFFFFFF80000000L + i));   // sign-extended, as CPython reports them
        for (int i = 0; i < CONSTANTS.length; i += 2) m.attrs.put((String) CONSTANTS[i], CONSTANTS[i + 1]);

        fn(m, "CloseKey", (a, k) -> hkClose(a.get(0)));
        fn(m, "ConnectRegistry", (a, k) -> connect(a.get(0), a.get(1)));
        fn(m, "CreateKey", (a, k) -> create(a.get(0), a.get(1), 0, MAXIMUM_ALLOWED));
        fn(m, "CreateKeyEx", (a, k) -> create(arg(a, k, 0, "key", null), arg(a, k, 1, "sub_key", null),
            (int) IoUtil.asLong(arg(a, k, 2, "reserved", 0L)), (int) IoUtil.asLong(arg(a, k, 3, "access", (long) KEY_WRITE))));
        fn(m, "DeleteKey", (a, k) -> deleteKey(a.get(0), a.get(1), 0));
        fn(m, "DeleteKeyEx", (a, k) -> deleteKey(arg(a, k, 0, "key", null), arg(a, k, 1, "sub_key", null), (int) IoUtil.asLong(arg(a, k, 2, "access", (long) KEY_WOW64_64KEY))));
        if (LangVersion.atLeast(3, 15)) fn(m, "DeleteTree", (a, k) -> deleteTree(a.get(0), a.size() > 1 ? a.get(1) : null));
        fn(m, "DeleteValue", (a, k) -> deleteValue(a.get(0), a.get(1)));
        fn(m, "EnumKey", (a, k) -> enumKey(a.get(0), (int) IoUtil.asLong(a.get(1))));
        fn(m, "EnumValue", (a, k) -> enumValue(a.get(0), (int) IoUtil.asLong(a.get(1))));
        fn(m, "ExpandEnvironmentStrings", (a, k) -> WindowsNative.expandEnvironmentStrings(str(a.get(0), "ExpandEnvironmentStrings")));
        fn(m, "FlushKey", (a, k) -> status(call1(WindowsNative.REG_FLUSH, a.get(0))));
        fn(m, "LoadKey", (a, k) -> loadKey(a.get(0), a.get(1), a.get(2)));
        fn(m, "OpenKey", (a, k) -> open(arg(a, k, 0, "key", null), arg(a, k, 1, "sub_key", null),
            (int) IoUtil.asLong(arg(a, k, 2, "reserved", 0L)), (int) IoUtil.asLong(arg(a, k, 3, "access", (long) KEY_READ))));
        m.attrs.put("OpenKeyEx", m.attrs.get("OpenKey"));
        fn(m, "QueryInfoKey", (a, k) -> queryInfo(a.get(0)));
        fn(m, "QueryValue", (a, k) -> queryValue(a.get(0), a.get(1)));
        fn(m, "QueryValueEx", (a, k) -> queryValueEx(a.get(0), a.get(1)));
        fn(m, "SaveKey", (a, k) -> saveKey(a.get(0), a.get(1)));
        fn(m, "SetValue", (a, k) -> setValue(a.get(0), a.get(1), a.get(2), a.get(3)));
        fn(m, "SetValueEx", (a, k) -> setValueEx(a.get(0), a.get(1), a.get(3), a.get(4)));
        fn(m, "DisableReflectionKey", (a, k) -> status(call1(REG_DISABLE_REFLECTION, a.get(0))));
        fn(m, "EnableReflectionKey", (a, k) -> status(call1(REG_ENABLE_REFLECTION, a.get(0))));
        fn(m, "QueryReflectionKey", (a, k) -> queryReflection(a.get(0)));
        return m;
    }

    private static Object arg(List<Object> a, Map<Object,Object> k, int i, String name, Object fallback) {
        return i < a.size() ? a.get(i) : k.containsKey(name) ? k.get(name) : fallback;
    }

    private static Object unsigned(long v) { return v >= 0 ? (Object) v : new BigInteger(Long.toUnsignedString(v)); }

    // ---- PyHKEY ------------------------------------------------------------------------------------

    private static long[] cell(Object self) { return state(self, long[].class, "winreg.PyHKEY"); }

    private static Object wrap(long handle) { return newInstance(hkeyClass, new long[]{handle}); }

    /** A key argument: a PyHKEY or an int handle (the predefined HKEY_* values included). */
    private static long hkey(Object o, boolean noneOk) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof PyInstance i && i.cls == hkeyClass) return cell(o)[0];
        if (o instanceof Long l) return l;
        if (o instanceof BigInteger b) return b.longValue();
        if (o == null) {
            if (noneOk) return 0;
            throw new PyException("TypeError", "None is not a valid HKEY in this context");
        }
        throw new PyException("TypeError", "The object is not a PyHKEY object");
    }

    public static Object hkClose(Object self) {
        if (NativeSubclass.unwrap(self) instanceof PyInstance i && i.cls == hkeyClass) {
            long[] c = cell(self);
            if (c[0] != 0) { long h = c[0]; c[0] = 0; status(close(h)); }
            return null;
        }
        status(close(hkey(self, false)));
        return null;
    }

    public static Object hkDetach(Object self) { long[] c = cell(self); long h = c[0]; c[0] = 0; return unsigned(h); }
    public static Object hkEnter(Object self) { return self; }
    public static Object hkExit(Object self, Object args) { return hkClose(self); }
    public static Object hkInt(Object self) { return unsigned(cell(self)[0]); }
    public static Object hkBool(Object self) { return cell(self)[0] != 0; }
    public static Object hkStr(Object self) { return String.format("<PyHKEY:0x%016X>", cell(self)[0]); }

    // ---- calls -------------------------------------------------------------------------------------

    private static MemorySegment seg(long h) { return MemorySegment.ofAddress(h); }

    private static Object status(int rc) {
        if (rc != 0) throw WindowsNative.winError(rc, null);
        return null;
    }

    private static int close(long h) {
        try { return (int) WindowsNative.REG_CLOSE.invokeExact(seg(h)); } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static int call1(MethodHandle mh, Object key) {
        try { return (int) mh.invokeExact(seg(hkey(key, false))); } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static String str(Object o, String who) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof String s) return s;
        throw new PyException("TypeError", who + "() argument must be str, not " + PyRuntime.typeName(o));
    }

    /** A sub-key argument: str, or None for the key itself. */
    private static MemorySegment subKey(Arena a, Object o) {
        o = NativeSubclass.unwrap(o);
        if (o == null) return MemorySegment.NULL;
        if (o instanceof String s) return WindowsNative.wide(a, s);
        throw new PyException("TypeError", "sub_key must be str or None, not " + PyRuntime.typeName(o));
    }

    private static Object open(Object key, Object sub, int reserved, int access) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int rc = (int) WindowsNative.REG_OPEN.invokeExact(seg(hkey(key, false)), subKey(a, sub), reserved, access, out);
            status(rc);
            return wrap(out.get(ADDRESS, 0).address());
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object create(Object key, Object sub, int reserved, int access) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            MemorySegment name = NativeSubclass.unwrap(sub) == null ? WindowsNative.wide(a, "") : subKey(a, sub);
            int rc = (int) WindowsNative.REG_CREATE.invokeExact(seg(hkey(key, false)), name, reserved, MemorySegment.NULL, 0, access,
                MemorySegment.NULL, out, MemorySegment.NULL);
            status(rc);
            return wrap(out.get(ADDRESS, 0).address());
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object connect(Object computer, Object key) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            Object c = NativeSubclass.unwrap(computer);
            MemorySegment name = c == null ? MemorySegment.NULL : WindowsNative.wide(a, str(c, "ConnectRegistry"));
            status((int) WindowsNative.REG_CONNECT.invokeExact(name, seg(hkey(key, false)), out));
            return wrap(out.get(ADDRESS, 0).address());
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object deleteKey(Object key, Object sub, int access) {
        try (Arena a = Arena.ofConfined()) {
            return status((int) WindowsNative.REG_DELETE_KEY_EX.invokeExact(seg(hkey(key, false)), WindowsNative.wide(a, str(sub, "DeleteKey")), access, 0));
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object deleteTree(Object key, Object sub) {
        try (Arena a = Arena.ofConfined()) {
            return status((int) WindowsNative.REG_DELETE_TREE.invokeExact(seg(hkey(key, false)), subKey(a, sub)));
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object deleteValue(Object key, Object value) {
        try (Arena a = Arena.ofConfined()) {
            return status((int) WindowsNative.REG_DELETE_VALUE.invokeExact(seg(hkey(key, false)), subKey(a, value)));
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object loadKey(Object key, Object sub, Object file) {
        try (Arena a = Arena.ofConfined()) {
            return status((int) REG_LOAD.invokeExact(seg(hkey(key, false)), WindowsNative.wide(a, str(sub, "LoadKey")), WindowsNative.wide(a, str(file, "LoadKey"))));
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object saveKey(Object key, Object file) {
        try (Arena a = Arena.ofConfined()) {
            return status((int) REG_SAVE.invokeExact(seg(hkey(key, false)), WindowsNative.wide(a, str(file, "SaveKey")), MemorySegment.NULL));
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object queryReflection(Object key) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT);
            status((int) REG_QUERY_REFLECTION.invokeExact(seg(hkey(key, false)), out));
            return out.get(JAVA_INT, 0) != 0;
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    /** {sub keys, values, max value name length, max value data length, last write FILETIME}. */
    private static long[] info(Arena a, long h) throws Throwable {
        MemorySegment subKeys = a.allocate(JAVA_INT), values = a.allocate(JAVA_INT), maxName = a.allocate(JAVA_INT), maxData = a.allocate(JAVA_INT);
        MemorySegment written = a.allocate(JAVA_LONG);
        MemorySegment nul = MemorySegment.NULL;
        int rc = (int) WindowsNative.REG_QUERY_INFO.invokeExact(seg(h), nul, nul, nul, subKeys, nul, nul, values, maxName, maxData, nul, written);
        status(rc);
        return new long[]{Integer.toUnsignedLong(subKeys.get(JAVA_INT, 0)), Integer.toUnsignedLong(values.get(JAVA_INT, 0)),
            Integer.toUnsignedLong(maxName.get(JAVA_INT, 0)), Integer.toUnsignedLong(maxData.get(JAVA_INT, 0)), written.get(JAVA_LONG, 0)};
    }

    private static Object queryInfo(Object key) {
        try (Arena a = Arena.ofConfined()) {
            long[] i = info(a, hkey(key, false));
            return tuple(i[0], i[1], unsigned(i[4]));
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object enumKey(Object key, int index) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = a.allocate(257 * 2L), len = a.allocate(JAVA_INT);
            len.set(JAVA_INT, 0, 257);
            MemorySegment nul = MemorySegment.NULL;
            status((int) WindowsNative.REG_ENUM_KEY.invokeExact(seg(hkey(key, false)), index, name, len, nul, nul, nul, nul));
            return WindowsNative.wideString(name, len.get(JAVA_INT, 0));
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    private static Object enumValue(Object key, int index) {
        try (Arena a = Arena.ofConfined()) {
            long h = hkey(key, false);
            long[] i = info(a, h);
            int nameCap = (int) i[2] + 1, dataCap = (int) Math.max(i[3], 1);
            while (true) {
                MemorySegment name = a.allocate(nameCap * 2L), nameLen = a.allocate(JAVA_INT), type = a.allocate(JAVA_INT);
                MemorySegment data = a.allocate(dataCap), dataLen = a.allocate(JAVA_INT);
                nameLen.set(JAVA_INT, 0, nameCap); dataLen.set(JAVA_INT, 0, dataCap);
                int rc = (int) WindowsNative.REG_ENUM_VALUE.invokeExact(seg(h), index, name, nameLen, MemorySegment.NULL, type, data, dataLen);
                if (rc == ERROR_MORE_DATA) { nameCap *= 2; dataCap *= 2; continue; }
                status(rc);
                int t = type.get(JAVA_INT, 0);
                return tuple(WindowsNative.wideString(name, nameLen.get(JAVA_INT, 0)), toPython(data, dataLen.get(JAVA_INT, 0), t), (long) t);
            }
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    /** {data segment, size, type} of a value, growing the buffer on ERROR_MORE_DATA; null name is the default value. */
    private static Object[] query(Arena a, long h, MemorySegment name) throws Throwable {
        int cap = 256;
        while (true) {
            MemorySegment data = a.allocate(cap), size = a.allocate(JAVA_INT), type = a.allocate(JAVA_INT);
            size.set(JAVA_INT, 0, cap);
            int rc = (int) WindowsNative.REG_QUERY_VALUE.invokeExact(seg(h), name, MemorySegment.NULL, type, data, size);
            if (rc == ERROR_MORE_DATA) { cap = Math.max(cap * 2, size.get(JAVA_INT, 0)); continue; }
            status(rc);
            return new Object[]{data, size.get(JAVA_INT, 0), type.get(JAVA_INT, 0)};
        }
    }

    private static Object queryValueEx(Object key, Object name) {
        try (Arena a = Arena.ofConfined()) {
            Object[] r = query(a, hkey(key, false), subKey(a, name));
            int type = (int) r[2];
            return tuple(toPython((MemorySegment) r[0], (int) r[1], type), (long) type);
        } catch (Throwable t) { throw WindowsNative.rethrow(t); }
    }

    /** The default value of {@code key\sub_key}, which must be a string. */
    private static Object queryValue(Object key, Object sub) {
        Object s = NativeSubclass.unwrap(sub);
        boolean child = s instanceof String str && !str.isEmpty();
        long h = child ? cell(open(key, s, 0, 1))[0] : hkey(key, false);   // KEY_QUERY_VALUE
        try (Arena a = Arena.ofConfined()) {
            Object[] r = query(a, h, MemorySegment.NULL);
            if ((int) r[2] != REG_SZ) throw WindowsNative.winError(ERROR_INVALID_DATA, null);
            return toPython((MemorySegment) r[0], (int) r[1], REG_SZ);
        } catch (Throwable t) { throw WindowsNative.rethrow(t);
        } finally { if (child) close(h); }
    }

    private static Object setValueEx(Object key, Object name, Object type, Object value) {
        int t = (int) IoUtil.asLong(type);
        byte[] data = toRegistry(value, t);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = data.length == 0 ? MemorySegment.NULL : a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, data);
            return status((int) WindowsNative.REG_SET_VALUE.invokeExact(seg(hkey(key, false)), subKey(a, name), 0, t, buf, data.length));
        } catch (Throwable e) { throw WindowsNative.rethrow(e); }
    }

    private static Object setValue(Object key, Object sub, Object type, Object value) {
        if (IoUtil.asLong(type) != REG_SZ) throw new PyException("TypeError", "type must be winreg.REG_SZ");
        if (!(NativeSubclass.unwrap(value) instanceof String)) throw new PyException("TypeError", "SetValue() argument 4 must be str, not " + PyRuntime.typeName(value));
        Object s = NativeSubclass.unwrap(sub);
        boolean child = s instanceof String str && !str.isEmpty();
        Object target = child ? create(key, s, 0, 2) : key;      // KEY_SET_VALUE
        try { return setValueEx(target, null, (long) REG_SZ, value); }
        finally { if (child) close(cell(target)[0]); }
    }

    // ---- value conversion (PC/winreg.c Reg2Py / Py2Reg) ------------------------------------------

    private static Object toPython(MemorySegment data, int size, int type) {
        byte[] b = data.asSlice(0, size).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        ByteBuffer le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        switch (type) {
            case REG_DWORD: return size < 4 ? 0L : Integer.toUnsignedLong(le.getInt(0));
            case REG_QWORD: return size < 8 ? 0L : unsigned(le.getLong(0));
            case REG_SZ, REG_EXPAND_SZ: {
                String s = new String(b, 0, size & ~1, StandardCharsets.UTF_16LE);
                int nul = s.indexOf('\0');
                return nul < 0 ? s : s.substring(0, nul);
            }
            case REG_MULTI_SZ: {
                List<Object> out = new ArrayList<>();
                String s = new String(b, 0, size & ~1, StandardCharsets.UTF_16LE);
                int start = 0;
                while (start < s.length()) {
                    int nul = s.indexOf('\0', start);
                    if (nul < 0) nul = s.length();
                    if (nul == start) break;                 // the empty string that ends the list
                    out.add(s.substring(start, nul));
                    start = nul + 1;
                }
                return out;
            }
            default: return size == 0 ? null : new PyBytes(b);
        }
    }

    private static byte[] toRegistry(Object value, int type) {
        Object v = NativeSubclass.unwrap(value);
        switch (type) {
            case REG_DWORD, REG_QWORD: {
                long n = 0;
                if (v != null) {
                    if (!(v instanceof Long || v instanceof BigInteger || v instanceof Boolean)) throw new PyException("ValueError", "Could not convert the data to the specified type.");
                    BigInteger big = v instanceof BigInteger bi ? bi : BigInteger.valueOf(IoUtil.asLong(v));
                    int bits = type == REG_DWORD ? 32 : 64;
                    if (big.signum() < 0 || big.bitLength() > bits)
                        throw new PyException("OverflowError", "int too big to convert");
                    n = big.longValue();
                }
                ByteBuffer bb = ByteBuffer.allocate(type == REG_DWORD ? 4 : 8).order(ByteOrder.LITTLE_ENDIAN);
                if (type == REG_DWORD) bb.putInt((int) n); else bb.putLong(n);
                return bb.array();
            }
            case REG_SZ, REG_EXPAND_SZ: {
                if (v == null) return new byte[2];
                if (!(v instanceof String s)) throw new PyException("ValueError", "Could not convert the data to the specified type.");
                return (s + "\0").getBytes(StandardCharsets.UTF_16LE);
            }
            case REG_MULTI_SZ: {
                if (v == null) return new byte[4];
                if (!(v instanceof List<?> items)) throw new PyException("ValueError", "Could not convert the data to the specified type.");
                StringBuilder sb = new StringBuilder();
                for (Object o : items) {
                    if (!(NativeSubclass.unwrap(o) instanceof String s)) throw new PyException("ValueError", "Could not convert the data to the specified type.");
                    sb.append(s).append('\0');
                }
                return sb.append('\0').toString().getBytes(StandardCharsets.UTF_16LE);
            }
            default: {
                if (v == null) return new byte[0];
                try { return IoUtil.bytesLike(v); }
                catch (PyException e) { throw new PyException("TypeError", "Objects of type '" + PyRuntime.typeName(v) + "' can not be used as binary registry values"); }
            }
        }
    }
}
