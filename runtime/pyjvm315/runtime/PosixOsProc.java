// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.List;
import java.util.Map;

/**
 * The {@code posix} process functions of N1, registered over the shared ones by {@code PosixOsFds.install} (kernel
 * descriptors only). Never loaded on Windows.
 */
final class PosixOsProc {
    private PosixOsProc() {}

    static void install(PyModule m) {
        m.attrs.put("sysconf_names", nameDict(PosixConfNames.SYSCONF_NAMES, PosixConfNames.SYSCONF_VALUES));
        m.attrs.put("confstr_names", nameDict(PosixConfNames.CONFSTR_NAMES, PosixConfNames.CONFSTR_VALUES));
        fn(m, "sysconf", (a, k) -> {
            one("sysconf", a, k);
            return PosixProc.sysconf(confName(m, a.get(0), "sysconf_names"));
        });
        fn(m, "confstr", (a, k) -> {
            one("confstr", a, k);
            return PosixProc.confstr(confName(m, a.get(0), "confstr_names"));
        });
        installWait(m);
        installSignals(m);
    }

    // ---------------------------------------------------------------- argument conversion

    /** A C int argument of the wait/kill family: the lane's OverflowError text (3.8), the TypeError of {@link #cInt} otherwise. */
    private static int cArg(Object o) {
        Object u = NativeSubclass.unwrap(o);
        if (u instanceof java.math.BigInteger b) throw overflow(b.signum() < 0, true);   // 3.13: beyond 64 bits is "C long" (measured)
        long v = PosixFiles.fd(o);
        if (v != (int) v) throw overflow(v < 0, false);
        return (int) v;
    }

    private static PyException overflow(boolean negative, boolean beyondLong) {
        if (LangVersion.atLeast(3, 14)) return new PyException("OverflowError", "Python int too large to convert to C int");
        if (beyondLong) return new PyException("OverflowError", "Python int too large to convert to C long");
        return new PyException("OverflowError", negative ? "signed integer is less than minimum" : "signed integer is greater than maximum");
    }

    /** Exactly {@code n} positional arguments, no keywords, with the lane's argument-count text. */
    private static void fixed(String name, List<Object> a, Map<Object, Object> k, int n) {
        if (!k.isEmpty()) throw new PyException("TypeError", "posix." + name + "() takes no keyword arguments");
        if (a.size() == n) return;
        throw new PyException("TypeError", LangVersion.atLeast(3, 14)
            ? name + " expected " + n + " arguments, got " + a.size()
            : name + "() takes exactly " + n + " arguments (" + a.size() + " given)");
    }

    private static void none(String name, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "posix." + name + "() takes no keyword arguments");
        if (!a.isEmpty()) throw new PyException("TypeError", "posix." + name + "() takes no arguments (" + a.size() + " given)");
    }

    /** The single {@code status} argument of the W* macros (keyword allowed), converted to a C int. */
    private static int statusArg(String name, List<Object> a, Map<Object, Object> k) {
        if (a.isEmpty() && !k.containsKey("status")) throw new PyException("TypeError", name + "() missing required argument 'status' (pos 1)");
        return cInt(NativeArgs.parse(name, a, k, new String[]{"status"}, 1, 0)[0]);
    }

    private static PyException osErr(long negErrno) { return PosixNative.osError((int) -negErrno, null); }

    // ---------------------------------------------------------------- wait family and W* macros

    private static final String[] WAITID_FIELDS = {"si_pid", "si_uid", "si_signo", "si_status", "si_code"};
    private static final String[] WAIT_CONST_NAMES = {"P_ALL", "P_PID", "P_PGID", "P_PIDFD", "WNOHANG", "WUNTRACED", "WSTOPPED", "WEXITED",
        "WCONTINUED", "WNOWAIT", "CLD_EXITED", "CLD_KILLED", "CLD_DUMPED", "CLD_TRAPPED", "CLD_STOPPED", "CLD_CONTINUED", "EX_OK",
        "EX_USAGE", "EX_DATAERR", "EX_NOINPUT", "EX_NOUSER", "EX_NOHOST", "EX_UNAVAILABLE", "EX_SOFTWARE", "EX_OSERR", "EX_OSFILE",
        "EX_CANTCREAT", "EX_IOERR", "EX_TEMPFAIL", "EX_PROTOCOL", "EX_NOPERM", "EX_CONFIG"};
    private static final long[] WAIT_CONST_VALUES = {0, 1, 2, 3, 1, 2, 2, 4, 8, 16777216, 1, 2, 3, 4, 5, 6, 0,
        64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78};

    private static void installWait(PyModule m) {
        for (int i = 0; i < WAIT_CONST_NAMES.length; i++) m.attrs.put(WAIT_CONST_NAMES[i], WAIT_CONST_VALUES[i]);
        PyClass widResult = PosixStructSeq.define("posix", "waitid_result", WAITID_FIELDS);
        m.attrs.put("waitid_result", widResult);

        fn(m, "waitpid", (a, k) -> {
            fixed("waitpid", a, k, 2);
            return waitResult(PosixProc.waitpid(cArg(a.get(0)), cArg(a.get(1))));
        });
        fn(m, "wait", (a, k) -> {
            none("wait", a, k);
            return waitResult(PosixProc.waitpid(-1, 0));
        });
        fn(m, "waitid", (a, k) -> {
            fixed("waitid", a, k, 3);
            int idtype = cArg(a.get(0)), id = cArg(a.get(1)), options = cArg(a.get(2));
            long[] v = new long[5];
            int r = PosixProc.waitid(idtype, id, options, v);
            if (r < 0) throw osErr(r);
            if (v[0] == 0) return null;
            return PosixStructSeq.make(widResult, v[0], v[1], v[2], v[3], v[4]);
        });

        fn(m, "WIFEXITED", (a, k) -> (statusArg("WIFEXITED", a, k) & 0x7f) == 0);
        fn(m, "WEXITSTATUS", (a, k) -> (long) ((statusArg("WEXITSTATUS", a, k) >> 8) & 0xff));
        fn(m, "WIFSIGNALED", (a, k) -> signaled(statusArg("WIFSIGNALED", a, k)));
        fn(m, "WTERMSIG", (a, k) -> (long) (statusArg("WTERMSIG", a, k) & 0x7f));
        fn(m, "WIFSTOPPED", (a, k) -> (statusArg("WIFSTOPPED", a, k) & 0xff) == 0x7f);
        fn(m, "WSTOPSIG", (a, k) -> (long) ((statusArg("WSTOPSIG", a, k) >> 8) & 0xff));
        fn(m, "WIFCONTINUED", (a, k) -> statusArg("WIFCONTINUED", a, k) == 0xffff);
        fn(m, "WCOREDUMP", (a, k) -> {
            one("WCOREDUMP", a, k);
            return (cInt(a.get(0)) & 0x80) != 0;
        });
        fn(m, "waitstatus_to_exitcode", (a, k) -> {
            int st = statusArg("waitstatus_to_exitcode", a, k);
            if ((st & 0x7f) == 0) return (long) ((st >> 8) & 0xff);
            if (signaled(st)) return (long) -(st & 0x7f);
            if ((st & 0xff) == 0x7f) throw new PyException("ValueError", "process stopped by delivery of signal " + ((st >> 8) & 0xff));
            throw new PyException("ValueError", "invalid wait status: " + st);
        });
    }

    private static boolean signaled(int st) { int s = st & 0x7f; return s != 0 && s != 0x7f; }

    /** {@code (pid, status)} from the packed result of {@link PosixProc#waitpid}, or the OSError of its -errno. */
    private static Object waitResult(long r) {
        if (r < 0) throw osErr(r);
        return tuple(r >>> 32, (long) (int) r);
    }

    // ---------------------------------------------------------------- kill, killpg, process groups

    private static void installSignals(PyModule m) {
        fn(m, "kill", (a, k) -> {
            fixed("kill", a, k, 2);
            int pid = cArg(a.get(0));
            int sig = (int) IoUtil.asLong(a.get(1));          // a 64-bit value truncated to its low 32 bits (measured)
            if (pid == PosixProc.getpid()) {
                SignalModule.kill(pid, sig);                  // in-process delivery; the handler has run when this returns
                return null;
            }
            int r = PosixProc.kill(pid, sig);
            if (r < 0) throw osErr(r);
            return null;
        });
        fn(m, "killpg", (a, k) -> {
            fixed("killpg", a, k, 2);
            int pgid = cArg(a.get(0)), sig = cArg(a.get(1));
            if (SysExtras.auditing()) SysExtras.audit("os.killpg", (long) pgid, (long) sig);
            int r = PosixProc.killpg(pgid, sig);
            if (r < 0) throw osErr(r);
            return null;
        });
        fn(m, "getpgid", (a, k) -> {
            int r = PosixProc.getpgid(cArg(NativeArgs.parse("getpgid", a, k, new String[]{"pid"}, 1, 0)[0]));
            if (r < 0) throw osErr(r);
            return (long) r;
        });
        fn(m, "getpgrp", (a, k) -> {
            none("getpgrp", a, k);
            return (long) PosixProc.getpgrp();
        });
        fn(m, "getsid", (a, k) -> {
            one("getsid", a, k);
            int r = PosixProc.getsid(cArg(a.get(0)));
            if (r < 0) throw osErr(r);
            return (long) r;
        });
        fn(m, "setpgid", (a, k) -> {
            fixed("setpgid", a, k, 2);
            int r = PosixProc.setpgid(cArg(a.get(0)), cArg(a.get(1)));
            if (r < 0) throw osErr(r);
            return null;
        });
    }

    /** A plain dict, new on every module creation, built from the generated arrays. */
    private static PyDict nameDict(String[] names, int[] values) {
        PyDict d = new PyDict();
        for (int i = 0; i < names.length; i++) d.put(names[i], (long) values[i]);
        return d;
    }

    /** CPython's argument-count texts for a positional-only, one-argument function. */
    private static void one(String name, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", "posix." + name + "() takes no keyword arguments");
        if (a.size() != 1) throw new PyException("TypeError", "posix." + name + "() takes exactly one argument (" + a.size() + " given)");
    }

    /** A C int argument with CPython's texts: a non-integer is a TypeError, a value outside 32 bits an OverflowError. */
    static int cInt(Object o) { return (int) PosixFds.cfd(PosixFiles.fd(o)); }

    /**
     * {@code conv_confname}: a str is looked up in the module's own {@code sysconf_names}/{@code confstr_names} dict at call
     * time (CPython does this: the table is a real, mutable object, and a change to it changes later lookups); an int is the
     * number itself. The table is read with a generic {@code table[name]} (any object, a dict subclass's {@code __getitem__}
     * honoured), and any failure of that lookup is the ValueError; only a missing attribute is the AttributeError. An object
     * with {@code __index__} is accepted from 3.14 on (measured: TypeError on 3.13).
     */
    private static int confName(PyModule m, Object o, String table) {
        Object u = NativeSubclass.unwrap(o);
        if (u instanceof String s) {
            Object t = m.attrs.get(table);
            if (t == null) throw new PyException("AttributeError", "module 'posix' has no attribute '" + table + "'");
            Object v;
            try { v = PyRuntime.getitem(t, o); }
            catch (PyException e) { throw new PyException("ValueError", "unrecognized configuration name"); }
            u = NativeSubclass.unwrap(v);     // the value goes through the same check as a direct argument
        }
        if (PyRuntime.isIntLike(u)) return cInt(u);
        if (LangVersion.atLeast(3, 14) && !(u instanceof String)) {
            java.math.BigInteger n;
            try { n = BuiltinsExtra.rangeIndex(u); }
            catch (PyException e) {
                if (!e.typeName.equals("TypeError")) throw e;      // an exception raised by __index__ itself propagates
                throw new PyException("TypeError", "configuration names must be strings or integers");
            }
            return cInt(n.bitLength() < 64 ? (Object) n.longValue() : n);
        }
        throw new PyException("TypeError", "configuration names must be strings or integers");
    }
}
