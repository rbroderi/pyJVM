// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.finish;
import static pyjvm315.runtime.NativeTypes.method;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small structseq factory for the POSIX modules ({@code os.waitid_result} now; {@code resource.struct_rusage} and the
 * N2 modules later): a tuple subclass whose fields are also attributes, with the module in the repr
 * ({@code posix.waitid_result(si_pid=1, ...)}). Same technique as {@code SysExtras.seqClass}. No FFM here.
 * An instance can be constructed from Python ({@code os.waitid_result((1, 2, 3, 4, 5))}, from any iterable of exactly
 * {@code n_fields} items, with CPython's texts, measured identical on 3.13/3.14/3.15) and pickled or copied
 * ({@code __reduce__}). Gaps: no {@code __replace__}; the class is sealed ({@link NativeTypes#seal}, which also makes
 * instance {@code __setattr__} honoured), so unlike CPython {@code os.waitid_result.x = 1} raises TypeError (CPython only
 * refuses subclassing); lifting that would change shared NativeTypes/PyRuntime behaviour for other types.
 */
final class PosixStructSeq {
    private PosixStructSeq() {}

    /** A new structseq class {@code module.name} with the given fields, all of them in the tuple part. */
    static PyClass define(String module, String name, String[] fields) {
        PyClass cls = (PyClass) PyRuntime.classCreate(name, module);
        PyRuntime.classAddBase(cls, PyRuntime.builtinType("tuple"));
        method(cls, PosixStructSeq.class, "__repr__", "seqRepr", "self");
        method(cls, PosixStructSeq.class, "__reduce__", "seqReduce", "self");
        method(cls, PosixStructSeq.class, "__dir__", "seqDir", "self");
        method(cls, PosixStructSeq.class, "__setattr__", "seqSetattr", "self,name,value");
        method(cls, PosixStructSeq.class, "__delattr__", "seqDelattr", "self,name");
        PyRuntime.classAddMethodEx(cls, "__new__", PosixStructSeq.class, "seqNew", "static", "", "cls", "", "args", "kwargs",
            new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(cls, "__new__", cls.methods.get("__new__").function);
        PyTuple match = new PyTuple();
        for (String f : fields) match.items.add(f);
        PyRuntime.classAddAttr(cls, "n_sequence_fields", (long) fields.length);
        PyRuntime.classAddAttr(cls, "n_fields", (long) fields.length);
        PyRuntime.classAddAttr(cls, "n_unnamed_fields", 0L);
        PyRuntime.classAddAttr(cls, "__match_args__", match);
        finish(cls);
        NativeTypes.seal(cls);   // a static type: no subclasses, no __dict__, and the __setattr__ above is honoured
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

    /** {@code cls(sequence, dict=None)}: the sequence is any iterable of exactly {@code n_fields} items (CPython's texts). */
    @SuppressWarnings("unchecked")
    public static Object seqNew(Object clsObj, Object argsObj, Object kwargsObj) {
        PyClass cls = (PyClass) clsObj;
        List<Object> args = ((PyTuple) argsObj).items;
        Map<Object, Object> kw = (Map<Object, Object>) kwargsObj;
        Object seq = null, dict = null;
        boolean haveSeq = false, haveDict = false;
        int given = args.size() + kw.size();
        if (given > 2) throw new PyException("TypeError", "structseq() takes at most 2 arguments (" + given + " given)");
        if (!args.isEmpty()) { seq = args.get(0); haveSeq = true; }
        if (args.size() > 1) { dict = args.get(1); haveDict = true; }
        for (Map.Entry<Object, Object> e : kw.entrySet()) {
            if ("sequence".equals(e.getKey())) {
                if (haveSeq) throw new PyException("TypeError", "argument for structseq() given by name ('sequence') and position (1)");
                seq = e.getValue(); haveSeq = true;
            } else if ("dict".equals(e.getKey())) {
                if (haveDict) throw new PyException("TypeError", "argument for structseq() given by name ('dict') and position (2)");
                dict = e.getValue(); haveDict = true;
            } else throw new PyException("TypeError", "structseq() got an unexpected keyword argument '" + e.getKey() + "'");
        }
        if (!haveSeq) throw new PyException("TypeError", "structseq() missing required argument 'sequence' (pos 1)");
        String full = cls.moduleName + "." + cls.name;
        // the sequence is converted first (CPython: PySequence_Fast); only creating the iterator is "requires a sequence",
        // an error raised while iterating propagates unchanged
        java.util.Iterator<?> it;
        try {
            it = PyRuntime.iterable(seq).iterator();
        } catch (PyException e) {
            if (e.typeName.equals("TypeError")) throw new PyException("TypeError", "constructor requires a sequence");
            throw e;
        }
        List<Object> items = new ArrayList<>();
        while (it.hasNext()) items.add(it.next());
        if (haveDict && !(NativeSubclass.unwrap(dict) instanceof PyDict)) {
            throw new PyException("TypeError", full + "() takes a dict as second arg, if any");
        }
        PyTuple match = (PyTuple) cls.attrs.get("__match_args__");
        int n = match.items.size();
        if (items.size() != n) {
            throw new PyException("TypeError", full + "() takes a " + n + "-sequence (" + items.size() + "-sequence given)");
        }
        // every field is in the sequence part, so any key of the dict is a duplicate or an unknown name
        if (haveDict && !((PyDict) NativeSubclass.unwrap(dict)).isEmpty()) {
            throw new PyException("TypeError", full + "() got duplicate or unexpected field name(s)");
        }
        return make(cls, items.toArray());
    }

    /** {@code dir(instance)}: the class's names plus the fields (the sealed class has no instance dict for the default to read). */
    static Object seqDir(Object self) {
        PyInstance o = (PyInstance) self;
        java.util.LinkedHashSet<Object> names = new java.util.LinkedHashSet<>((List<?>) PyRuntime.defaultDir(o.cls));
        names.addAll(((PyTuple) o.cls.attrs.get("__match_args__")).items);
        return new ArrayList<>(names);
    }

    /**
     * Struct sequences are read-only; set and delete give the same texts (measured on 3.13/3.14/3.15): a field name
     * {@code readonly attribute}; another class attribute {@code 'm.T' object attribute 'n' is read-only}; any other
     * name {@code 'm.T' object has no attribute 'n' and no __dict__ for setting new attributes}.
     */
    static Object seqSetattr(Object self, Object name, Object value) { throw readonly(self, name); }

    static Object seqDelattr(Object self, Object name) { throw readonly(self, name); }

    private static PyException readonly(Object self, Object nameObj) {
        PyClass cls = ((PyInstance) self).cls;
        String name = (String) nameObj;
        if (((PyTuple) cls.attrs.get("__match_args__")).items.contains(name)) return new PyException("AttributeError", "readonly attribute");
        String full = cls.moduleName + "." + cls.name;
        if (cls.lookupAttr(name) != PyRuntime.MISSING) {
            return new PyException("AttributeError", "'" + full + "' object attribute '" + name + "' is read-only");
        }
        return new PyException("AttributeError", "'" + full + "' object has no attribute '" + name + "' and no __dict__ for setting new attributes");
    }

    /** {@code (cls, (tuple(self), {}))}, the shape CPython gives (measured), so {@code pickle} and {@code copy} rebuild through {@link #seqNew}. */
    static Object seqReduce(Object self) {
        PyInstance o = (PyInstance) self;
        PyTuple whole = new PyTuple();
        whole.items.addAll(((PyTuple) o.payload).items);
        PyTuple args = new PyTuple();
        args.items.add(whole);
        args.items.add(new PyDict());
        PyTuple out = new PyTuple();
        out.items.add(o.cls);
        out.items.add(args);
        return out;
    }
}
