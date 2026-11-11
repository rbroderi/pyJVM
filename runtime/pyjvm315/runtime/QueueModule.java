// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code _queue}: the C-accelerated {@code queue.SimpleQueue} (unbounded FIFO, reentrant: put never blocks) and
 * {@code queue.Empty}. Blocking {@code get} waits on a condition, so it releases the queue for other threads.
 */
public final class QueueModule {
    private QueueModule() {}

    private static PyClass queueClass, emptyClass;

    static final class Q {
        final ReentrantLock lock = new ReentrantLock();
        final Condition nonEmpty = lock.newCondition();
        final ArrayDeque<Object> items = new ArrayDeque<>();
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_queue", "pyjvm315.runtime.QueueModule");
        Class<?> c = QueueModule.class;
        emptyClass = (PyClass) PyRuntime.classCreate("Empty", "_queue"); PyRuntime.classAddBase(emptyClass, PyRuntime.builtinType("Exception"));
        finish(emptyClass);
        PyRuntime.classAddAttr(emptyClass, "__doc__", "Exception raised by Queue.get(block=0)/get_nowait().");
        queueClass = define("_queue", "SimpleQueue", null);
        method(queueClass, c, "__init__", "qInit", "self");
        method(queueClass, c, "put", "qPut", "self,item,block,timeout", defaults("block", true, "timeout", null));
        method(queueClass, c, "put_nowait", "qPutNowait", "self,item");
        method(queueClass, c, "get", "qGet", "self,block,timeout", defaults("block", true, "timeout", null));
        method(queueClass, c, "get_nowait", "qGetNowait", "self");
        method(queueClass, c, "empty", "qEmpty", "self");
        method(queueClass, c, "qsize", "qSize", "self");
        staticMethod(queueClass, c, "__class_getitem__", "qClassGetItem", "item", defaults());
        finish(queueClass);
        m.attrs.put("Empty", emptyClass);
        m.attrs.put("SimpleQueue", queueClass);
        return m;
    }

    private static Q q(Object self) { return state(self, Q.class, "SimpleQueue"); }

    public static Object qInit(Object self) { setState(self, new Q()); return null; }

    public static Object qPut(Object self, Object item, Object block, Object timeout) {
        Q q = q(self);
        q.lock.lock();
        try { q.items.add(item); q.nonEmpty.signal(); }
        finally { q.lock.unlock(); }
        return null;
    }

    public static Object qPutNowait(Object self, Object item) { return qPut(self, item, true, null); }

    public static Object qGetNowait(Object self) { return qGet(self, false, null); }

    public static Object qGet(Object self, Object block, Object timeout) {
        Q q = q(self);
        boolean blocking = PyRuntime.truth(block);
        long nanos = -1;
        if (timeout != null && blocking) {
            if (!(timeout instanceof Number n)) throw new PyException("TypeError", "'" + PyRuntime.typeName(timeout) + "' object cannot be interpreted as an integer or float");
            double t = n.doubleValue();
            if (t < 0) throw new PyException("ValueError", "'timeout' must be a non-negative number");
            if (t > ThreadModule.TIMEOUT_MAX) throw new PyException("OverflowError", "timeout value is too large");
            nanos = (long) (t * 1e9);
        }
        q.lock.lock();
        try {
            while (q.items.isEmpty()) {
                if (!blocking || nanos == 0) throw empty();
                try {
                    if (nanos < 0) q.nonEmpty.await();
                    else nanos = q.nonEmpty.awaitNanos(nanos);
                    if (nanos < 0 && q.items.isEmpty()) throw empty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PyException("KeyboardInterrupt", null);
                }
            }
            return q.items.poll();
        } finally { q.lock.unlock(); }
    }

    private static PyException empty() { return PyRuntime.userException(emptyClass); }

    public static Object qEmpty(Object self) {
        Q q = q(self);
        q.lock.lock();
        try { return q.items.isEmpty(); } finally { q.lock.unlock(); }
    }

    public static Object qSize(Object self) {
        Q q = q(self);
        q.lock.lock();
        try { return (long) q.items.size(); } finally { q.lock.unlock(); }
    }

    public static Object qClassGetItem(Object item) { return TypeParamsSupport.subscript(queueClass, item); }
}
