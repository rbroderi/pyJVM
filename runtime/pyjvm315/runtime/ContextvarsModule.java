// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code _contextvars}: Context, ContextVar and Token. Each Java thread has its own current Context; a Context is a
 * mutable mapping from ContextVar to value (CPython uses an immutable HAMT, which only matters for the cost of
 * {@code copy_context}).
 */
public final class ContextvarsModule {
    private ContextvarsModule() {}

    private static PyClass contextClass, varClass, tokenClass;
    private static PyInstance missing;
    private static final ThreadLocal<PyInstance> CURRENT = new ThreadLocal<>();

    static final class Ctx { final LinkedHashMap<Object,Object> data = new LinkedHashMap<>(); boolean entered; }
    static final class Var { String name; Object dflt; boolean hasDefault; }
    static final class Tok { PyInstance var; Object old; boolean hadOld; boolean used; PyInstance ctx; }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_contextvars", "pyjvm315.runtime.ContextvarsModule");
        Class<?> c = ContextvarsModule.class;

        tokenClass = define("_contextvars", "Token", null);
        PyClass missingClass = define("_contextvars", "Token.MISSING", null);
        missingClass.qualname = "Token.MISSING";
        method(missingClass, c, "__repr__", "missingRepr", "self");
        finish(missingClass);
        missing = newInstance(missingClass, "missing");
        method(tokenClass, c, "__repr__", "tokRepr", "self");
        PyRuntime.classAddProperty(tokenClass, "var", c.getName(), "tokVar", null);
        PyRuntime.classAddProperty(tokenClass, "old_value", c.getName(), "tokOld", null);
        finish(tokenClass);
        PyRuntime.classAddAttr(tokenClass, "MISSING", missing);

        varClass = define("_contextvars", "ContextVar", null);
        methodVarKw(varClass, c, "__init__", "cvInit", "self,name", "args", "kwargs", defaults());
        methodVar(varClass, c, "get", "cvGet", "self", "args");
        method(varClass, c, "set", "cvSet", "self,value");
        method(varClass, c, "reset", "cvReset", "self,token");
        method(varClass, c, "__repr__", "cvRepr", "self");
        method(varClass, c, "__hash__", "cvHash", "self");
        PyRuntime.classAddProperty(varClass, "name", c.getName(), "cvName", null);
        finish(varClass);

        contextClass = define("_contextvars", "Context", null);
        method(contextClass, c, "__init__", "ctxInit", "self");
        methodVar(contextClass, c, "get", "ctxGet", "self", "args");
        method(contextClass, c, "__getitem__", "ctxGetItem", "self,var");
        method(contextClass, c, "__contains__", "ctxContains", "self,var");
        method(contextClass, c, "__len__", "ctxLen", "self");
        method(contextClass, c, "__iter__", "ctxIter", "self");
        method(contextClass, c, "keys", "ctxKeys", "self");
        method(contextClass, c, "values", "ctxValues", "self");
        method(contextClass, c, "items", "ctxItems", "self");
        method(contextClass, c, "copy", "ctxCopy", "self");
        methodVarKw(contextClass, c, "run", "ctxRun", "self,callable", "args", "kwargs", defaults());
        finish(contextClass);

        m.attrs.put("Context", contextClass);
        m.attrs.put("ContextVar", varClass);
        m.attrs.put("Token", tokenClass);
        fn(m, "copy_context", (a, k) -> ctxCopy(current()));
        return m;
    }

    private static PyInstance current() {
        PyInstance ctx = CURRENT.get();
        if (ctx == null) {
            ctx = newInstance(contextClass, new Ctx());
            CURRENT.set(ctx);
        }
        return ctx;
    }

    private static Ctx ctx(Object o) { return state(o, Ctx.class, "Context"); }
    private static Var var(Object o) { return state(o, Var.class, "ContextVar"); }

    public static Object missingRepr(Object self) { return "<Token.MISSING>"; }

    // ---- ContextVar ------------------------------------------------------------------------------

    public static Object cvInit(Object self, Object name, Object args, Object kwargs) {
        if (!(name instanceof String s)) throw new PyException("TypeError", "context variable name must be a str");
        if (!((PyTuple) args).items.isEmpty()) throw new PyException("TypeError", "ContextVar() takes exactly 1 positional argument (" + (1 + ((PyTuple) args).items.size()) + " given)");
        Var v = new Var();
        v.name = s;
        @SuppressWarnings("unchecked") Map<Object,Object> kw = (Map<Object,Object>) kwargs;
        for (Map.Entry<Object,Object> e : kw.entrySet()) {
            if (!"default".equals(e.getKey())) throw new PyException("TypeError", "'" + e.getKey() + "' is an invalid keyword argument for ContextVar()");
            v.dflt = e.getValue();
            v.hasDefault = true;
        }
        setState(self, v);
        return null;
    }

    public static Object cvName(Object self) { return var(self).name; }
    public static Object cvHash(Object self) { return (long) System.identityHashCode(self); }
    public static Object cvRepr(Object self) {
        Var v = var(self);
        return "<ContextVar name=" + PyRuntime.pyRepr(v.name) + (v.hasDefault ? " default=" + PyRuntime.pyRepr(v.dflt) : "") + " at 0x" + Integer.toHexString(System.identityHashCode(self)) + ">";
    }

    public static Object cvGet(Object self, Object args) {
        Var v = var(self);
        ArrayList<Object> a = ((PyTuple) args).items;
        if (a.size() > 1) throw new PyException("TypeError", "get expected at most 1 argument, got " + a.size());
        Ctx c = ctx(current());
        if (c.data.containsKey(self)) return c.data.get(self);
        if (!a.isEmpty()) return a.get(0);
        if (v.hasDefault) return v.dflt;
        throw new PyException("LookupError", self);
    }

    public static Object cvSet(Object self, Object value) {
        var(self);
        PyInstance cur = current();
        Ctx c = ctx(cur);
        Tok t = new Tok();
        t.var = (PyInstance) self;
        t.hadOld = c.data.containsKey(self);
        t.old = c.data.get(self);
        t.ctx = cur;
        c.data.put(self, value);
        return newInstance(tokenClass, t);
    }

    public static Object cvReset(Object self, Object token) {
        if (!(token instanceof PyInstance ti) || !(ti.slotValues.get(STATE) instanceof Tok t))
            throw new PyException("TypeError", "expected an instance of Token, got " + PyRuntime.pyRepr(token));
        if (t.used) throw new PyException("RuntimeError", PyRuntime.pyRepr(token) + " has already been used once");
        if (t.var != self) throw new PyException("ValueError", PyRuntime.pyRepr(token) + " was created by a different ContextVar");
        PyInstance cur = current();
        if (t.ctx != cur) throw new PyException("ValueError", PyRuntime.pyRepr(token) + " was created in a different Context");
        Ctx c = ctx(cur);
        if (t.hadOld) c.data.put(self, t.old); else c.data.remove(self);
        t.used = true;
        return null;
    }

    // ---- Token -----------------------------------------------------------------------------------

    private static Tok tok(Object o) { return state(o, Tok.class, "Token"); }
    public static Object tokVar(Object self) { return tok(self).var; }
    public static Object tokOld(Object self) { Tok t = tok(self); return t.hadOld ? t.old : missing; }
    public static Object tokRepr(Object self) {
        Tok t = tok(self);
        return "<Token" + (t.used ? " used" : "") + " var=" + PyRuntime.pyRepr(t.var) + " at 0x" + Integer.toHexString(System.identityHashCode(self)) + ">";
    }

    // ---- Context ---------------------------------------------------------------------------------

    public static Object ctxInit(Object self) {
        if (!(self instanceof PyInstance i) || i.slotValues.get(STATE) == null) setState(self, new Ctx());
        return null;
    }

    public static Object ctxGet(Object self, Object args) {
        ArrayList<Object> a = ((PyTuple) args).items;
        if (a.isEmpty() || a.size() > 2) throw new PyException("TypeError", "get expected 1 or 2 arguments, got " + a.size());
        Ctx c = ctx(self);
        return c.data.containsKey(a.get(0)) ? c.data.get(a.get(0)) : a.size() == 2 ? a.get(1) : null;
    }

    public static Object ctxGetItem(Object self, Object v) {
        var(v);
        Ctx c = ctx(self);
        if (!c.data.containsKey(v)) throw new PyException("KeyError", v);
        return c.data.get(v);
    }

    public static Object ctxContains(Object self, Object v) { var(v); return ctx(self).data.containsKey(v); }
    public static Object ctxLen(Object self) { return (long) ctx(self).data.size(); }
    public static Object ctxIter(Object self) { return new ArrayList<>(ctx(self).data.keySet()).iterator(); }
    public static Object ctxKeys(Object self) { return new ArrayList<>(ctx(self).data.keySet()); }
    public static Object ctxValues(Object self) { return new ArrayList<>(ctx(self).data.values()); }
    public static Object ctxItems(Object self) {
        ArrayList<Object> out = new ArrayList<>();
        for (Map.Entry<Object,Object> e : ctx(self).data.entrySet()) out.add(tuple(e.getKey(), e.getValue()));
        return out;
    }

    public static Object ctxCopy(Object self) {
        Ctx fresh = new Ctx();
        fresh.data.putAll(ctx(self).data);
        return newInstance(contextClass, fresh);
    }

    public static Object ctxRun(Object self, Object callable, Object args, Object kwargs) {
        Ctx c = ctx(self);
        if (c.entered) throw new PyException("RuntimeError", "cannot enter context: " + PyRuntime.pyRepr(self) + " is already entered");
        PyInstance previous = CURRENT.get();
        c.entered = true;
        CURRENT.set((PyInstance) self);
        try {
            @SuppressWarnings("unchecked") Map<Object,Object> kw = (Map<Object,Object>) kwargs;
            return PyRuntime.callFunction(callable, new ArrayList<Object>(((PyTuple) args).items), kw);
        } finally {
            c.entered = false;
            CURRENT.set(previous);
        }
    }
}
