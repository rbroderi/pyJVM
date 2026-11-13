// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.TimeZone;

/**
 * {@code time}: clocks, {@code sleep} and the timezone constants; calendar conversion is in {@link TimeCalendar}.
 */
public final class TimeModule {
    private TimeModule() {}

    private static final long START = System.nanoTime();

    private static double seconds(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Double d) return d;
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof java.math.BigInteger b) return b.doubleValue();
        throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer or float");
    }

    static PyModule create() {
        PyModule m = new PyModule("time", "pyjvm315.runtime.TimeModule");
        NativeTypes.fn(m, "time", (a, k) -> { Instant now = Instant.now(); return now.getEpochSecond() + now.getNano() / 1e9; });
        NativeTypes.fn(m, "time_ns", (a, k) -> { Instant now = Instant.now(); return now.getEpochSecond() * 1_000_000_000L + now.getNano(); });
        NativeTypes.fn(m, "monotonic", (a, k) -> System.nanoTime() / 1e9);
        NativeTypes.fn(m, "monotonic_ns", (a, k) -> System.nanoTime());
        NativeTypes.fn(m, "perf_counter", (a, k) -> System.nanoTime() / 1e9);
        NativeTypes.fn(m, "perf_counter_ns", (a, k) -> System.nanoTime());
        NativeTypes.fn(m, "process_time", (a, k) -> cpuNanos() / 1e9);
        NativeTypes.fn(m, "process_time_ns", (a, k) -> cpuNanos());
        NativeTypes.fn(m, "thread_time", (a, k) -> cpuNanos() / 1e9);
        NativeTypes.fn(m, "thread_time_ns", (a, k) -> cpuNanos());
        NativeTypes.fn(m, "sleep", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", "sleep() takes exactly one argument (" + a.size() + " given)");
            double s = seconds(a.get(0));
            if (s < 0) throw new PyException("ValueError", "sleep length must be non-negative");
            if (SignalModule.active) { SignalModule.sleep((long) (s * 1e9)); return null; }
            try { Thread.sleep((long) (s * 1000), (int) ((s * 1e9) % 1_000_000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        });
        TimeCalendar.define(m);
        TimeZone tz = TimeZone.getDefault();
        long offset = -tz.getRawOffset() / 1000;
        boolean dst = tz.useDaylightTime();
        m.attrs.put("timezone", offset);
        m.attrs.put("altzone", dst ? offset - tz.getDSTSavings() / 1000 : offset);
        m.attrs.put("daylight", dst ? 1L : 0L);
        m.attrs.put("tzname", NativeTypes.tuple(tz.getDisplayName(false, TimeZone.SHORT), tz.getDisplayName(true, TimeZone.SHORT)));
        return m;
    }

    private static long cpuNanos() {
        try {
            if (ManagementFactory.getThreadMXBean().isCurrentThreadCpuTimeSupported()) return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        } catch (RuntimeException ignored) { }
        return System.nanoTime() - START;
    }
}
