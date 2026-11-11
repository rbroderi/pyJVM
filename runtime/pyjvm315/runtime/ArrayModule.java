// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

public final class ArrayModule {
    private ArrayModule() {}

    /** The writable byte view behind an array object, or null when {@code o} is not an array. */
    static Object writableBuffer(Object o) { return null; }

    static void writeBytes(Object target, int offset, byte[] src) { throw new PyException("TypeError", "not writable"); }
}
