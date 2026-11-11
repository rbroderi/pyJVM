// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * An instance of a class that defines {@code __del__}. The JVM runs {@link #finalize} (once, on its finalizer
 * thread) when the object becomes unreachable; that method only queues the object, resurrected, for its creating
 * thread. {@code __del__} then runs at that thread's next poll point (see {@link WeakrefDelivery}). Because the JVM
 * finalizes an object at most once, PEP 442 holds: a resurrected object is never finalized a second time, and
 * garbage cycles containing finalizable objects are collected like any other.
 */
final class GcFinalizable extends PyInstance {
    private final WeakrefDelivery.ThreadInfo owner = WeakrefDelivery.currentThread();
    volatile boolean finalized;

    GcFinalizable(PyClass cls) {
        super(cls);
        WeakrefDelivery.ensureWatcher();
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    protected void finalize() {
        final GcFinalizable self = this;
        WeakrefDelivery.post(new WeakrefDelivery.Task(owner, WeakrefDelivery.SEQ.incrementAndGet(), true) {
            @Override void run() {
                self.finalized = true;
                Object del = PyRuntime.getattr(self, "__del__");
                PyRuntime.callFunction(del, new ArrayList<Object>(), new LinkedHashMap<Object, Object>());
            }
            @Override String what() { return null; }
            @Override Object subject() { return PyRuntime.getattr(self, "__del__"); }
        });
    }
}
