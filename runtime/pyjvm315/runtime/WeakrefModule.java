// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code _weakref}: {@code ref} is backed by java.lang.ref.WeakReference. Callbacks cannot run at the instant the
 * collector clears a referent (the JVM has no reference counting), so they are delivered, on the Python thread, at
 * the next weakref poll point: creating or calling a reference, {@code getweakrefcount/getweakrefs},
 * {@code _remove_dead_weakref}, and every import that reaches the import system. Weak proxies are not implemented.
 */
public final class WeakrefModule {
    private WeakrefModule() {}

    private static PyClass refClass, proxyClass, callableProxyClass;

    /** The Java side of one {@code ref} instance. The Python-level ref object is held weakly so that an unreferenced ref never fires. */
    static final class RefState extends WeakReference<Object> {
        final WeakReference<PyInstance> owner;
        final Object callback;
        long hash;
        boolean hashed;
        RefState(Object referent, PyInstance owner, Object callback, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.owner = new WeakReference<>(owner);
            this.callback = callback;
        }
    }

    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final Set<RefState> LIVE = ConcurrentHashMap.newKeySet();

    static synchronized PyModule create() {
        PyModule m = new PyModule("_weakref", "pyjvm315.runtime.WeakrefModule");
        refClass = define("_weakref", "ReferenceType", null);
        refClass.qualname = "ReferenceType";
        staticMethodVar(refClass, WeakrefModule.class, "__new__", "refNew", "cls", "args");
        method(refClass, WeakrefModule.class, "__init__", "refInit", "self,ob,callback", defaults("callback", null));
        method(refClass, WeakrefModule.class, "__call__", "refCall", "self");
        method(refClass, WeakrefModule.class, "__hash__", "refHash", "self");
        method(refClass, WeakrefModule.class, "__eq__", "refEq", "self,other");
        method(refClass, WeakrefModule.class, "__ne__", "refNe", "self,other");
        method(refClass, WeakrefModule.class, "__repr__", "refRepr", "self");
        PyRuntime.classAddProperty(refClass, "__callback__", WeakrefModule.class.getName(), "refCallbackGetter", null);
        finish(refClass);
        m.attrs.put("ref", refClass);
        m.attrs.put("ReferenceType", refClass);
        proxyClass = define("_weakref", "ProxyType", null);
        finish(proxyClass);
        callableProxyClass = define("_weakref", "CallableProxyType", null);
        finish(callableProxyClass);
        m.attrs.put("ProxyType", proxyClass);
        m.attrs.put("CallableProxyType", callableProxyClass);
        fn(m, "proxy", (a, k) -> { throw new PyException("NotImplementedError", "_weakref.proxy: weak proxies are not implemented"); });
        fn(m, "getweakrefcount", (a, k) -> (long) refsTo(a.get(0)).size());
        fn(m, "getweakrefs", (a, k) -> { ArrayList<Object> out = new ArrayList<>(); for (RefState s : refsTo(a.get(0))) { PyInstance o = s.owner.get(); if (o != null) out.add(o); } return out; });
        fn(m, "_remove_dead_weakref", (a, k) -> {
            pollDead();
            @SuppressWarnings("unchecked") Map<Object,Object> d = (Map<Object,Object>) a.get(0);
            Object v = d.get(a.get(1));
            if (v instanceof PyInstance i && i.slotValues.get(STATE) instanceof RefState s && s.get() == null) d.remove(a.get(1));
            return null;
        });
        return m;
    }

    static PyClass refClass() { create0(); return refClass; }
    private static void create0() { if (refClass == null) PyRuntime.importNative("_weakref"); }

    /** Objects CPython allows a weak reference to; value types (int, str, tuple, ...) are refused as in CPython. */
    static boolean weakrefable(Object o) {
        return o instanceof PyInstance || o instanceof PyClass || o instanceof PyFunction || o instanceof PyModule || o instanceof PyGenerator
            || o instanceof PyCoroutine || o instanceof PyAsyncGenerator || o instanceof BoundCallable || o instanceof PyBuiltinType
            || o instanceof PyCode || o instanceof PyFrame || o instanceof PyMethodDescriptor || o instanceof PyManagedProperty
            || o instanceof PySet || o instanceof PyFrozenSet || o instanceof NativeFunction || o instanceof PyBuiltinFunction;
    }

    private static ArrayList<RefState> refsTo(Object referent) {
        pollDead();
        ArrayList<RefState> out = new ArrayList<>();
        for (RefState s : LIVE) if (s.get() == referent && referent != null) out.add(s);
        return out;
    }

    /** Run the callbacks of references whose referent has been collected since the last poll. */
    public static void pollDead() {
        RefState dead;
        while ((dead = (RefState) QUEUE.poll()) != null) {
            LIVE.remove(dead);
            PyInstance owner = dead.owner.get();
            if (owner == null || dead.callback == null) continue;
            try {
                ArrayList<Object> args = new ArrayList<>(); args.add(owner);
                PyRuntime.callFunction(dead.callback, args, new java.util.LinkedHashMap<Object,Object>());
            } catch (PyException e) {
                System.err.println("Exception ignored while calling weakref callback " + PyRuntime.pyRepr(dead.callback) + ": " + e);
            }
        }
    }

    public static Object refNew(Object cls, Object args) { return new PyInstance((PyClass) cls); }

    public static Object refInit(Object self, Object ob, Object callback) {
        pollDead();
        if (!weakrefable(ob)) throw new PyException("TypeError", "cannot create weak reference to '" + PyRuntime.typeName(ob) + "' object");
        RefState s = new RefState(ob, (PyInstance) self, callback, QUEUE);
        LIVE.add(s);
        setState(self, s);
        return null;
    }

    private static RefState st(Object self) { return state(self, RefState.class, "weakref.ReferenceType"); }

    public static Object refCall(Object self) { pollDead(); return st(self).get(); }

    public static Object refHash(Object self) {
        RefState s = st(self);
        if (s.hashed) return s.hash;
        Object o = s.get();
        if (o == null) throw new PyException("TypeError", "weak object has gone away");
        s.hash = ((Number) PyRuntime.hash(o)).longValue();
        s.hashed = true;
        return s.hash;
    }

    private static RefState otherState(Object other) {
        return other instanceof PyInstance i && i.slotValues.get(STATE) instanceof RefState s ? s : null;
    }

    public static Object refEq(Object self, Object other) {
        RefState a = st(self), b = otherState(other);
        if (b == null) return PyRuntime.notImplemented();
        Object x = a.get(), y = b.get();
        if (x == null || y == null) return self == other;
        return PyRuntime.eq(x, y);
    }

    public static Object refNe(Object self, Object other) {
        Object r = refEq(self, other);
        if (r == PyRuntime.notImplemented()) return r;
        return !PyRuntime.truth(r);
    }

    public static Object refRepr(Object self) {
        RefState s = st(self);
        Object o = s.get();
        String at = "0x" + Long.toHexString((Long) PyRuntime.id(self));
        if (o == null) return "<weakref at " + at + "; dead>";
        return "<weakref at " + at + "; to '" + PyRuntime.typeName(o) + "' at 0x" + Long.toHexString((Long) PyRuntime.id(o)) + ">";
    }

    public static Object refCallbackGetter(Object self) { return st(self).get() == null ? null : st(self).callback; }
}
