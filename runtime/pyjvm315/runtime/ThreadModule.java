// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code _thread}: lock objects backed by java.util.concurrent. {@code lock} (allocate_lock) is a binary semaphore
 * (any thread may release it, as in Python); {@code RLock} is a reentrant lock with owner and recursion count.
 * Threads are real JVM threads (no GIL: see {@link SyncSupport} for how shared structures stay consistent).
 * {@code _ThreadHandle}, {@code start_joinable_thread}, {@code _shutdown} etc. are what CPython's own
 * Lib/threading.py needs to run unmodified.
 */
public final class ThreadModule {
    private ThreadModule() {}

    static final double TIMEOUT_MAX = 9223372036.0;
    private static PyClass lockClass, rlockClass, handleClass, hookArgsClass;
    private static volatile PyClass localClass;
    static PyClass localClass() { return localClass; }

    /**
     * The {@code __dict__} of a {@code _thread._local} instance: every thread sees its own dict. A thread that
     * touches the object for the first time gets an empty dict and, if the class defines {@code __init__}, a call
     * of it with the arguments the object was created with (CPython's documented _local behaviour).
     */
    static final class LocalFields extends LinkedHashMap<String,Object> {
        private final PyInstance owner;
        private final Thread creator = Thread.currentThread();
        private final ThreadLocal<LinkedHashMap<String,Object>> maps = new ThreadLocal<>();
        private Object[] initArgs = new Object[0];
        private java.util.Map<Object,Object> initKwargs = new LinkedHashMap<>();
        LocalFields(PyInstance owner) { this.owner = owner; }
        void recordInit(Object[] args, java.util.Map<Object,Object> kwargs) { initArgs = args.clone(); initKwargs = new LinkedHashMap<>(kwargs); }
        private LinkedHashMap<String,Object> cur() {
            LinkedHashMap<String,Object> m = maps.get();
            if (m != null) return m;
            m = new LinkedHashMap<>();
            maps.set(m);
            if (Thread.currentThread() != creator) {
                PyMethod init = owner.cls.lookupMethod("__init__");
                if (init != null) PyRuntime.invokeKw(owner, init, new ArrayList<Object>(java.util.Arrays.asList(initArgs)), initKwargs);
            }
            return m;
        }
        @Override public Object get(Object k) { return cur().get(k); }
        @Override public Object getOrDefault(Object k, Object d) { return cur().getOrDefault(k, d); }
        @Override public boolean containsKey(Object k) { return cur().containsKey(k); }
        @Override public Object put(String k, Object v) { return cur().put(k, v); }
        @Override public Object putIfAbsent(String k, Object v) { return cur().putIfAbsent(k, v); }
        @Override public Object remove(Object k) { return cur().remove(k); }
        @Override public void putAll(java.util.Map<? extends String, ?> o) { cur().putAll(o); }
        @Override public void clear() { cur().clear(); }
        @Override public int size() { return cur().size(); }
        @Override public boolean isEmpty() { return cur().isEmpty(); }
        @Override public java.util.Set<String> keySet() { return cur().keySet(); }
        @Override public java.util.Collection<Object> values() { return cur().values(); }
        @Override public java.util.Set<java.util.Map.Entry<String,Object>> entrySet() { return cur().entrySet(); }
    }
    /** The thread that imported {@code _thread} (the interpreter's main thread). */
    static final Thread MAIN = Thread.currentThread();
    private static final java.util.concurrent.atomic.AtomicInteger RUNNING = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong SERIAL = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long stackBytes = 0;
    /** Non-daemon handles that interpreter shutdown must wait for. */
    private static final java.util.Set<HandleState> JOINABLE = ConcurrentHashMap.newKeySet();

    /** State of a {@code _ThreadHandle}: the OS thread (if started here) and a done latch. */
    static final class HandleState {
        volatile Thread thread; volatile long ident; volatile boolean started, external;
        final CountDownLatch done = new CountDownLatch(1);
    }

    /** Lock state: a binary semaphore. */
    static final class LockState { final Semaphore sem = new Semaphore(1); }

    /** RLock state: java.util.concurrent reentrant lock. */
    static final class RLockState { final ReentrantLock lock = new ReentrantLock(); }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_thread", "pyjvm315.runtime.ThreadModule");
        lockClass = define("_thread", "lock", null);
        method(lockClass, ThreadModule.class, "__init__", "lockInit", "self");
        String[] names = {"acquire", "acquire_lock"};
        for (String n : names) method(lockClass, ThreadModule.class, n, "lockAcquire", "self,blocking,timeout", defaults("blocking", true, "timeout", -1L));
        method(lockClass, ThreadModule.class, "__enter__", "lockEnter", "self");
        methodVar(lockClass, ThreadModule.class, "__exit__", "lockExit", "self", "args");
        method(lockClass, ThreadModule.class, "release", "lockRelease", "self");
        method(lockClass, ThreadModule.class, "release_lock", "lockRelease", "self");
        method(lockClass, ThreadModule.class, "locked", "lockLocked", "self");
        method(lockClass, ThreadModule.class, "locked_lock", "lockLocked", "self");
        method(lockClass, ThreadModule.class, "_at_fork_reinit", "lockReinit", "self");
        method(lockClass, ThreadModule.class, "__repr__", "lockRepr", "self");
        finish(lockClass);

        rlockClass = define("_thread", "RLock", null);
        method(rlockClass, ThreadModule.class, "__init__", "rlockInit", "self");
        method(rlockClass, ThreadModule.class, "acquire", "rlockAcquire", "self,blocking,timeout", defaults("blocking", true, "timeout", -1L));
        method(rlockClass, ThreadModule.class, "__enter__", "rlockEnter", "self");
        methodVar(rlockClass, ThreadModule.class, "__exit__", "rlockExit", "self", "args");
        method(rlockClass, ThreadModule.class, "release", "rlockRelease", "self");
        method(rlockClass, ThreadModule.class, "locked", "rlockLocked", "self");
        method(rlockClass, ThreadModule.class, "_is_owned", "rlockIsOwned", "self");
        method(rlockClass, ThreadModule.class, "_release_save", "rlockReleaseSave", "self");
        method(rlockClass, ThreadModule.class, "_acquire_restore", "rlockAcquireRestore", "self,state");
        method(rlockClass, ThreadModule.class, "_recursion_count", "rlockRecursionCount", "self");
        method(rlockClass, ThreadModule.class, "_at_fork_reinit", "rlockReinit", "self");
        method(rlockClass, ThreadModule.class, "__repr__", "rlockRepr", "self");
        finish(rlockClass);

        m.attrs.put("lock", lockClass);
        m.attrs.put("LockType", lockClass);
        m.attrs.put("RLock", rlockClass);
        m.attrs.put("error", PyRuntime.builtinType("RuntimeError"));
        m.attrs.put("TIMEOUT_MAX", TIMEOUT_MAX);
        fn(m, "allocate_lock", (a, k) -> { requireNone("allocate_lock", a, k); return newInstance(lockClass, new LockState()); });
        fn(m, "allocate", (a, k) -> { requireNone("allocate", a, k); return newInstance(lockClass, new LockState()); });
        fn(m, "get_ident", (a, k) -> Thread.currentThread().threadId());
        fn(m, "get_native_id", (a, k) -> Thread.currentThread().threadId());
        fn(m, "_count", (a, k) -> (long) RUNNING.get());
        fn(m, "stack_size", (a, k) -> stackSize(a));
        fn(m, "daemon_threads_allowed", (a, k) -> true);
        fn(m, "_is_main_interpreter", (a, k) -> true);
        fn(m, "_get_main_thread_ident", (a, k) -> MAIN.threadId());
        fn(m, "set_name", (a, k) -> { if (!a.isEmpty() && a.get(0) instanceof String s) Thread.currentThread().setName(s); return null; });
        fn(m, "_get_name", (a, k) -> Thread.currentThread().getName());
        fn(m, "interrupt_main", (a, k) -> {
            if (Thread.currentThread() == MAIN) throw new PyException("KeyboardInterrupt", null);
            MAIN.interrupt(); return null;
        });
        fn(m, "start_new_thread", (a, k) -> startNew(a));
        fn(m, "start_joinable_thread", (a, k) -> startJoinable(a, k));
        fn(m, "_make_thread_handle", (a, k) -> {
            HandleState h = new HandleState();
            h.ident = ((Number) a.get(0)).longValue(); h.started = true; h.external = true;
            return newInstance(handleClass, h);
        });
        fn(m, "_shutdown", (a, k) -> { shutdownJoin(); return null; });
        handleClass = define("_thread", "_ThreadHandle", null);
        method(handleClass, ThreadModule.class, "__init__", "handleInit", "self");
        method(handleClass, ThreadModule.class, "join", "handleJoin", "self,timeout", defaults("timeout", null));
        method(handleClass, ThreadModule.class, "is_done", "handleIsDone", "self");
        method(handleClass, ThreadModule.class, "_set_done", "handleSetDone", "self");
        method(handleClass, ThreadModule.class, "__repr__", "handleRepr", "self");
        PyRuntime.classAddProperty(handleClass, "ident", ThreadModule.class.getName(), "handleIdent", null);
        finish(handleClass);
        m.attrs.put("_ThreadHandle", handleClass);
        hookArgsClass = define("_thread", "ExceptHookArgs", null);
        method(hookArgsClass, ThreadModule.class, "__new__", "hookArgsNew", "cls,args");
        method(hookArgsClass, ThreadModule.class, "__len__", "hookArgsLen", "self");
        method(hookArgsClass, ThreadModule.class, "__getitem__", "hookArgsGet", "self,index");
        method(hookArgsClass, ThreadModule.class, "__iter__", "hookArgsIter", "self");
        method(hookArgsClass, ThreadModule.class, "__repr__", "hookArgsRepr", "self");
        String[] fields = {"exc_type", "exc_value", "exc_traceback", "thread"};
        for (String f : fields) PyRuntime.classAddProperty(hookArgsClass, f, ThreadModule.class.getName(), "hookArgs_" + f, null);
        finish(hookArgsClass);
        m.attrs.put("_ExceptHookArgs", hookArgsClass);
        localClass = define("_thread", "_local", null);
        finish(localClass);
        m.attrs.put("_local", localClass);
        fn(m, "_excepthook", (a, k) -> excepthook(a));
        return m;
    }

    // ---- threads ----
    private static Object stackSize(List<Object> a) {
        long old = stackBytes;
        if (a.size() > 1) throw new PyException("TypeError", "stack_size expected at most 1 argument, got " + a.size());
        if (!a.isEmpty()) {
            if (!(a.get(0) instanceof Number n) || a.get(0) instanceof Double) throw new PyException("TypeError", "'" + PyRuntime.typeName(a.get(0)) + "' object cannot be interpreted as an integer");
            long v = n.longValue();
            if (v != 0 && v < 32768) throw new PyException("ValueError", "size not valid: " + v + " bytes");
            stackBytes = v;
        }
        return old;
    }

    private static Thread newThread(Runnable body, boolean daemon, String name) {
        long size = stackBytes != 0 ? stackBytes : 64L << 20;
        Thread t = new Thread(null, body, name != null ? name : "Thread-" + SERIAL.incrementAndGet(), size);
        t.setDaemon(daemon);
        return t;
    }

    private static Object startNew(List<Object> a) {
        if (a.size() < 2 || a.size() > 3) throw new PyException("TypeError", "start_new_thread expected at least 2 arguments, got " + a.size());
        Object fn = a.get(0), args = a.get(1), kwargs = a.size() > 2 ? a.get(2) : null;
        if (!PyRuntime.truth(PyRuntime.callable_(fn))) throw new PyException("TypeError", "first arg must be callable");
        if (!(args instanceof PyTuple)) throw new PyException("TypeError", "2nd arg must be a tuple");
        if (kwargs != null && !(kwargs instanceof java.util.Map)) throw new PyException("TypeError", "optional 3rd arg must be a dictionary");
        ArrayList<Object> argv = new ArrayList<>(((PyTuple) args).items);
        LinkedHashMap<Object,Object> kw = new LinkedHashMap<>();
        if (kwargs != null) kw.putAll((java.util.Map<?,?>) kwargs);
        SyncSupport.enterMultiThreaded();
        RUNNING.incrementAndGet();
        Thread t = newThread(() -> {
            try { Tracing.touch(); runBody(fn, argv, kw); }
            finally { RUNNING.decrementAndGet(); SyncSupport.threadExit(); }
        }, true, null);
        t.start();
        return t.threadId();
    }

    private static Object startJoinable(List<Object> a, java.util.Map<Object,Object> k) {
        Object fn = !a.isEmpty() ? a.get(0) : k.get("function");
        Object handle = k.get("handle");
        boolean daemon = !k.containsKey("daemon") || PyRuntime.truth(k.get("daemon"));
        if (!PyRuntime.truth(PyRuntime.callable_(fn))) throw new PyException("TypeError", "thread function must be callable");
        HandleState h = handle == null ? new HandleState() : state(handle, HandleState.class, "_ThreadHandle");
        if (h.started) throw new PyException("RuntimeError", "thread already started");
        SyncSupport.enterMultiThreaded();
        RUNNING.incrementAndGet();
        h.started = true;
        Thread t = newThread(() -> {
            try { Tracing.touch(); runBody(fn, new ArrayList<>(), new LinkedHashMap<>()); }
            finally { RUNNING.decrementAndGet(); SyncSupport.threadExit(); h.done.countDown(); JOINABLE.remove(h); }
        }, daemon, null);
        h.thread = t; h.ident = t.threadId();
        if (!daemon) JOINABLE.add(h);
        t.start();
        return handle != null ? handle : newInstance(handleClass, h);
    }

    /** Thread body: an exception that escapes is reported as "Exception ignored in thread started by ...". */
    private static void runBody(Object fn, ArrayList<Object> args, LinkedHashMap<Object,Object> kwargs) {
        try { PyRuntime.callFunction(fn, args, kwargs); }
        catch (Throwable t) { reportUnraisable(t, fn); }
    }

    private static void reportUnraisable(Throwable t, Object fn) {
        try {
            Throwable err = t;
            if (!(t instanceof PyException)) {
                if (t instanceof StackOverflowError) err = new PyException("RecursionError", "maximum recursion depth exceeded");
                else if (t instanceof ArithmeticException || t instanceof IndexOutOfBoundsException || t instanceof java.util.NoSuchElementException
                         || t instanceof ClassCastException || t instanceof IllegalArgumentException)
                    err = new PyException(PyRuntime.pythonExceptionType(t), PyRuntime.exceptionValue(t));
                else if (t instanceof Error e) throw e;
            }
            Object value = PyRuntime.exceptionInstance(err);
            if (ExceptionGroupSupport.excTypeName(value).equals("SystemExit")) return;
            Object stream = BuiltinsExtra.currentStream("stderr");
            PyRuntime.callMethod(stream, "write", new Object[]{"Exception ignored in thread started by: " + PyRuntime.pyRepr(fn) + "\n"});
            ExceptionGroupSupport.defaultExcepthook(PyRuntime.typeOf(value), value, null);
        } catch (Throwable ignored) { }
    }

    /** Interpreter shutdown: wait for every non-daemon thread started with start_joinable_thread (including late starters). */
    static void shutdownJoin() {
        Thread me = Thread.currentThread();
        while (true) {
            HandleState next = null;
            for (HandleState h : JOINABLE) if (h.thread != me) { next = h; break; }
            if (next == null) return;
            try { next.done.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            JOINABLE.remove(next);
        }
    }

    /** Called when the main program ends: run threading._shutdown (if threading was imported), then join non-daemon threads. */
    public static void mainExit() {
        if (Thread.currentThread() != MAIN) return;
        try {
            Object threading = ImportSupport.MODULES.get("threading");
            if (threading != null) PyRuntime.callFunction(PyRuntime.getattr(threading, "_shutdown"), new ArrayList<>(), new LinkedHashMap<>());
        } catch (Throwable t) {
            reportUnraisable(t, null);
        }
        shutdownJoin();
    }

    // ---- threading.excepthook support ----
    private static PyTuple hookTuple(Object self) { return state(self, PyTuple.class, "ExceptHookArgs"); }
    public static Object hookArgsNew(Object cls, Object args) {
        PyTuple t = new PyTuple();
        for (Object o : PyRuntime.iterable(args)) t.items.add(o);
        if (t.items.size() != 4) throw new PyException("TypeError", "_thread.ExceptHookArgs takes a 4-sequence (" + t.items.size() + "-sequence given)");
        return newInstance((PyClass) cls, t);
    }
    public static Object hookArgsLen(Object self) { return 4L; }
    public static Object hookArgsGet(Object self, Object index) { return PyRuntime.getitem(hookTuple(self), index); }
    public static Object hookArgsIter(Object self) { return PyRuntime.iter(hookTuple(self)); }
    public static Object hookArgsRepr(Object self) {
        PyTuple t = hookTuple(self);
        return "_thread.ExceptHookArgs(exc_type=" + PyRuntime.pyRepr(t.items.get(0)) + ", exc_value=" + PyRuntime.pyRepr(t.items.get(1))
            + ", exc_traceback=" + PyRuntime.pyRepr(t.items.get(2)) + ", thread=" + PyRuntime.pyRepr(t.items.get(3)) + ")";
    }
    public static Object hookArgs_exc_type(Object self) { return hookTuple(self).items.get(0); }
    public static Object hookArgs_exc_value(Object self) { return hookTuple(self).items.get(1); }
    public static Object hookArgs_exc_traceback(Object self) { return hookTuple(self).items.get(2); }
    public static Object hookArgs_thread(Object self) { return hookTuple(self).items.get(3); }

    /** _thread._excepthook(args): "Exception in thread NAME:" and the traceback on sys.stderr; SystemExit is silent. */
    private static Object excepthook(List<Object> a) {
        if (a.size() != 1) throw new PyException("TypeError", "_thread._excepthook() takes exactly one argument");
        if (!(a.get(0) instanceof PyInstance inst) || !(inst.slotValues.get(STATE) instanceof PyTuple t))
            throw new PyException("TypeError", "_thread._excepthook argument type must be ExceptHookArgs");
        Object type = t.items.get(0), value = t.items.get(1), tb = t.items.get(2), thread = t.items.get(3);
        if (type instanceof PyBuiltinType bt && bt.name.equals("SystemExit")) return null;
        if (value != null && ExceptionGroupSupport.excTypeName(value).equals("SystemExit")) return null;
        Object err = BuiltinsExtra.currentStream("stderr");
        if (err == null && thread != null) err = PyRuntime.getattr(thread, "_stderr");
        if (err == null) return null;
        String name;
        if (thread != null) name = PyRuntime.pyStr(PyRuntime.getattr(thread, "name"));
        else name = "0x" + Long.toHexString(Thread.currentThread().threadId());
        PyRuntime.callMethod(err, "write", new Object[]{"Exception in thread " + name + ":\n"});
        if (value != null) ExceptionGroupSupport.defaultExcepthook(type, value, tb);
        else PyRuntime.callMethod(err, "write", new Object[]{PyRuntime.pyStr(type) + "\n"});
        return null;
    }

    public static Object handleInit(Object self) { setState(self, new HandleState()); return null; }
    private static HandleState hs(Object self) { return state(self, HandleState.class, "_ThreadHandle"); }
    public static Object handleIdent(Object self) { HandleState h = hs(self); return h.started ? (Object) h.ident : (Object) 0L; }
    public static Object handleIsDone(Object self) { return hs(self).done.getCount() == 0; }
    public static Object handleSetDone(Object self) { hs(self).done.countDown(); return null; }
    public static Object handleJoin(Object self, Object timeout) {
        HandleState h = hs(self);
        if (!h.started) throw new PyException("RuntimeError", "thread not started");
        if (h.ident == Thread.currentThread().threadId() && h.done.getCount() != 0) throw new PyException("RuntimeError", "Cannot join current thread");
        try {
            if (timeout == null) h.done.await();
            else {
                double t = ((Number) timeout).doubleValue();
                if (t > TIMEOUT_MAX) throw new PyException("OverflowError", "timeout value is too large");
                h.done.await((long) (Math.max(t, 0) * 1e9), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PyException("KeyboardInterrupt", null);
        }
        return null;
    }
    public static Object handleRepr(Object self) {
        HandleState h = hs(self);
        return "<_thread._ThreadHandle object: ident=" + (h.started ? h.ident : 0) + ">";
    }

    private static void requireNone(String name, List<Object> a, java.util.Map<Object,Object> k) {
        if (!a.isEmpty() || !k.isEmpty()) throw new PyException("TypeError", name + "() takes no arguments");
    }

    /** blocking/timeout -> -1 (wait forever), 0 (try once) or a positive wait in nanoseconds. */
    private static long waitNanos(Object blocking, Object timeout) {
        boolean block = PyRuntime.truth(blocking);
        double t = timeout instanceof Number n ? n.doubleValue() : -1.0;
        if (timeout != null && !(timeout instanceof Number))
            throw new PyException("TypeError", "'" + PyRuntime.typeName(timeout) + "' object cannot be interpreted as an integer or float");
        if (!block && t != -1.0) throw new PyException("ValueError", "can't specify a timeout for a non-blocking call");
        if (t < 0 && t != -1.0) throw new PyException("ValueError", "timeout value must be a non-negative number");
        if (t > TIMEOUT_MAX) throw new PyException("OverflowError", "timeout value is too large");
        if (!block) return 0;
        if (t == -1.0) return -1;
        return (long) (t * 1e9);
    }

    // ---- lock ----
    public static Object lockAcquire(Object self, Object blocking, Object timeout) {
        Semaphore s = state(self, LockState.class, "lock").sem;
        long w = waitNanos(blocking, timeout);
        try {
            if (w < 0) { s.acquire(); return true; }
            if (w == 0) return s.tryAcquire();
            return s.tryAcquire(w, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PyException("KeyboardInterrupt", null);
        }
    }
    public static Object lockInit(Object self) { setState(self, new LockState()); return null; }
    public static Object lockEnter(Object self) { return lockAcquire(self, true, -1L); }
    public static Object lockExit(Object self, Object args) { return lockRelease(self); }
    public static Object lockRelease(Object self) {
        Semaphore s = state(self, LockState.class, "lock").sem;
        synchronized (s) {
            if (s.availablePermits() > 0) throw new PyException("RuntimeError", "release unlocked lock");
            s.release();
        }
        return null;
    }
    public static Object lockLocked(Object self) { return state(self, LockState.class, "lock").sem.availablePermits() == 0; }
    public static Object lockReinit(Object self) { setState(self, new LockState()); return null; }
    public static Object lockRepr(Object self) {
        return "<" + (Boolean.TRUE.equals(lockLocked(self)) ? "locked" : "unlocked") + " _thread.lock object at 0x" + Long.toHexString((Long) PyRuntime.id(self)) + ">";
    }

    // ---- RLock ----
    public static Object rlockInit(Object self) { setState(self, new RLockState()); return null; }
    private static ReentrantLock rl(Object self) { return state(self, RLockState.class, "RLock").lock; }
    public static Object rlockAcquire(Object self, Object blocking, Object timeout) {
        ReentrantLock l = rl(self);
        long w = waitNanos(blocking, timeout);
        try {
            if (w < 0) { l.lock(); return true; }
            if (w == 0) return l.tryLock();
            return l.tryLock(w, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PyException("KeyboardInterrupt", null);
        }
    }
    public static Object rlockEnter(Object self) { return rlockAcquire(self, true, -1L); }
    public static Object rlockExit(Object self, Object args) { return rlockRelease(self); }
    public static Object rlockRelease(Object self) {
        ReentrantLock l = rl(self);
        if (!l.isHeldByCurrentThread()) throw new PyException("RuntimeError", "cannot release un-acquired lock");
        l.unlock();
        return null;
    }
    public static Object rlockLocked(Object self) { return rl(self).isLocked(); }
    public static Object rlockIsOwned(Object self) { return rl(self).isHeldByCurrentThread(); }
    public static Object rlockRecursionCount(Object self) { ReentrantLock l = rl(self); return l.isHeldByCurrentThread() ? (long) l.getHoldCount() : 0L; }
    public static Object rlockReleaseSave(Object self) {
        ReentrantLock l = rl(self);
        if (!l.isHeldByCurrentThread()) throw new PyException("RuntimeError", "cannot release un-acquired lock");
        int count = l.getHoldCount();
        for (int i = 0; i < count; i++) l.unlock();
        return tuple((long) count, Thread.currentThread().threadId());
    }
    public static Object rlockAcquireRestore(Object self, Object saved) {
        ReentrantLock l = rl(self);
        if (!(saved instanceof PyTuple t) || t.items.size() != 2) throw new PyException("TypeError", "_acquire_restore() argument must be a (count, owner) tuple");
        long count = ((Number) t.items.get(0)).longValue();
        for (long i = 0; i < count; i++) l.lock();
        return null;
    }
    public static Object rlockReinit(Object self) { setState(self, new RLockState()); return null; }
    public static Object rlockRepr(Object self) {
        ReentrantLock l = rl(self);
        return "<" + (l.isLocked() ? "locked" : "unlocked") + " _thread.RLock object owner=" + (l.isLocked() ? "" : "0") + " count=" + l.getHoldCount() + " at 0x" + Long.toHexString((Long) PyRuntime.id(self)) + ">";
    }
}
