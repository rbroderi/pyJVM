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

    static boolean enabled;
    static Object traceFn, profileFn;
    /** True while a hook runs: events are not generated for the hook itself. */
    private static boolean busy;

    static void refresh() { enabled = traceFn != null || profileFn != null || Monitoring.active(); }

    static Object settrace(Object fn) { traceFn = fn; refresh(); return null; }
    static Object setprofile(Object fn) { profileFn = fn; refresh(); return null; }

    private static Object invoke(Object fn, PyFrame frame, String event, Object arg) {
        ArrayList<Object> args = new ArrayList<>();
        args.add(frame); args.add(event); args.add(arg);
        return callFunction(fn, args, new LinkedHashMap<Object, Object>());
    }

    private static Object runHook(Object fn, ActiveFrame a, String event, Object arg, boolean isTrace) {
        PyFrame frame = PyFrameSupport.frameFor(a);
        PyFrameSupport.captureChain();
        busy = true;
        try { return invoke(fn, frame, event, arg); }
        catch (RuntimeException e) {
            if (isTrace) traceFn = null; else profileFn = null;
            refresh();
            throw e;
        }
        finally { busy = false; }
    }

    /** A Python frame was entered (function call or generator resume). */
    static void call(ActiveFrame a) {
        if (busy) return;
        if (profileFn != null) runHook(profileFn, a, "call", null, false);
        if (traceFn != null) {
            Object local = runHook(traceFn, a, "call", null, true);
            a.localTrace = local;
            if (a.pyFrame != null) a.pyFrame.trace = local;
        }
        Monitoring.start(a);
    }

    static void localEvent(ActiveFrame a, String event, Object arg) {
        if (busy || a.localTrace == null) return;
        Object local = runHook(a.localTrace, a, event, arg, true);
        if (local == null) return;   // a None result keeps the current local trace function
        a.localTrace = local;
        if (a.pyFrame != null) a.pyFrame.trace = local;
    }

    /** The frame is leaving normally (return) or by yielding. */
    static void ret(ActiveFrame a, Object value, boolean yielded) {
        if (busy) return;
        localEvent(a, "return", value);
        if (profileFn != null) runHook(profileFn, a, "return", value, false);
        Monitoring.ret(a, value, yielded);
    }

    /** An exception is leaving the frame: exception event (if not yet reported here), then return with None. */
    static void unwind(ActiveFrame a, PyException e) {
        if (busy) return;
        reportException(a, e);
        localEvent(a, "return", null);
        if (profileFn != null) runHook(profileFn, a, "return", null, false);
        Monitoring.unwind(a, e);
    }

    /** Exception event, once per (exception, frame). */
    static void reportException(ActiveFrame a, PyException e) {
        if (busy || e.tracedFrame == a) return;
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
        if (!enabled || busy) return;
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
