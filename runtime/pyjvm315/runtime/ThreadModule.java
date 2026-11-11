// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code _thread}: lock objects backed by java.util.concurrent. {@code lock} (allocate_lock) is a binary semaphore
 * (any thread may release it, as in Python); {@code RLock} is a reentrant lock with owner and recursion count.
 * Starting threads is not implemented: the interpreter state (module table, frame stacks) is not thread-safe yet.
 */
public final class ThreadModule {
    private ThreadModule() {}

    static final double TIMEOUT_MAX = 9223372036.0;
    private static PyClass lockClass, rlockClass;

    /** Lock state: a binary semaphore. */
    static final class LockState { final Semaphore sem = new Semaphore(1); }

    /** RLock state: java.util.concurrent reentrant lock. */
    static final class RLockState { final ReentrantLock lock = new ReentrantLock(); }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_thread", "pyjvm315.runtime.ThreadModule");
        lockClass = define("_thread", "lock", null);
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
        fn(m, "_count", (a, k) -> 0L);
        fn(m, "stack_size", (a, k) -> 0L);
        fn(m, "start_new_thread", (a, k) -> { throw new PyException("NotImplementedError", "_thread.start_new_thread: starting threads is not implemented (the interpreter state is not thread-safe)"); });
        fn(m, "start_joinable_thread", (a, k) -> { throw new PyException("NotImplementedError", "_thread.start_joinable_thread: starting threads is not implemented (the interpreter state is not thread-safe)"); });
        return m;
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
