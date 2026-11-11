// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The {@code array} module.  {@code array.array} is a native class whose instances carry an {@link ArrayData};
 * Python code can subclass it.  The sequence protocol and the named methods live in {@link ArrayOps} and
 * {@link ArrayMethods}.
 */
public final class ArrayModule {
    private ArrayModule() {}

    static final String ME = ArrayModule.class.getName();
    static PyClass arrayClass, iterClass;
    static NativeFunction reconstructor;

    static String typecodes() {
        return LangVersion.atLeast(3, 15) ? "bBuwhHiIlLqQefd" : "bBuwhHiIlLqQfd";
    }

    static boolean validCode(String tc) {
        if (tc.length() == 1) {
            char c = tc.charAt(0);
            if (LangVersion.atLeast(3, 15) && c == 'e') return true;
            return "bBuwhHiIlLqQfd".indexOf(c) >= 0;
        }
        return LangVersion.atLeast(3, 15) && (tc.equals("Zf") || tc.equals("Zd"));
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("array", ME);
        Class<?> c = ArrayModule.class;
        arrayClass = define("array", "array", null);
        PyRuntime.classAddMethodEx(arrayClass, "__new__", c, "arrayNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(arrayClass, "__new__", arrayClass.methods.get("__new__").function);
        ArrayOps.define(arrayClass);
        ArrayMethods.define(arrayClass);
        PyRuntime.classAddProperty(arrayClass, "typecode", ME, "typecodeOf", null);
        PyRuntime.classAddProperty(arrayClass, "itemsize", ME, "itemsizeOf", null);
        PyRuntime.classAddAttr(arrayClass, "__hash__", null);
        finish(arrayClass);
        iterClass = define("array", "arrayiterator", null);
        method(iterClass, c, "__iter__", "iterSelf", "self");
        method(iterClass, c, "__next__", "iterNext", "self");
        finish(iterClass);
        m.attrs.put("array", arrayClass);
        m.attrs.put("ArrayType", arrayClass);
        m.attrs.put("typecodes", LangVersion.atLeast(3, 15) ? tupleOfChars(typecodes()) : typecodes());
        reconstructor = new NativeFunction("_array_reconstructor", (a, k) -> ArrayMethods.reconstruct(a, k));
        m.attrs.put("_array_reconstructor", reconstructor);
        m.attrs.put("__doc__", "This module defines an object type which can efficiently represent\nan array of basic values: characters, integers, floating-point\nnumbers.  Arrays are sequence types and behave very much like lists,\nexcept that the type of objects stored in them is constrained.\n");
        return m;
    }

    private static Object tupleOfChars(String s) {
        PyTuple t = new PyTuple();
        for (int i = 0; i < s.length(); i++) {
            t.items.add(String.valueOf(s.charAt(i)));
            if (s.charAt(i) == 'f' || s.charAt(i) == 'd') { /* complex codes follow below */ }
        }
        t.items.add("Zf");
        t.items.add("Zd");
        return t;
    }

    // ---- hooks used by struct / io ---------------------------------------------------------------------------

    /** The writable byte view behind an array object, or null when {@code o} is not an array. */
    static Object writableBuffer(Object o) {
        return o instanceof PyInstance i && i.slotValues.get(STATE) instanceof ArrayData a ? a : null;
    }

    static void writeBytes(Object target, int offset, byte[] src) {
        ArrayData a = (ArrayData) target;
        System.arraycopy(src, 0, a.buf, offset, src.length);
    }

    /** The array behind {@code o} (an array or an instance of a subclass), else null. */
    static ArrayData asArray(Object o) {
        return o instanceof PyInstance i && i.slotValues.get(STATE) instanceof ArrayData a ? a : null;
    }

    static ArrayData self(Object o) {
        ArrayData a = asArray(o);
        if (a == null) {
            if (o instanceof PyInstance i && i.cls.mro.contains(arrayClass)) throw new PyException("TypeError", "array object is not initialized");
            throw new PyException("TypeError", "descriptor requires a 'array.array' object but received '" + PyRuntime.typeName(o) + "'");
        }
        return a;
    }

    // ---- construction ----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Object arrayNew(Object cls, Object argsObj, Object kwargsObj) {
        List<Object> args = ((PyTuple) argsObj).items;
        Map<Object, Object> kw = (Map<Object, Object>) kwargsObj;
        PyClass pc = (PyClass) cls;
        if (pc == arrayClass && !kw.isEmpty()) throw new PyException("TypeError", "array.array() takes no keyword arguments");
        if (args.isEmpty()) throw new PyException("TypeError", "array() takes at least 1 argument (0 given)");
        if (args.size() > 2) throw new PyException("TypeError", "array() takes at most 2 arguments (" + args.size() + " given)");
        Object tcObj = NativeSubclass.unwrap(args.get(0));
        if (!(tcObj instanceof String tc)) {
            throw new PyException("TypeError", LangVersion.atLeast(3, 15)
                ? "array() argument 1 must be str, not " + PyRuntime.typeName(tcObj)
                : "array() argument 1 must be a unicode character, not " + PyRuntime.typeName(tcObj));
        }
        if (!LangVersion.atLeast(3, 15) && tc.codePointCount(0, tc.length()) != 1)
            throw new PyException("TypeError", "array() argument 1 must be a unicode character, not str");
        if (!validCode(tc)) {
            throw new PyException("ValueError", LangVersion.atLeast(3, 15)
                ? "bad typecode (must be b, B, u, w, h, H, i, I, l, L, q, Q, e, f, d, Zf or Zd)"
                : "bad typecode (must be b, B, u, w, h, H, i, I, l, L, q, Q, f or d)");
        }
        char code = tc.equals("Zf") ? 'F' : tc.equals("Zd") ? 'D' : tc.charAt(0);
        if (code == 'u' && LangVersion.atLeast(3, 14))
            WarningsModule.warn(List.of("The 'u' type code is deprecated and will be removed in Python 3.16", PyRuntime.builtinType("DeprecationWarning"), 2L), new java.util.LinkedHashMap<>());
        ArrayData d = new ArrayData(code);
        PyInstance inst = newInstance(pc, d);
        if (args.size() == 2) fill(d, args.get(1));
        return inst;
    }

    static void fill(ArrayData d, Object initial) {
        Object u = NativeSubclass.unwrap(initial);
        ArrayData other = asArray(u);
        if (u instanceof String s) {
            if (!d.unicode()) throw new PyException("TypeError", "cannot use a str to initialize an array with typecode '" + d.code() + "'");
            ArrayMethods.appendString(d, s);
        } else if (u instanceof PyBytes || u instanceof PyByteArray) {
            ArrayMethods.frombytes(d, IoUtil.bytesLike(u));
        } else if (other != null && other.unicode() && !d.unicode()) {
            throw new PyException("TypeError", "cannot use a unicode array to initialize an array with typecode '" + d.code() + "'");
        } else if (other != null && other.tc == d.tc) {
            d.buf = Arrays.copyOf(other.buf, other.n * other.isz);
            d.n = other.n;
        } else if (u instanceof List<?> || u instanceof PyTuple) {
            Iterable<?> items = u instanceof PyTuple t ? t.items : (List<?>) u;
            for (Object x : items) d.append(x);
        } else {
            for (Object x : PyRuntime.iterable(initial)) d.append(x);
        }
    }

    // ---- properties and the iterator ---------------------------------------------------------------------------

    public static Object typecodeOf(Object self) { return self(self).code(); }
    public static Object itemsizeOf(Object self) { return (long) self(self).isz; }

    static final class ArrIter extends PyLazyIterator {
        final ArrayData a; int i;
        ArrIter(ArrayData a) { this.a = a; }
        Object compute() {
            if (a == null || i >= a.n) throw new NoSuchElementException();
            return a.get(i++);
        }
        String pyTypeName() { return "arrayiterator"; }
    }

    static Object iterator(ArrayData a) { return new ArrIter(a); }

    public static Object iterSelf(Object self) { return self; }

    public static Object iterNext(Object self) {
        if (self instanceof ArrIter it) { if (it.hasNext()) return it.next(); throw new PyException("StopIteration", ""); }
        throw new PyException("TypeError", "not an array iterator");
    }

    static BigInteger big(Object o) { return BuiltinsExtra.rangeIndex(o); }
}
