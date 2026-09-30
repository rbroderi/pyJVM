package pyjvm315.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

public final class PyRuntime {
    private PyRuntime() {}

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    // ---------- Python integer representation ----------
    // Fast path: java.lang.Long. Slow path: java.math.BigInteger.
    // Results are compacted back to Long whenever they fit.
    public static Object pyInt(String decimal) {
        try {
            return Long.valueOf(decimal);
        } catch (NumberFormatException ignored) {
            return compact(new BigInteger(decimal));
        }
    }

    private static Object compact(BigInteger value) {
        if (value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
            return value.longValue();
        }
        return value;
    }

    private static boolean isIntLike(Object value) {
        return value instanceof Long || value instanceof BigInteger || value instanceof Boolean;
    }

    private static BigInteger bigInt(Object value) {
        if (value instanceof BigInteger n) return n;
        if (value instanceof Long n) return BigInteger.valueOf(n);
        if (value instanceof Boolean b) return b ? BigInteger.ONE : BigInteger.ZERO;
        throw typeError("integer operand required", value);
    }

    private static double number(Object value) {
        if (value instanceof Long n) return n.doubleValue();
        if (value instanceof BigInteger n) return n.doubleValue();
        if (value instanceof Double n) return n;
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        throw typeError("numeric operand required", value);
    }

    private static RuntimeException typeError(String message, Object value) {
        return new IllegalArgumentException(message + ": " + typeName(value));
    }

    private static String typeName(Object value) {
        if (value == null) return "NoneType";
        if (value instanceof Long || value instanceof BigInteger) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof Boolean) return "bool";
        if (value instanceof String) return "str";
        if (value instanceof PyTuple) return "tuple";
        if (value instanceof List) return "list";
        if (value instanceof Map) return "dict";
        if (value instanceof Set) return "set";
        if (value instanceof PyRange) return "range";
        if (value instanceof PyFunction) return "function";
        if (value instanceof PyGenerator) return "generator";
        if (value instanceof PyClass) return "type";
        if (value instanceof PyInstance i) return i.cls.name;
        if (value instanceof PyDictView) return "dict_view";
        if (value instanceof PyModule) return "module";
        if (value instanceof PyExceptionValue e) return e.typeName;
        return value.getClass().getSimpleName();
    }

    // ---------- Python module loading ----------
    private static final LinkedHashMap<String,PyModule> MODULE_CACHE = new LinkedHashMap<>();
    public static final class PyModule {
        final String name, className;
        final LinkedHashMap<String,Object> attrs = new LinkedHashMap<>();
        PyModule(String name,String className){this.name=name;this.className=className;}
        @Override public String toString(){return "<module '"+name+"'>";}
    }
    public static Object importModule(Object nameObj,Object classObj) {
        String name=(String)nameObj, className=(String)classObj;
        PyModule cached=MODULE_CACHE.get(name); if(cached!=null) return cached;
        PyModule module=new PyModule(name,className); MODULE_CACHE.put(name,module);
        int dot=name.lastIndexOf('.');
        if(dot>0){ PyModule parent=MODULE_CACHE.get(name.substring(0,dot)); if(parent!=null) parent.attrs.put(name.substring(dot+1),module); }
        for(var e:MODULE_CACHE.entrySet()) {
            String childName=e.getKey(); int childDot=childName.lastIndexOf('.');
            if(childDot>0 && childName.substring(0,childDot).equals(name)) module.attrs.put(childName.substring(childDot+1),e.getValue());
        }
        try {
            Class<?> cls=Class.forName(className);
            var main=cls.getDeclaredMethod("main",String[].class);
            main.invoke(null,(Object)new String[0]);
            return module;
        } catch(java.lang.reflect.InvocationTargetException exc) {
            MODULE_CACHE.remove(name); Throwable cause=exc.getCause();
            if(cause instanceof RuntimeException r) throw r;
            if(cause instanceof Error e) throw e;
            throw new RuntimeException(cause);
        } catch(ReflectiveOperationException exc) {
            MODULE_CACHE.remove(name); throw new PyException("ImportError","cannot import module '"+name+"': "+exc.getMessage());
        }
    }
    public static Object moduleGetattr(Object moduleObj,Object nameObj) {
        PyModule module=(PyModule)moduleObj; String name=(String)nameObj;
        if(module.attrs.containsKey(name)) return module.attrs.get(name);
        String[] fields={"__py_global_"+name,"__py_function_"+name,"__py_class_"+name};
        try {
            Class<?> cls=Class.forName(module.className);
            for(String f:fields) {
                try { return cls.getField(f).get(null); } catch(NoSuchFieldException ignored) {}
            }
        } catch(ReflectiveOperationException exc) { throw new RuntimeException(exc); }
        throw new PyException("AttributeError","module '"+module.name+"' has no attribute '"+name+"'");
    }

    // ---------- Builtin type objects / introspection ----------
    public static final class PyBuiltinType {
        final String name;
        PyBuiltinType(String name){this.name=name;}
        @Override public String toString(){return "<class '"+name+"'>";}
        @Override public boolean equals(Object other){return other instanceof PyBuiltinType t && name.equals(t.name);}
        @Override public int hashCode(){return name.hashCode();}
    }
    public static Object builtinType(Object nameObj){return new PyBuiltinType((String)nameObj);}
    public static Object typeOf(Object value){
        if(value instanceof PyInstance i) return i.cls;
        return new PyBuiltinType(typeName(value));
    }
    public static Object callable_(Object value){return value instanceof PyFunction || value instanceof BoundMethod || value instanceof PyClass || value instanceof PyBuiltinType;}

    // ---------- Display / truth ----------
    public static Object print(Object value) {
        System.out.println(pyStr(value));
        return null;
    }

    public static String pyStr(Object value) {
        if (value == null) return "None";
        if (value instanceof Boolean b) return b ? "True" : "False";
        if (value instanceof String s) return s;
        if (value instanceof PyTuple t) return t.pyRepr();
        if (value instanceof PyDictView v) return v.pyRepr();
        if (value instanceof PyExceptionValue e) return e.value == null ? "" : pyStr(e.value);
        if (value instanceof PyException e) return e.getMessage() == null ? "" : e.getMessage();
        if (value instanceof Throwable t) return t.getMessage() == null ? "" : t.getMessage();
        if (value instanceof List<?> xs) return seqRepr(xs, "[", "]");
        if (value instanceof Set<?> xs) {
            if (xs.isEmpty()) return "set()";
            return seqRepr(xs, "{", "}");
        }
        if (value instanceof Map<?, ?> m) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append(pyRepr(e.getKey())).append(": ").append(pyRepr(e.getValue()));
            }
            return out.append('}').toString();
        }
        return String.valueOf(value);
    }

    public static String pyRepr(Object value) {
        if (value instanceof String s) return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
        return pyStr(value);
    }

    private static String seqRepr(Iterable<?> xs, String left, String right) {
        StringBuilder out = new StringBuilder(left);
        boolean first = true;
        for (Object x : xs) {
            if (!first) out.append(", ");
            first = false;
            out.append(pyRepr(x));
        }
        return out.append(right).toString();
    }

    public static boolean truth(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Long n) return n != 0L;
        if (value instanceof BigInteger n) return n.signum() != 0;
        if (value instanceof Double n) return n != 0.0;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof List<?> xs) return !xs.isEmpty();
        if (value instanceof Map<?, ?> xs) return !xs.isEmpty();
        if (value instanceof Set<?> xs) return !xs.isEmpty();
        if (value instanceof PyTuple t) return !t.items.isEmpty();
        return true;
    }

    // ---------- Arithmetic ----------
    public static Object add(Object a, Object b) {
        if (a instanceof String sa && b instanceof String sb) return sa + sb;
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            ArrayList<Object> out = new ArrayList<>(la.size() + lb.size());
            out.addAll(la); out.addAll(lb); return out;
        }
        if (isIntLike(a) && isIntLike(b)) {
            if (a instanceof Long x && b instanceof Long y) {
                try { return Math.addExact(x, y); }
                catch (ArithmeticException ignored) {}
            }
            return compact(bigInt(a).add(bigInt(b)));
        }
        return number(a) + number(b);
    }

    public static Object sub(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) {
            if (a instanceof Long x && b instanceof Long y) {
                try { return Math.subtractExact(x, y); }
                catch (ArithmeticException ignored) {}
            }
            return compact(bigInt(a).subtract(bigInt(b)));
        }
        return number(a) - number(b);
    }

    public static Object mul(Object a, Object b) {
        if (a instanceof String sa && isIntLike(b)) return repeatString(sa, bigInt(b));
        if (b instanceof String sb && isIntLike(a)) return repeatString(sb, bigInt(a));
        if (a instanceof List<?> la && isIntLike(b)) return repeatList(la, bigInt(b));
        if (b instanceof List<?> lb && isIntLike(a)) return repeatList(lb, bigInt(a));
        if (isIntLike(a) && isIntLike(b)) {
            if (a instanceof Long x && b instanceof Long y) {
                try { return Math.multiplyExact(x, y); }
                catch (ArithmeticException ignored) {}
            }
            return compact(bigInt(a).multiply(bigInt(b)));
        }
        return number(a) * number(b);
    }

    public static Object truediv(Object a, Object b) {
        double y = number(b);
        if (y == 0.0) throw new ArithmeticException("division by zero");
        return number(a) / y;
    }

    public static Object floordiv(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) {
            BigInteger x = bigInt(a), y = bigInt(b);
            if (y.signum() == 0) throw new ArithmeticException("integer division or modulo by zero");
            BigInteger[] qr = x.divideAndRemainder(y);
            if (qr[1].signum() != 0 && qr[1].signum() != y.signum()) qr[0] = qr[0].subtract(BigInteger.ONE);
            return compact(qr[0]);
        }
        double x = number(a), y = number(b);
        if (y == 0.0) throw new ArithmeticException("float floor division by zero");
        return Math.floor(x / y);
    }

    public static Object mod(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) {
            BigInteger x = bigInt(a), y = bigInt(b);
            if (y.signum() == 0) throw new ArithmeticException("integer division or modulo by zero");
            BigInteger r = x.remainder(y);
            if (r.signum() != 0 && r.signum() != y.signum()) r = r.add(y);
            return compact(r);
        }
        double x = number(a), y = number(b);
        if (y == 0.0) throw new ArithmeticException("float modulo");
        return x - Math.floor(x / y) * y;
    }

    public static Object neg(Object a) {
        if (a instanceof Long n) {
            if (n != Long.MIN_VALUE) return -n;
            return compact(BigInteger.valueOf(n).negate());
        }
        if (a instanceof BigInteger n) return compact(n.negate());
        if (a instanceof Boolean b) return b ? -1L : 0L;
        if (a instanceof Double n) return -n;
        throw typeError("bad operand type for unary -", a);
    }

    public static Object not_(Object a) { return !truth(a); }
    public static Object pos(Object a) {
        if (isIntLike(a)) return compact(bigInt(a));
        if (a instanceof Double d) return d;
        throw typeError("bad operand type for unary +", a);
    }
    public static Object invert(Object a) { return compact(bigInt(a).not()); }
    public static Object abs(Object a) {
        if (isIntLike(a)) return compact(bigInt(a).abs());
        if (a instanceof Double d) return Math.abs(d);
        throw typeError("bad operand type for abs()", a);
    }
    public static Object pow(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) {
            BigInteger exp = bigInt(b);
            if (exp.signum() < 0) return Math.pow(number(a), number(b));
            if (exp.bitLength() > 31) throw new ArithmeticException("exponent too large");
            return compact(bigInt(a).pow(exp.intValue()));
        }
        return Math.pow(number(a), number(b));
    }
    public static Object bitAnd(Object a, Object b) { return compact(bigInt(a).and(bigInt(b))); }
    public static Object bitOr(Object a, Object b) { return compact(bigInt(a).or(bigInt(b))); }
    public static Object bitXor(Object a, Object b) { return compact(bigInt(a).xor(bigInt(b))); }
    public static Object lshift(Object a, Object b) {
        BigInteger n = bigInt(b); if (n.signum() < 0) throw new ArithmeticException("negative shift count");
        if (n.bitLength() > 31) throw new ArithmeticException("shift count too large");
        return compact(bigInt(a).shiftLeft(n.intValue()));
    }
    public static Object rshift(Object a, Object b) {
        BigInteger n = bigInt(b); if (n.signum() < 0) throw new ArithmeticException("negative shift count");
        if (n.bitLength() > 31) return bigInt(a).signum() < 0 ? -1L : 0L;
        return compact(bigInt(a).shiftRight(n.intValue()));
    }

    // ---------- Equality / ordering ----------
    public static Object eq(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) return bigInt(a).equals(bigInt(b));
        if (a instanceof Number && b instanceof Number) return Double.compare(number(a), number(b)) == 0;
        return Objects.equals(a, b);
    }

    public static Object ne(Object a, Object b) { return !(Boolean) eq(a, b); }
    public static Object is_(Object a, Object b) { return a == b; }
    public static Object is_not(Object a, Object b) { return a != b; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int cmp(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) return bigInt(a).compareTo(bigInt(b));
        if (a instanceof Number && b instanceof Number) return Double.compare(number(a), number(b));
        if (a != null && b != null && a.getClass() == b.getClass() && a instanceof Comparable c) return c.compareTo(b);
        throw new IllegalArgumentException("comparison not supported between '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    public static Object lt(Object a, Object b) { return cmp(a, b) < 0; }
    public static Object le(Object a, Object b) { return cmp(a, b) <= 0; }
    public static Object gt(Object a, Object b) { return cmp(a, b) > 0; }
    public static Object ge(Object a, Object b) { return cmp(a, b) >= 0; }

    public static Object bool_(Object value) { return truth(value); }
    public static Object str_(Object value) { return pyStr(value); }
    public static Object repr_(Object value) { return pyRepr(value); }
    public static Object int_(Object value) {
        if (isIntLike(value)) return compact(bigInt(value));
        if (value instanceof Double d) return compact(BigInteger.valueOf(d.longValue()));
        if (value instanceof String s) return pyInt(s.trim());
        throw typeError("int() argument must be a string or a number", value);
    }
    public static Object float_(Object value) {
        if (value instanceof String s) return Double.valueOf(s.trim());
        return number(value);
    }
    public static Object sum(Object value) {
        Object total = 0L;
        for (Object x : iterable(value)) total = add(total, x);
        return total;
    }
    public static Object any(Object value) {
        for (Object x : iterable(value)) if (truth(x)) return true;
        return false;
    }
    public static Object all(Object value) {
        for (Object x : iterable(value)) if (!truth(x)) return false;
        return true;
    }
    public static Object min(Object value) { return extreme(value, true); }
    public static Object max(Object value) { return extreme(value, false); }
    private static Object extreme(Object value, boolean wantMin) {
        Iterator<?> it = iterable(value).iterator();
        if (!it.hasNext()) throw new IllegalArgumentException((wantMin ? "min" : "max") + "() arg is an empty sequence");
        Object best = it.next();
        while (it.hasNext()) {
            Object x = it.next(); int c = cmp(x, best);
            if ((wantMin && c < 0) || (!wantMin && c > 0)) best = x;
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    public static Object printArgs(Object argsObj, Object sepObj, Object endObj) {
        List<Object> args = (List<Object>)argsObj;
        String sep = sepObj == null ? " " : (String)sepObj;
        String end = endObj == null ? "\n" : (String)endObj;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i != 0) out.append(sep);
            out.append(pyStr(args.get(i)));
        }
        System.out.print(out.toString()); System.out.print(end); return null;
    }

    public static void assertFail(Object msg) {
        if (msg == null) throw new AssertionError();
        throw new AssertionError(pyStr(msg));
    }

    private static String repeatString(String s, BigInteger count) {
        if (count.signum() <= 0) return "";
        if (count.bitLength() > 31) throw new OutOfMemoryError("repeated string is too long");
        return s.repeat(count.intValue());
    }
    private static Object repeatList(List<?> xs, BigInteger count) {
        ArrayList<Object> out = new ArrayList<>();
        if (count.signum() <= 0) return out;
        if (count.bitLength() > 31) throw new OutOfMemoryError("repeated list is too long");
        for (int i=0;i<count.intValue();i++) out.addAll(xs);
        return out;
    }

    // ---------- Containers ----------
    public static Object list0() { return new ArrayList<Object>(); }
    @SuppressWarnings("unchecked")
    public static void listAppend(Object list, Object value) { ((List<Object>) list).add(value); }
    @SuppressWarnings("unchecked")
    public static void listExtend(Object list, Object values) {
        List<Object> out = (List<Object>) list;
        for (Object value : iterable(values)) out.add(value);
    }
    public static Object listFrom(Object values) { ArrayList<Object> out=new ArrayList<>(); for(Object x:iterable(values)) out.add(x); return out; }

    public static Object tuple0() { return new PyTuple(); }
    public static void tupleAppend(Object tuple, Object value) { ((PyTuple) tuple).items.add(value); }
    public static void tupleExtend(Object tuple, Object values) { for(Object x:iterable(values)) ((PyTuple)tuple).items.add(x); }
    public static Object tupleFrom(Object values) { PyTuple out=new PyTuple(); for(Object x:iterable(values)) out.items.add(x); return out; }

    public static Object dict0() { return new LinkedHashMap<Object, Object>(); }
    @SuppressWarnings("unchecked")
    public static void dictPut(Object dict, Object key, Object value) { ((Map<Object, Object>) dict).put(key, value); }
    @SuppressWarnings("unchecked")
    public static void dictPutUnique(Object dict, Object key, Object value) {
        Map<Object,Object> out = (Map<Object,Object>) dict;
        if (out.containsKey(key)) throw new PyException("TypeError", "got multiple values for keyword argument '" + key + "'");
        out.put(key, value);
    }
    @SuppressWarnings("unchecked")
    public static void dictMergeUnique(Object dict, Object other) {
        if (!(other instanceof Map<?,?> src)) throw new PyException("TypeError", "argument after ** must be a mapping");
        Map<Object,Object> out = (Map<Object,Object>) dict;
        for (var e : src.entrySet()) {
            Object key = e.getKey();
            if (!(key instanceof String)) throw new PyException("TypeError", "keywords must be strings");
            if (out.containsKey(key)) throw new PyException("TypeError", "got multiple values for keyword argument '" + key + "'");
            out.put(key, e.getValue());
        }
    }
    @SuppressWarnings("unchecked")
    public static void dictUpdate(Object dict, Object other) {
        if (!(other instanceof Map<?,?> src)) throw new PyException("TypeError", "dictionary update sequence element has invalid form");
        ((Map<Object,Object>)dict).putAll((Map<Object,Object>)src);
    }
    public static Object dictFrom(Object value) { Object out=dict0(); dictUpdate(out,value); return out; }

    public static Object set0() { return new LinkedHashSet<Object>(); }
    @SuppressWarnings("unchecked")
    public static void setAdd(Object set, Object value) { ((Set<Object>) set).add(value); }
    @SuppressWarnings("unchecked")
    public static void setUpdate(Object set, Object values) { for(Object x:iterable(values)) ((Set<Object>)set).add(x); }
    public static Object setFrom(Object values) { LinkedHashSet<Object> out=new LinkedHashSet<>(); for(Object x:iterable(values)) out.add(x); return out; }

    public static Object len(Object value) {
        long n;
        if (value instanceof String s) n = s.codePointCount(0, s.length());
        else if (value instanceof List<?> xs) n = xs.size();
        else if (value instanceof Map<?, ?> xs) n = xs.size();
        else if (value instanceof Set<?> xs) n = xs.size();
        else if (value instanceof PyTuple t) n = t.items.size();
        else if (value instanceof PyRange r) return compact(r.length());
        else throw typeError("object has no len()", value);
        return n;
    }

    public static Object getitem(Object value, Object key) {
        if (key instanceof PySlice sl) return sliceGet(value, sl);
        if (value instanceof Map<?, ?> m) {
            if (!m.containsKey(key)) throw new NoSuchElementException("key not found: " + pyRepr(key));
            return m.get(key);
        }
        int i = asIndex(key);
        if (value instanceof List<?> xs) return xs.get(normalizeIndex(i, xs.size()));
        if (value instanceof PyTuple t) return t.items.get(normalizeIndex(i, t.items.size()));
        if (value instanceof String s) {
            int count = s.codePointCount(0, s.length());
            int p = normalizeIndex(i, count);
            int start = s.offsetByCodePoints(0, p);
            int end = s.offsetByCodePoints(start, 1);
            return s.substring(start, end);
        }
        throw typeError("object is not subscriptable", value);
    }

    @SuppressWarnings("unchecked")
    public static void setitem(Object value, Object key, Object item) {
        if (value instanceof Map<?, ?> m) { ((Map<Object, Object>)m).put(key, item); return; }
        int i = asIndex(key);
        if (value instanceof List<?> xs) { ((List<Object>)xs).set(normalizeIndex(i, xs.size()), item); return; }
        throw typeError("object does not support item assignment", value);
    }
    @SuppressWarnings("unchecked")
    public static void delitem(Object value, Object key) {
        if (value instanceof Map<?,?> m) { if(!m.containsKey(key)) throw new NoSuchElementException("key not found"); ((Map<Object,Object>)m).remove(key); return; }
        int i=asIndex(key);
        if (value instanceof List<?> xs) { ((List<Object>)xs).remove(normalizeIndex(i,xs.size())); return; }
        throw typeError("object does not support item deletion",value);
    }

    public static Object contains(Object container, Object needle) {
        if (container instanceof String s && needle instanceof String n) return s.contains(n);
        if (container instanceof Map<?, ?> m) return m.containsKey(needle);
        if (container instanceof Set<?> s) return s.contains(needle);
        if (container instanceof List<?> xs) return xs.contains(needle);
        if (container instanceof PyTuple t) return t.items.contains(needle);
        for (Object value : iterable(container)) if ((Boolean)eq(value, needle)) return true;
        return false;
    }

    private static int asIndex(Object value) {
        BigInteger n = bigInt(value);
        try { return n.intValueExact(); }
        catch (ArithmeticException exc) { throw new IndexOutOfBoundsException("cannot fit index into JVM list index"); }
    }

    private static int normalizeIndex(int index, int size) {
        int out = index < 0 ? index + size : index;
        if (out < 0 || out >= size) throw new IndexOutOfBoundsException("index out of range");
        return out;
    }

    public static Object slice(Object start, Object stop, Object step) { return new PySlice(start, stop, step); }
    private static Object sliceGet(Object value, PySlice sl) {
        int size;
        if (value instanceof List<?> xs) size = xs.size();
        else if (value instanceof PyTuple t) size = t.items.size();
        else if (value instanceof String str) size = str.codePointCount(0, str.length());
        else throw typeError("object is not sliceable", value);
        int step = sl.step == null ? 1 : asIndex(sl.step);
        if (step == 0) throw new IllegalArgumentException("slice step cannot be zero");
        int start = sl.start == null ? (step > 0 ? 0 : size - 1) : normalizeSliceIndex(asIndex(sl.start), size, step > 0);
        int stop = sl.stop == null ? (step > 0 ? size : -1) : normalizeSliceStop(asIndex(sl.stop), size, step > 0);
        if (value instanceof String str) {
            StringBuilder out = new StringBuilder();
            for (int i=start; step>0 ? i<stop : i>stop; i+=step) {
                if (i < 0 || i >= size) continue;
                int a=str.offsetByCodePoints(0,i), z=str.offsetByCodePoints(a,1); out.append(str, a, z);
            }
            return out.toString();
        }
        if (value instanceof PyTuple t) {
            PyTuple out = new PyTuple();
            for (int i=start; step>0 ? i<stop : i>stop; i+=step) if (i>=0 && i<size) out.items.add(t.items.get(i));
            return out;
        }
        ArrayList<Object> out = new ArrayList<>(); List<?> xs=(List<?>)value;
        for (int i=start; step>0 ? i<stop : i>stop; i+=step) if (i>=0 && i<size) out.add(xs.get(i));
        return out;
    }
    private static int normalizeSliceIndex(int i, int size, boolean positive) {
        if (i < 0) i += size; if (positive) return Math.max(0, Math.min(size, i)); return Math.max(-1, Math.min(size-1, i));
    }
    private static int normalizeSliceStop(int i, int size, boolean positive) {
        if (i < 0) i += size; if (positive) return Math.max(0, Math.min(size, i)); return Math.max(-1, Math.min(size-1, i));
    }
    public static final class PySlice {
        final Object start, stop, step; PySlice(Object start,Object stop,Object step){this.start=start;this.stop=stop;this.step=step;}
    }

    public static Object unpack(Object value, Object countObj) {
        int count = asIndex(countObj); ArrayList<Object> out = new ArrayList<>();
        for (Object x : iterable(value)) out.add(x);
        if (out.size() != count) throw new IllegalArgumentException("cannot unpack sequence of size " + out.size() + " into " + count + " values");
        return out;
    }
    public static Object unpackEx(Object value,Object beforeObj,Object afterObj) {
        int before=asIndex(beforeObj), after=asIndex(afterObj); ArrayList<Object> src=new ArrayList<>(); for(Object x:iterable(value)) src.add(x);
        if(src.size()<before+after) throw new PyException("ValueError","not enough values to unpack");
        ArrayList<Object> out=new ArrayList<>();
        for(int i=0;i<before;i++) out.add(src.get(i));
        out.add(new ArrayList<Object>(src.subList(before,src.size()-after)));
        for(int i=src.size()-after;i<src.size();i++) out.add(src.get(i));
        return out;
    }

    public static Object enumerate1(Object value) {
        ArrayList<Object> out = new ArrayList<>(); long i=0;
        for (Object x : iterable(value)) { PyTuple t=new PyTuple(); t.items.add(i++); t.items.add(x); out.add(t); }
        return out;
    }
    public static Object zip2(Object a, Object b) {
        ArrayList<Object> out = new ArrayList<>(); Iterator<?> ia=iterable(a).iterator(), ib=iterable(b).iterator();
        while (ia.hasNext() && ib.hasNext()) { PyTuple t=new PyTuple(); t.items.add(ia.next()); t.items.add(ib.next()); out.add(t); }
        return out;
    }
    public static Object sorted(Object value) {
        ArrayList<Object> out=new ArrayList<>(); for(Object x:iterable(value)) out.add(x);
        out.sort((a,b)->cmp(a,b)); return out;
    }
    public static Object reversed(Object value) {
        ArrayList<Object> out=new ArrayList<>(); for(Object x:iterable(value)) out.add(x); Collections.reverse(out); return out;
    }
    public static Object next_(Object iterator) {
        try { return ((Iterator<?>)iterator).next(); }
        catch(PyGeneratorEnd e) { throw new PyException("StopIteration",e.value); }
        catch(NoSuchElementException e) { throw new PyException("StopIteration",null); }
    }

    // ---------- Iteration / range ----------
    public static Object range1(Object stop) { return new PyRange(BigInteger.ZERO, bigInt(stop), BigInteger.ONE); }
    public static Object range2(Object start, Object stop) { return new PyRange(bigInt(start), bigInt(stop), BigInteger.ONE); }
    public static Object range3(Object start, Object stop, Object step) { return new PyRange(bigInt(start), bigInt(stop), bigInt(step)); }

    public static Object iter(Object value) { return iterable(value).iterator(); }
    public static boolean iterHasNext(Object iterator) { return ((Iterator<?>) iterator).hasNext(); }
    public static Object iterNext(Object iterator) { return ((Iterator<?>) iterator).next(); }

    private static Iterable<?> iterable(Object value) {
        if (value instanceof Iterable<?> x) return x;
        if (value instanceof Map<?, ?> m) return m.keySet();
        if (value instanceof String s) {
            ArrayList<String> chars = new ArrayList<>();
            s.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
            return chars;
        }
        throw typeError("object is not iterable", value);
    }

    public static final class PyDictView implements Iterable<Object> {
        final Map<Object,Object> map; final String kind;
        PyDictView(Map<Object,Object> map,String kind){this.map=map;this.kind=kind;}
        @Override public Iterator<Object> iterator() {
            if(kind.equals("keys")) return map.keySet().iterator();
            if(kind.equals("values")) return map.values().iterator();
            Iterator<Map.Entry<Object,Object>> base=map.entrySet().iterator();
            return new Iterator<>() {
                public boolean hasNext(){return base.hasNext();}
                public Object next(){var e=base.next();PyTuple t=new PyTuple();t.items.add(e.getKey());t.items.add(e.getValue());return t;}
            };
        }
        String pyRepr(){return "dict_"+kind+"("+seqRepr(this,"[","]")+")";}
    }

    public static final class PyTuple implements Iterable<Object> {
        private final ArrayList<Object> items = new ArrayList<>();
        @Override public Iterator<Object> iterator() { return items.iterator(); }
        public String pyRepr() {
            String body = seqRepr(items, "(", ")");
            if (items.size() == 1) return body.substring(0, body.length() - 1) + ",)";
            return body;
        }
        @Override public boolean equals(Object other) { return other instanceof PyTuple t && items.equals(t.items); }
        @Override public int hashCode() { return items.hashCode(); }
    }

    public static final class PyRange implements Iterable<Object> {
        private final BigInteger start, stop, step;
        PyRange(BigInteger start, BigInteger stop, BigInteger step) {
            if (step.signum() == 0) throw new IllegalArgumentException("range() arg 3 must not be zero");
            this.start = start; this.stop = stop; this.step = step;
        }
        BigInteger length() {
            if (step.signum() > 0) {
                if (start.compareTo(stop) >= 0) return BigInteger.ZERO;
                return stop.subtract(start).subtract(BigInteger.ONE).divide(step).add(BigInteger.ONE);
            }
            if (start.compareTo(stop) <= 0) return BigInteger.ZERO;
            BigInteger pos = step.negate();
            return start.subtract(stop).subtract(BigInteger.ONE).divide(pos).add(BigInteger.ONE);
        }
        @Override public Iterator<Object> iterator() {
            return new Iterator<>() {
                BigInteger current = start;
                @Override public boolean hasNext() { return step.signum() > 0 ? current.compareTo(stop) < 0 : current.compareTo(stop) > 0; }
                @Override public Object next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    BigInteger out = current;
                    current = current.add(step);
                    return compact(out);
                }
            };
        }
        @Override public String toString() { return "range(" + start + ", " + stop + ", " + step + ")"; }
    }


    // ---------- Lexical environments / closure cells ----------
    public static final class PyEnv {
        final PyEnv parent;
        final LinkedHashMap<String,Object> values = new LinkedHashMap<>();
        PyEnv(PyEnv parent) { this.parent = parent; }
    }

    public static Object envChild(Object parentObj) {
        return new PyEnv((PyEnv)parentObj);
    }

    public static void envSetLocal(Object envObj, Object nameObj, Object value) {
        ((PyEnv)envObj).values.put((String)nameObj, value);
    }

    public static void envSetNonlocal(Object envObj, Object nameObj, Object value) {
        PyEnv env = ((PyEnv)envObj).parent;
        String name = (String)nameObj;
        while (env != null) {
            if (env.values.containsKey(name)) { env.values.put(name, value); return; }
            env = env.parent;
        }
        throw new PyException("NameError", "no binding for nonlocal '" + name + "' found");
    }

    public static Object envGetLocal(Object envObj, Object nameObj) {
        PyEnv env=(PyEnv)envObj; String name=(String)nameObj;
        if (!env.values.containsKey(name)) throw new PyException("UnboundLocalError", "local variable '" + name + "' referenced before assignment");
        return env.values.get(name);
    }

    public static Object envGet(Object envObj, Object nameObj) {
        PyEnv env=(PyEnv)envObj; String name=(String)nameObj;
        while (env != null) {
            if (env.values.containsKey(name)) return env.values.get(name);
            env=env.parent;
        }
        throw new PyException("NameError", "free variable '" + name + "' is not defined");
    }


    // ---------- Generators ----------
    private static final Object GENERATOR_DONE = new Object();

    public static final class PyGenerator implements Iterator<Object>, Iterable<Object> {
        final String owner, resumeMethod;
        final PyEnv env;
        long state = 0;
        boolean finished = false;
        Object returnValue = null;
        Object sentValue = null;
        Object pendingException = null;
        boolean started = false;
        boolean buffered = false;
        Object bufferedValue = null;

        PyGenerator(String owner, String resumeMethod, PyEnv env) {
            this.owner=owner; this.resumeMethod=resumeMethod; this.env=env;
        }

        private Object advance() {
            if (finished) throw new PyGeneratorEnd(returnValue);
            started = true;
            try {
                Class<?> cls=Class.forName(owner);
                var reflected=cls.getDeclaredMethod(resumeMethod, Object.class);
                Object value=reflected.invoke(null, this);
                if (value == GENERATOR_DONE) { finished=true; throw new PyGeneratorEnd(returnValue); }
                return value;
            } catch (java.lang.reflect.InvocationTargetException exc) {
                Throwable cause=exc.getCause();
                if (cause instanceof PyGeneratorEnd e) { finished=true; throw e; }
                if (cause instanceof PyException p && p.typeName.equals("StopIteration")) {
                    finished=true;
                    throw new PyException("RuntimeError", "generator raised StopIteration");
                }
                if (cause instanceof RuntimeException r) { finished=true; throw r; }
                if (cause instanceof Error e) { finished=true; throw e; }
                throw new RuntimeException(cause);
            } catch (ReflectiveOperationException exc) {
                throw new RuntimeException(exc);
            }
        }

        @Override public boolean hasNext() {
            if (finished) return false;
            if (buffered) return true;
            try { bufferedValue=advance(); buffered=true; return true; }
            catch (PyGeneratorEnd e) { return false; }
        }

        @Override public Object next() {
            if (buffered) { Object v=bufferedValue; bufferedValue=null; buffered=false; return v; }
            return advance();
        }

        Object send(Object value) {
            if (!started && value != null) throw new PyException("TypeError", "can't send non-None value to a just-started generator");
            sentValue=value;
            try { return next(); } finally { sentValue=null; }
        }
        Object throw_(Object value) {
            if (finished) { raiseObject(value); return null; }
            if (!started) { finished=true; raiseObject(value); return null; }
            pendingException=value;
            try { return next(); } finally { pendingException=null; }
        }
        Object close() {
            if (finished) return null;
            if (!started) { finished=true; buffered=false; bufferedValue=null; return null; }
            pendingException=new PyExceptionValue("GeneratorExit",null);
            try {
                Object yielded=next();
                throw new PyException("RuntimeError","generator ignored GeneratorExit");
            } catch (PyGeneratorEnd end) { return null; }
              catch (PyException exc) { if(exc.typeName.equals("GeneratorExit")) { finished=true; return null; } throw exc; }
            finally { pendingException=null; buffered=false; bufferedValue=null; }
        }

        @Override public Iterator<Object> iterator() { return this; }
        @Override public String toString() { return "<generator object>"; }
    }

    private static final class PyGeneratorEnd extends NoSuchElementException {
        final Object value;
        PyGeneratorEnd(Object value) { this.value=value; }
    }

    public static Object makeGenerator(Object ownerObj, Object methodObj, Object envObj) {
        return new PyGenerator((String)ownerObj,(String)methodObj,(PyEnv)envObj);
    }
    public static Object generatorEnv(Object genObj) { return ((PyGenerator)genObj).env; }
    public static Object generatorState(Object genObj) { return ((PyGenerator)genObj).state; }
    public static Object generatorSentValue(Object genObj) { return ((PyGenerator)genObj).sentValue; }
    public static Object generatorResumeValue(Object genObj) {
        PyGenerator gen=(PyGenerator)genObj;
        if(gen.pendingException!=null) { Object exc=gen.pendingException; gen.pendingException=null; raiseObject(exc); }
        return gen.sentValue;
    }
    public static void generatorSetState(Object genObj, Object stateObj) { ((PyGenerator)genObj).state=bigInt(stateObj).longValueExact(); }
    public static Object generatorFinish(Object genObj, Object value) {
        PyGenerator gen=(PyGenerator)genObj; gen.returnValue=value; gen.finished=true; return GENERATOR_DONE;
    }

    public static final class PyYieldFromResult {
        final boolean done;
        final Object value;
        PyYieldFromResult(boolean done, Object value) { this.done=done; this.value=value; }
    }

    // One PEP-380 delegation step. A sent value is forwarded to a Python
    // generator delegate; ordinary JVM/Python iterators only accept None.
    public static Object yieldFromStep(Object iteratorObj, Object sentValue) {
        try {
            if (iteratorObj instanceof PyGenerator gen) {
                return new PyYieldFromResult(false, gen.send(sentValue));
            }
            if (sentValue != null) {
                throw new PyException("AttributeError", "iterator has no attribute 'send'");
            }
            return new PyYieldFromResult(false, ((Iterator<?>)iteratorObj).next());
        } catch (PyGeneratorEnd end) {
            return new PyYieldFromResult(true, end.value);
        } catch (NoSuchElementException end) {
            return new PyYieldFromResult(true, null);
        }
    }

    // Resume a PEP-380 delegation from the parent generator. Pending exceptions
    // are forwarded to a generator delegate's throw()/close(); otherwise the
    // parent's sent value is forwarded with send().
    public static Object yieldFromResumeStep(Object iteratorObj, Object parentObj) {
        PyGenerator parent=(PyGenerator)parentObj;
        Object pending=parent.pendingException;
        if(pending!=null) {
            parent.pendingException=null;
            if(iteratorObj instanceof PyGenerator gen) {
                if(exceptionObjectIs(pending,"GeneratorExit")) {
                    gen.close();
                    raiseObject(pending);
                    return null;
                }
                try {
                    return new PyYieldFromResult(false,gen.throw_(pending));
                } catch(PyGeneratorEnd end) {
                    return new PyYieldFromResult(true,end.value);
                }
            }
            raiseObject(pending);
            return null;
        }
        return yieldFromStep(iteratorObj,parent.sentValue);
    }

    private static boolean exceptionObjectIs(Object value,String name) {
        if(value instanceof PyExceptionValue e) return exceptionIsSubclass(e.typeName,name);
        if(value instanceof PyException e) return exceptionIsSubclass(e.typeName,name);
        return false;
    }
    public static boolean yieldFromDone(Object resultObj) { return ((PyYieldFromResult)resultObj).done; }
    public static Object yieldFromValue(Object resultObj) { return ((PyYieldFromResult)resultObj).value; }
    public static Object yieldFromReturnValue(Object iteratorObj) {
        if (iteratorObj instanceof PyGenerator gen) return gen.returnValue;
        return null;
    }

    // ---------- Python function objects / argument binding ----------
    public static Object makeFunction(Object ownerObj, Object methodObj, Object posonlyObj, Object poskwObj,
                                      Object kwonlyObj, Object varargObj, Object kwargObj, Object defaultsObj) {
        @SuppressWarnings("unchecked")
        Map<Object,Object> rawDefaults = (Map<Object,Object>) defaultsObj;
        LinkedHashMap<String,Object> defaults = new LinkedHashMap<>();
        for (var e : rawDefaults.entrySet()) defaults.put((String)e.getKey(), e.getValue());
        return new PyFunction(
            (String)ownerObj, (String)methodObj,
            splitNames((String)posonlyObj), splitNames((String)poskwObj), splitNames((String)kwonlyObj),
            (String)varargObj, (String)kwargObj, defaults
        );
    }

    public static Object makeFunctionEx(Object ownerObj, Object methodObj, Object posonlyObj, Object poskwObj,
                                        Object kwonlyObj, Object varargObj, Object kwargObj, Object defaultsObj,
                                        Object closureObj, Object envModeObj) {
        @SuppressWarnings("unchecked")
        Map<Object,Object> rawDefaults = (Map<Object,Object>) defaultsObj;
        LinkedHashMap<String,Object> defaults = new LinkedHashMap<>();
        for (var e : rawDefaults.entrySet()) defaults.put((String)e.getKey(), e.getValue());
        return new PyFunction(
            (String)ownerObj, (String)methodObj,
            splitNames((String)posonlyObj), splitNames((String)poskwObj), splitNames((String)kwonlyObj),
            (String)varargObj, (String)kwargObj, defaults, (PyEnv)closureObj, (Boolean)envModeObj
        );
    }

    private static List<String> splitNames(String csv) {
        if (csv == null || csv.isEmpty()) return List.of();
        return Arrays.asList(csv.split(",", -1));
    }

    public static Object callFunction(Object callable, Object argsObj, Object kwargsObj) {
        @SuppressWarnings("unchecked") List<Object> args = (List<Object>) argsObj;
        @SuppressWarnings("unchecked") Map<Object,Object> kwargs = (Map<Object,Object>) kwargsObj;
        if (callable instanceof PyFunction f) return f.call(args, kwargs);
        if (callable instanceof BoundMethod bm) {
            if (!kwargs.isEmpty()) throw new PyException("TypeError", "method keyword arguments are not implemented yet");
            return callMethod(bm.self, bm.name, args.toArray());
        }
        if (callable instanceof PyClass cls) {
            if (!kwargs.isEmpty()) throw new PyException("TypeError", "class keyword arguments are not implemented yet");
            return instantiate(cls, args.toArray());
        }
        throw new PyException("TypeError", "object is not callable");
    }

    public static final class PyFunction {
        final String owner, method;
        final List<String> posonly, poskw, kwonly;
        final String vararg, kwarg;
        final LinkedHashMap<String,Object> defaults;
        final PyEnv closure;
        final boolean envMode;

        PyFunction(String owner, String method, List<String> posonly, List<String> poskw, List<String> kwonly,
                   String vararg, String kwarg, LinkedHashMap<String,Object> defaults) {
            this(owner, method, posonly, poskw, kwonly, vararg, kwarg, defaults, null, false);
        }

        PyFunction(String owner, String method, List<String> posonly, List<String> poskw, List<String> kwonly,
                   String vararg, String kwarg, LinkedHashMap<String,Object> defaults, PyEnv closure, boolean envMode) {
            this.owner=owner; this.method=method; this.posonly=posonly; this.poskw=poskw; this.kwonly=kwonly;
            this.vararg=vararg; this.kwarg=kwarg; this.defaults=defaults; this.closure=closure; this.envMode=envMode;
        }

        Object call(List<Object> args, Map<Object,Object> kwargsRaw) {
            LinkedHashMap<String,Object> assigned = new LinkedHashMap<>();
            ArrayList<Object> extraPos = new ArrayList<>();
            LinkedHashMap<String,Object> extraKw = new LinkedHashMap<>();
            List<String> positional = new ArrayList<>();
            positional.addAll(posonly); positional.addAll(poskw);

            int i=0;
            for (Object arg : args) {
                if (i < positional.size()) assigned.put(positional.get(i++), arg);
                else extraPos.add(arg);
            }
            if (!extraPos.isEmpty() && vararg == null)
                throw new PyException("TypeError", method + "() takes " + positional.size() + " positional arguments but more were given");

            for (var e : kwargsRaw.entrySet()) {
                if (!(e.getKey() instanceof String key)) throw new PyException("TypeError", "keywords must be strings");
                Object value=e.getValue();
                if (posonly.contains(key)) {
                    if (kwarg != null) extraKw.put(key, value);
                    else throw new PyException("TypeError", method + "() got some positional-only arguments passed as keyword arguments: '" + key + "'");
                } else if (poskw.contains(key) || kwonly.contains(key)) {
                    if (assigned.containsKey(key)) throw new PyException("TypeError", method + "() got multiple values for argument '" + key + "'");
                    assigned.put(key, value);
                } else if (kwarg != null) {
                    extraKw.put(key, value);
                } else {
                    throw new PyException("TypeError", method + "() got an unexpected keyword argument '" + key + "'");
                }
            }

            for (String name : positional) {
                if (!assigned.containsKey(name)) {
                    if (defaults.containsKey(name)) assigned.put(name, defaults.get(name));
                    else throw new PyException("TypeError", method + "() missing required positional argument: '" + name + "'");
                }
            }
            for (String name : kwonly) {
                if (!assigned.containsKey(name)) {
                    if (defaults.containsKey(name)) assigned.put(name, defaults.get(name));
                    else throw new PyException("TypeError", method + "() missing required keyword-only argument: '" + name + "'");
                }
            }

            ArrayList<Object> bound = new ArrayList<>();
            for (String name : positional) bound.add(assigned.get(name));
            for (String name : kwonly) bound.add(assigned.get(name));
            if (vararg != null) { PyTuple tuple=new PyTuple(); tuple.items.addAll(extraPos); bound.add(tuple); }
            if (kwarg != null) bound.add(new LinkedHashMap<Object,Object>(extraKw));
            return invokeStatic(bound.toArray());
        }

        private Object invokeStatic(Object[] bound) {
            try {
                Class<?> cls=Class.forName(owner);
                int hidden = envMode ? 1 : 0;
                Class<?>[] types=new Class<?>[bound.length + hidden]; Arrays.fill(types, Object.class);
                var reflected=cls.getDeclaredMethod(method, types);
                Object[] actual;
                if (envMode) {
                    actual = new Object[bound.length + 1];
                    actual[0] = closure;
                    System.arraycopy(bound, 0, actual, 1, bound.length);
                } else actual = bound;
                return reflected.invoke(null, actual);
            } catch (java.lang.reflect.InvocationTargetException exc) {
                Throwable cause=exc.getCause();
                if (cause instanceof RuntimeException r) throw r;
                if (cause instanceof Error e) throw e;
                throw new RuntimeException(cause);
            } catch (ReflectiveOperationException exc) {
                throw new RuntimeException(exc);
            }
        }

        @Override public String toString() { return "<function " + method + ">"; }
    }


    // ---------- Context managers ----------
    public static Object withEnter(Object manager) {
        return callMethod(manager, "__enter__", new Object[0]);
    }
    public static void withExitNormal(Object manager) {
        callMethod(manager, "__exit__", new Object[]{null, null, null});
    }
    public static boolean withExitException(Object manager, Object throwableObj) {
        Throwable t=(Throwable)throwableObj;
        Object result=callMethod(manager, "__exit__", new Object[]{new PyExceptionType(pythonExceptionType(t)), exceptionValue(t), null});
        return truth(result);
    }

    // ---------- Exceptions ----------
    public static Object makeException(Object typeObj, Object value) {
        return new PyExceptionValue((String)typeObj, value);
    }

    public static void raiseObject(Object value) {
        if (value instanceof PyExceptionValue e) throw new PyException(e.typeName, e.value, e.cause, e.context, e.suppressContext);
        if (value instanceof PyException e) throw e;
        throw new PyException("TypeError", "exceptions must derive from BaseException");
    }
    public static void rethrowThrowable(Object value) {
        if (value instanceof RuntimeException r) throw r;
        if (value instanceof Error e) throw e;
        if (value instanceof Throwable t) throw new RuntimeException(t);
        throw new PyException("RuntimeError", "no active exception to reraise");
    }
    public static void raiseObjectWithContext(Object value, Object contextObj) {
        PyException raised;
        if (value instanceof PyExceptionValue e) raised=new PyException(e.typeName,e.value,e.cause,e.context,e.suppressContext);
        else if (value instanceof PyException e) raised=e;
        else throw new PyException("TypeError", "exceptions must derive from BaseException");
        if(!raised.suppressContext && contextObj instanceof Throwable t) {
            raised.context=exceptionInstance(t);
        }
        throw raised;
    }
    public static void raiseObjectFrom(Object value, Object causeObj) {
        PyException raised;
        if(value instanceof PyExceptionValue e) raised=new PyException(e.typeName,e.value);
        else if(value instanceof PyException e) raised=e;
        else throw new PyException("TypeError","exceptions must derive from BaseException");
        if(causeObj == null) { raised.cause=null; raised.suppressContext=true; throw raised; }
        if(causeObj instanceof PyExceptionValue e) raised.cause=e;
        else if(causeObj instanceof PyException e) raised.cause=new PyExceptionValue(e.typeName,e.value);
        else throw new PyException("TypeError","exception causes must derive from BaseException");
        raised.suppressContext=true;
        throw raised;
    }

    public static boolean exceptionMatches(Object throwableObj, Object typeObj) {
        Throwable t = (Throwable)throwableObj;
        String requested = typeObj instanceof PyBuiltinType bt ? bt.name : (String)typeObj;
        String actual = pythonExceptionType(t);
        return exceptionIsSubclass(actual, requested);
    }

    private static boolean exceptionIsSubclass(String actual, String requested) {
        if (actual.equals(requested)) return true;
        if (requested.equals("BaseException")) return true;
        if (requested.equals("Exception")) return !actual.equals("KeyboardInterrupt") && !actual.equals("SystemExit") && !actual.equals("GeneratorExit");
        if (requested.equals("ArithmeticError")) return Set.of("ArithmeticError","ZeroDivisionError","OverflowError","FloatingPointError").contains(actual);
        if (requested.equals("LookupError")) return Set.of("LookupError","IndexError","KeyError").contains(actual);
        if (requested.equals("NameError")) return actual.equals("UnboundLocalError");
        if (requested.equals("OSError")) return Set.of("FileNotFoundError","PermissionError","TimeoutError","ConnectionError","BlockingIOError","ChildProcessError","InterruptedError","IsADirectoryError","NotADirectoryError","ProcessLookupError").contains(actual);
        return false;
    }

    public static Object exceptionValue(Object throwableObj) {
        Throwable t = (Throwable)throwableObj;
        if (t instanceof PyException p) return p.value;
        String msg = t.getMessage();
        return msg == null ? "" : msg;
    }
    public static Object exceptionInstance(Object throwableObj) {
        Throwable t=(Throwable)throwableObj;
        if (t instanceof PyException p) return new PyExceptionValue(p.typeName,p.value,p.cause,p.context,p.suppressContext);
        return new PyExceptionValue(pythonExceptionType(t), exceptionValue(t));
    }

    private static String pythonExceptionType(Throwable t) {
        if (t instanceof PyException p) return p.typeName;
        if (t instanceof ArithmeticException) return "ZeroDivisionError";
        if (t instanceof IndexOutOfBoundsException) return "IndexError";
        if (t instanceof NoSuchElementException) return "KeyError";
        if (t instanceof IllegalArgumentException || t instanceof ClassCastException) return "TypeError";
        if (t instanceof AssertionError) return "AssertionError";
        if (t instanceof NoSuchElementException) return "KeyError";
        return "RuntimeError";
    }

    public static final class PyExceptionType {
        final String name;
        PyExceptionType(String name) { this.name=name; }
        @Override public String toString() { return "<class '" + name + "'>"; }
    }

    public static final class PyExceptionValue {
        final String typeName; final Object value; final Object cause; final Object context; final boolean suppressContext;
        PyExceptionValue(String typeName, Object value) { this(typeName,value,null,null,false); }
        PyExceptionValue(String typeName, Object value, Object cause, Object context, boolean suppressContext) { this.typeName=typeName; this.value=value; this.cause=cause; this.context=context; this.suppressContext=suppressContext; }
        @Override public String toString() { return typeName + (value == null ? "" : "(" + pyRepr(value) + ")"); }
    }

    public static final class PyException extends RuntimeException {
        final String typeName; final Object value; Object cause; Object context; boolean suppressContext;
        PyException(String typeName, Object value) { this(typeName,value,null,null,false); }
        PyException(String typeName, Object value, Object cause, Object context, boolean suppressContext) { super(value == null ? null : pyStr(value)); this.typeName=typeName; this.value=value; this.cause=cause; this.context=context; this.suppressContext=suppressContext; }
        @Override public String toString() { return typeName + (value == null ? "" : ": " + pyStr(value)); }
    }

    // ---------- Python classes / instances ----------
    public static Object class0(Object name) { return new PyClass("__main__", (String) name); }
    public static void classAddBase(Object cls, Object base) { ((PyClass)cls).bases.add((PyClass)base); }
    public static void classAddMethod(Object cls, Object pyName, Object owner, Object javaName) {
        ((PyClass)cls).methods.put((String)pyName, new PyMethod((String)owner, (String)javaName));
    }
    public static void classFinalize(Object cls) { ((PyClass)cls).computeMro(); }

    public static Object instantiate0(Object cls) { return instantiate((PyClass)cls, new Object[0]); }
    public static Object instantiate1(Object cls, Object a) { return instantiate((PyClass)cls, new Object[]{a}); }
    public static Object instantiate2(Object cls, Object a, Object b) { return instantiate((PyClass)cls, new Object[]{a,b}); }
    public static Object instantiate3(Object cls, Object a, Object b, Object c) { return instantiate((PyClass)cls, new Object[]{a,b,c}); }

    private static Object instantiate(PyClass cls, Object[] args) {
        PyInstance instance = new PyInstance(cls);
        PyMethod init = cls.lookup("__init__");
        if (init != null) invoke(instance, init, args);
        else if (args.length != 0) throw new IllegalArgumentException(cls.name + "() takes no arguments");
        return instance;
    }

    private static void requireArgs(String name,Object[] args,int count) { if(args.length!=count) throw new PyException("TypeError",name+"() takes "+count+" arguments"); }

    public static Object callMethod0(Object obj, Object name) { return callMethod(obj, (String)name, new Object[0]); }
    public static Object callMethod1(Object obj, Object name, Object a) { return callMethod(obj, (String)name, new Object[]{a}); }
    public static Object callMethod2(Object obj, Object name, Object a, Object b) { return callMethod(obj, (String)name, new Object[]{a,b}); }
    public static Object callMethod3(Object obj, Object name, Object a, Object b, Object c) { return callMethod(obj, (String)name, new Object[]{a,b,c}); }

    private static Object callMethod(Object obj, String name, Object[] args) {
        if (obj instanceof PyGenerator gen) {
            return switch(name) {
                case "send" -> { requireArgs(name,args,1); yield gen.send(args[0]); }
                case "throw" -> { requireArgs(name,args,1); yield gen.throw_(args[0]); }
                case "close" -> { requireArgs(name,args,0); yield gen.close(); }
                case "__iter__" -> { requireArgs(name,args,0); yield gen; }
                case "__next__" -> { requireArgs(name,args,0); yield next_(gen); }
                default -> throw new PyException("AttributeError","'generator' object has no attribute '"+name+"'");
            };
        }
        if (obj instanceof PyModule module) {
            Object callable=moduleGetattr(module,name);
            ArrayList<Object> positional=new ArrayList<>(Arrays.asList(args));
            return callFunction(callable,positional,new LinkedHashMap<Object,Object>());
        }
        if (obj instanceof String str) {
            return switch(name) {
                case "upper" -> { requireArgs(name,args,0); yield str.toUpperCase(Locale.ROOT); }
                case "lower" -> { requireArgs(name,args,0); yield str.toLowerCase(Locale.ROOT); }
                case "strip" -> { requireArgs(name,args,0); yield str.strip(); }
                case "lstrip" -> { requireArgs(name,args,0); yield str.stripLeading(); }
                case "rstrip" -> { requireArgs(name,args,0); yield str.stripTrailing(); }
                case "startswith" -> { requireArgs(name,args,1); yield str.startsWith((String)args[0]); }
                case "endswith" -> { requireArgs(name,args,1); yield str.endsWith((String)args[0]); }
                case "replace" -> { requireArgs(name,args,2); yield str.replace((String)args[0],(String)args[1]); }
                case "split" -> {
                    if(args.length==0) yield new ArrayList<Object>(Arrays.asList(str.trim().isEmpty()?new String[0]:str.trim().split("\\s+")));
                    requireArgs(name,args,1); yield new ArrayList<Object>(Arrays.asList(str.split(java.util.regex.Pattern.quote((String)args[0]),-1)));
                }
                case "join" -> { requireArgs(name,args,1); ArrayList<String> parts=new ArrayList<>(); for(Object x:iterable(args[0])) parts.add((String)x); yield String.join(str,parts); }
                case "find" -> { requireArgs(name,args,1); yield (long)str.indexOf((String)args[0]); }
                case "count" -> { requireArgs(name,args,1); String sub=(String)args[0]; if(sub.isEmpty()) yield (long)(str.length()+1); long n=0; for(int i=0;(i=str.indexOf(sub,i))>=0;i+=sub.length())n++; yield n; }
                default -> throw new PyException("AttributeError","'str' object has no attribute '"+name+"'");
            };
        }
        if (obj instanceof List<?> raw) {
            @SuppressWarnings("unchecked") List<Object> list=(List<Object>)raw;
            return switch(name) {
                case "append" -> { requireArgs(name,args,1); list.add(args[0]); yield null; }
                case "extend" -> { requireArgs(name,args,1); for(Object x:iterable(args[0])) list.add(x); yield null; }
                case "pop" -> { if(args.length>1) throw new PyException("TypeError","pop expected at most 1 argument"); int i=args.length==0?list.size()-1:normalizeIndex(asIndex(args[0]),list.size()); yield list.remove(i); }
                case "clear" -> { requireArgs(name,args,0); list.clear(); yield null; }
                case "copy" -> { requireArgs(name,args,0); yield new ArrayList<Object>(list); }
                case "count" -> { requireArgs(name,args,1); long n=0; for(Object x:list) if((Boolean)eq(x,args[0])) n++; yield n; }
                case "index" -> { requireArgs(name,args,1); int i=list.indexOf(args[0]); if(i<0) throw new PyException("ValueError","value is not in list"); yield (long)i; }
                case "reverse" -> { requireArgs(name,args,0); Collections.reverse(list); yield null; }
                case "sort" -> { requireArgs(name,args,0); list.sort((a,b)->cmp(a,b)); yield null; }
                default -> throw new PyException("AttributeError","'list' object has no attribute '"+name+"'");
            };
        }
        if (obj instanceof Map<?,?> raw) {
            @SuppressWarnings("unchecked") Map<Object,Object> map=(Map<Object,Object>)raw;
            return switch(name) {
                case "get" -> { if(args.length<1||args.length>2) throw new PyException("TypeError","get expected 1 or 2 arguments"); yield map.getOrDefault(args[0],args.length==2?args[1]:null); }
                case "keys" -> { requireArgs(name,args,0); yield new PyDictView(map, "keys"); }
                case "values" -> { requireArgs(name,args,0); yield new PyDictView(map, "values"); }
                case "items" -> { requireArgs(name,args,0); yield new PyDictView(map, "items"); }
                case "update" -> { requireArgs(name,args,1); dictUpdate(map,args[0]); yield null; }
                case "pop" -> { if(args.length<1||args.length>2) throw new PyException("TypeError","pop expected 1 or 2 arguments"); if(map.containsKey(args[0])) yield map.remove(args[0]); if(args.length==2) yield args[1]; throw new PyException("KeyError",args[0]); }
                default -> throw new PyException("AttributeError","'dict' object has no attribute '"+name+"'");
            };
        }
        if (obj instanceof Set<?> raw) {
            @SuppressWarnings("unchecked") Set<Object> set=(Set<Object>)raw;
            return switch(name) {
                case "add" -> { requireArgs(name,args,1); set.add(args[0]); yield null; }
                case "discard" -> { requireArgs(name,args,1); set.remove(args[0]); yield null; }
                case "remove" -> { requireArgs(name,args,1); if(!set.remove(args[0])) throw new PyException("KeyError",args[0]); yield null; }
                case "clear" -> { requireArgs(name,args,0); set.clear(); yield null; }
                case "copy" -> { requireArgs(name,args,0); yield new LinkedHashSet<Object>(set); }
                default -> throw new PyException("AttributeError","'set' object has no attribute '"+name+"'");
            };
        }
        if (!(obj instanceof PyInstance instance)) throw typeError("method call on non-instance", obj);
        PyMethod method = instance.cls.lookup(name);
        if (method == null) throw new IllegalArgumentException("'" + instance.cls.name + "' object has no method '" + name + "'");
        return invoke(instance, method, args);
    }

    private static Object invoke(PyInstance self, PyMethod method, Object[] args) {
        try {
            Class<?> owner = Class.forName(method.owner);
            Class<?>[] types = new Class<?>[args.length + 1];
            Arrays.fill(types, Object.class);
            var reflected = owner.getDeclaredMethod(method.javaName, types);
            Object[] actual = new Object[args.length + 1];
            actual[0] = self;
            System.arraycopy(args, 0, actual, 1, args.length);
            return reflected.invoke(null, actual);
        } catch (java.lang.reflect.InvocationTargetException exc) {
            Throwable cause = exc.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error e) throw e;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException exc) {
            throw new RuntimeException(exc);
        }
    }

    public static Object getattr(Object obj, Object nameObj) {
        String name = (String)nameObj;
        if (obj instanceof PyModule) return moduleGetattr(obj,nameObj);
        if (obj instanceof PyExceptionValue exc) {
            if (name.equals("args")) { PyTuple t=new PyTuple(); if(exc.value!=null)t.items.add(exc.value); return t; }
            if (name.equals("value") && exc.typeName.equals("StopIteration")) return exc.value;
            if (name.equals("__cause__")) return exc.cause;
            if (name.equals("__context__")) return exc.context;
            if (name.equals("__suppress_context__")) return exc.suppressContext;
            throw new PyException("AttributeError", "'"+exc.typeName+"' object has no attribute '"+name+"'");
        }
        if (obj instanceof PyInstance instance) {
            if (instance.fields.containsKey(name)) return instance.fields.get(name);
            if (instance.cls.lookup(name) != null) return new BoundMethod(instance, name);
            throw new IllegalArgumentException("'" + instance.cls.name + "' object has no attribute '" + name + "'");
        }
        throw typeError("attribute access on unsupported object", obj);
    }

    public static Object getattrDefault(Object obj,Object nameObj,Object defaultValue) {
        try { return getattr(obj,nameObj); } catch(RuntimeException e) { return defaultValue; }
    }
    public static Object hasattr(Object obj,Object nameObj) {
        try { getattr(obj,nameObj); return true; } catch(RuntimeException e) { return false; }
    }

    public static void setattr(Object obj, Object nameObj, Object value) {
        if (obj instanceof PyInstance instance) { instance.fields.put((String)nameObj, value); return; }
        throw typeError("attribute assignment on unsupported object", obj);
    }
    public static void delattr(Object obj,Object nameObj) {
        if(obj instanceof PyInstance instance) { String name=(String)nameObj; if(!instance.fields.containsKey(name)) throw new PyException("AttributeError","attribute not found"); instance.fields.remove(name); return; }
        throw typeError("attribute deletion on unsupported object",obj);
    }

    public static Object isInstance(Object obj, Object clsObj) {
        if (clsObj instanceof PyTuple tuple) {
            for(Object c:tuple.items) if((Boolean)isInstance(obj,c)) return true;
            return false;
        }
        if (clsObj instanceof PyClass cls) return obj instanceof PyInstance instance && instance.cls.mro.contains(cls);
        if (clsObj instanceof PyBuiltinType bt) {
            return switch(bt.name) {
                case "object" -> true;
                case "int" -> isIntLike(obj);
                case "bool" -> obj instanceof Boolean;
                case "float" -> obj instanceof Double;
                case "str" -> obj instanceof String;
                case "list" -> obj instanceof List<?>;
                case "tuple" -> obj instanceof PyTuple;
                case "dict" -> obj instanceof Map<?,?>;
                case "set" -> obj instanceof Set<?>;
                case "range" -> obj instanceof PyRange;
                case "type" -> obj instanceof PyClass || obj instanceof PyBuiltinType;
                case "BaseException", "Exception" -> obj instanceof PyExceptionValue || obj instanceof PyException;
                default -> false;
            };
        }
        throw new PyException("TypeError","isinstance() arg 2 must be a type or tuple of types");
    }

    public static Object isSubclass(Object subObj,Object clsObj) {
        if(clsObj instanceof PyTuple tuple){for(Object c:tuple.items) if((Boolean)isSubclass(subObj,c)) return true; return false;}
        if(subObj instanceof PyBuiltinType a && clsObj instanceof PyBuiltinType b){
            if(a.name.equals(b.name)||b.name.equals("object")) return true;
            if(a.name.equals("bool")&&b.name.equals("int")) return true;
            return false;
        }
        if(subObj instanceof PyClass a && clsObj instanceof PyClass b) return a.mro.contains(b);
        throw new PyException("TypeError","issubclass() arg 1 must be a class");
    }

    private record PyMethod(String owner, String javaName) {}
    private record BoundMethod(PyInstance self, String name) {}

    public static final class PyInstance {
        final PyClass cls;
        final LinkedHashMap<String,Object> fields = new LinkedHashMap<>();
        PyInstance(PyClass cls) { this.cls = cls; }
        @Override public String toString() { return "<" + cls.name + " object>"; }
    }

    public static final class PyClass {
        final String moduleName;
        final String name;
        final ArrayList<PyClass> bases = new ArrayList<>();
        final LinkedHashMap<String,PyMethod> methods = new LinkedHashMap<>();
        List<PyClass> mro = List.of(this);
        PyClass(String moduleName, String name) { this.moduleName=moduleName; this.name = name; }

        PyMethod lookup(String name) {
            for (PyClass c : mro) {
                PyMethod m = c.methods.get(name);
                if (m != null) return m;
            }
            return null;
        }

        void computeMro() {
            if (bases.isEmpty()) { mro = List.of(this); return; }
            ArrayList<List<PyClass>> seqs = new ArrayList<>();
            for (PyClass base : bases) seqs.add(new ArrayList<>(base.mro));
            seqs.add(new ArrayList<>(bases));
            ArrayList<PyClass> out = new ArrayList<>();
            out.add(this);
            while (true) {
                seqs.removeIf(List::isEmpty);
                if (seqs.isEmpty()) break;
                PyClass candidate = null;
                outer:
                for (List<PyClass> seq : seqs) {
                    PyClass head = seq.get(0);
                    boolean inTail = false;
                    for (List<PyClass> other : seqs) {
                        if (other.size() > 1 && other.subList(1, other.size()).contains(head)) { inTail = true; break; }
                    }
                    if (!inTail) { candidate = head; break outer; }
                }
                if (candidate == null) throw new IllegalArgumentException("inconsistent method resolution order for class " + name);
                out.add(candidate);
                for (List<PyClass> seq : seqs) if (!seq.isEmpty() && seq.get(0) == candidate) seq.remove(0);
            }
            mro = List.copyOf(out);
        }
        @Override public String toString() { return "<class '" + moduleName + "." + name + "'>"; }
    }

}
