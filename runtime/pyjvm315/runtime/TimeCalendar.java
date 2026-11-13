// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.finish;
import static pyjvm315.runtime.NativeTypes.fn;
import static pyjvm315.runtime.NativeTypes.method;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code time.struct_time} and the calendar functions of {@code time}: gmtime, localtime, mktime, strftime, asctime, ctime, strptime. */
final class TimeCalendar {
    private TimeCalendar() {}

    static final String[] NAMES = {"tm_year", "tm_mon", "tm_mday", "tm_hour", "tm_min", "tm_sec", "tm_wday", "tm_yday", "tm_isdst", "tm_zone", "tm_gmtoff"};
    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] FULL_DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] FULL_MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    private static PyClass structTime;

    static void define(PyModule m) {
        structTime = NativeTypes.define("time", "struct_time", (PyClass) null);
        PyRuntime.classAddBase(structTime, PyRuntime.builtinType("tuple"));
        PyRuntime.classAddMethodEx(structTime, "__new__", TimeCalendar.class, "stNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(structTime, "__new__", structTime.methods.get("__new__").function);
        method(structTime, TimeCalendar.class, "__repr__", "stRepr", "self");
        PyTuple match = new PyTuple();
        for (int i = 0; i < 9; i++) match.items.add(NAMES[i]);
        PyRuntime.classAddAttr(structTime, "n_sequence_fields", 9L);
        PyRuntime.classAddAttr(structTime, "n_fields", 11L);
        PyRuntime.classAddAttr(structTime, "n_unnamed_fields", 0L);
        PyRuntime.classAddAttr(structTime, "__match_args__", match);
        finish(structTime);
        m.attrs.put("struct_time", structTime);
        fn(m, "gmtime", (a, k) -> fromEpoch(a, ZoneOffset.UTC, true));
        fn(m, "localtime", (a, k) -> fromEpoch(a, ZoneId.systemDefault(), false));
        fn(m, "mktime", (a, k) -> mktime(a));
        fn(m, "asctime", (a, k) -> asctimeOf(a.isEmpty() ? tuple(structOf(ZonedDateTime.now(), false)) : tuple(a.get(0))));
        fn(m, "ctime", (a, k) -> asctimeOf(tuple(fromEpoch(a, ZoneId.systemDefault(), false))));
        fn(m, "strftime", (a, k) -> strftime(a));
        fn(m, "strptime", (a, k) -> {
            Object mod = ImportSupport.importBuiltin(new java.util.ArrayList<Object>(List.of("_strptime", "", "", new PyTuple(), 0L)), new java.util.LinkedHashMap<Object, Object>());
            return PyRuntime.callMethod(mod, "_strptime_time", a.toArray());
        });
        fn(m, "tzset", (a, k) -> null);
    }

    // ---------- struct_time ----------

    static PyInstance make(long[] f, String zone, long gmtoff) {
        PyInstance o = PyInstance.create(structTime);
        PyTuple t = new PyTuple();
        for (int i = 0; i < 9; i++) t.items.add(f[i]);
        o.payload = t;
        for (int i = 0; i < 9; i++) o.fields.put(NAMES[i], f[i]);
        o.fields.put("tm_zone", zone);
        o.fields.put("tm_gmtoff", gmtoff);
        return o;
    }

    @SuppressWarnings("unchecked")
    public static Object stNew(Object cls, Object argsObj, Object kwargsObj) {
        List<Object> args = ((PyTuple) argsObj).items;
        if (args.isEmpty()) throw new PyException("TypeError", "time.struct_time() takes at least 1 argument (0 given)");
        if (args.size() > 2) throw new PyException("TypeError", "time.struct_time() takes at most 2 arguments (" + args.size() + " given)");
        List<Object> items = new java.util.ArrayList<>();
        for (Object x : PyRuntime.iterable(args.get(0))) items.add(x);
        if (items.size() < 9) throw new PyException("TypeError", "time.struct_time() takes an at least 9-sequence (" + items.size() + "-sequence given)");
        if (items.size() > 11) throw new PyException("TypeError", "time.struct_time() takes an at most 11-sequence (" + items.size() + "-sequence given)");
        PyClass pc = (PyClass) cls;
        PyInstance o = PyInstance.create(pc);
        PyTuple t = new PyTuple();
        for (int i = 0; i < 9; i++) { t.items.add(items.get(i)); o.fields.put(NAMES[i], items.get(i)); }
        o.payload = t;
        o.fields.put("tm_zone", items.size() > 9 ? items.get(9) : null);
        o.fields.put("tm_gmtoff", items.size() > 10 ? items.get(10) : null);
        return o;
    }

    public static Object stRepr(Object self) {
        PyInstance o = (PyInstance) self;
        PyTuple t = (PyTuple) o.payload;
        StringBuilder sb = new StringBuilder("time.struct_time(");
        for (int i = 0; i < t.items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(NAMES[i]).append('=').append(PyRuntime.pyRepr(t.items.get(i)));
        }
        return sb.append(')').toString();
    }

    private static long num(Object o) {
        Object v = NativeSubclass.unwrap(o);
        if (v instanceof Double) throw new PyException("TypeError", "'float' object cannot be interpreted as an integer");
        return IoUtil.asLong(v);
    }

    /** The first nine fields (plus zone and gmtoff, when present) of a struct_time or a 9-tuple. */
    static Object[] tuple(Object o) {
        Object v = NativeSubclass.unwrap(o);
        if (!(v instanceof PyTuple t)) throw new PyException("TypeError", "Tuple or struct_time argument required");
        if (t.items.size() != 9 && !(o instanceof PyInstance)) throw new PyException("TypeError", "function takes exactly 9 arguments (" + t.items.size() + " given)");
        if (t.items.size() < 9) throw new PyException("TypeError", "time.struct_time() takes an at least 9-sequence (" + t.items.size() + "-sequence given)");
        Object[] r = new Object[11];
        for (int i = 0; i < 9; i++) r[i] = t.items.get(i);
        if (o instanceof PyInstance pi) { r[9] = pi.fields.get("tm_zone"); r[10] = pi.fields.get("tm_gmtoff"); }
        return r;
    }

    // ---------- conversion ----------

    private static PyInstance structOf(ZonedDateTime z, boolean utc) {
        long[] f = {z.getYear(), z.getMonthValue(), z.getDayOfMonth(), z.getHour(), z.getMinute(), z.getSecond(),
            z.getDayOfWeek().getValue() - 1, z.getDayOfYear(), 0};
        String zone;
        if (utc) zone = "UTC";
        else {
            boolean dst = z.getZone().getRules().isDaylightSavings(z.toInstant());
            f[8] = dst ? 1 : 0;
            zone = java.util.TimeZone.getTimeZone(z.getZone()).getDisplayName(dst, java.util.TimeZone.SHORT, Locale.ROOT);
        }
        return make(f, zone, z.getOffset().getTotalSeconds());
    }

    private static Object fromEpoch(List<Object> a, ZoneId zone, boolean utc) {
        double secs;
        if (a.isEmpty() || a.get(0) == null) {
            return structOf(ZonedDateTime.now(zone), utc);
        }
        Object v = a.get(0);
        if (v instanceof Double d) {
            if (d.isNaN()) throw new PyException("ValueError", "Invalid value NaN (not a number)");
            if (d.isInfinite()) throw new PyException("OverflowError", "timestamp out of range for platform time_t");
            secs = Math.floor(d);
        } else if (v instanceof Long || v instanceof Boolean || v instanceof BigInteger) {
            Object n = NativeSubclass.unwrap(v);
            if (n instanceof BigInteger b && b.bitLength() > 63) throw new PyException("OverflowError", "timestamp out of range for platform time_t");
            secs = IoUtil.asLong(n);
        } else if (PyRuntime.truth(PyRuntime.hasattr(v, "__index__"))) {
            secs = IoUtil.asLong(v);
        } else if (PyRuntime.truth(PyRuntime.hasattr(v, "__float__"))) {
            secs = Math.floor(((Number) PyRuntime.callMethod(v, "__float__", new Object[0])).doubleValue());
        } else throw new PyException("TypeError", "'" + PyRuntime.typeName(v) + "' object cannot be interpreted as an integer");
        if (Math.abs(secs) > 67768036191676799.0) throw new PyException("OverflowError", "timestamp out of range for platform time_t");
        try {
            return structOf(Instant.ofEpochSecond((long) secs).atZone(zone), utc);
        } catch (java.time.DateTimeException e) {
            throw new PyException("OverflowError", "timestamp out of range for platform time_t");
        }
    }

    private static Object mktime(List<Object> a) {
        if (a.size() != 1) throw new PyException("TypeError", "mktime() takes exactly one argument (" + a.size() + " given)");
        Object[] t = tuple(a.get(0));
        LocalDateTime ldt = normalize(num(t[0]), num(t[1]), num(t[2]), num(t[3]), num(t[4]), num(t[5]));
        if (ldt == null) throw new PyException("OverflowError", "mktime argument out of range");
        ZonedDateTime z = ldt.atZone(ZoneId.systemDefault());
        long isdst = num(t[8]);
        if (isdst >= 0) {   // tm_isdst picks between the two readings of an ambiguous time
            ZonedDateTime alt = isdst > 0 ? z.withEarlierOffsetAtOverlap() : z.withLaterOffsetAtOverlap();
            if (z.getZone().getRules().isDaylightSavings(alt.toInstant()) == (isdst > 0)) z = alt;
        }
        return (double) z.toEpochSecond();
    }

    private static LocalDateTime normalize(long year, long mon, long mday, long hour, long min, long sec) {
        try {
            return LocalDateTime.of((int) year, 1, 1, 0, 0, 0).plusMonths(mon - 1).plusDays(mday - 1).plusHours(hour).plusMinutes(min).plusSeconds(sec);
        } catch (java.time.DateTimeException | ArithmeticException e) {
            return null;
        }
    }

    // ---------- formatting ----------

    private static String asctimeOf(Object[] t) {
        long wday = num(t[6]), mon = num(t[1]);
        checkRange(t);
        return String.format("%s %s %2d %02d:%02d:%02d %d", DAYS[(int) Math.floorMod(wday, 7)], MONTHS[(int) (mon - 1)], num(t[2]), num(t[3]), num(t[4]), num(t[5]), num(t[0]));
    }

    private static void checkRange(Object[] t) {
        long mon = num(t[1]);
        if (mon < 1 || mon > 12) throw new PyException("ValueError", "month out of range");
    }

    private static Object strftime(List<Object> a) {
        if (a.isEmpty() || a.size() > 2) throw new PyException("TypeError", "strftime() takes " + (a.isEmpty() ? "at least 1 argument (0 given)" : "at most 2 arguments (" + a.size() + " given)"));
        if (!(a.get(0) instanceof String fmt)) throw new PyException("TypeError", "strftime() argument 1 must be str, not " + PyRuntime.typeName(a.get(0)));
        Object[] t = a.size() == 2 ? tuple(a.get(1)) : tuple(structOf(ZonedDateTime.now(), false));
        long mon = num(t[1]), mday = num(t[2]), hour = num(t[3]), min = num(t[4]), sec = num(t[5]), wday = num(t[6]), yday = num(t[7]);
        if (mon == 0) mon = 1; if (mday == 0) mday = 1; if (yday == 0) yday = 1;   // CPython accepts the zero "unset" values
        if (mon < 1 || mon > 12) throw new PyException("ValueError", "month out of range");
        if (mday < 1 || mday > 31) throw new PyException("ValueError", "day of month out of range");
        if (hour < 0 || hour > 23) throw new PyException("ValueError", "hour out of range");
        if (min < 0 || min > 59) throw new PyException("ValueError", "minute out of range");
        if (sec < 0 || sec > 61) throw new PyException("ValueError", "seconds out of range");
        if (wday < -1) throw new PyException("ValueError", "day of week out of range");
        wday = Math.floorMod(wday, 7);
        if (yday < 1 || yday > 366) throw new PyException("ValueError", "day of year out of range");
        long year = num(t[0]);
        String zone = t[9] instanceof String s ? s : (String) (java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT, Locale.ROOT));
        long gmtoff = t[10] != null ? num(t[10]) : java.util.TimeZone.getDefault().getRawOffset() / 1000;
        StringBuilder out = new StringBuilder();
        int n = fmt.length();
        for (int i = 0; i < n; i++) {
            char c = fmt.charAt(i);
            if (c != '%') { out.append(c); continue; }
            int j = i + 1;
            char flag = 0;
            if (j < n && "-_0^#".indexOf(fmt.charAt(j)) >= 0) flag = fmt.charAt(j++);
            int width = -1;
            while (j < n && Character.isDigit(fmt.charAt(j))) width = (width < 0 ? 0 : width * 10) + (fmt.charAt(j++) - '0');
            while (j < n && (fmt.charAt(j) == 'E' || fmt.charAt(j) == 'O')) j++;
            if (j >= n) { out.append(fmt, i, n); break; }
            char d = fmt.charAt(j);
            String piece;
            boolean numeric = true;
            int pad = 2;
            switch (d) {
                case 'Y' -> { piece = Long.toString(year); pad = year >= 0 ? 1 : 1; }
                case 'y' -> piece = Long.toString(Math.floorMod(year, 100));
                case 'C' -> piece = Long.toString(Math.floorDiv(year, 100));
                case 'm' -> piece = Long.toString(mon);
                case 'd' -> piece = Long.toString(mday);
                case 'e' -> { piece = Long.toString(mday); if (flag == 0) flag = '_'; }
                case 'H' -> piece = Long.toString(hour);
                case 'k' -> { piece = Long.toString(hour); if (flag == 0) flag = '_'; }
                case 'I' -> piece = Long.toString(hour % 12 == 0 ? 12 : hour % 12);
                case 'l' -> { piece = Long.toString(hour % 12 == 0 ? 12 : hour % 12); if (flag == 0) flag = '_'; }
                case 'M' -> piece = Long.toString(min);
                case 'S' -> piece = Long.toString(sec);
                case 'j' -> { piece = Long.toString(yday); pad = 3; }
                case 'w' -> { piece = Long.toString((wday + 1) % 7); pad = 1; }
                case 'u' -> { piece = Long.toString(wday + 1); pad = 1; }
                case 'U' -> piece = Long.toString((yday - 1 + 7 - ((wday + 1) % 7)) / 7);
                case 'W' -> piece = Long.toString((yday - 1 + 7 - wday) / 7);
                case 'G', 'g', 'V' -> {
                    java.time.LocalDate ld = java.time.LocalDate.ofYearDay((int) year, (int) Math.min(yday, java.time.Year.of((int) year).length()));
                    long wy = ld.get(IsoFields.WEEK_BASED_YEAR), wk = ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                    piece = d == 'G' ? Long.toString(wy) : d == 'g' ? Long.toString(wy % 100) : Long.toString(wk);
                    if (d == 'G') pad = 1;
                }
                case 's' -> { piece = Long.toString(mktimeSeconds(t)); pad = 1; }
                case 'a' -> { piece = DAYS[(int) wday]; numeric = false; }
                case 'A' -> { piece = FULL_DAYS[(int) wday]; numeric = false; }
                case 'b', 'h' -> { piece = MONTHS[(int) mon - 1]; numeric = false; }
                case 'B' -> { piece = FULL_MONTHS[(int) mon - 1]; numeric = false; }
                case 'p' -> { piece = hour >= 12 ? "PM" : "AM"; numeric = false; }
                case 'P' -> { piece = hour >= 12 ? "pm" : "am"; numeric = false; }
                case 'Z' -> { piece = zone; numeric = false; }
                case 'z' -> {
                    long off = gmtoff; String sign = off < 0 ? "-" : "+"; off = Math.abs(off);
                    piece = sign + String.format("%02d%02d", off / 3600, off % 3600 / 60); numeric = false;
                }
                case 'c' -> { piece = strftimeSub("%a %b %e %H:%M:%S %Y", t); numeric = false; }
                case 'x' -> { piece = strftimeSub("%m/%d/%y", t); numeric = false; }
                case 'X', 'T' -> { piece = strftimeSub("%H:%M:%S", t); numeric = false; }
                case 'D' -> { piece = strftimeSub("%m/%d/%y", t); numeric = false; }
                case 'F' -> { piece = strftimeSub("%Y-%m-%d", t); numeric = false; }
                case 'R' -> { piece = strftimeSub("%H:%M", t); numeric = false; }
                case 'r' -> { piece = strftimeSub("%I:%M:%S %p", t); numeric = false; }
                case 'n' -> { piece = "\n"; numeric = false; }
                case 't' -> { piece = "\t"; numeric = false; }
                case '%' -> { piece = "%"; numeric = false; }
                default -> { out.append(fmt, i, j + 1); i = j; continue; }
            }
            if (numeric) {
                int w = width >= 0 ? width : pad;
                boolean neg = piece.startsWith("-");
                String digits = neg ? piece.substring(1) : piece;
                if (flag == '-') w = 0;
                char padc = flag == '_' ? ' ' : '0';
                StringBuilder sb = new StringBuilder();
                int need = w - digits.length() - (neg ? 1 : 0);
                if (padc == ' ') { for (int q = 0; q < need; q++) sb.append(' '); if (neg) sb.append('-'); }
                else { if (neg) sb.append('-'); for (int q = 0; q < need; q++) sb.append('0'); }
                piece = sb.append(digits).toString();
            } else {
                if (flag == '^') piece = piece.toUpperCase(Locale.ROOT);
                else if (flag == '#') piece = piece.equals(piece.toUpperCase(Locale.ROOT)) ? piece.toLowerCase(Locale.ROOT) : piece.toUpperCase(Locale.ROOT);
                while (width > piece.length()) piece = (flag == '0' ? "0" : " ") + piece;
            }
            out.append(piece);
            i = j;
        }
        return out.toString();
    }

    private static String strftimeSub(String fmt, Object[] t) {
        return (String) strftime(List.of(fmt, tupleToStruct(t)));
    }

    private static Object tupleToStruct(Object[] t) {
        PyTuple tup = new PyTuple();
        for (int i = 0; i < 9; i++) tup.items.add(t[i]);
        return tup;
    }

    private static long mktimeSeconds(Object[] t) {
        LocalDateTime ldt = normalize(num(t[0]), num(t[1]), num(t[2]), num(t[3]), num(t[4]), num(t[5]));
        return ldt == null ? 0 : ldt.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
