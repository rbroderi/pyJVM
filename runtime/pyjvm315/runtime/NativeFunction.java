// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.List;
import java.util.Map;

/** A function implemented in Java and exposed to Python code (native modules). */
final class NativeFunction {
    interface Body { Object call(List<Object> args, Map<Object,Object> kwargs); }
    final String name; final Body body;
    NativeFunction(String name, Body body) { this.name = name; this.body = body; }
    @Override public String toString() { return "<built-in function " + name + ">"; }
}
