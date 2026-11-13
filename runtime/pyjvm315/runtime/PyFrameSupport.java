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

    private static final java.util.concurrent.ConcurrentHashMap<String, PyCode> CODES = new java.util.concurrent.ConcurrentHashMap<>();
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

    /** sys._current_frames(): {thread id: innermost frame} for every thread that is running Python code. */
    static Object currentFrames() {
        PyDict out = new PyDict();
        Thread me = Thread.currentThread();
        for (java.util.Map.Entry<Thread, ArrayDeque<ActiveFrame>> e : FRAME_STACKS.entrySet()) {
            ArrayDeque<ActiveFrame> stack = e.getKey() == me ? LOGICAL_FRAMES.get() : e.getValue();
            ActiveFrame[] frames;
            try { frames = stack.toArray(new ActiveFrame[0]); } catch (RuntimeException racing) { continue; }
            PyFrame inner = null, previous = null;
            for (ActiveFrame a : frames) {
                if (a == null) continue;
                PyFrame f = frameFor(a);
                if (previous != null) previous.back = f; else inner = f;
                previous = f;
            }
            if (previous != null) previous.back = null;
            if (inner != null) out.put(e.getKey().threadId(), inner);
        }
        return out;
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

    static Object globalsOf(PyFrame frame) {
        return frame.active != null ? globalsOf(frame.active) : globalsOf(null, frame.owner);
    }

    static Object globalsOf(ActiveFrame a) {
        return a == null ? globalsOf(null, null) : globalsOf(a.namespace, a.owner);
    }

    private static Object globalsOf(java.util.Map<Object, Object> namespace, Object owner) {
        LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
        if (namespace != null) { m.putAll(namespace); return m; }
        if (owner == null) return m;
        try {
            Class<?> c = ownerClass(owner);
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
            case "f_globals" -> globalsOf(frame);
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
            case "co_flags", "co_argcount", "co_posonlyargcount", "co_kwonlyargcount", "co_nlocals", "co_stacksize" -> 0L;
            case "co_freevars", "co_cellvars", "co_varnames", "co_names", "co_consts" -> new PyTuple();
            case "co_code" -> throw noBytecode();
            default -> throw new PyException("AttributeError", "code has no attribute '" + name + "'");
        };
    }

    /** The one documented error for everything that needs CPython bytecode: dis of functions and code objects. */
    static PyException noBytecode() {
        return new PyException("NotImplementedError",
            "pyJVM compiles Python to JVM bytecode and has no CPython bytecode: dis.dis(), dis.get_instructions(), dis.Bytecode and code_info() of functions and code objects are unsupported");
    }

    /** A method call on a code object: the bytecode-dependent ones (co_lines, co_positions, replace...) are unsupported. */
    static PyException codeMethod(String name) {
        if (name.startsWith("co_") || name.equals("replace") || name.startsWith("_varname")) return noBytecode();
        return new PyException("AttributeError", "'code' object has no attribute '" + name + "'");
    }
}
