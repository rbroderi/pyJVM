// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Calendar conversion for {@code time}: struct_time, gmtime, localtime, mktime, asctime, ctime, strftime and strptime
 * (the last one delegates to the stdlib's pure-Python {@code _strptime}, as CPython does).
 */
final class TimeCalendar {
    private TimeCalendar() {}

    /** struct_time is a tuple subclass written in Python and built on first use (it needs the compiler, so not at module creation). */
    private static final String STRUCT_TIME_SOURCE = """
class struct_time(tuple):
    n_sequence_fields = 9
    n_fields = 11
    n_unnamed_fields = 0
    _fields = ('tm_year', 'tm_mon', 'tm_mday', 'tm_hour', 'tm_min', 'tm_sec', 'tm_wday', 'tm_yday', 'tm_isdst')

    def __new__(cls, seq, dict=None):
        seq = tuple(seq)
        if len(seq) < 9:
            raise TypeError("time.struct_time() takes an at least 9-sequence (%d-sequence given)" % len(seq))
        if len(seq) > 11:
            raise TypeError("time.struct_time() takes an at most 11-sequence (%d-sequence given)" % len(seq))
        self = tuple.__new__(cls, seq[:9])
        extra = seq[9:] + (None, None)
        self._extra = (extra[0], extra[1])
        return self

    tm_year = property(lambda self: self[0])
    tm_mon = property(lambda self: self[1])
    tm_mday = property(lambda self: self[2])
    tm_hour = property(lambda self: self[3])
    tm_min = property(lambda self: self[4])
    tm_sec = property(lambda self: self[5])
    tm_wday = property(lambda self: self[6])
    tm_yday = property(lambda self: self[7])
    tm_isdst = property(lambda self: self[8])
    tm_zone = property(lambda self: self._extra[0])
    tm_gmtoff = property(lambda self: self._extra[1])

    def __repr__(self):
        return "time.struct_time(" + ", ".join("%s=%r" % (n, v) for n, v in zip(self._fields, self)) + ")"

    def __reduce__(self):
        return (struct_time, (tuple(self), None))

struct_time.__module__ = 'time'
struct_time.__qualname__ = 'struct_time'
""";

    private static Object structTimeClass;

    static synchronized Object structTime() {
        if (structTimeClass == null) {
            PyDict globals = new PyDict();
            globals.put("__name__", "time");
            ExecSupport.run("exec", STRUCT_TIME_SOURCE, globals, null, "exec");
            structTimeClass = globals.get("struct_time");
        }
        return structTimeClass;
    }

    static void install(PyModule m) {
        NativeTypes.fn(m, "gmtime", (a, k) -> build(at(a, ZoneOffset.UTC)));
        NativeTypes.fn(m, "localtime", (a, k) -> build(at(a, ZoneId.systemDefault())));
        NativeTypes.fn(m, "mktime", (a, k) -> mktime(a));
        NativeTypes.fn(m, "asctime", (a, k) -> asctime(a.isEmpty() ? now(ZoneId.systemDefault()) : fields(a.get(0))));
        NativeTypes.fn(m, "ctime", (a, k) -> asctime(at(a, ZoneId.systemDefault()).fields()));
        NativeTypes.fn(m, "strftime", (a, k) -> strftime(a));
        NativeTypes.fn(m, "strptime", (a, k) -> strptime(a));
        m.attrs.put("_STRUCT_TM_ITEMS", 11L);
        m.lazyAttrs = attr -> { if (attr.equals("struct_time") && !m.attrs.containsKey(attr)) m.attrs.put(attr, structTime()); };
    }

    // ---- broken-down time -------------------------------------------------------------------------

    /** year, month, day, hour, minute, second, weekday (Monday 0), day of year (1-based), isdst, zone name, utc offset. */
    private record Fields(long[] v, String zone, long offset) {
        long[] fields() { return v; }
    }

    private static Fields now(ZoneId zone) { return of(ZonedDateTime.now(zone)); }

    private static Fields of(ZonedDateTime t) {
        boolean dst = t.getZone().getRules().isDaylightSavings(t.toInstant());
        long[] v = {t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond(),
            t.getDayOfWeek().getValue() - 1, t.getDayOfYear(), dst ? 1 : 0};
        String name = t.getZone() instanceof ZoneOffset ? "GMT" : java.util.TimeZone.getTimeZone(t.getZone()).getDisplayName(dst, java.util.TimeZone.SHORT);
        return new Fields(v, name, t.getOffset().getTotalSeconds());
    }

    private static Fields at(List<Object> a, ZoneId zone) {
        if (a.isEmpty() || a.get(0) == null) return now(zone);
        Object o = a.get(0);
        double secs = o instanceof Long l ? l : o instanceof Double d ? d : o instanceof Boolean b ? (b ? 1 : 0)
            : o instanceof java.math.BigInteger bi ? bi.doubleValue() : Double.NaN;
        if (Double.isNaN(secs)) {
            if (o instanceof Double || !(o instanceof Number)) throw new PyException("TypeError", "'" + PyRuntime.typeName(o) + "' object cannot be interpreted as an integer");
            throw new PyException("ValueError", "Invalid value NaN (not a number)");
        }
        long whole = (long) Math.floor(secs);
        try { return of(Instant.ofEpochSecond(whole).atZone(zone)); }
        catch (java.time.DateTimeException e) { throw new PyException("OverflowError", "timestamp out of range for platform time_t"); }
    }

    private static Object build(Fields f) {
        PyTuple t = new PyTuple();
        for (long x : f.v()) t.items.add(x);
        t.items.add(f.zone());
        t.items.add(f.offset());
        ArrayList<Object> args = new ArrayList<>();
        args.add(t);
        return PyRuntime.callFunction(structTime(), args, new LinkedHashMap<Object,Object>());
    }

    /** The first nine fields of a struct_time or any 9-sequence; zone name and offset when a struct_time carries them. */
    private static Fields fields(Object o) {
        ArrayList<Object> items = new ArrayList<>();
        for (Object x : PyRuntime.iterable(o)) items.add(x);
        if (items.size() != 9 && items.size() != 11 && !(o instanceof PyInstance))
            throw new PyException("TypeError", "function takes exactly 9 arguments (" + items.size() + " given)");
        if (items.size() < 9) throw new PyException("TypeError", "time.struct_time() takes an at least 9-sequence (" + items.size() + "-sequence given)");
        long[] v = new long[9];
        for (int i = 0; i < 9; i++) {
            Object x = items.get(i);
            if (!(x instanceof Long) && !(x instanceof Boolean)) throw new PyException("TypeError", "'" + PyRuntime.typeName(x) + "' object cannot be interpreted as an integer");
            v[i] = x instanceof Boolean b ? (b ? 1 : 0) : (Long) x;
        }
        String zone = null; long off = 0;
        try {
            if (o instanceof PyInstance) {
                Object z = PyRuntime.getattr(o, "tm_zone"), g = PyRuntime.getattr(o, "tm_gmtoff");
                if (z instanceof String s) zone = s;
                if (g instanceof Long l) off = l;
            }
        } catch (PyException ignored) { }
        if (zone == null) zone = java.util.TimeZone.getDefault().getDisplayName(v[8] > 0, java.util.TimeZone.SHORT);
        return new Fields(v, zone, off);
    }

    private static Object mktime(List<Object> a) {
        if (a.size() != 1) throw new PyException("TypeError", "mktime() takes exactly one argument (" + a.size() + " given)");
        long[] v = fields(a.get(0)).v();
        try {
            LocalDateTime base = LocalDateTime.of((int) v[0], 1, 1, 0, 0, 0).plusMonths(v[1] - 1).plusDays(v[2] - 1).plusHours(v[3]).plusMinutes(v[4]).plusSeconds(v[5]);
            return (double) base.atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (java.time.DateTimeException e) {
            throw new PyException("OverflowError", "mktime argument out of range");
        }
    }

    // ---- text ------------------------------------------------------------------------------------------

    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};

    private static String two(long n) { return (n < 10 ? "0" : "") + n; }
    private static String pad(long n) { return (n < 10 ? " " : "") + n; }

    private static Object asctime(Fields f) { return asctime(f.v()); }

    private static String asctime(long[] v) {
        return DAYS[(int) v[6]].substring(0, 3) + " " + MONTHS[(int) v[1] - 1].substring(0, 3) + " " + pad(v[2]) + " "
            + two(v[3]) + ":" + two(v[4]) + ":" + two(v[5]) + " " + v[0];
    }

    private static Object strftime(List<Object> a) {
        if (a.isEmpty() || !(a.get(0) instanceof String fmt)) throw new PyException("TypeError", "strftime() argument 1 must be str, not " + (a.isEmpty() ? "None" : PyRuntime.typeName(a.get(0))));
        Fields f = a.size() > 1 ? fields(a.get(1)) : now(ZoneId.systemDefault());
        long[] v = f.v();
        if (v[1] < 1 || v[1] > 12) { if (v[1] == 0) v[1] = 1; else throw new PyException("ValueError", "month out of range"); }
        if (v[2] == 0) v[2] = 1;
        if (v[3] < 0 || v[3] > 23) throw new PyException("ValueError", "hour out of range");
        if (v[4] < 0 || v[4] > 59) throw new PyException("ValueError", "minute out of range");
        if (v[5] < 0 || v[5] > 61) throw new PyException("ValueError", "seconds out of range");
        if (v[6] < 0) v[6] = 6; else if (v[6] > 6) throw new PyException("ValueError", "day of week out of range");
        if (v[7] == 0) v[7] = 1;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c != '%' || i + 1 >= fmt.length()) { out.append(c); continue; }
            char d = fmt.charAt(++i);
            if (!directive(out, d, f)) out.append('%').append(d);
        }
        return out.toString();
    }

    private static boolean directive(StringBuilder out, char d, Fields f) {
        long[] v = f.v();
        long hour12 = v[3] % 12 == 0 ? 12 : v[3] % 12;
        long yday0 = v[7] - 1;
        switch (d) {
            case 'a' -> out.append(DAYS[(int) v[6]], 0, 3);
            case 'A' -> out.append(DAYS[(int) v[6]]);
            case 'b', 'h' -> out.append(MONTHS[(int) v[1] - 1], 0, 3);
            case 'B' -> out.append(MONTHS[(int) v[1] - 1]);
            case 'c' -> out.append(asctime(v));
            case 'C' -> out.append(two(v[0] / 100));
            case 'd' -> out.append(two(v[2]));
            case 'D' -> out.append(two(v[1])).append('/').append(two(v[2])).append('/').append(two(v[0] % 100));
            case 'e' -> out.append(pad(v[2]));
            case 'F' -> out.append(v[0]).append('-').append(two(v[1])).append('-').append(two(v[2]));
            case 'H' -> out.append(two(v[3]));
            case 'I' -> out.append(two(hour12));
            case 'j' -> out.append(String.format("%03d", v[7]));
            case 'k' -> out.append(pad(v[3]));
            case 'l' -> out.append(pad(hour12));
            case 'm' -> out.append(two(v[1]));
            case 'M' -> out.append(two(v[4]));
            case 'n' -> out.append('\n');
            case 'p' -> out.append(v[3] < 12 ? "AM" : "PM");
            case 'r' -> out.append(two(hour12)).append(':').append(two(v[4])).append(':').append(two(v[5])).append(' ').append(v[3] < 12 ? "AM" : "PM");
            case 'R' -> out.append(two(v[3])).append(':').append(two(v[4]));
            case 'S' -> out.append(two(v[5]));
            case 't' -> out.append('\t');
            case 'T', 'X' -> out.append(two(v[3])).append(':').append(two(v[4])).append(':').append(two(v[5]));
            case 'u' -> out.append(v[6] + 1);
            case 'U' -> out.append(two((yday0 + 7 - (v[6] + 1) % 7) / 7));
            case 'w' -> out.append((v[6] + 1) % 7);
            case 'W' -> out.append(two((yday0 + 7 - v[6]) / 7));
            case 'x' -> out.append(two(v[1])).append('/').append(two(v[2])).append('/').append(two(v[0] % 100));
            case 'y' -> out.append(two(v[0] % 100));
            case 'Y' -> out.append(v[0]);
            case 'z' -> {
                long off = f.offset();
                out.append(off < 0 ? '-' : '+').append(two(Math.abs(off) / 3600)).append(two(Math.abs(off) % 3600 / 60));
            }
            case 'Z' -> out.append(f.zone());
            case '%' -> out.append('%');
            default -> { return false; }
        }
        return true;
    }

    private static Object strptime(List<Object> a) {
        Object module = ImportSupport.doImport("_strptime", null, null, NativeTypes.tuple("_strptime_time"), 0L);
        return PyRuntime.callFunction(PyRuntime.getattr(module, "_strptime_time"), new ArrayList<>(a), new LinkedHashMap<Object,Object>());
    }
}
