// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code pwd} and {@code grp} through libc (NSS sources such as systemd are honoured; there is no Java-side parser of
 * {@code /etc/passwd}). The lookups are the reentrant {@code get*_r} functions with a native buffer that doubles while
 * the function returns {@code ERANGE}; they return the error number, so no errno is read and the result cell decides
 * "found". The enumerations use {@code setpwent}/{@code getpwent}/{@code endpwent} (static libc storage) under one Java
 * lock, every string copied inside it. Layouts and rules: n2_plan 1.6, 3.2, 3.6. Never loaded on Windows.
 */
final class PosixAccounts {
    private PosixAccounts() {}

    private static final int ERANGE = 34;
    private static final long BUF_LIMIT = 64L << 20;
    /** Test hook (n2 ticket 3): when positive, the first buffer of every lookup has this size. Read once. */
    private static final long TEST_BUF = Math.min(Long.getLong("pyjvm.test.pwbuf", 0L), BUF_LIMIT);
    /** Serialises the enumerations of both databases (the {@code ent} functions keep process-wide state). */
    static final Object ENT_LOCK = new Object();

    private static final MethodHandle GETPWUID_R = PosixNative.fnNoCapture("getpwuid_r", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle GETPWNAM_R = PosixNative.fnNoCapture("getpwnam_r", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle GETGRGID_R = PosixNative.fnNoCapture("getgrgid_r", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle GETGRNAM_R = PosixNative.fnNoCapture("getgrnam_r", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle SETPWENT = PosixNative.fnNoCapture("setpwent", FunctionDescriptor.ofVoid());
    private static final MethodHandle ENDPWENT = PosixNative.fnNoCapture("endpwent", FunctionDescriptor.ofVoid());
    private static final MethodHandle GETPWENT = PosixNative.fnNoCapture("getpwent", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle SETGRENT = PosixNative.fnNoCapture("setgrent", FunctionDescriptor.ofVoid());
    private static final MethodHandle ENDGRENT = PosixNative.fnNoCapture("endgrent", FunctionDescriptor.ofVoid());
    private static final MethodHandle GETGRENT = PosixNative.fnNoCapture("getgrent", FunctionDescriptor.of(ADDRESS));

    private static final String[] PASSWD_FIELDS = {"pw_name", "pw_passwd", "pw_uid", "pw_gid", "pw_gecos", "pw_dir", "pw_shell"};
    private static final String[] GROUP_FIELDS = {"gr_name", "gr_passwd", "gr_gid", "gr_mem"};
    private static final PyClass PASSWD = PosixStructSeq.define("pwd", "struct_passwd", PASSWD_FIELDS);
    private static final PyClass GROUP = PosixStructSeq.define("grp", "struct_group", GROUP_FIELDS);

    private static final long SC_GETGR_R_SIZE_MAX = 69, SC_GETPW_R_SIZE_MAX = 70;

    // ---------------------------------------------------------------- modules

    static PyModule pwd() {
        PyModule m = new PyModule("pwd", PosixAccounts.class.getName());
        NativeTypes.fn(m, "getpwuid", (a, k) -> {
            one("pwd.getpwuid", a, k);
            int uid;
            try {
                uid = toId(a.get(0), "uid");
            } catch (PyException e) {
                if (e.typeName.equals("OverflowError")) throw new PyException("KeyError", "getpwuid(): uid not found");
                throw e;
            }
            Object r = lookup(true, false, uid, null);
            if (r == null) throw new PyException("KeyError", "getpwuid(): uid not found: " + (uid == -1 ? -1L : (uid & 0xffffffffL)));
            return r;
        });
        NativeTypes.fn(m, "getpwnam", (a, k) -> {
            one("pwd.getpwnam", a, k);
            Object n = NativeSubclass.unwrap(a.get(0));
            if (!(n instanceof String s)) throw new PyException("TypeError", "getpwnam() argument must be str, not " + PyRuntime.typeName(n));
            byte[] name = nameBytes(s);
            Object r = lookup(true, true, 0, name);
            if (r == null) throw new PyException("KeyError", "getpwnam(): name not found: " + PyRuntime.pyRepr(s));
            return r;
        });
        NativeTypes.fn(m, "getpwall", (a, k) -> {
            none("pwd.getpwall", a, k);
            return all(true);
        });
        m.attrs.put("struct_passwd", PASSWD);
        return m;
    }

    static PyModule grp() {
        PyModule m = new PyModule("grp", PosixAccounts.class.getName());
        NativeTypes.fn(m, "getgrgid", (a, k) -> {
            Object arg = clinicOne("getgrgid", LangVersion.atLeast(3, 14) ? "getgrgid()" : "function", "id", a, k);
            int gid = toId(arg, "gid");
            Object r = lookup(false, false, gid, null);
            if (r == null) throw new PyException("KeyError", "getgrgid(): gid not found: " + (gid == -1 ? -1L : (gid & 0xffffffffL)));
            return r;
        });
        NativeTypes.fn(m, "getgrnam", (a, k) -> {
            Object arg = clinicOne("getgrnam", "getgrnam()", "name", a, k);
            Object n = NativeSubclass.unwrap(arg);
            if (!(n instanceof String s)) throw new PyException("TypeError", "getgrnam() argument 'name' must be str, not " + PyRuntime.typeName(n));
            byte[] name = nameBytes(s);
            Object r = lookup(false, true, 0, name);
            if (r == null) throw new PyException("KeyError", "getgrnam(): name not found: " + PyRuntime.pyRepr(s));
            return r;
        });
        NativeTypes.fn(m, "getgrall", (a, k) -> {
            none("grp.getgrall", a, k);
            return all(false);
        });
        m.attrs.put("struct_group", GROUP);
        return m;
    }

    // ---------------------------------------------------------------- arguments

    private static void one(String name, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", name + "() takes no keyword arguments");
        if (a.size() != 1) throw new PyException("TypeError", name + "() takes exactly one argument (" + a.size() + " given)");
    }

    private static void none(String name, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", name + "() takes no keyword arguments");
        if (!a.isEmpty()) throw new PyException("TypeError", name + "() takes no arguments (" + a.size() + " given)");
    }

    /**
     * Argument Clinic's count rules for {@code f(x, /)}-less one-parameter functions (measured: the given count is
     * positional plus keyword, whatever the keywords' names; with nothing positional and no keyword called
     * {@code param} the argument is "missing").
     */
    private static Object clinicOne(String fn, String shown, String param, List<Object> a, Map<Object, Object> k) {
        int given = a.size() + k.size();
        if (given > 1) throw new PyException("TypeError", shown + " takes at most 1 argument (" + given + " given)");
        if (!a.isEmpty()) return a.get(0);
        if (k.containsKey(param)) return k.get(param);
        throw new PyException("TypeError", shown + " missing required argument '" + param + "' (pos 1)");
    }

    /** A name for libc: UTF-8 with surrogateescape (a lone surrogate outside U+DC80..DCFF is the encoder's error), no NUL. */
    private static byte[] nameBytes(String s) {
        byte[] b = Codecs.encode(s, "utf-8", "surrogateescape");
        for (byte x : b) if (x == 0) throw new PyException("ValueError", "embedded null byte");
        return b;
    }

    private static final BigInteger MAX_ID = BigInteger.valueOf(0xffffffffL), TWO63 = BigInteger.ONE.shiftLeft(63);

    /**
     * CPython's uid/gid converter: an int (bool, or anything with {@code __index__}); {@code -1} is {@code (uid_t)-1}; a
     * value in {@code [0, 2**32)} is itself (as an unsigned 32-bit pattern in the int); anything else is an OverflowError
     * ({@code 2**63} and above "greater than maximum", every other value "less than minimum": measured, also for
     * {@code 2**32}). {@code pwd} turns that OverflowError into a KeyError, {@code grp} lets it out.
     */
    static int toId(Object o, String what) {
        Object u = NativeSubclass.unwrap(o);
        BigInteger v;
        if (u instanceof Long l) v = BigInteger.valueOf(l);
        else if (u instanceof Boolean b) v = b ? BigInteger.ONE : BigInteger.ZERO;
        else if (u instanceof BigInteger bi) v = bi;
        else {
            // __index__ is looked up on the type (not the instance); any failure of the conversion is the one TypeError
            PyException notInt = new PyException("TypeError", what + " should be integer, not " + PyRuntime.typeName(o));
            Object method = u instanceof PyInstance pi ? pi.cls.lookupAttr("__index__") : PyRuntime.MISSING;
            if (method == PyRuntime.MISSING || method == null) throw notInt;
            Object r;
            try {
                java.util.List<Object> args = new ArrayList<>();
                args.add(o);
                r = NativeSubclass.unwrap(PyRuntime.callFunction(method, args, new java.util.LinkedHashMap<Object, Object>()));
            } catch (PyException e) { throw notInt; }
            if (r instanceof Long l) v = BigInteger.valueOf(l);
            else if (r instanceof Boolean b) v = b ? BigInteger.ONE : BigInteger.ZERO;
            else if (r instanceof BigInteger bi) v = bi;
            else throw notInt;
        }
        if (v.equals(BigInteger.valueOf(-1))) return -1;
        if (v.signum() >= 0 && v.compareTo(MAX_ID) <= 0) return (int) v.longValue();
        if (v.compareTo(TWO63) >= 0) throw new PyException("OverflowError", what + " is greater than maximum");
        throw new PyException("OverflowError", what + " is less than minimum");
    }

    // ---------------------------------------------------------------- lookups

    private static long initialBuffer(boolean passwd) {
        if (TEST_BUF > 0) return TEST_BUF;
        long n = PosixProc.sysconf((int) (passwd ? SC_GETPW_R_SIZE_MAX : SC_GETGR_R_SIZE_MAX));
        return n > 0 ? n : 1024;
    }

    /**
     * One reentrant lookup. The entry (a {@code struct_passwd}/{@code struct_group}) or {@code null} for "not found" (any
     * return value but {@code 0}/{@code ERANGE}, or a NULL result cell). {@code ERANGE} doubles the buffer, up to 64 MiB.
     */
    static Object lookup(boolean passwd, boolean byName, int id, byte[] name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment rec = a.allocate(passwd ? 48 : 32, 8);
            MemorySegment cell = a.allocate(8, 8);
            MemorySegment key = null;
            if (byName) {
                key = a.allocate(name.length + 1L);       // zero-filled: the last byte is the NUL
                MemorySegment.copy(name, 0, key, JAVA_BYTE, 0, name.length);
            }
            long size = initialBuffer(passwd);
            while (true) {
                try (Arena attempt = Arena.ofConfined()) {          // one buffer at a time: a failed attempt's buffer is freed
                    MemorySegment buf = attempt.allocate(size, 8);
                    cell.set(ADDRESS, 0, MemorySegment.NULL);
                    int rc;
                    try {
                        if (passwd) rc = byName ? (int) GETPWNAM_R.invokeExact(key, rec, buf, size, cell) : (int) GETPWUID_R.invokeExact(id, rec, buf, size, cell);
                        else rc = byName ? (int) GETGRNAM_R.invokeExact(key, rec, buf, size, cell) : (int) GETGRGID_R.invokeExact(id, rec, buf, size, cell);
                    } catch (Throwable t) { throw PosixNative.rethrow(t); }
                    if (rc != ERANGE) {
                        if (rc != 0 || cell.get(ADDRESS, 0).address() == 0L) return null;
                        return passwd ? passwdOf(rec) : groupOf(rec);     // strings copied before this arena closes
                    }
                }
                size *= 2;
                if (size > BUF_LIMIT) throw new PyException("MemoryError", null);
            }
        }
    }

    /** Every entry, in the database's order, under {@link #ENT_LOCK}; {@code endpwent}/{@code endgrent} always runs. */
    static List<Object> all(boolean passwd) {
        List<Object> out = new ArrayList<>();
        synchronized (ENT_LOCK) {
            try {
                if (passwd) SETPWENT.invokeExact(); else SETGRENT.invokeExact();
            } catch (Throwable t) { throw PosixNative.rethrow(t); }
            try {
                while (true) {
                    MemorySegment p;
                    try {
                        p = passwd ? (MemorySegment) GETPWENT.invokeExact() : (MemorySegment) GETGRENT.invokeExact();
                    } catch (Throwable t) { throw PosixNative.rethrow(t); }
                    if (p.address() == 0L) break;
                    out.add(passwd ? passwdOf(p.reinterpret(48)) : groupOf(p.reinterpret(32)));
                }
            } finally {
                try {
                    if (passwd) ENDPWENT.invokeExact(); else ENDGRENT.invokeExact();
                } catch (Throwable t) { throw PosixNative.rethrow(t); }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- struct readers

    /** {@code struct passwd} (48 bytes): name, passwd, uid, gid, gecos, dir, shell. Every string is copied here. */
    private static PyInstance passwdOf(MemorySegment s) {
        return PosixStructSeq.make(PASSWD, cstr(s.get(ADDRESS, 0)), cstrOrNone(s.get(ADDRESS, 8)), id(s.get(JAVA_INT, 16)), id(s.get(JAVA_INT, 20)),
            cstrOrNone(s.get(ADDRESS, 24)), cstrOrNone(s.get(ADDRESS, 32)), cstrOrNone(s.get(ADDRESS, 40)));
    }

    /** {@code struct group} (32 bytes): name, passwd, gid, {@code char **} members (NULL-terminated). */
    private static PyInstance groupOf(MemorySegment s) {
        List<Object> mem = new ArrayList<>();
        MemorySegment arr = s.get(ADDRESS, 24);
        if (arr.address() != 0L) {
            MemorySegment all = arr.reinterpret(Long.MAX_VALUE);
            for (long off = 0; ; off += 8) {
                MemorySegment item = all.get(ADDRESS, off);
                if (item.address() == 0L) break;
                mem.add(cstr(item));
            }
        }
        return PosixStructSeq.make(GROUP, cstr(s.get(ADDRESS, 0)), cstrOrNone(s.get(ADDRESS, 8)), id(s.get(JAVA_INT, 16)), mem);
    }

    /** An unsigned 32-bit id as a Python int, except that {@code 2**32 - 1} is {@code -1} (CPython's {@code _PyLong_FromUid}). */
    private static Long id(int v) { return v == -1 ? -1L : (v & 0xffffffffL); }

    /** A field that CPython (3.13+, SET_STRING) turns into None when the {@code char *} is NULL. */
    private static String cstrOrNone(MemorySegment p) { return p.address() == 0L ? null : cstr(p); }

    /** A NUL-terminated C string as text (UTF-8, surrogateescape); a NULL pointer is the empty string. */
    private static String cstr(MemorySegment p) {
        if (p.address() == 0L) return "";
        MemorySegment all = p.reinterpret(Long.MAX_VALUE);
        long n = 0;
        while (all.get(JAVA_BYTE, n) != 0) n++;
        byte[] b = new byte[(int) n];
        MemorySegment.copy(all, JAVA_BYTE, 0, b, 0, (int) n);
        return Codecs.decode(b, "utf-8", "surrogateescape");
    }
}
