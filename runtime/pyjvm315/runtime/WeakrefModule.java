// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code _weakref}: {@code ref}, {@code proxy} and the counting helpers, on top of java.lang.ref.WeakReference.
 *
 * <p>Every live reference is recorded in a registry keyed (weakly, by identity) on its referent, so that
 * {@code getweakrefs}, {@code getweakrefcount} and CPython's rule that a callback-less exact {@code ref} is shared
 * are cheap. The JVM has no reference counting: a reference is cleared when the collector finds its referent
 * unreachable, and its callback then runs on the thread that created the reference at that thread's next poll point
 * (see {@link WeakrefDelivery}), not at the instant of the last {@code del}. The Python-level reference object is
 * itself held weakly by the registry, so an unreferenced reference never fires its callback.
 */
public final class WeakrefModule {
    private WeakrefModule() {}

    static PyClass refClass, proxyClass, callableProxyClass;

    static final int REF = 0, PROXY = 1, CALLABLE_PROXY = 2;

    /**
     * The Java side of one ref or proxy instance: a weak reference to the referent. It is reachable only from its
     * Python-level object (never from a global), so a callback that references the referent does not keep it alive
     * and a ref object that is itself collected never fires.
     */
    static final class RefState extends WeakReference<Object> {
        final PyInstance owner;
        final Object callback;
        final long seq = WeakrefDelivery.SEQ.incrementAndGet();
        final WeakrefDelivery.ThreadInfo thread = WeakrefDelivery.currentThread();
        final int kind;
        final boolean basic;
        long hash;
        boolean hashed;
        RefState(Object referent, PyInstance owner, Object callback, int kind, boolean basic) {
            super(referent, callback == null ? null : WeakrefDelivery.QUEUE);
            this.owner = owner;
            this.callback = callback;
            this.kind = kind;
            this.basic = basic;
        }
    }

    /** Identity-keyed weak map key; queued so that the registry forgets a collected referent. */
    static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object o, boolean queued) { super(o, queued ? WeakrefDelivery.QUEUE : null); hash = System.identityHashCode(o); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object x) {
            if (this == x) return true;
            if (!(x instanceof Key k)) return false;
            Object a = get();
            return a != null && a == k.get();
        }
    }

    /** A registry slot: weak, so the registry never keeps a ref (and through it a callback) alive. */
    private static final class Slot extends WeakReference<RefState> {
        Slot(RefState s) { super(s); }
    }

    /** All references to one referent, in getweakrefs() order: basic ref, basic proxy, then newest first. */
    private static final class Entry {
        final ArrayList<Slot> refs = new ArrayList<>();
        void prune() { refs.removeIf(s -> s.get() == null); }
    }

    private static final HashMap<Key, Entry> REGISTRY = new HashMap<>();

    static synchronized PyModule create() {
        PyModule m = new PyModule("_weakref", "pyjvm315.runtime.WeakrefModule");
        WeakrefDelivery.ensureWatcher();
        refClass = define("weakref", "ReferenceType", null);
        refClass.qualname = "ReferenceType";
        PyRuntime.classAddMethodEx(refClass, "__new__", WeakrefModule.class, "refNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(refClass, "__new__", refClass.methods.get("__new__").function);
        methodVarKw(refClass, WeakrefModule.class, "__init__", "refInit", "self", "args", "kwargs", new PyDict());
        method(refClass, WeakrefModule.class, "__call__", "refCall", "self");
        method(refClass, WeakrefModule.class, "__hash__", "refHash", "self");
        method(refClass, WeakrefModule.class, "__eq__", "refEq", "self,other");
        method(refClass, WeakrefModule.class, "__ne__", "refNe", "self,other");
        method(refClass, WeakrefModule.class, "__repr__", "refRepr", "self");
        PyRuntime.classAddMethodEx(refClass, "__class_getitem__", WeakrefModule.class, "refClassGetitem", "class", "", "cls,item", "", null, null, new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(refClass, "__class_getitem__", PyRuntime.methodDescriptor(refClass.methods.get("__class_getitem__").function, "class"));
        PyRuntime.classAddProperty(refClass, "__callback__", WeakrefModule.class.getName(), "refCallbackGetter", null);
        finish(refClass);
        refClass.weakrefOk = true;
        m.attrs.put("ref", refClass);
        m.attrs.put("ReferenceType", refClass);
        proxyClass = proxyType("ProxyType", false);
        callableProxyClass = proxyType("CallableProxyType", true);
        m.attrs.put("ProxyType", proxyClass);
        m.attrs.put("CallableProxyType", callableProxyClass);
        fn(m, "proxy", (a, k) -> proxy(a, k));
        fn(m, "getweakrefcount", (a, k) -> getweakrefcount(argument(a, "getweakrefcount")));
        fn(m, "getweakrefs", (a, k) -> getweakrefs(argument(a, "getweakrefs")));
        fn(m, "_remove_dead_weakref", (a, k) -> {
            WeakrefDelivery.poll();
            @SuppressWarnings("unchecked") Map<Object,Object> d = (Map<Object,Object>) a.get(0);
            Object v = d.get(a.get(1));
            if (v instanceof PyInstance i && i.slotValues.get(STATE) instanceof RefState s && s.get() == null) d.remove(a.get(1));
            return null;
        });
        WeakrefDelivery.installHook((PyModule) PyRuntime.importNative("sys"));
        return m;
    }

    private static PyClass proxyType(String name, boolean callable) {
        PyClass c = define("weakref", name, null);
        c.qualname = name;
        WeakrefProxy.install(c, callable);
        finish(c);
        c.weakProxy = true;
        c.weakrefOk = false;
        return c;
    }

    private static Object argument(List<Object> a, String fn) {
        if (a.size() != 1) throw new PyException("TypeError", fn + "() takes exactly one argument (" + a.size() + " given)");
        return a.get(0);
    }

    static PyClass refClass() { create0(); return refClass; }
    private static void create0() { if (refClass == null) PyRuntime.importNative("_weakref"); }

    // ---- which objects can be weakly referenced ----------------------------------------------------

    /** Called while a class is built: decides whether its instances accept weak references and gives them __weakref__. */
    static void configureClass(PyClass cls, boolean own) {
        boolean inherited = false;
        for (PyClass b : cls.bases) if (b.weakrefOk) { inherited = true; break; }
        String kind = cls.nativeKind;
        boolean varSized = "int".equals(kind) || "tuple".equals(kind) || "bytes".equals(kind) || "bool".equals(kind);
        boolean ok = inherited || (own && !varSized) || "set".equals(kind) || "frozenset".equals(kind);
        cls.weakrefOk = ok;
        if (own && !inherited && !varSized)
            cls.properties.put("__weakref__", new PyProperty(WeakrefModule.class.getName(), "weakrefAttr", null));
    }

    /** {@code obj.__weakref__}: the first (basic) reference to obj, or None. */
    public static Object weakrefAttr(Object self) {
        List<Object> all = getweakrefs(self);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Objects CPython allows a weak reference to; value types (int, str, tuple, ...) are refused as in CPython. */
    static boolean weakrefable(Object o) {
        if (o instanceof PyInstance i) return i.cls.weakrefOk && !i.cls.name.equals("object");
        return o instanceof PyClass || o instanceof PyFunction || o instanceof PyModule || o instanceof PyGenerator
            || o instanceof PyCoroutine || o instanceof PyAsyncGenerator || o instanceof BoundCallable || o instanceof BoundMethod || o instanceof PyBuiltinType
            || o instanceof PyCode || o instanceof PyFrame || o instanceof PyMethodDescriptor || o instanceof PyManagedProperty
            || o instanceof PySet || o instanceof PyFrozenSet || o instanceof NativeFunction || o instanceof PyBuiltinFunction;
    }

    private static PyException notWeakrefable(Object ob) {
        return new PyException("TypeError", "cannot create weak reference to '" + PyRuntime.typeName(ob) + "' object");
    }

    // ---- registry ----------------------------------------------------------------------------------

    /** Create the state for a new ref/proxy owned by {@code owner} and record it. */
    private static RefState register(Object referent, PyInstance owner, Object callback, int kind) {
        boolean basic = callback == null && owner.cls == (kind == REF ? refClass : kind == PROXY ? proxyClass : callableProxyClass);
        RefState s = new RefState(referent, owner, callback, kind, basic);
        synchronized (REGISTRY) {
            Key probe = new Key(referent, false);
            Entry e = REGISTRY.get(probe);
            if (e == null) { e = new Entry(); REGISTRY.put(new Key(referent, true), e); }
            e.prune();
            int at = 0;
            while (at < e.refs.size()) {
                RefState x = e.refs.get(at).get();
                if (x == null || !x.basic || (basic && x.kind >= kind)) break;
                at++;
            }
            e.refs.add(at, new Slot(s));
        }
        return s;
    }

    private static RefState findBasic(Object referent, int kind) {
        synchronized (REGISTRY) {
            Entry e = REGISTRY.get(new Key(referent, false));
            if (e == null) return null;
            for (Slot sl : e.refs) {
                RefState s = sl.get();
                if (s != null && s.basic && s.kind == kind && s.get() == referent) return s;
            }
        }
        return null;
    }

    /** Watcher thread: the referent of a ref with a callback was collected. */
    static void referentDied(RefState s) {
        final PyInstance ref = s.owner;
        final Object cb = s.callback;
        if (cb == null) return;
        WeakrefDelivery.post(new WeakrefDelivery.Task(s.thread, s.seq, false) {
            @Override void run() { PyRuntime.callFunction(cb, new ArrayList<Object>(List.of(ref)), new LinkedHashMap<Object, Object>()); }
            @Override String what() { return null; }
            @Override Object subject() { return cb; }
        });
    }

    /** Watcher thread: a registered referent was collected; drop its registry entry. */
    static void keyDied(Key k) {
        synchronized (REGISTRY) { REGISTRY.remove(k); }
    }

    private static List<Object> liveOwners(Object referent) {
        ArrayList<Object> out = new ArrayList<>();
        if (referent == null) return out;
        synchronized (REGISTRY) {
            Entry e = REGISTRY.get(new Key(referent, false));
            if (e != null) for (Slot sl : e.refs) {
                RefState s = sl.get();
                if (s != null && s.get() == referent) out.add(s.owner);
            }
        }
        return out;
    }

    static Object getweakrefcount(Object referent) {
        WeakrefDelivery.poll();
        return (long) liveOwners(referent).size();
    }

    static List<Object> getweakrefs(Object referent) {
        WeakrefDelivery.poll();
        return liveOwners(referent);
    }

    // ---- ref ---------------------------------------------------------------------------------------

    private static Object[] refArgs(Object args, String what) {
        List<Object> items = ((PyTuple) args).items;
        if (items.isEmpty() || items.size() > 2)
            throw new PyException("TypeError", what + " expected " + (items.isEmpty() ? "at least 1 argument, got 0" : "at most 2 arguments, got " + items.size()));
        return new Object[]{items.get(0), items.size() > 1 ? items.get(1) : null};
    }

    /** {@code ref.__new__(cls, ob, callback=None)}: also where the shared callback-less ref is found. */
    public static Object refNew(Object cls, Object args, Object kwargs) {
        WeakrefDelivery.poll();
        Object[] a = refArgs(args, "__new__");
        Object ob = a[0], callback = a[1];
        if (!weakrefable(ob)) throw notWeakrefable(ob);
        PyClass c = (PyClass) cls;
        if (callback == null && c == refClass) {
            RefState shared = findBasic(ob, REF);
            if (shared != null) { return shared.owner; }
        }
        PyInstance self = new PyInstance(c);
        setState(self, register(ob, self, callback, REF));
        return self;
    }

    public static Object refInit(Object self, Object args, Object kwargs) {
        refArgs(args, "__init__");
        return null;
    }

    private static RefState st(Object self) { return state(self, RefState.class, "weakref.ReferenceType"); }

    public static Object refCall(Object self) { WeakrefDelivery.poll(); return st(self).get(); }

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
        return other instanceof PyInstance i && i.cls.mro.contains(refClass) && i.slotValues.get(STATE) instanceof RefState s ? s : null;
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

    public static Object refClassGetitem(Object cls, Object item) {
        PyTuple args = new PyTuple();
        if (item instanceof PyTuple t) args.items.addAll(t.items); else args.items.add(item);
        return new TypeParamsGeneric(cls, args, true);
    }

    // ---- proxy -------------------------------------------------------------------------------------

    private static Object proxy(List<Object> a, Map<Object, Object> k) {
        WeakrefDelivery.poll();
        if (!k.isEmpty()) throw new PyException("TypeError", "proxy() takes no keyword arguments");
        if (a.isEmpty() || a.size() > 2)
            throw new PyException("TypeError", "proxy expected " + (a.isEmpty() ? "at least 1 argument, got 0" : "at most 2 arguments, got " + a.size()));
        Object ob = a.get(0), callback = a.size() > 1 ? a.get(1) : null;
        if (!weakrefable(ob)) throw notWeakrefable(ob);
        int kind = PyRuntime.truth(PyRuntime.callBuiltin("callable", new ArrayList<Object>(List.of(ob)), new LinkedHashMap<Object, Object>())) ? CALLABLE_PROXY : PROXY;
        if (callback == null) {
            RefState shared = findBasic(ob, kind);
            if (shared != null) { return shared.owner; }
        }
        PyInstance self = new PyInstance(kind == PROXY ? proxyClass : callableProxyClass);
        setState(self, register(ob, self, callback, kind));
        return self;
    }
}
