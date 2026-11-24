// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.finish;
import static pyjvm315.runtime.NativeTypes.method;

/**
 * A small structseq factory for the POSIX modules ({@code os.waitid_result} now; {@code resource.struct_rusage} and the
 * N2 modules later): a tuple subclass whose fields are also attributes, with the module in the repr
 * ({@code posix.waitid_result(si_pid=1, ...)}). Same technique as {@code SysExtras.seqClass}. No FFM here.
 * Gap: an instance cannot be constructed from Python ({@code os.waitid_result((1, 2, 3, 4, 5))}).
 */
final class PosixStructSeq {
    private PosixStructSeq() {}

    /** A new structseq class {@code module.name} with the given fields, all of them in the tuple part. */
    static PyClass define(String module, String name, String[] fields) {
        PyClass cls = (PyClass) PyRuntime.classCreate(name, module);
        PyRuntime.classAddBase(cls, PyRuntime.builtinType("tuple"));
        method(cls, PosixStructSeq.class, "__repr__", "seqRepr", "self");
        PyTuple match = new PyTuple();
        for (String f : fields) match.items.add(f);
        PyRuntime.classAddAttr(cls, "n_sequence_fields", (long) fields.length);
        PyRuntime.classAddAttr(cls, "n_fields", (long) fields.length);
        PyRuntime.classAddAttr(cls, "n_unnamed_fields", 0L);
        PyRuntime.classAddAttr(cls, "__match_args__", match);
        finish(cls);
        return cls;
    }

    /** An instance of a class made by {@link #define}; one value per field. */
    static PyInstance make(PyClass cls, Object... values) {
        PyTuple match = (PyTuple) cls.attrs.get("__match_args__");
        PyInstance o = PyInstance.create(cls);
        PyTuple t = new PyTuple();
        for (Object v : values) t.items.add(v);
        o.payload = t;
        for (int i = 0; i < values.length; i++) o.fields.put((String) match.items.get(i), values[i]);
        return o;
    }

    static Object seqRepr(Object self) {
        PyInstance o = (PyInstance) self;
        PyTuple t = (PyTuple) o.payload;
        PyTuple match = (PyTuple) o.cls.attrs.get("__match_args__");
        StringBuilder sb = new StringBuilder(o.cls.moduleName).append('.').append(o.cls.name).append('(');
        for (int i = 0; i < t.items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(match.items.get(i)).append('=').append(PyRuntime.pyRepr(t.items.get(i)));
        }
        return sb.append(')').toString();
    }
}
