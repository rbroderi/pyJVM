// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * {@code math}. Functions follow CPython's argument handling (ints, bools, floats and objects with {@code __float__}),
 * its error classes ({@code ValueError: math domain error}, {@code OverflowError: math range error}) and its integer
 * results. {@code erf}, {@code erfc}, {@code gamma} and {@code lgamma} raise NotImplementedError: they need a
 * correctly rounded special-function implementation that is not written yet.
 */
public final class MathModule {
    private MathModule() {}

    private static double num(Object o) {
        if (o instanceof Double d) return d;
        if (o instanceof Long l) return l;
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof BigInteger b) {
            double d = b.doubleValue();
            if (Double.isInfinite(d)) throw new PyException("OverflowError", "int too large to convert to float");
            return d;
        }
        if (o instanceof PyInstance i && i.payload != null) return num(i.payload);
        if (o == null || o instanceof String) throw new PyException("TypeError", "must be real number, not " + PyRuntime.typeName(o));
        Object f = PyRuntime.float_(o);
        if (f instanceof Double d) return d;
        throw new PyException("TypeError", "must be real number, not " + PyRuntime.typeName(o));
    }

    private static BigInteger integer(Object o) {
        if (o instanceof Long l) return BigInteger.valueOf(l);
        if (o instanceof BigInteger b) return b;
        if (o instanceof Boolean b) return b ? BigInteger.ONE : BigInteger.ZERO;
        if (o instanceof PyInstance i && i.payload != null) return integer(i.payload);
        throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer");
    }

    private static Object norm(BigInteger b) { return b.bitLength() < 64 ? (Object) b.longValue() : b; }

    private static double check(double result, double... inputs) {
        if (Double.isNaN(result)) {
            for (double in : inputs) if (Double.isNaN(in)) return result;
            throw new PyException("ValueError", "math domain error");
        }
        if (Double.isInfinite(result)) {
            for (double in : inputs) if (Double.isInfinite(in)) return result;
            throw new PyException("OverflowError", "math range error");
        }
        return result;
    }

    private static void def1(PyModule m, String name, DoubleUnaryOperator op) {
        NativeTypes.fn(m, name, (a, k) -> {
            if (a.size() != 1 || !k.isEmpty()) throw new PyException("TypeError", name + "() takes exactly one argument (" + a.size() + " given)");
            double x = num(a.get(0));
            return check(op.applyAsDouble(x), x);
        });
    }

    private static void def2(PyModule m, String name, DoubleBinaryOperator op) {
        NativeTypes.fn(m, name, (a, k) -> {
            if (a.size() != 2 || !k.isEmpty()) throw new PyException("TypeError", name + " expected 2 arguments, got " + a.size());
            double x = num(a.get(0)), y = num(a.get(1));
            return check(op.applyAsDouble(x, y), x, y);
        });
    }

    private static void domain1(PyModule m, String name, DoubleUnaryOperator op, java.util.function.DoublePredicate valid) {
        NativeTypes.fn(m, name, (a, k) -> {
            if (a.size() != 1 || !k.isEmpty()) throw new PyException("TypeError", name + "() takes exactly one argument (" + a.size() + " given)");
            double x = num(a.get(0));
            if (!Double.isNaN(x) && !valid.test(x)) throw new PyException("ValueError", "math domain error");
            return check(op.applyAsDouble(x), x);
        });
    }

    private static Object log(Object arg) {
        if (arg instanceof BigInteger b && b.signum() > 0 && b.bitLength() > 1000) {
            int shift = b.bitLength() - 60;
            return Math.log(b.shiftRight(shift).doubleValue()) + shift * Math.log(2);
        }
        double x = num(arg);
        if (x <= 0) throw new PyException("ValueError", "expected a positive input");
        return Math.log(x);
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("math", "pyjvm315.runtime.MathModule");
        m.attrs.put("pi", Math.PI);
        m.attrs.put("e", Math.E);
        m.attrs.put("tau", 2 * Math.PI);
        m.attrs.put("inf", Double.POSITIVE_INFINITY);
        m.attrs.put("nan", Double.NaN);

        def1(m, "exp", Math::exp);
        def1(m, "expm1", Math::expm1);
        def1(m, "exp2", x -> Math.pow(2, x));
        def1(m, "sin", Math::sin);
        def1(m, "cos", Math::cos);
        def1(m, "tan", Math::tan);
        def1(m, "atan", Math::atan);
        def1(m, "sinh", Math::sinh);
        def1(m, "cosh", Math::cosh);
        def1(m, "tanh", Math::tanh);
        def1(m, "asinh", x -> Double.isInfinite(x) ? x : Math.log(x + Math.sqrt(x * x + 1)));
        def1(m, "cbrt", Math::cbrt);
        def1(m, "fabs", Math::abs);
        def1(m, "degrees", Math::toDegrees);
        def1(m, "radians", Math::toRadians);
        domain1(m, "sqrt", Math::sqrt, x -> x >= 0);
        domain1(m, "asin", Math::asin, x -> x >= -1 && x <= 1);
        domain1(m, "acos", Math::acos, x -> x >= -1 && x <= 1);
        domain1(m, "acosh", x -> Math.log(x + Math.sqrt(x * x - 1)), x -> x >= 1);
        domain1(m, "atanh", x -> 0.5 * Math.log((1 + x) / (1 - x)), x -> x > -1 && x < 1);
        domain1(m, "log2", x -> Math.log(x) / Math.log(2), x -> x > 0);
        domain1(m, "log10", Math::log10, x -> x > 0);
        domain1(m, "log1p", Math::log1p, x -> x > -1);
        def2(m, "atan2", Math::atan2);
        def2(m, "copysign", Math::copySign);
        def2(m, "hypot", Math::hypot);
        def2(m, "nextafter", (x, y) -> x == y ? y : Math.nextAfter(x, y));
        NativeTypes.fn(m, "fmod", (a, k) -> {
            double x = num(a.get(0)), y = num(a.get(1));
            if (Double.isInfinite(x) && !Double.isNaN(y) || y == 0 && !Double.isNaN(x)) throw new PyException("ValueError", "math domain error");
            return x % y;
        });
        NativeTypes.fn(m, "remainder", (a, k) -> {
            double x = num(a.get(0)), y = num(a.get(1));
            if (Double.isInfinite(x) && !Double.isNaN(y) || y == 0 && !Double.isNaN(x)) throw new PyException("ValueError", "math domain error");
            return Math.IEEEremainder(x, y);
        });
        NativeTypes.fn(m, "pow", (a, k) -> {
            double x = num(a.get(0)), y = num(a.get(1));
            if (x == 0 && y < 0 && !Double.isInfinite(y)) throw new PyException("ValueError", "math domain error");
            if (x < 0 && !Double.isInfinite(x) && y != Math.rint(y) && !Double.isInfinite(y)) throw new PyException("ValueError", "math domain error");
            return check(Math.pow(x, y), x, y);
        });
        NativeTypes.fn(m, "log", (a, k) -> {
            if (a.isEmpty() || a.size() > 2) throw new PyException("TypeError", "log expected at least 1 argument, got " + a.size());
            Object lx = log(a.get(0));
            if (a.size() == 1) return lx;
            Object lb = log(a.get(1));
            double d = (Double) lb;
            if (d == 0) throw new PyException("ZeroDivisionError", "division by zero");
            return (Double) lx / d;
        });
        NativeTypes.fn(m, "floor", (a, k) -> roundInt(a, true));
        NativeTypes.fn(m, "ceil", (a, k) -> roundInt(a, false));
        NativeTypes.fn(m, "trunc", (a, k) -> {
            Object o = a.get(0);
            if (o instanceof Long || o instanceof BigInteger || o instanceof Boolean) return norm(integer(o));
            if (o instanceof PyInstance) { Object r = PyRuntime.callMethod(o, "__trunc__", new Object[0]); return r; }
            double x = num(o);
            return doubleToInt(x < 0 ? Math.ceil(x) : Math.floor(x));
        });
        NativeTypes.fn(m, "isnan", (a, k) -> Double.isNaN(num(a.get(0))));
        NativeTypes.fn(m, "isinf", (a, k) -> Double.isInfinite(num(a.get(0))));
        NativeTypes.fn(m, "isfinite", (a, k) -> { double x = num(a.get(0)); return !Double.isNaN(x) && !Double.isInfinite(x); });
        NativeTypes.fn(m, "isclose", (a, k) -> {
            double x = num(a.get(0)), y = num(a.get(1));
            double rel = k.containsKey("rel_tol") ? num(k.get("rel_tol")) : 1e-9, abs = k.containsKey("abs_tol") ? num(k.get("abs_tol")) : 0.0;
            if (rel < 0 || abs < 0) throw new PyException("ValueError", "tolerances must be non-negative");
            if (x == y) return true;
            if (Double.isInfinite(x) || Double.isInfinite(y)) return false;
            double diff = Math.abs(x - y);
            return diff <= Math.abs(rel * y) || diff <= Math.abs(rel * x) || diff <= abs;
        });
        NativeTypes.fn(m, "modf", (a, k) -> {
            double x = num(a.get(0));
            if (Double.isInfinite(x)) return NativeTypes.tuple(Math.copySign(0.0, x), x);
            double whole = x < 0 ? Math.ceil(x) : Math.floor(x);
            return NativeTypes.tuple(Math.copySign(x - whole, x), whole);
        });
        NativeTypes.fn(m, "frexp", (a, k) -> {
            double x = num(a.get(0));
            if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) return NativeTypes.tuple(x, 0L);
            int exp = Math.getExponent(x) + 1;
            if (exp < Double.MIN_EXPONENT) { double scaled = x * 0x1p54; exp = Math.getExponent(scaled) + 1 - 54; return NativeTypes.tuple(x / Math.scalb(1.0, exp), (long) exp); }
            return NativeTypes.tuple(x / Math.scalb(1.0, exp), (long) exp);
        });
        NativeTypes.fn(m, "ldexp", (a, k) -> {
            double x = num(a.get(0));
            BigInteger e = integer(a.get(1));
            int exp = e.bitLength() > 31 ? (e.signum() < 0 ? -100000 : 100000) : e.intValue();
            return check(Math.scalb(x, exp), x);
        });
        NativeTypes.fn(m, "ulp", (a, k) -> { double x = num(a.get(0)); return Double.isInfinite(x) ? Double.POSITIVE_INFINITY : Math.ulp(x); });
        NativeTypes.fn(m, "fsum", (a, k) -> fsum(a.get(0)));
        NativeTypes.fn(m, "prod", (a, k) -> {
            Object result = k.containsKey("start") ? k.get("start") : (Object) 1L;
            Object source = PyRuntime.iter(a.get(0));
            while (true) {
                try { result = PyRuntime.mul(result, PyLazyIterator.pull(source)); } catch (java.util.NoSuchElementException end) { return result; }
            }
        });
        NativeTypes.fn(m, "sumprod", (a, k) -> {
            List<Object> p = drain(a.get(0)), q = drain(a.get(1));
            if (p.size() != q.size()) throw new PyException("ValueError", "Inputs are not the same length");
            boolean anyFloat = false, generic = false;
            for (List<Object> side : List.of(p, q)) for (Object o : side) {
                if (o instanceof Double) anyFloat = true;
                else if (!(o instanceof Long || o instanceof BigInteger || o instanceof Boolean)) generic = true;
            }
            if (generic) {
                Object total = 0L;
                for (int i = 0; i < p.size(); i++) total = PyRuntime.add(total, PyRuntime.mul(p.get(i), q.get(i)));
                return total;
            }
            if (!anyFloat) {
                BigInteger total = BigInteger.ZERO;
                for (int i = 0; i < p.size(); i++) total = total.add(integer(p.get(i)).multiply(integer(q.get(i))));
                return PyRuntime.compact(total);
            }
            boolean finite = true;
            for (List<Object> side : List.of(p, q)) for (Object o : side) if (o instanceof Double d && (d.isNaN() || d.isInfinite())) finite = false;
            if (!finite) {
                double total = 0;
                for (int i = 0; i < p.size(); i++) total += num(p.get(i)) * num(q.get(i));
                return total;
            }
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;   // exact products and sum, rounded once (CPython accumulates in extended precision)
            for (int i = 0; i < p.size(); i++) total = total.add(exact(p.get(i)).multiply(exact(q.get(i))));
            return total.doubleValue();
        });
        NativeTypes.fn(m, "dist", (a, k) -> {
            List<Object> p = drain(a.get(0)), q = drain(a.get(1));
            if (p.size() != q.size()) throw new PyException("ValueError", "both points must have the same number of dimensions");
            double sum = 0;
            for (int i = 0; i < p.size(); i++) { double d = num(p.get(i)) - num(q.get(i)); sum += d * d; }
            return Math.sqrt(sum);
        });
        NativeTypes.fn(m, "hypot", (a, k) -> {
            double sum = 0, scale = 0;
            for (Object o : a) scale = Math.max(scale, Math.abs(num(o)));
            if (scale == 0 || Double.isInfinite(scale) || Double.isNaN(scale)) return scale;
            for (Object o : a) { double v = num(o) / scale; sum += v * v; }
            return scale * Math.sqrt(sum);
        });
        NativeTypes.fn(m, "factorial", (a, k) -> {
            BigInteger n = integer(a.get(0));
            if (n.signum() < 0) throw new PyException("ValueError", "factorial() not defined for negative values");
            BigInteger r = BigInteger.ONE;
            for (long i = 2; i <= n.longValueExact(); i++) r = r.multiply(BigInteger.valueOf(i));
            return norm(r);
        });
        NativeTypes.fn(m, "gcd", (a, k) -> { BigInteger r = BigInteger.ZERO; for (Object o : a) r = r.gcd(integer(o)); return norm(r); });
        NativeTypes.fn(m, "lcm", (a, k) -> {
            BigInteger r = BigInteger.ONE;
            for (Object o : a) {
                BigInteger v = integer(o).abs();
                if (v.signum() == 0) return 0L;
                r = r.multiply(v).divide(r.gcd(v));
            }
            return norm(r);
        });
        NativeTypes.fn(m, "isqrt", (a, k) -> {
            BigInteger n = integer(a.get(0));
            if (n.signum() < 0) throw new PyException("ValueError", "isqrt() argument must be nonnegative");
            return norm(n.sqrt());
        });
        NativeTypes.fn(m, "comb", (a, k) -> {
            BigInteger n = integer(a.get(0)), r = integer(a.get(1));
            if (n.signum() < 0) throw new PyException("ValueError", "n must be a non-negative integer");
            if (r.signum() < 0) throw new PyException("ValueError", "k must be a non-negative integer");
            if (r.compareTo(n) > 0) return 0L;
            r = r.min(n.subtract(r));
            BigInteger out = BigInteger.ONE;
            for (long i = 1; i <= r.longValueExact(); i++) out = out.multiply(n.subtract(BigInteger.valueOf(i - 1))).divide(BigInteger.valueOf(i));
            return norm(out);
        });
        NativeTypes.fn(m, "perm", (a, k) -> {
            BigInteger n = integer(a.get(0));
            BigInteger r = a.size() > 1 && a.get(1) != null ? integer(a.get(1)) : n;
            if (n.signum() < 0) throw new PyException("ValueError", "n must be a non-negative integer");
            if (r.signum() < 0) throw new PyException("ValueError", "k must be a non-negative integer");
            if (r.compareTo(n) > 0) return 0L;
            BigInteger out = BigInteger.ONE;
            for (long i = 0; i < r.longValueExact(); i++) out = out.multiply(n.subtract(BigInteger.valueOf(i)));
            return norm(out);
        });
        for (String unimplemented : new String[]{"erf", "erfc", "gamma", "lgamma"}) {
            NativeTypes.fn(m, unimplemented, (a, k) -> { throw new PyException("NotImplementedError", "math." + unimplemented + " is not implemented"); });
        }
        return m;
    }

    private static java.math.BigDecimal exact(Object o) {
        return o instanceof Double d ? new java.math.BigDecimal(d) : new java.math.BigDecimal(integer(o));
    }
    private static List<Object> drain(Object iterable) {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        Object source = PyRuntime.iter(iterable);
        while (true) {
            try { out.add(PyLazyIterator.pull(source)); } catch (java.util.NoSuchElementException end) { return out; }
        }
    }

    private static Object doubleToInt(double x) {
        if (Double.isNaN(x)) throw new PyException("ValueError", "cannot convert float NaN to integer");
        if (Double.isInfinite(x)) throw new PyException("OverflowError", "cannot convert float infinity to integer");
        if (Math.abs(x) < 9.0e18) return (long) x;
        return norm(new BigDecimal(x).toBigInteger());
    }

    private static Object roundInt(List<Object> a, boolean floor) {
        Object o = a.get(0);
        if (o instanceof Long || o instanceof BigInteger || o instanceof Boolean) return norm(integer(o));
        if (o instanceof PyInstance inst && inst.payload == null) {
            return PyRuntime.callMethod(o, floor ? "__floor__" : "__ceil__", new Object[0]);
        }
        double x = num(o);
        return doubleToInt(floor ? Math.floor(x) : Math.ceil(x));
    }

    /** Shewchuk's exact summation, as in CPython. */
    private static Object fsum(Object iterable) {
        java.util.ArrayList<Double> partials = new java.util.ArrayList<>();
        double special = 0;
        boolean hasSpecial = false;
        for (Object o : drain(iterable)) {
            double x = num(o);
            if (Double.isNaN(x) || Double.isInfinite(x)) { special += x; hasSpecial = true; continue; }
            int used = 0;
            for (double y : new java.util.ArrayList<>(partials)) {
                if (Math.abs(x) < Math.abs(y)) { double t = x; x = y; y = t; }
                double hi = x + y, lo = y - (hi - x);
                if (lo != 0) partials.set(used++, lo);
                x = hi;
            }
            while (partials.size() > used) partials.remove(partials.size() - 1);
            partials.add(x);
        }
        if (hasSpecial) return special;
        double total = 0;
        for (int i = partials.size() - 1; i >= 0; i--) {
            double x = partials.get(i), hi = total + x, lo = x - (hi - total);
            total = hi;
            if (lo != 0) {
                if (i > 0 && (lo < 0) == (partials.get(i - 1) < 0)) {
                    double y = lo * 2, t = total + y;
                    if (y == t - total) total = t;
                }
                break;
            }
        }
        return total;
    }
}
