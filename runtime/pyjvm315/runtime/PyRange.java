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
    @Override public String toString() { return "range(" + start + ", " + stop + ", " + step + ")"; }
}
