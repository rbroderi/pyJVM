// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.math.BigInteger;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

final class PyRange implements Iterable<Object> {
    final BigInteger start, stop, step;
    PyRange(BigInteger start, BigInteger stop, BigInteger step) {
        if (step.signum() == 0) throw new IllegalArgumentException("range() arg 3 must not be zero");
        this.start = start; this.stop = stop; this.step = step;
    }
    BigInteger length() {
        if (step.signum() > 0) {
            if (start.compareTo(stop) >= 0) return BigInteger.ZERO;
            return stop.subtract(start).subtract(BigInteger.ONE).divide(step).add(BigInteger.ONE);
        }
        if (start.compareTo(stop) <= 0) return BigInteger.ZERO;
        BigInteger pos = step.negate();
        return start.subtract(stop).subtract(BigInteger.ONE).divide(pos).add(BigInteger.ONE);
    }
    @Override public Iterator<Object> iterator() {
        return new Iterator<>() {
            BigInteger current = start;
            @Override public boolean hasNext() { return step.signum() > 0 ? current.compareTo(stop) < 0 : current.compareTo(stop) > 0; }
            @Override public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                BigInteger out = current;
                current = current.add(step);
                return compact(out);
            }
        };
    }
    /** range[start:stop:step] is another range, computed with CPython's slice rules. */
    PyRange slice(PySlice sl) {
        BigInteger len = length();
        BigInteger sstep = sl.step == null ? BigInteger.ONE : bigInt(sl.step);
        if (sstep.signum() == 0) throw new PyException("ValueError", "slice step cannot be zero");
        boolean pos = sstep.signum() > 0;
        BigInteger lower = pos ? BigInteger.ZERO : BigInteger.ONE.negate(), upper = pos ? len : len.subtract(BigInteger.ONE);
        BigInteger a = sl.start == null ? (pos ? lower : upper) : clamp(bigInt(sl.start), len, lower, upper);
        BigInteger z = sl.stop == null ? (pos ? upper : lower) : clamp(bigInt(sl.stop), len, lower, upper);
        return new PyRange(start.add(a.multiply(step)), start.add(z.multiply(step)), step.multiply(sstep));
    }
    private static BigInteger clamp(BigInteger i, BigInteger len, BigInteger lower, BigInteger upper) {
        if (i.signum() < 0) i = i.add(len);
        return i.max(lower).min(upper);
    }
    /** Ranges compare by the sequence they denote, not by their parameters. */
    @Override public boolean equals(Object other) {
        if (!(other instanceof PyRange r)) return false;
        BigInteger n = length();
        if (!n.equals(r.length())) return false;
        if (n.signum() == 0) return true;
        if (!start.equals(r.start)) return false;
        return n.equals(BigInteger.ONE) || step.equals(r.step);
    }
    @Override public int hashCode() {
        BigInteger n = length();
        if (n.signum() == 0) return 0;
        return java.util.Objects.hash(n, start, n.equals(BigInteger.ONE) ? BigInteger.ZERO : step);
    }
    @Override public String toString() {
        return "range(" + start + ", " + stop + (step.equals(BigInteger.ONE) ? "" : ", " + step) + ")";
    }
}
