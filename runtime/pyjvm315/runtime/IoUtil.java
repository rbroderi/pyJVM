// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.LinkedHashSet;

/** Shared helpers of the {@code _io} implementation: argument coercion, the standard exceptions and the dirty-writer registry. */
final class IoUtil {
    private IoUtil() {}

    static final int DEFAULT_BUFFER_SIZE = 8192;

    static PyException closed() { return new PyException("ValueError", "I/O operation on closed file."); }
    static PyException unsupported(String what) { return PyRuntime.userException(PyRuntime.instantiate(IoModule.unsupportedClass, new Object[]{what})); }
    static PyException uninitialized() { return new PyException("ValueError", "I/O operation on uninitialized object"); }

    /** An integer argument ({@code __index__} accepted). */
    static long asLong(Object o) {
        java.math.BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (b.bitLength() > 63) throw new PyException("OverflowError", "Python int too large to convert to C ssize_t");
        return b.longValue();
    }

    static final Object END = new Object();

    /** The next item of an iterator (Java iterator or a Python object with __next__), or END. */
    static Object step(Object it) {
        if (it instanceof java.util.Iterator<?> j) return j.hasNext() ? j.next() : END;
        try { return PyRuntime.callMethod(it, "__next__", new Object[0]); }
        catch (PyException e) { if (e.typeName.equals("StopIteration")) return END; throw e; }
    }

    static int asInt(Object o) { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, asLong(o))); }

    /** An optional size argument: None means -1. */
    static long sizeArg(Object o) { return o == null ? -1 : asLong(o); }

    static Object call(Object o, String method, Object... args) { return PyRuntime.callMethod(o, method, args); }

    static byte[] bytesLike(Object o) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof PyBytes b) return b.data;
        if (o instanceof PyByteSequence s) return s.toByteArray();
        ArrayData arr = ArrayModule.asArray(o);
        if (arr != null) return arr.toByteArray();
        throw new PyException("TypeError", "a bytes-like object is required, not '" + PyRuntime.typeName(o) + "'");
    }

    /** Copy {@code n} bytes into a writable buffer object (bytearray or memoryview over one). */
    static void fill(Object target, byte[] src, int off, int n) {
        target = NativeSubclass.unwrap(target);
        if (target instanceof PyByteArray a) { for (int i = 0; i < n; i++) a.data.set(i, src[off + i]); return; }
        if (target instanceof PyMemoryView m && !m.readonly()) { for (int i = 0; i < n; i++) m.setUnsigned(i, src[off + i] & 0xFF); return; }
        ArrayData arr = ArrayModule.asArray(target);
        if (arr != null) { System.arraycopy(src, off, arr.buf, 0, n); return; }
        throw new PyException("TypeError", "readinto() argument 1 must be read-write bytes-like object, not " + PyRuntime.typeName(target));
    }

    static int bufferSize(Object target) {
        target = NativeSubclass.unwrap(target);
        if (target instanceof PyByteSequence s) return s.byteSize();
        ArrayData arr = ArrayModule.asArray(target);
        if (arr != null) return arr.byteSize();
        throw new PyException("TypeError", "readinto() argument 1 must be read-write bytes-like object, not " + PyRuntime.typeName(target));
    }

    // ---- writers with unflushed data ---------------------------------------------------------------
    // CPython flushes a file when its last reference drops; the JVM has no reference counting.  A writer that holds
    // unwritten data registers here, and every path-level file-system operation (open, stat, listdir, rename, ...)
    // and the process exit flush the registered writers first, so `open(p, "w").write(s)` followed by a read of p
    // behaves as it does in CPython.

    interface Pending { void flushQuietly(); }

    private static final LinkedHashSet<Pending> DIRTY = new LinkedHashSet<>();
    private static boolean syncing;

    static synchronized void dirty(Pending p) { DIRTY.add(p); }
    static synchronized void clean(Pending p) { DIRTY.remove(p); }

    static void syncAll() {
        synchronized (IoUtil.class) { if (DIRTY.isEmpty() || syncing) return; syncing = true; }
        try {
            for (int round = 0; round < 4; round++) {
                Pending[] all;
                synchronized (IoUtil.class) { if (DIRTY.isEmpty()) break; all = DIRTY.toArray(new Pending[0]); }
                for (Pending p : all) { try { p.flushQuietly(); } catch (RuntimeException ignored) { } synchronized (IoUtil.class) { DIRTY.remove(p); } }
            }
        } finally { synchronized (IoUtil.class) { syncing = false; } }
    }
}
