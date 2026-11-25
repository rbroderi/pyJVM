// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
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
        m.attrs.put("POSIX_SPAWN_OPEN", 0L);
        m.attrs.put("POSIX_SPAWN_CLOSE", 1L);
        m.attrs.put("POSIX_SPAWN_DUP2", 2L);
        fn(m, "posix_spawn", (a, k) -> posixSpawn("posix_spawn", false, a, k));
        fn(m, "posix_spawnp", (a, k) -> posixSpawn("posix_spawnp", true, a, k));
    }

    // ---------------------------------------------------------------- posix_spawn, posix_spawnp

    private static final String[] SPAWN_KW = {"file_actions", "setpgroup", "resetids", "setsid", "setsigmask", "setsigdef", "scheduler"};
    private static final int KW_FA = 0, KW_PGROUP = 1, KW_RESETIDS = 2, KW_SETSID = 3, KW_SIGMASK = 4, KW_SIGDEF = 5, KW_SCHED = 6;

    /**
     * {@code os.posix_spawn(path, argv, env, /, *, file_actions=(), setpgroup=None, resetids=False, setsid=False,
     * setsigmask=(), setsigdef=(), scheduler=None)} and {@code posix_spawnp}: CPython's argument checks in its order
     * (plan 3.6), then one call of {@link PosixSpawn#spawn}. The emulated cwd becomes the first action; the signal mask
     * is the {@code setsigmask} argument when given (even empty), else the Python-level blocked set.
     */
    private static Object posixSpawn(String name, boolean usePath, List<Object> a, Map<Object, Object> k) {
        if (a.size() != 3) throw new PyException("TypeError", name + "() takes exactly 3 positional arguments (" + a.size() + " given)");
        Object[] kw = new Object[SPAWN_KW.length];
        java.util.Arrays.fill(kw, NativeArgs.MISSING);
        for (Map.Entry<Object, Object> e : k.entrySet()) {
            int idx = e.getKey() instanceof String s ? List.of(SPAWN_KW).indexOf(s) : -1;
            if (idx < 0) throw new PyException("TypeError", name + "() got an unexpected keyword argument '" + PyRuntime.pyStr(e.getKey()) + "'");
            kw[idx] = e.getValue();
        }
        PosixSpawn.Plan p = new PosixSpawn.Plan();
        p.usePath = usePath;
        Object pathObj = spawnPath(name, a.get(0));
        Object pathU = NativeSubclass.unwrap(pathObj);
        p.path = pathU instanceof PyBytes b ? b.data : Codecs.encode((String) pathU, "utf-8", "surrogateescape");
        for (byte x : p.path) if (x == 0) throw new PyException("ValueError", name + ": embedded null character in path");
        // clinic order: the bool converters of resetids and setsid run before the scheduler type check (measured)
        boolean resetids = kw[KW_RESETIDS] != NativeArgs.MISSING && PyRuntime.truth(kw[KW_RESETIDS]);
        boolean setsid = kw[KW_SETSID] != NativeArgs.MISSING && PyRuntime.truth(kw[KW_SETSID]);
        Object sched = kw[KW_SCHED];
        boolean hasSched = sched != NativeArgs.MISSING && sched != null;
        if (hasSched && !(NativeSubclass.unwrap(sched) instanceof PyTuple)) throw new PyException("TypeError", name + ": scheduler must be a tuple or None");
        Object argv = a.get(1), env = a.get(2);
        Object argvU = NativeSubclass.unwrap(argv);
        if (!(argvU instanceof List || argvU instanceof PyTuple)) throw new PyException("TypeError", name + ": argv must be a tuple or list");
        List<?> items = argvU instanceof PyTuple t ? t.items : (List<?>) argvU;
        if (items.isEmpty()) throw new PyException("ValueError", name + ": argv must not be empty");
        if (env != null && !isMapping(env)) throw new PyException("TypeError", name + ": environment must be a mapping object or None");
        p.argv = new byte[items.size()][];
        for (int i = 0; i < p.argv.length; i++) p.argv[i] = fsArg(items.get(i));
        if (p.argv[0].length == 0) throw new PyException("ValueError", name + ": argv first element cannot be empty");
        p.envp = env == null ? null : envList(env);
        Object fa = kw[KW_FA];
        List<PosixSpawn.Action> actions = fa == NativeArgs.MISSING || fa == null ? List.of() : fileActions(fa);
        spawnAttributes(name, p, kw, resetids, setsid, hasSched);
        if (SysExtras.auditing()) SysExtras.audit("os.posix_spawn", pathObj, argv, env);
        PosixSpawn.Action cwd = PosixSpawn.cwdAction();
        if (cwd != null) p.actions.add(cwd);
        p.actions.addAll(actions);
        long r = PosixSpawn.spawn(p);
        if (r > 0) return r;
        PyException err = PosixNative.osError((int) -r, null);
        throw p.failedAtAdd ? err : PosixFds.withFilename(err, pathObj);
    }

    /** {@code path_converter} for {@code posix_spawn}: str or bytes as given, a PathLike's {@code __fspath__} result; the object is the error's filename. */
    private static Object spawnPath(String name, Object o) {
        Object u = NativeSubclass.unwrap(o);
        if (u instanceof String || u instanceof PyBytes) return o;
        if (o instanceof PyInstance i && i.cls.lookupMethod("__fspath__") != null) return PosixModule.fspath(o);
        throw new PyException("TypeError", name + ": path should be string, bytes or os.PathLike, not " + PyRuntime.typeName(o));
    }

    /** {@code PyUnicode_FSConverter}: str (UTF-8, surrogateescape), bytes or a PathLike, without an embedded NUL. */
    private static byte[] fsArg(Object o) {
        Object u = NativeSubclass.unwrap(o);
        Object f = u instanceof String || u instanceof PyBytes ? u : PosixModule.fspath(o);
        byte[] b = f instanceof PyBytes pb ? pb.data : Codecs.encode((String) f, "utf-8", "surrogateescape");
        for (byte x : b) if (x == 0) throw new PyException("ValueError", "embedded null byte");
        return b;
    }

    /** {@code PyMapping_Check}: the type supports subscription (dict, list, tuple, str, bytes, or a class with {@code __getitem__}). */
    private static boolean isMapping(Object o) {
        Object u = NativeSubclass.unwrap(o);
        if (u instanceof Map || u instanceof List || u instanceof PyTuple || u instanceof String || u instanceof PyBytes
            || u instanceof PyByteArray || u instanceof PyMemoryView || u instanceof PyRange) return true;
        return o instanceof PyInstance i && (i.cls.lookupMethod("__getitem__") != null || i.cls.lookupAttr("__getitem__") != PyRuntime.MISSING);
    }

    /** {@code parse_envlist}: {@code keys()} and {@code values()} of the mapping, each pair as {@code KEY=value}. */
    private static byte[][] envList(Object env) {
        List<Object> keys = asList(PyRuntime.callMethod(env, "keys", new Object[0]));
        List<Object> vals = asList(PyRuntime.callMethod(env, "values", new Object[0]));
        byte[][] out = new byte[keys.size()][];
        for (int i = 0; i < out.length; i++) {
            if (i >= vals.size()) throw new PyException("IndexError", "list index out of range");
            byte[] key = fsArg(keys.get(i)), val = fsArg(vals.get(i));
            boolean bad = key.length == 0;
            for (int j = 1; j < key.length && !bad; j++) bad = key[j] == '=';
            if (bad) throw new PyException("ValueError", "illegal environment variable name");
            byte[] kv = java.util.Arrays.copyOf(key, key.length + 1 + val.length);
            kv[key.length] = '=';
            System.arraycopy(val, 0, kv, key.length + 1, val.length);
            out[i] = kv;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) { return (List<Object>) PyRuntime.listFrom(o); }

    /** {@code parse_file_actions}: a sequence of non-empty tuples {@code (OPEN, fd, path, flags, mode)}, {@code (CLOSE, fd)}, {@code (DUP2, fd, newfd)}. */
    private static List<PosixSpawn.Action> fileActions(Object fa) {
        List<Object> seq;
        try { seq = asList(fa); }
        catch (PyException e) {
            if (!e.typeName.equals("TypeError")) throw e;
            throw new PyException("TypeError", "file_actions must be a sequence or None");
        }
        List<PosixSpawn.Action> out = new ArrayList<>();
        long[] table = {-1};                        // getdtablesize(), read on first use
        for (Object item : seq) {
            if (!(NativeSubclass.unwrap(item) instanceof PyTuple t) || t.items.isEmpty())
                throw new PyException("TypeError", "Each file_actions element must be a non-empty tuple");
            List<Object> v = t.items;
            long tag = cLong(v.get(0));
            if (tag == 0) {
                if (v.size() != 5) throw new PyException("TypeError", "A open file_action tuple must have 5 elements");
                int fd = argInt(v.get(1));
                byte[] path = fsArg(v.get(2));
                int oflag = argInt(v.get(3));
                Object modeU = NativeSubclass.unwrap(v.get(4));
                if (!PyRuntime.isIntLike(modeU)) throw new PyException("TypeError", "A open file_action tuple must have 5 elements");
                int mode = openMode(modeU);
                validFd(table, fd);
                out.add(new PosixSpawn.Action(PosixSpawn.OPEN, fd, oflag, mode, path));
            } else if (tag == 1) {
                if (v.size() != 2) throw new PyException("TypeError", "A close file_action tuple must have 2 elements");
                int fd = argInt(v.get(1));
                validFd(table, fd);
                out.add(new PosixSpawn.Action(PosixSpawn.CLOSE, fd, 0, 0, null));
            } else if (tag == 2) {
                if (v.size() != 3) throw new PyException("TypeError", "A dup2 file_action tuple must have 3 elements");
                int fd = argInt(v.get(1)), fd2 = argInt(v.get(2));
                validFd(table, fd);
                validFd(table, fd2);
                out.add(new PosixSpawn.Action(PosixSpawn.DUP2, fd, fd2, 0, null));
            } else {
                throw new PyException("TypeError", "Unknown file_actions identifier");
            }
        }
        return out;
    }

    /**
     * glibc's {@code __spawn_valid_fd}, checked as each action is parsed: CPython adds every action while it parses
     * {@code file_actions}, so the add function's EBADF (no filename) wins over every later argument error and comes
     * before the audit event. Invalid is {@code fd < 0 || fd >= getdtablesize()} (= {@code sysconf(_SC_OPEN_MAX)}).
     * Other add failures (ENOMEM) still surface from {@link PosixSpawn#spawn} through {@code failedAtAdd}.
     */
    private static void validFd(long[] table, int fd) {
        if (fd >= 0) {
            if (table[0] < 0) table[0] = PosixProc.sysconf(4);
            if (fd < table[0]) return;
        }
        throw PosixNative.osError(9, null);
    }

    /**
     * The {@code k} format (unsigned long bit field) of the OPEN mode, cast to {@code mode_t}: any int, masked. 3.15
     * warns {@code DeprecationWarning: integer value out of range} outside {@code [LONG_MIN, ULONG_MAX]}.
     */
    private static int openMode(Object modeU) {
        if (!(modeU instanceof java.math.BigInteger bi)) return (int) PosixFiles.fd(modeU);
        if (LangVersion.atLeast(3, 15) && (bi.bitLength() > 64 || bi.signum() < 0 && bi.bitLength() > 63))
            PyRuntime.callMethod(ImportSupport.importBuiltin(new ArrayList<Object>(List.of("warnings")), new java.util.LinkedHashMap<Object, Object>()), "warn",
                new Object[]{"integer value out of range", PyRuntime.builtinType("DeprecationWarning"), 2L});   // through Python, so catch_warnings sees it
        return bi.intValue();
    }

    /** {@code PyLong_AsLong}: the integer TypeError, or OverflowError beyond 64 bits. */
    private static long cLong(Object o) {
        if (NativeSubclass.unwrap(o) instanceof java.math.BigInteger) throw new PyException("OverflowError", "Python int too large to convert to C long");
        return PosixFiles.fd(o);
    }

    /** The {@code i} format of {@code PyArg_ParseTuple}: a C int with its overflow texts (lane-independent). */
    private static int argInt(Object o) {
        long v = cLong(o);
        if (v > Integer.MAX_VALUE) throw new PyException("OverflowError", "signed integer is greater than maximum");
        if (v < Integer.MIN_VALUE) throw new PyException("OverflowError", "signed integer is less than minimum");
        return (int) v;
    }

    /** {@code parse_posix_spawn_flags}, in signature order: setpgroup, resetids, setsid, setsigmask, setsigdef, scheduler. */
    private static void spawnAttributes(String name, PosixSpawn.Plan p, Object[] kw, boolean resetids, boolean setsid, boolean hasSched) {
        Object pg = kw[KW_PGROUP];
        if (pg != NativeArgs.MISSING && pg != null) {
            p.pgroup = cInt(pg);
            p.flags |= PosixSpawn.SETPGROUP;
        }
        if (resetids) p.flags |= PosixSpawn.RESETIDS;
        // the reference CPython builds have no POSIX_SPAWN_SETSID support (plan U3, owner decision): never set from here
        if (setsid) throw new PyException("NotImplementedError", name + ": setsid unavailable on this platform");
        p.sigmask = kw[KW_SIGMASK] != NativeArgs.MISSING ? sigset(kw[KW_SIGMASK]) : SignalModule.blockedMask();
        if (kw[KW_SIGDEF] != NativeArgs.MISSING) {
            p.sigdefault = sigset(kw[KW_SIGDEF]);
            p.flags |= PosixSpawn.SETSIGDEF;
        }
        if (hasSched) throw new PyException("NotImplementedError", name + ": scheduler is not supported by pyJVM");
    }

    /** {@code _Py_Sigset_Converter}: an iterable of signal numbers 1..64 as bits {@code 1L << (sig - 1)}. */
    private static long sigset(Object o) {
        long m = 0;
        for (Object item : PyRuntime.iterable(o)) {
            Object u = NativeSubclass.unwrap(item);
            long s = u instanceof java.math.BigInteger ? -1 : PosixFiles.fd(item);
            if (s <= 0 || s > 64) throw new PyException("ValueError", "signal number " + s + " out of range [1; 64]");
            m |= 1L << (s - 1);
        }
        return m;
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
