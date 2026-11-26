// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static pyjvm315.runtime.NativeTypes.fn;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code resource} module over kernel calls ({@code getrlimit}, {@code setrlimit}, {@code prlimit}, {@code getrusage},
 * {@code sysconf(_SC_PAGESIZE)}); N2 plan 1.7, 3.6, 3.8. Reached only through {@code ImportSupport} behind
 * {@code IoFd.KERNEL}. The limits are those of the JVM process: nothing here guards them (CPython semantics), see the
 * safety rules of the tests (plan 2.3 B).
 *
 * <p>Lane rules (3.8): on 3.13/3.14 {@code RLIM_INFINITY} is {@code -1} (only infinity is reported negative; every
 * other limit is read unsigned); on 3.15 it is {@code 2**64 - 1}, {@code RLIM_SAVED_CUR}/{@code RLIM_SAVED_MAX} exist and passing
 * {@code -1} warns. On every lane {@code -1} given to a setter means infinity and any other negative value is a ValueError
 * (measured, {@code work/n2/measure/resource_neg-31x.txt}).
 */
final class PosixResourceModule {
    private PosixResourceModule() {}

    private static final String ME = PosixResourceModule.class.getName();
    private static final int EPERM = 1, EINVAL = 22, NLIMITS = 16, _SC_PAGESIZE = 30;
    private static final BigInteger MINUS_ONE = BigInteger.valueOf(-1);
    private static final BigInteger UNSIGNED_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private static final MethodHandle GETRLIMIT = PosixNative.fn("getrlimit", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle SETRLIMIT = PosixNative.fn("setrlimit", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle PRLIMIT = PosixNative.fn("prlimit", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GETRUSAGE = PosixNative.fn("getrusage", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));

    static synchronized PyModule create() {
        PyModule m = new PyModule("resource", ME);
        PosixLibcConsts.install(m, PosixLibcConsts.RESOURCE_NAMES, PosixLibcConsts.RESOURCE_VALUES);
        if (LangVersion.atLeast(3, 15)) {
            m.attrs.put("RLIM_INFINITY", UNSIGNED_MAX);
            m.attrs.put("RLIM_SAVED_CUR", UNSIGNED_MAX);
            m.attrs.put("RLIM_SAVED_MAX", UNSIGNED_MAX);
        } else {
            m.attrs.put("RLIM_INFINITY", -1L);
        }
        m.attrs.put("error", PyRuntime.builtinType("OSError"));
        m.attrs.put("struct_rusage", PosixOsProc.rusageClass());
        fn(m, "getrlimit", (a, k) -> getrlimit(NativeArgs.one("resource.getrlimit", a, k)));
        fn(m, "setrlimit", (a, k) -> setrlimit(positional("setrlimit", "resource.setrlimit", a, k, 2, 2)));
        fn(m, "prlimit", (a, k) -> prlimit(positional("prlimit", "resource.prlimit", a, k, 2, 3)));
        fn(m, "getrusage", (a, k) -> getrusage(NativeArgs.one("resource.getrusage", a, k)));
        fn(m, "getpagesize", (a, k) -> {
            if (!k.isEmpty()) throw new PyException("TypeError", "resource.getpagesize() takes no keyword arguments");
            if (!a.isEmpty()) throw new PyException("TypeError", "resource.getpagesize() takes no arguments (" + a.size() + " given)");
            return PosixProc.sysconf(_SC_PAGESIZE);
        });
        return m;
    }

    // ---------------------------------------------------------------- arguments

    /** Clinic-style positional-only parsing: no keywords, and {@code name expected [at least|at most ]N argument(s), got M}. */
    private static List<Object> positional(String name, String qualified, List<Object> a, Map<Object, Object> k, int min, int max) {
        if (k != null && !k.isEmpty()) throw new PyException("TypeError", qualified + "() takes no keyword arguments");
        int n = a.size();
        if (n < min || n > max) {
            String kind = min == max ? "" : n < min ? "at least " : "at most ";
            int bound = n < min ? min : max;
            throw new PyException("TypeError", name + " expected " + kind + bound + " argument" + (bound == 1 ? "" : "s") + ", got " + n);
        }
        return a;
    }

    /** The resource number: a C int, then the range check (negative and 16 and above are ValueErrors). */
    private static int resourceArg(Object o) {
        int r = PosixOsProc.cInt(o);
        if (r < 0 || r >= NLIMITS) throw new PyException("ValueError", "invalid resource specified");
        return r;
    }

    /**
     * {@code py2rlim}: an int (or {@code __index__}); {@code -1} is infinity (3.15 warns), other negatives are a ValueError,
     * beyond 64 bits an OverflowError; values from 2**63 up are the unsigned bit pattern.
     */
    private static long rlim(Object o) {
        BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (b.signum() < 0) {
            if (!b.equals(MINUS_ONE)) throw new PyException("ValueError", "Cannot convert negative int");
            if (LangVersion.atLeast(3, 15))
                PyRuntime.callMethod(ImportSupport.importBuiltin(new ArrayList<Object>(List.of("warnings")), new LinkedHashMap<Object, Object>()), "warn",
                    new Object[]{"Use RLIM_INFINITY instead of negative limit value.", PyRuntime.builtinType("DeprecationWarning"), 2L});   // through Python, so catch_warnings sees it
            return -1L;
        }
        if (b.bitLength() > 64) throw new PyException("OverflowError", "Python int too large to convert to C rlim_t");
        return b.longValue();
    }

    /**
     * One native limit as a Python int. {@code rlim_t} is unsigned: every value is read unsigned, except that 3.13/3.14 report
     * {@code RLIM_INFINITY} (all bits set) as -1; 3.15 reports it as {@code 2**64 - 1}.
     */
    private static Object rlimOut(long v) {
        if (v >= 0) return v;
        if (v == -1L && !LangVersion.atLeast(3, 15)) return -1L;
        return new BigInteger(Long.toUnsignedString(v));
    }

    private static PyTuple pair(MemorySegment rl) {
        return NativeTypes.tuple(rlimOut(rl.get(JAVA_LONG, 0L)), rlimOut(rl.get(JAVA_LONG, 8L)));
    }

    /** {@code limits}: any iterable of exactly two ints, written as two unsigned 64-bit values (soft first). */
    private static void limitsInto(Object limits, MemorySegment rl) {
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) PyRuntime.listFrom(limits);
        if (items.size() != 2) throw new PyException("ValueError", "expected a tuple of 2 integers");
        long soft = rlim(items.get(0));
        long hard = rlim(items.get(1));
        rl.set(JAVA_LONG, 0L, soft);
        rl.set(JAVA_LONG, 8L, hard);
    }

    // ---------------------------------------------------------------- functions

    private static Object getrlimit(Object arg) {
        int res = resourceArg(arg);
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena ar = Arena.ofConfined()) {
            MemorySegment rl = ar.allocate(16, 8);
            int r = (int) GETRLIMIT.invokeExact(cs, res, rl);
            if (r == 0) return pair(rl);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        throw PosixNative.osError(PosixNative.errno(cs), null);
    }

    private static Object setrlimit(List<Object> a) {
        int res = resourceArg(a.get(0));
        Object limits = a.get(1);
        if (SysExtras.auditing()) SysExtras.audit("resource.setrlimit", (long) res, limits);   // after the resource check, before the limits
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena ar = Arena.ofConfined()) {
            MemorySegment rl = ar.allocate(16, 8);
            limitsInto(limits, rl);
            int r = (int) SETRLIMIT.invokeExact(cs, res, rl);
            if (r == 0) return null;
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        int e = PosixNative.errno(cs);
        if (e == EINVAL) throw new PyException("ValueError", "current limit exceeds maximum limit");
        if (e == EPERM) throw new PyException("ValueError", "not allowed to raise maximum limit");
        throw PosixNative.osError(e, null);
    }

    private static Object prlimit(List<Object> a) {
        int pid = PosixOsProc.cInt(a.get(0));
        int res = resourceArg(a.get(1));
        Object limits = a.size() > 2 ? a.get(2) : null;
        if (SysExtras.auditing()) SysExtras.audit("resource.prlimit", (long) pid, (long) res, limits);
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena ar = Arena.ofConfined()) {
            MemorySegment neu = MemorySegment.NULL;
            if (limits != null) {
                neu = ar.allocate(16, 8);
                limitsInto(limits, neu);
            }
            MemorySegment old = ar.allocate(16, 8);
            int r = (int) PRLIMIT.invokeExact(cs, pid, res, neu, old);
            if (r == 0) return pair(old);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        int e = PosixNative.errno(cs);
        if (e == EINVAL) throw new PyException("ValueError", "current limit exceeds maximum limit");
        throw PosixNative.osError(e, null);   // EPERM is a PermissionError here, ESRCH a ProcessLookupError
    }

    private static Object getrusage(Object arg) {
        int who = PosixOsProc.cInt(arg);
        MemorySegment cs = PosixNative.tl().cs;
        try (Arena ar = Arena.ofConfined()) {
            MemorySegment ru = ar.allocate(144, 8);   // zeroed by allocate; read only after success
            int r = (int) GETRUSAGE.invokeExact(cs, who, ru);
            if (r == 0) return PosixOsProc.rusage(ru);
        } catch (Throwable t) { throw PosixNative.rethrow(t); }
        int e = PosixNative.errno(cs);
        if (e == EINVAL) throw new PyException("ValueError", "invalid who parameter");
        throw PosixNative.osError(e, null);
    }
}
