// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * sys.settrace / sys.setprofile event delivery. Hooks are reached only when {@link #enabled} is set (checked in
 * PyFunction.invokeStatic and PyGenerator.advance) or from code compiled in instrumented mode (line and handler events).
 */
final class Tracing {
    private Tracing() {}

    /** The global fast-path gate: some thread has a trace/profile function, or monitoring is active. */
    static volatile boolean enabled;
    /** sys.settrace / sys.setprofile are per thread (threading.settrace installs the hook in each new thread). */
    private static final class TS { volatile Object traceFn, profileFn; boolean busy; }
    private static final java.util.Set<TS> ALL = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<TS> STATE = ThreadLocal.withInitial(() -> { TS s = new TS(); ALL.add(s); return s; });
    private static final java.util.concurrent.atomic.AtomicInteger HOOKED = new java.util.concurrent.atomic.AtomicInteger();

    static void touch() { STATE.get(); }
    static Object traceFn() { return STATE.get().traceFn; }
    static Object profileFn() { return STATE.get().profileFn; }

    static void refresh() { enabled = HOOKED.get() > 0 || Monitoring.active(); }

    private static void setHooks(TS s, Object trace, Object profile) {
        synchronized (s) {
            boolean was = s.traceFn != null || s.profileFn != null;
            s.traceFn = trace; s.profileFn = profile;
            boolean now = trace != null || profile != null;
            if (was != now) HOOKED.addAndGet(now ? 1 : -1);
        }
        refresh();
    }

    /** sys._settraceallthreads / sys._setprofileallthreads: every thread that has run Python code, and the caller. */
    static Object setAllThreads(Object fn, boolean trace) {
        STATE.get();
        for (TS s : ALL) { if (trace) setHooks(s, fn, s.profileFn); else setHooks(s, s.traceFn, fn); }
        return null;
    }

    static Object settrace(Object fn) { TS s = STATE.get(); setHooks(s, fn, s.profileFn); return null; }
    static Object setprofile(Object fn) { TS s = STATE.get(); setHooks(s, s.traceFn, fn); return null; }

    /** A thread ends: its hooks no longer count towards the global gate. */
    static void threadFinished() {
        TS s = STATE.get();
        if (s.traceFn != null || s.profileFn != null) setHooks(s, null, null);
        ALL.remove(s);
        STATE.remove();
    }

    private static Object invoke(Object fn, PyFrame frame, String event, Object arg) {
        ArrayList<Object> args = new ArrayList<>();
        args.add(frame); args.add(event); args.add(arg);
        return callFunction(fn, args, new LinkedHashMap<Object, Object>());
    }

    private static Object runHook(Object fn, ActiveFrame a, String event, Object arg, boolean isTrace) {
        PyFrame frame = PyFrameSupport.frameFor(a);
        PyFrameSupport.captureChain();
        TS s = STATE.get();
        s.busy = true;
        try { return invoke(fn, frame, event, arg); }
        catch (RuntimeException e) {
            if (isTrace) setHooks(s, null, s.profileFn); else setHooks(s, s.traceFn, null);
            throw e;
        }
        finally { s.busy = false; }
    }

    /** A Python frame was entered (function call or generator resume). */
    static void call(ActiveFrame a) {
        TS s = STATE.get();
        if (s.busy) return;
        if (s.profileFn != null) runHook(s.profileFn, a, "call", null, false);
        if (s.traceFn != null) {
            Object local = runHook(s.traceFn, a, "call", null, true);
            a.localTrace = local;
            if (a.pyFrame != null) a.pyFrame.trace = local;
        }
        Monitoring.start(a);
    }

    static void localEvent(ActiveFrame a, String event, Object arg) {
        if (STATE.get().busy || a.localTrace == null) return;
        Object local = runHook(a.localTrace, a, event, arg, true);
        if (local == null) return;   // a None result keeps the current local trace function
        a.localTrace = local;
        if (a.pyFrame != null) a.pyFrame.trace = local;
    }

    /** The frame is leaving normally (return) or by yielding. */
    static void ret(ActiveFrame a, Object value, boolean yielded) {
        TS s = STATE.get();
        if (s.busy) return;
        localEvent(a, "return", value);
        if (s.profileFn != null) runHook(s.profileFn, a, "return", value, false);
        Monitoring.ret(a, value, yielded);
    }

    /** An exception is leaving the frame: exception event (if not yet reported here), then return with None. */
    static void unwind(ActiveFrame a, PyException e) {
        TS s = STATE.get();
        if (s.busy) return;
        reportException(a, e);
        localEvent(a, "return", null);
        if (s.profileFn != null) runHook(s.profileFn, a, "return", null, false);
        Monitoring.unwind(a, e);
    }

    /** Exception event, once per (exception, frame). */
    static void reportException(ActiveFrame a, PyException e) {
        if (STATE.get().busy || e.tracedFrame == a) return;
        e.tracedFrame = a;
        if (a.localTrace != null) {
            PyTuple info = new PyTuple();
            Object value = exceptionInstance(e);
            info.items.add(typeOf(value)); info.items.add(value); info.items.add(e.traceback);
            localEvent(a, "exception", info);
        }
        Monitoring.raised(a, e);
    }

    /** Line event from code compiled in instrumented mode. */
    public static void traceLine(Object lineObj) {
        ActiveFrame a = currentLogicalFrame();
        if (a == null) return;
        a.line = ((Number) lineObj).longValue();
        if (!enabled || STATE.get().busy) return;
        localEvent(a, "line", null);
        Monitoring.line(a);
    }

    /** Entry of an except handler in instrumented code: report the exception arriving in this frame. */
    public static void traceHandler(Object throwable) {
        ActiveFrame a = currentLogicalFrame();
        if (a == null || !enabled || !(throwable instanceof PyException p)) return;
        reportException(a, p);
        Monitoring.handled(a, p);
    }
}
