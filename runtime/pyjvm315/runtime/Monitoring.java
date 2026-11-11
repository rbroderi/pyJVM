// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * sys.monitoring (PEP 669) for the events the logical-frame model can deliver faithfully: PY_START, PY_RESUME,
 * PY_RETURN, PY_YIELD, LINE, RAISE, EXCEPTION_HANDLED and PY_UNWIND. Selecting any other event is a
 * NotImplementedError rather than a silent no-op. Instruction offsets passed to callbacks are always 0.
 */
final class Monitoring {
    private Monitoring() {}

    static final int PY_START = 1, PY_RESUME = 2, PY_RETURN = 4, PY_YIELD = 8, LINE = 32,
        RAISE = 2048, EXCEPTION_HANDLED = 4096, PY_UNWIND = 8192;
    static final int SUPPORTED = PY_START | PY_RESUME | PY_RETURN | PY_YIELD | LINE | RAISE | EXCEPTION_HANDLED | PY_UNWIND;
    static final Object DISABLE = new Object() { @Override public String toString() { return "DISABLE"; } };
    static final Object MISSING = new Object() { @Override public String toString() { return "MISSING"; } };

    private static final int TOOLS = 6;
    private static final String[] toolNames = new String[TOOLS];
    private static final int[] toolEvents = new int[TOOLS];
    @SuppressWarnings("unchecked")
    private static final LinkedHashMap<Integer, Object>[] callbacks = new LinkedHashMap[TOOLS];
    static { for (int i = 0; i < TOOLS; i++) callbacks[i] = new LinkedHashMap<>(); }
    private static final ThreadLocal<boolean[]> BUSY = ThreadLocal.withInitial(() -> new boolean[1]);

    static boolean active() { for (int e : toolEvents) if (e != 0) return true; return false; }

    private static int tool(Object id) {
        if (!(id instanceof Long l) || l < 0 || l >= TOOLS) throw new PyException("ValueError", "invalid tool " + pyRepr(id) + " (must be between 0 and 5)");
        return (int) (long) l;
    }

    private static int bitIndex(Object event) {
        if (!(event instanceof Long l) || l <= 0 || Long.bitCount(l) != 1)
            throw new PyException("ValueError", "The callback can only be set for one event at a time");
        return (int) (long) l;
    }

    static void install(PyModule m) {
        fn(m, "use_tool_id", a -> {
            int t = tool(a.get(0));
            if (toolNames[t] != null) throw new PyException("ValueError", "tool " + t + " is already in use");
            toolNames[t] = (String) a.get(1); return null;
        });
        fn(m, "free_tool_id", a -> { int t = tool(a.get(0)); clear(t); toolNames[t] = null; Tracing.refresh(); return null; });
        fn(m, "clear_tool_id", a -> { clear(tool(a.get(0))); Tracing.refresh(); return null; });
        fn(m, "get_tool", a -> toolNames[tool(a.get(0))]);
        fn(m, "register_callback", a -> {
            int t = tool(a.get(0)); int bit = bitIndex(a.get(1));
            Object old = callbacks[t].get(bit);
            if (a.get(2) == null) callbacks[t].remove(bit); else callbacks[t].put(bit, a.get(2));
            return old == null ? null : old;
        });
        fn(m, "set_events", a -> {
            int t = tool(a.get(0)); long mask = ((Number) a.get(1)).longValue();
            if ((mask & ~(long) SUPPORTED) != 0)
                throw new PyException("NotImplementedError", "sys.monitoring events not supported by pyJVM: mask " + (mask & ~(long) SUPPORTED)
                    + " (supported: PY_START, PY_RESUME, PY_RETURN, PY_YIELD, LINE, RAISE, EXCEPTION_HANDLED, PY_UNWIND)");
            toolEvents[t] = (int) mask; Tracing.refresh(); return null;
        });
        fn(m, "get_events", a -> (long) toolEvents[tool(a.get(0))]);
        fn(m, "set_local_events", a -> { throw new PyException("NotImplementedError", "sys.monitoring.set_local_events is not supported by pyJVM"); });
        fn(m, "get_local_events", a -> 0L);
        fn(m, "restart_events", a -> null);
        m.attrs.put("DISABLE", DISABLE); m.attrs.put("MISSING", MISSING);
        m.attrs.put("DEBUGGER_ID", 0L); m.attrs.put("COVERAGE_ID", 1L); m.attrs.put("PROFILER_ID", 2L); m.attrs.put("OPTIMIZER_ID", 5L);
        PyModule events = new PyModule("sys.monitoring.events", "pyjvm315.runtime.Monitoring");
        Object[][] table = {{"BRANCH", 262144L}, {"BRANCH_LEFT", 256L}, {"BRANCH_RIGHT", 512L}, {"CALL", 16L}, {"C_RAISE", 131072L},
            {"C_RETURN", 65536L}, {"EXCEPTION_HANDLED", 4096L}, {"INSTRUCTION", 64L}, {"JUMP", 128L}, {"LINE", 32L},
            {"NO_EVENTS", 0L}, {"PY_RESUME", 2L}, {"PY_RETURN", 4L}, {"PY_START", 1L}, {"PY_THROW", 16384L}, {"PY_UNWIND", 8192L},
            {"PY_YIELD", 8L}, {"RAISE", 2048L}, {"RERAISE", 32768L}, {"STOP_ITERATION", 1024L}};
        for (Object[] row : table) events.attrs.put((String) row[0], row[1]);
        m.attrs.put("events", events);
    }

    private interface Fn { Object call(List<Object> args); }
    private static void fn(PyModule m, String name, Fn body) { m.attrs.put(name, new NativeFunction("monitoring." + name, (a, k) -> body.call(a))); }

    private static void clear(int t) { toolEvents[t] = 0; callbacks[t].clear(); }

    private static void fire(int bit, ActiveFrame a, Object... extra) {
        if (BUSY.get()[0]) return;
        PyCode code = PyFrameSupport.frameFor(a).code;
        for (int t = 0; t < TOOLS; t++) {
            if ((toolEvents[t] & bit) == 0) continue;
            Object cb = callbacks[t].get(bit);
            if (cb == null) continue;
            ArrayList<Object> args = new ArrayList<>();
            args.add(code);
            if (bit != LINE) args.add(0L);
            for (Object e : extra) args.add(e);
            BUSY.get()[0] = true;
            try { callFunction(cb, args, new LinkedHashMap<Object, Object>()); }
            finally { BUSY.get()[0] = false; }
        }
    }

    static void start(ActiveFrame a) { fire(a.resumed ? PY_RESUME : PY_START, a); }
    static void ret(ActiveFrame a, Object value, boolean yielded) { fire(yielded ? PY_YIELD : PY_RETURN, a, value); }
    static void unwind(ActiveFrame a, PyException e) { fire(PY_UNWIND, a, exceptionInstance(e)); }
    static void raised(ActiveFrame a, PyException e) { fire(RAISE, a, exceptionInstance(e)); }
    static void handled(ActiveFrame a, PyException e) { fire(EXCEPTION_HANDLED, a, exceptionInstance(e)); }
    static void line(ActiveFrame a) { fire(LINE, a, a.line); }
}
