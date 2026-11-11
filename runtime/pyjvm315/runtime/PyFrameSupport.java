// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;

/** Python-visible frame objects built over the logical frame stack (sys._getframe, f_back, gi_frame, ...). */
final class PyFrameSupport {
    private PyFrameSupport() {}

    private static final java.util.HashMap<String, PyCode> CODES = new java.util.HashMap<>();
    static PyCode codeOf(ActiveFrame a) {
        return CODES.computeIfAbsent(a.name + "\0" + a.filename + "\0" + a.firstlineno, k -> new PyCode(a.name, a.filename, a.firstlineno));
    }

    /** The frame object of an active frame (created on first use, then shared so f_trace and identity persist). */
    static PyFrame frameFor(ActiveFrame a) {
        PyFrame f = a.pyFrame;
        if (f == null) {
            f = new PyFrame(codeOf(a), a.locals);
            f.active = a;
            a.pyFrame = f;
        }
        return f;
    }

    /** Frame objects for the whole logical stack, linked through f_back; returns the innermost. */
    static PyFrame captureChain() {
        ArrayDeque<ActiveFrame> stack = LOGICAL_FRAMES.get();
        PyFrame inner = null, previous = null;
        for (Iterator<ActiveFrame> it = stack.iterator(); it.hasNext(); ) {
            PyFrame f = frameFor(it.next());
            if (previous != null) previous.back = f; else inner = f;
            previous = f;
        }
        if (previous != null) previous.back = null;
        return inner;
    }

    static Object getFrame(Object depthObj) {
        long depth = depthObj == null ? 0 : ((Number) depthObj).longValue();
        PyFrame f = captureChain();
        for (long i = 0; i < depth && f != null; i++) f = f.back;
        if (f == null) throw new PyException("ValueError", "call stack is not deep enough");
        return f;
    }

    /** Frame object of a suspended or running generator; None once it has finished. */
    static Object generatorFrame(PyGenerator g) {
        if (g.finished) return null;
        ActiveFrame a = g.logicalFrame();
        PyFrame f = frameFor(a);
        boolean running = false;
        for (ActiveFrame on : LOGICAL_FRAMES.get()) if (on == a) { running = true; break; }
        if (running) captureChain(); else f.back = null;
        return f;
    }

    static Object generatorAttr(PyGenerator g, String name) {
        return switch (name) {
            case "gi_frame" -> generatorFrame(g);
            case "gi_running" -> g.running;
            default -> codeOf(g.logicalFrame());
        };
    }

    static Object coroutineAttr(PyCoroutine c, String name) {
        return switch (name) {
            case "cr_frame" -> coroutineFrame(c);
            case "cr_running" -> false;
            default -> new PyCode(c.displayName, c.function != null ? c.function.filename : "", c.function != null ? c.function.firstlineno : 0);
        };
    }

    static Object coroutineFrame(PyCoroutine c) {
        PyCoroutine target = c;
        while (target.adopted != null) target = target.adopted;
        if (target.finished || target.closed) return null;
        if (target.frame != null) return generatorFrame(target.frame);
        ActiveFrame placeholder = new ActiveFrame(target.displayName, target.function.filename, target.function.firstlineno);
        PyFrame f = new PyFrame(codeOf(placeholder), placeholder.locals);
        f.active = placeholder;
        return f;
    }

    static Object globalsOf(ActiveFrame a) {
        LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
        if (a == null) return m;
        if (a.namespace != null) { m.putAll(a.namespace); return m; }
        if (a.owner == null) return m;
        try {
            Class<?> c = ownerClass(a.owner);
            Object unbound = unbound();
            for (Field field : c.getDeclaredFields()) {
                String n = field.getName();
                if (!n.startsWith("__py_global_") || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object v = field.get(null);
                if (v != unbound) m.put(n.substring("__py_global_".length()), v);
            }
        } catch (ReflectiveOperationException e) { throw new PyException("RuntimeError", "cannot read frame globals: " + e); }
        return m;
    }

    static Object frameAttr(PyFrame frame, String name) {
        return switch (name) {
            case "f_code" -> frame.code;
            case "f_locals" -> frame.locals;
            case "f_back" -> frame.back;
            case "f_lineno" -> frame.lineno();
            case "f_globals" -> globalsOf(frame.active);
            case "f_trace" -> frame.trace;
            case "f_lasti" -> 0L;
            default -> throw new PyException("AttributeError", "frame has no attribute '" + name + "'");
        };
    }

    static void setFrameAttr(PyFrame frame, String name, Object value) {
        if (name.equals("f_trace")) {
            frame.trace = value;
            if (frame.active != null) frame.active.localTrace = value;
            return;
        }
        throw new PyException("AttributeError", "readonly attribute");
    }

    static Object codeAttr(PyCode code, String name) {
        return switch (name) {
            case "co_name", "co_qualname" -> code.coName;
            case "co_filename" -> code.coFilename;
            case "co_firstlineno" -> code.coFirstlineno;
            default -> throw new PyException("AttributeError", "code has no attribute '" + name + "'");
        };
    }
}
