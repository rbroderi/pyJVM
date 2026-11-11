// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * Free-threaded model: there is no GIL. Every shared mutable structure protects itself with its own monitor
 * ({@code synchronized (this)} on the Java object), but only once a second thread exists. Until the first
 * {@code _thread.start_*} call {@link #MT} is false and every container takes its lock-free single-thread path, so
 * single-threaded programs pay one volatile read (a plain load on x86/ARMv8) per operation and nothing else.
 *
 * The flag only ever goes false -> true, before the new thread starts (Thread.start happens-before the thread's
 * first action), so no operation of the starting thread is in flight on a structure the new thread can reach
 * except through that start.
 */
final class SyncSupport {
    private SyncSupport() {}

    /** True once a second thread has been started. */
    static volatile boolean MT;

    static void enterMultiThreaded() { MT = true; }

    /** Per-thread cleanup when a Python thread finishes. */
    static void threadExit() {
        PyRuntime.threadFinished();
        Tracing.threadFinished();
    }
}
