// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** Chaining metadata of an exception instance whose class is defined in Python. */
final class ExcState {
    Object cause, context;
    boolean suppressContext;
    PyTraceback traceback;
}
