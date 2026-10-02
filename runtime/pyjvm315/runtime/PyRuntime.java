package pyjvm315.runtime;

import java.math.BigInteger;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
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
        if (value instanceof PyComplex) return "complex";
        if (value instanceof Boolean) return "bool";
        if (value instanceof String) return "str";
        if (value instanceof PyBytes) return "bytes";
        if (value instanceof PyByteArray) return "bytearray";
        if (value instanceof PyMemoryView) return "memoryview";
        if (value instanceof PyTuple) return "tuple";
        if (value instanceof List) return "list";
        if (value instanceof Map) return "dict";
        if (value instanceof Set) return "set";
        if (value instanceof PyRange) return "range";
        if (value instanceof PyFunction) return "function";
        if (value instanceof PyBuiltinFunction) return "builtin_function_or_method";
        if (value instanceof BuiltinBoundMethod) return "builtin_function_or_method";
        if (value instanceof PyMap) return "map";
        if(value instanceof PySlice)return "slice";
        if(value==NOT_IMPLEMENTED)return "NotImplementedType";
        if(value==ELLIPSIS)return "ellipsis";
        if (value instanceof PyGenerator) return "generator";
        if (value instanceof PyCoroutine) return "coroutine";
        if (value instanceof PyAsyncGenerator) return "async_generator";
        if (value instanceof PyClass) return "type";
        if (value instanceof PyInstance i) return i.cls.name;
        if (value instanceof PyDictView) return "dict_view";
        if (value instanceof PyModule) return "module";
        if (value instanceof PySuper) return "super";
        if (value instanceof PyExceptionValue e) return e.typeName;
        return value.getClass().getSimpleName();
    }

    // ---------- Python module loading ----------
    public static final class PyCompiledLoader {
        final String name,path;
        PyCompiledLoader(String name,String path){this.name=name;this.path=path;}
        @Override public String toString(){return "<pyjvm315 loader for '"+name+"'>";}
    }
    public static final class PyModuleSpec {
        final String name,origin,parent;
        final Object loader;
        final Object submoduleSearchLocations;
        PyModuleSpec(String name,String origin,String parent,boolean isPackage,Object loader){
            this.name=name;this.origin=origin;this.parent=parent;this.loader=loader;
            this.submoduleSearchLocations=isPackage?new ArrayList<Object>():null;
        }
        @Override public String toString(){return "ModuleSpec(name='"+name+"', origin='"+origin+"')";}
    }
    public static Object makeModuleLoader(Object nameObj,Object pathObj){
        return new PyCompiledLoader((String)nameObj,(String)pathObj);
    }
    public static Object makeModuleSpec(Object nameObj,Object originObj,Object parentObj,Object packageObj,Object loaderObj){
        return new PyModuleSpec((String)nameObj,(String)originObj,(String)parentObj,(Boolean)packageObj,loaderObj);
    }

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
    private static Object moduleDict(PyModule module) {
        LinkedHashMap<Object,Object> out=new LinkedHashMap<>();
        out.putAll(module.attrs);
        try {
            Class<?> cls=Class.forName(module.className);
            for(var field:cls.getFields()) {
                String fieldName=field.getName(), pyName=null;
                if(fieldName.startsWith("__py_global_")) pyName=fieldName.substring("__py_global_".length());
                else if(fieldName.startsWith("__py_function_")) pyName=fieldName.substring("__py_function_".length());
                else if(fieldName.startsWith("__py_class_")) pyName=fieldName.substring("__py_class_".length());
                if(pyName!=null) {Object value=field.get(null);if(value!=UNBOUND)out.put(pyName,value);}
            }
        } catch(ReflectiveOperationException exc) { throw new RuntimeException(exc); }
        return out;
    }
    public static Object moduleGetattr(Object moduleObj,Object nameObj) {
        PyModule module=(PyModule)moduleObj; String name=(String)nameObj;
        if(name.equals("__dict__")) return moduleDict(module);
        if(module.attrs.containsKey(name)) return module.attrs.get(name);
        String[] fields={"__py_global_"+name,"__py_function_"+name,"__py_class_"+name};
        try {
            Class<?> cls=Class.forName(module.className);
            for(String f:fields) {
                try { Object value=cls.getField(f).get(null);if(value!=UNBOUND)return value;break; } catch(NoSuchFieldException ignored) {}
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
    private record PyBuiltinFunction(String name) {}
    private record BuiltinBoundMethod(Object self,String name) {}
    public static Object builtinFunction(Object name) { return new PyBuiltinFunction((String)name); }
    private static Object callBuiltin(String name,List<Object> args,Map<Object,Object> kwargs) {
        if(name.equals("print")) {
            for(Object key:kwargs.keySet()) if(!key.equals("sep") && !key.equals("end"))
                throw new PyException("TypeError","unsupported print keyword");
            return printArgs(args,kwargs.get("sep"),kwargs.get("end"));
        }
        if(!kwargs.isEmpty()) throw new PyException("TypeError",name+"() does not accept keyword arguments");
        if(name.equals("map")) {
            if(args.size()<2) throw new PyException("TypeError","map() must have at least two arguments");
            return new PyMap(args.get(0),args.subList(1,args.size()));
        }
        int n=args.size();
        if(name.equals("next") && (n==1 || n==2)) {
            try { return next_(args.get(0)); }
            catch(PyException e) { if(n==2 && exceptionIsSubclass(e.typeName,"StopIteration")) return args.get(1); throw e; }
        }
        if(name.equals("iter") && (n==1 || n==2)) return n==1?iter(args.get(0)):callableIterator(args.get(0),args.get(1));
        if(name.equals("getattr") && (n==2 || n==3)) return n==2?getattr(args.get(0),args.get(1)):getattrDefault(args.get(0),args.get(1),args.get(2));
        if(name.equals("hasattr") && n==2) return hasattr(args.get(0),args.get(1));
        if(name.equals("setattr") && n==3) { setattr(args.get(0),args.get(1),args.get(2)); return null; }
        if(name.equals("delattr") && n==2) { delattr(args.get(0),args.get(1)); return null; }
        if(name.equals("isinstance") && n==2) return isInstance(args.get(0),args.get(1));
        if(name.equals("issubclass") && n==2) return isSubclass(args.get(0),args.get(1));
        if(name.equals("pow") && (n==2 || n==3)) return n==2?pow(args.get(0),args.get(1)):powMod(args.get(0),args.get(1),args.get(2));
        if(name.equals("sum") && (n==1 || n==2)) {
            Object total=n==1?0L:args.get(1);
            if(total instanceof String || total instanceof PyByteSequence) throw new PyException("TypeError","sum() cannot sum strings or bytes");
            for(Object item:iterable(args.get(0))) total=add(total,item);
            return total;
        }
        if(n!=1) throw new PyException("TypeError","invalid arguments to "+name+"()");
        Object value=args.get(0);
        return switch(name) {
            case "ord" -> ord(value);
            case "chr" -> chr(value);
            case "repr" -> repr_(value);
            case "hash" -> hash(value);
            case "id" -> id(value);
            case "len" -> len(value);
            case "reversed" -> reversed(value);
            case "callable" -> callable_(value);
            case "abs" -> abs(value);
            case "any" -> any(value);
            case "all" -> all(value);
            default -> throw new PyException("TypeError","invalid arguments to "+name+"()");
        };
    }

    private static Object callableIterator(Object function,Object sentinel) {
        if(!truth(callable_(function)) && !(function instanceof PyInstance i && i.cls.lookupMethod("__call__")!=null))
            throw new PyException("TypeError","iter(v, w): v must be callable");
        return new Iterator<Object>() {
            boolean done=false, buffered=false; Object value;
            public boolean hasNext() {
                if(done) return false;
                if(buffered) return true;
                try { value=callFunction(function,new ArrayList<>(),new LinkedHashMap<>()); }
                catch(PyException e) { if(exceptionIsSubclass(e.typeName,"StopIteration")){done=true;return false;}throw e; }
                if(truth(eq(value,sentinel))){done=true;return false;}
                buffered=true;return true;
            }
            public Object next() { if(!hasNext())throw new NoSuchElementException();buffered=false;return value; }
        };
    }

    private static Object powMod(Object base,Object exponent,Object modulus) {
        if(modulus==null)return pow(base,exponent);
        if(base instanceof PyComplex || exponent instanceof PyComplex)throw new PyException("ValueError","complex modulo");
        if(!isIntLike(base)||!isIntLike(exponent)||!isIntLike(modulus)) throw new PyException("TypeError","pow() with modulus requires integer arguments");
        BigInteger m=bigInt(modulus), e=bigInt(exponent), a=bigInt(base);
        if(m.signum()==0) throw new PyException("ValueError","pow() 3rd argument cannot be 0");
        BigInteger magnitude=m.abs();
        if(magnitude.equals(BigInteger.ONE)) return 0L;
        if(e.signum()<0) {
            try { a=a.mod(magnitude).modInverse(magnitude); }
            catch(ArithmeticException error) { throw new PyException("ValueError","base is not invertible for the given modulus"); }
            e=e.negate();
        }
        BigInteger result=a.mod(magnitude).modPow(e,magnitude);
        if(m.signum()<0 && result.signum()!=0) result=result.subtract(magnitude);
        return compact(result);
    }

    public static Object ord(Object value) {
        if(value instanceof String text) {
            if(text.codePointCount(0,text.length())!=1) throw new PyException("TypeError","ord() requires one character");
            return (long)text.codePointAt(0);
        }
        if(value instanceof PyBytes || value instanceof PyByteArray) {
            PyByteSequence bytes=(PyByteSequence)value;
            if(bytes.byteSize()!=1) throw new PyException("TypeError","ord() requires one byte");
            return (long)bytes.unsignedAt(0);
        }
        throw new PyException("TypeError","ord() expected a character");
    }
    public static Object chr(Object value) {
        if(value instanceof PyInstance instance) {
            PyMethod index=instance.cls.lookupMethod("__index__");
            if(index==null) throw new PyException("TypeError","object cannot be interpreted as an integer");
            value=invoke(instance,index,new Object[0]);
        }
        if(!isIntLike(value)) throw new PyException("TypeError","chr() requires an integer");
        BigInteger code=bigInt(value);
        if(code.signum()<0 || code.compareTo(BigInteger.valueOf(0x10ffff))>0)
            throw new PyException("ValueError","chr() arg not in range(0x110000)");
        return new String(Character.toChars(code.intValue()));
    }
    public static final class PyMap implements Iterator<Object>, Iterable<Object> {
        final Object function; final ArrayList<Object> iterators=new ArrayList<>();
        Object buffered; boolean hasBuffered=false;
        PyMap(Object function,List<Object> sources) {
            this.function=function;
            for(Object source:sources) iterators.add(iter(source));
        }
        public Iterator<Object> iterator(){return this;}
        public boolean hasNext() {
            if(hasBuffered) return true;
            try { buffered=compute(); hasBuffered=true; return true; }
            catch(NoSuchElementException end){return false;}
            catch(PyException end){if(end.typeName.equals("StopIteration"))return false;throw end;}
        }
        private Object compute() {
            ArrayList<Object> args=new ArrayList<>();
            for(Object iterator:iterators) {
                try { args.add(next_(iterator)); }
                catch(PyException end) {if(end.typeName.equals("StopIteration"))throw new NoSuchElementException();throw end;}
            }
            return callFunction(function,args,new LinkedHashMap<Object,Object>());
        }
        public Object next() {
            if(hasBuffered){Object out=buffered;buffered=null;hasBuffered=false;return out;}
            return compute();
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<String,PyBuiltinType> BUILTIN_TYPES = new java.util.concurrent.ConcurrentHashMap<>();
    public static Object builtinType(Object nameObj){return BUILTIN_TYPES.computeIfAbsent((String)nameObj,PyBuiltinType::new);}
    public static Object typeOf(Object value){
        if(value instanceof PyInstance i) return i.cls;
        if(value instanceof PyClass cls) return cls.metaclass != null ? cls.metaclass : builtinType("type");
        return builtinType(typeName(value));
    }
    public static Object callable_(Object value){return value instanceof PyFunction || value instanceof PyBuiltinFunction || value instanceof BuiltinBoundMethod || value instanceof BoundMethod || value instanceof BoundSuperMethod || value instanceof BoundClassMethod || value instanceof BoundStaticMethod || value instanceof UnboundMethod || value instanceof PyClass || value instanceof PyBuiltinType;}

    private enum Singleton { NOT_IMPLEMENTED, ELLIPSIS }
    private static final Object NOT_IMPLEMENTED=Singleton.NOT_IMPLEMENTED, ELLIPSIS=Singleton.ELLIPSIS;
    public static Object notImplemented() { return NOT_IMPLEMENTED; }
    public static Object ellipsis() { return ELLIPSIS; }
    private static final ReferenceQueue<Object> ID_QUEUE=new ReferenceQueue<>();
    private static final Map<IdentityRef,Long> IDENTITIES=new HashMap<>();
    private static long nextIdentity=2;
    private static final class IdentityRef extends WeakReference<Object> {
        final int hash;
        IdentityRef(Object value,ReferenceQueue<Object> queue){super(value,queue);hash=System.identityHashCode(value);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other || other instanceof IdentityRef ref && get()!=null && get()==ref.get();}
    }
    public static synchronized Object id(Object value) {
        if(value==null)return 1L;
        IdentityRef expired;
        while((expired=(IdentityRef)ID_QUEUE.poll())!=null)IDENTITIES.remove(expired);
        IdentityRef lookup=new IdentityRef(value,null);
        Long found=IDENTITIES.get(lookup);
        if(found!=null)return found;
        long allocated=nextIdentity++;
        IDENTITIES.put(new IdentityRef(value,ID_QUEUE),allocated);
        return allocated;
    }
    private static final BigInteger HASH_MODULUS=BigInteger.ONE.shiftLeft(61).subtract(BigInteger.ONE);
    private static final long[] HASH_SECRET=hashSecret();
    private static long[] hashSecret(){
        byte[] bytes=new byte[16];String configured=System.getenv("PYTHONHASHSEED");
        if(configured==null || configured.equals("random"))new java.security.SecureRandom().nextBytes(bytes);
        else {
            long seed;
            try{seed=Long.parseLong(configured);}catch(NumberFormatException error){throw new IllegalArgumentException("invalid PYTHONHASHSEED");}
            if(seed<0 || seed>0xffffffffL)throw new IllegalArgumentException("invalid PYTHONHASHSEED");
            if(seed!=0){int state=(int)seed;for(int i=0;i<bytes.length;i++){state=state*214013+2531011;bytes[i]=(byte)(state>>>16);}}
        }
        return new long[]{littleEndian(bytes,0,8),littleEndian(bytes,8,8)};
    }
    private static long normalizeHash(long value){return value==-1?-2:value;}
    private static long numericHash(BigInteger value){return normalizeHash(value.abs().mod(HASH_MODULUS).longValue() * value.signum());}
    private static long littleEndian(byte[] bytes,int start,int count){
        long value=0;for(int i=0;i<count;i++)value|=(bytes[start+i]&255L)<<(8*i);return value;
    }
    private static void sipRound(long[] v){
        v[0]+=v[1];v[1]=Long.rotateLeft(v[1],13);v[1]^=v[0];v[0]=Long.rotateLeft(v[0],32);
        v[2]+=v[3];v[3]=Long.rotateLeft(v[3],16);v[3]^=v[2];
        v[0]+=v[3];v[3]=Long.rotateLeft(v[3],21);v[3]^=v[0];
        v[2]+=v[1];v[1]=Long.rotateLeft(v[1],17);v[1]^=v[2];v[2]=Long.rotateLeft(v[2],32);
    }
    private static long byteHash(byte[] bytes){
        if(bytes.length==0)return 0;
        long k0=HASH_SECRET[0],k1=HASH_SECRET[1];
        long[] v={k0^0x736f6d6570736575L,k1^0x646f72616e646f6dL,k0^0x6c7967656e657261L,k1^0x7465646279746573L};
        int offset=0;
        while(offset+8<=bytes.length){long word=littleEndian(bytes,offset,8);v[3]^=word;sipRound(v);v[0]^=word;offset+=8;}
        long tail=((long)bytes.length<<56)|littleEndian(bytes,offset,bytes.length-offset);
        v[3]^=tail;sipRound(v);v[0]^=tail;v[2]^=255;sipRound(v);sipRound(v);sipRound(v);
        return normalizeHash(v[0]^v[1]^v[2]^v[3]);
    }
    private static byte[] unicodeHashBytes(String text){
        int[] points=text.codePoints().toArray();int maximum=0;for(int point:points)maximum=Math.max(maximum,point);
        int width=maximum<=255?1:maximum<=65535?2:4;byte[] bytes=new byte[points.length*width];
        for(int i=0;i<points.length;i++)for(int j=0;j<width;j++)bytes[i*width+j]=(byte)(points[i]>>>(8*j));
        return bytes;
    }
    public static Object hash(Object value) {
        if(isIntLike(value))return numericHash(bigInt(value));
        if(value instanceof PyComplex z) {
            long real=Double.isNaN(z.real)?(Long)id(z):((Number)hash(z.real)).longValue();
            long imag=Double.isNaN(z.imag)?(Long)id(z):((Number)hash(z.imag)).longValue();
            return normalizeHash(real+1000003L*imag);
        }
        if(value instanceof Double d) {
            if(d.isNaN())return id(value);
            if(d.isInfinite())return d>0?314159L:-314159L;
            long bits=Double.doubleToRawLongBits(d), fraction=bits&0xfffffffffffffL;
            int exponent=(int)((bits>>>52)&2047);
            BigInteger significand=BigInteger.valueOf(exponent==0?fraction:fraction|(1L<<52));
            int power=exponent==0?-1074:exponent-1023-52;
            BigInteger number=significand.mod(HASH_MODULUS);
            if(power>=0) number=number.multiply(BigInteger.TWO.modPow(BigInteger.valueOf(power),HASH_MODULUS)).mod(HASH_MODULUS);
            else number=number.multiply(BigInteger.TWO.modInverse(HASH_MODULUS).modPow(BigInteger.valueOf(-power),HASH_MODULUS)).mod(HASH_MODULUS);
            return normalizeHash(number.longValue()*(bits<0?-1:1));
        }
        if(value instanceof String text)return byteHash(unicodeHashBytes(text));
        if(value instanceof PyBytes bytes)return byteHash(bytes.data);
        if(value instanceof PyTuple tuple) {
            long accumulator=0x27d4eb2f165667c5L;
            for(Object item:tuple.items){accumulator+=((Number)hash(item)).longValue()*0xc2b2ae3d27d4eb4fL;accumulator=Long.rotateLeft(accumulator,31)*0x9e3779b185ebca87L;}
            accumulator+=tuple.items.size()^(0x27d4eb2f165667c5L^3527539L);
            return accumulator==-1?1546275796L:accumulator;
        }
        if(value instanceof PySlice sl){PyTuple parts=new PyTuple();parts.items.add(sl.start);parts.items.add(sl.stop);parts.items.add(sl.step);return hash(parts);}
        if(value instanceof List<?> || value instanceof Map<?,?> || value instanceof Set<?> || value instanceof PyByteArray)
            throw new PyException("TypeError","unhashable type: '"+typeName(value)+"'");
        if(value instanceof PyMemoryView view){if(!view.readonly())throw new PyException("ValueError","cannot hash writable memoryview object");return byteHash(view.toByteArray());}
        if(value instanceof PyInstance instance) {
            for(PyClass cls:instance.cls.mro) {
                if(cls.attrs.containsKey("__hash__")) {
                    Object method=cls.attrs.get("__hash__");
                    if(method==null)throw new PyException("TypeError","unhashable type: '"+instance.cls.name+"'");
                    return hashResult(callFunction(descriptorGet(method,instance,instance.cls),new ArrayList<>(),new LinkedHashMap<>()));
                }
                PyMethod method=cls.methods.get("__hash__");
                if(method!=null)return hashResult(invoke(instance,method,new Object[0]));
            }
        }
        return id(value);
    }
    private static Object hashResult(Object result){
        if(!isIntLike(result))throw new PyException("TypeError","__hash__ method should return an integer");
        BigInteger n=bigInt(result);
        return n.bitLength()<64?normalizeHash(n.longValue()):numericHash(n);
    }

    // ---------- Display / truth ----------
    public static Object print(Object value) {
        System.out.println(pyStr(value));
        return null;
    }

    public static String pyStr(Object value) {
        if (value == null) return "None";
        if (value instanceof PyComplex z) return z.toString();
        if(value==NOT_IMPLEMENTED)return "NotImplemented";
        if(value==ELLIPSIS)return "Ellipsis";
        if (value instanceof Boolean b) return b ? "True" : "False";
        if (value instanceof String s) return s;
        if (value instanceof PyBytes bytes) return bytesRepr(bytes.data);
        if (value instanceof PyByteArray bytes) return "bytearray("+bytesRepr(bytes.toByteArray())+")";
        if (value instanceof PyMemoryView view) return view.toString();
        if (value instanceof PyTuple t) return t.pyRepr();
        if (value instanceof PyDictView v) return v.pyRepr();
        if (value instanceof PyExceptionValue e) return e.value == null ? "" : pyStr(e.value);
        if (value instanceof PyException e) return e.getMessage() == null ? "" : e.getMessage();
        if (value instanceof Throwable t) return t.getMessage() == null ? "" : t.getMessage();
        if (value instanceof PyInstance instance) {
            PyMethod m=instance.cls.lookupMethod("__str__");
            if(m!=null){Object out=invoke(instance,m,new Object[0]);if(!(out instanceof String))throw new PyException("TypeError","__str__ returned non-string");return (String)out;}
        }
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

    private static String stringRepr(String text) {
        char quote=text.indexOf('\'')>=0 && text.indexOf('"')<0 ? '"' : '\'';
        StringBuilder result=new StringBuilder().append(quote);
        text.codePoints().forEach(code -> {
            if(code==quote || code=='\\') result.append('\\').appendCodePoint(code);
            else if(code=='\n') result.append("\\n");
            else if(code=='\r') result.append("\\r");
            else if(code=='\t') result.append("\\t");
            else {
                int kind=Character.getType(code);
                boolean printable=code==32 || !(Character.isISOControl(code) ||
                    kind==Character.UNASSIGNED || kind==Character.FORMAT || kind==Character.SURROGATE ||
                    kind==Character.PRIVATE_USE || kind==Character.SPACE_SEPARATOR ||
                    kind==Character.LINE_SEPARATOR || kind==Character.PARAGRAPH_SEPARATOR);
                if(printable) result.appendCodePoint(code);
                else if(code<256) result.append(String.format(Locale.ROOT,"\\x%02x",code));
                else if(code<65536) result.append(String.format(Locale.ROOT,"\\u%04x",code));
                else result.append(String.format(Locale.ROOT,"\\U%08x",code));
            }
        });
        return result.append(quote).toString();
    }
    public static String pyRepr(Object value) {
        if (value instanceof String s) return stringRepr(s);
        if (value instanceof PyBytes bytes) return bytesRepr(bytes.data);
        if (value instanceof PyByteArray bytes) return "bytearray("+bytesRepr(bytes.toByteArray())+")";
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__repr__");if(m!=null){Object out=invoke(instance,m,new Object[0]);if(!(out instanceof String))throw new PyException("TypeError","__repr__ returned non-string");return (String)out;}}
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
        if(value==NOT_IMPLEMENTED)throw new PyException("TypeError","NotImplemented should not be used in a boolean context");
        if (value instanceof Boolean b) return b;
        if (value instanceof Long n) return n != 0L;
        if (value instanceof BigInteger n) return n.signum() != 0;
        if (value instanceof Double n) return n != 0.0;
        if (value instanceof PyComplex z) return z.real != 0.0 || z.imag != 0.0;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof PyByteSequence seq) return seq.byteSize()!=0;
        if (value instanceof List<?> xs) return !xs.isEmpty();
        if (value instanceof Map<?, ?> xs) return !xs.isEmpty();
        if (value instanceof Set<?> xs) return !xs.isEmpty();
        if (value instanceof PyTuple t) return !t.items.isEmpty();
        if(value instanceof PyInstance instance){
            PyMethod bm=instance.cls.lookupMethod("__bool__");
            if(bm!=null){Object out=invoke(instance,bm,new Object[0]);if(!(out instanceof Boolean))throw new PyException("TypeError","__bool__ should return bool");return (Boolean)out;}
            PyMethod lm=instance.cls.lookupMethod("__len__");
            if(lm!=null){Object out=invoke(instance,lm,new Object[0]);BigInteger n=bigInt(out);if(n.signum()<0)throw new PyException("ValueError","__len__() should return >= 0");return n.signum()!=0;}
        }
        return true;
    }

    private static Object binarySpecial(Object a,Object b,String leftName,String rightName,boolean comparison) {
        PyInstance left=a instanceof PyInstance i?i:null, right=b instanceof PyInstance i?i:null;
        PyMethod lm=left==null?null:left.cls.lookupMethod(leftName), rm=right==null?null:right.cls.lookupMethod(rightName);
        boolean rightFirst=left!=null && right!=null && left.cls!=right.cls && right.cls.mro.contains(left.cls)
            && rm!=null && (comparison || !rm.equals(left.cls.lookupMethod(rightName)));
        if(rightFirst){Object result=invoke(right,rm,new Object[]{a});if(result!=NOT_IMPLEMENTED)return result;}
        if(lm!=null){Object result=invoke(left,lm,new Object[]{b});if(result!=NOT_IMPLEMENTED)return result;}
        if(!rightFirst && rm!=null && (comparison || left==null || left.cls!=right.cls)) {
            Object result=invoke(right,rm,new Object[]{a});if(result!=NOT_IMPLEMENTED)return result;
        }
        return NOT_IMPLEMENTED;
    }

    // ---------- Complex numbers ----------
    public static final class PyComplex {
        final double real, imag;
        PyComplex(double real,double imag){this.real=real;this.imag=imag;}
        @Override public String toString(){
            boolean negativeImag=Double.doubleToRawLongBits(imag)<0 && !Double.isNaN(imag);
            String imaginary=complexComponent(negativeImag?-imag:imag);
            if(real==0.0 && Double.doubleToRawLongBits(real)>=0)
                return (negativeImag?"-":"")+imaginary+"j";
            return "("+complexComponent(real)+(negativeImag?"-":"+")+imaginary+"j)";
        }
    }
    private static String complexComponent(double value){
        if(Double.isNaN(value))return "nan";
        if(Double.isInfinite(value))return value<0?"-inf":"inf";
        if(value==0)return Double.doubleToRawLongBits(value)<0?"-0":"0";
        String text=Double.toString(value).toLowerCase(Locale.ROOT);
        int e=text.indexOf('e');
        if(e>=0){
            int exponent=Integer.parseInt(text.substring(e+1));
            if(exponent>=-4 && exponent<16) return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
            String mantissa=text.substring(0,e); if(mantissa.endsWith(".0"))mantissa=mantissa.substring(0,mantissa.length()-2);
            return mantissa+"e"+(exponent<0?"-":"+")+String.format(Locale.ROOT,"%02d",Math.abs(exponent));
        }
        return text.endsWith(".0")?text.substring(0,text.length()-2):text;
    }
    public static Object complexLiteral(Object real,Object imag){return new PyComplex(number(real),number(imag));}
    private static double complexReal(Object value,boolean protocol){
        if(protocol && value instanceof PyInstance instance){
            PyMethod method=instance.cls.lookupMethod("__float__");
            if(method!=null){Object out=invoke(instance,method,new Object[0]);if(!(out instanceof Double))throw new PyException("TypeError","__float__ returned non-float");return (Double)out;}
            method=instance.cls.lookupMethod("__index__");
            if(method!=null){Object out=invoke(instance,method,new Object[0]);if(!isIntLike(out))throw new PyException("TypeError","__index__ returned non-int");value=out;}
        }
        double out=number(value);
        if(value instanceof BigInteger && !Double.isFinite(out))throw new PyException("OverflowError","int too large to convert to float");
        return out;
    }
    private static PyComplex complexValue(Object value){return value instanceof PyComplex z?z:new PyComplex(complexReal(value,false),0.0);}
    private static Object complexConstructor(List<Object> args,Map<Object,Object> kwargs){
        if(args.size()>2)throw new PyException("TypeError","complex() takes at most 2 arguments");
        boolean singlePositional=args.size()==1 && kwargs.isEmpty(),converted=false;
        Object real=args.isEmpty()?0L:args.get(0),imag=args.size()<2?0L:args.get(1);
        boolean hasImag=args.size()==2;
        for(Map.Entry<Object,Object> entry:kwargs.entrySet()){
            if(entry.getKey().equals("real")){if(!args.isEmpty())throw new PyException("TypeError","multiple values for real");real=entry.getValue();}
            else if(entry.getKey().equals("imag")){if(hasImag)throw new PyException("TypeError","multiple values for imag");imag=entry.getValue();hasImag=true;}
            else throw new PyException("TypeError","invalid complex() keyword");
        }
        if(real instanceof String text){if(!singlePositional)throw new PyException("TypeError","complex() string input requires one positional argument");return parseComplex(text);}
        if(real instanceof PyInstance instance){
            PyMethod method=instance.cls.lookupMethod("__complex__");
            if(method!=null){real=invoke(instance,method,new Object[0]);converted=true;if(!(real instanceof PyComplex))throw new PyException("TypeError","__complex__ returned non-complex");}
        }
        if(singlePositional && !converted && real instanceof PyComplex)return real;
        double r=real instanceof PyComplex z?z.real:complexReal(real,true);
        double i=hasImag?(imag instanceof PyComplex z?z.real:complexReal(imag,true)):(real instanceof PyComplex z?z.imag:0.0);
        if(hasImag && imag instanceof PyComplex z)r-=z.imag;
        if(hasImag && real instanceof PyComplex z)i+=z.imag;
        return new PyComplex(r,i);
    }
    private static final String COMPLEX_NUMBER="(?:[0-9](?:_?[0-9])*(?:\\.(?:[0-9](?:_?[0-9])*)?)?|\\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?|inf(?:inity)?|nan";
    private static final java.util.regex.Pattern COMPLEX_TEXT=java.util.regex.Pattern.compile("^([+-]?(?:"+COMPLEX_NUMBER+"))?(?:([+-])((?:"+COMPLEX_NUMBER+"))?)?([jJ])?$",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static PyComplex parseComplex(String text){
        text=text.strip();
        if(text.startsWith("(") && text.endsWith(")"))text=text.substring(1,text.length()-1).strip();
        if(text.equals("j") || text.equals("+j"))return new PyComplex(0,1);
        if(text.equals("-j"))return new PyComplex(0,-1);
        var match=COMPLEX_TEXT.matcher(text);
        if(!match.matches() || match.group(1)==null || (match.group(2)!=null && match.group(4)==null))throw new PyException("ValueError","complex() arg is a malformed string");
        double first=(Double)float_(match.group(1).replace("_",""));
        if(match.group(4)==null)return new PyComplex(first,0);
        if(match.group(2)==null)return new PyComplex(0,first);
        double second=match.group(3)==null?1:(Double)float_(match.group(3).replace("_",""));
        return new PyComplex(first,match.group(2).equals("-")?-second:second);
    }
    private static PyComplex complexMultiply(PyComplex z,PyComplex w){
        double a=z.real,b=z.imag,c=w.real,d=w.imag;
        double ac=a*c,bd=b*d,ad=a*d,bc=b*c,r=ac-bd,i=ad+bc;
        if(Double.isNaN(r) && Double.isNaN(i)){
            boolean recalc=false;
            if(Double.isInfinite(a) || Double.isInfinite(b)){
                a=Math.copySign(Double.isInfinite(a)?1:0,a);b=Math.copySign(Double.isInfinite(b)?1:0,b);
                if(Double.isNaN(c))c=Math.copySign(0,c);if(Double.isNaN(d))d=Math.copySign(0,d);recalc=true;
            }
            if(Double.isInfinite(c) || Double.isInfinite(d)){
                c=Math.copySign(Double.isInfinite(c)?1:0,c);d=Math.copySign(Double.isInfinite(d)?1:0,d);
                if(Double.isNaN(a))a=Math.copySign(0,a);if(Double.isNaN(b))b=Math.copySign(0,b);recalc=true;
            }
            if(!recalc && (Double.isInfinite(ac)||Double.isInfinite(bd)||Double.isInfinite(ad)||Double.isInfinite(bc))){
                if(Double.isNaN(a))a=Math.copySign(0,a);if(Double.isNaN(b))b=Math.copySign(0,b);
                if(Double.isNaN(c))c=Math.copySign(0,c);if(Double.isNaN(d))d=Math.copySign(0,d);recalc=true;
            }
            if(recalc){r=Double.POSITIVE_INFINITY*(a*c-b*d);i=Double.POSITIVE_INFINITY*(a*d+b*c);}
        }
        return new PyComplex(r,i);
    }
    private static PyComplex complexDivide(PyComplex a,PyComplex b){
        if(b.real==0 && b.imag==0)throw new PyException("ZeroDivisionError","complex division by zero");
        double r,i;
        if(Math.abs(b.real)>=Math.abs(b.imag)){
            double ratio=b.imag/b.real,denom=b.real+b.imag*ratio;
            r=(a.real+a.imag*ratio)/denom;i=(a.imag-a.real*ratio)/denom;
        }else if(Math.abs(b.imag)>=Math.abs(b.real)){
            double ratio=b.real/b.imag,denom=b.real*ratio+b.imag;
            r=(a.real*ratio+a.imag)/denom;i=(a.imag*ratio-a.real)/denom;
        }else{r=Double.NaN;i=Double.NaN;}
        if(Double.isNaN(r) && Double.isNaN(i)){
            if((Double.isInfinite(a.real)||Double.isInfinite(a.imag)) && Double.isFinite(b.real) && Double.isFinite(b.imag)){
                double x=Math.copySign(Double.isInfinite(a.real)?1:0,a.real),y=Math.copySign(Double.isInfinite(a.imag)?1:0,a.imag);
                r=Double.POSITIVE_INFINITY*(x*b.real+y*b.imag);i=Double.POSITIVE_INFINITY*(y*b.real-x*b.imag);
            }else if((Double.isInfinite(b.real)||Double.isInfinite(b.imag)) && Double.isFinite(a.real) && Double.isFinite(a.imag)){
                double x=Math.copySign(Double.isInfinite(b.real)?1:0,b.real),y=Math.copySign(Double.isInfinite(b.imag)?1:0,b.imag);
                r=0.0*(a.real*x+a.imag*y);i=0.0*(a.imag*x-a.real*y);
            }
        }
        return new PyComplex(r,i);
    }
    private static PyComplex realComplexDivide(double a,PyComplex b){
        if(b.real==0 && b.imag==0)throw new PyException("ZeroDivisionError","complex division by zero");
        double r,i;
        if(Math.abs(b.real)>=Math.abs(b.imag)){
            double ratio=b.imag/b.real,denom=b.real+b.imag*ratio;
            r=a/denom;i=(-a*ratio)/denom;
        }else if(Math.abs(b.imag)>=Math.abs(b.real)){
            double ratio=b.real/b.imag,denom=b.real*ratio+b.imag;
            r=(a*ratio)/denom;i=(-a)/denom;
        }else{r=Double.NaN;i=Double.NaN;}
        if(Double.isNaN(r) && Double.isNaN(i) && Double.isFinite(a) && (Double.isInfinite(b.real)||Double.isInfinite(b.imag))){
            double x=Math.copySign(Double.isInfinite(b.real)?1:0,b.real),y=Math.copySign(Double.isInfinite(b.imag)?1:0,b.imag);
            r=0.0*(a*x);i=0.0*(-a*y);
        }
        return new PyComplex(r,i);
    }
    private static PyComplex complexPower(PyComplex a,PyComplex b){
        if(b.real==0 && b.imag==0)return new PyComplex(1,0);
        if(b.imag==0 && b.real==Math.rint(b.real) && Math.abs(b.real)<=100){
            int n=(int)Math.abs(b.real);PyComplex result=new PyComplex(1,0),factor=a;
            while(n!=0){if((n&1)!=0)result=complexMultiply(result,factor);n>>=1;if(n!=0)factor=complexMultiply(factor,factor);}
            result=b.real<0?complexDivide(new PyComplex(1,0),result):result;
            if(Double.isInfinite(result.real)||Double.isInfinite(result.imag))throw new PyException("OverflowError","complex exponentiation");
            return result;
        }
        if(a.real==0 && a.imag==0){if(b.imag!=0 || b.real<0)throw new PyException("ZeroDivisionError","0.0 to a negative or complex power");return new PyComplex(0,0);}
        double magnitude=Math.hypot(a.real,a.imag),angle=Math.atan2(a.imag,a.real);
        double length=Math.pow(magnitude,b.real),phase=angle*b.real;
        if(b.imag!=0){length*=Math.exp(-angle*b.imag);phase+=b.imag*Math.log(magnitude);}
        double r=length*Math.cos(phase),i=length*Math.sin(phase);
        if(Double.isInfinite(r)||Double.isInfinite(i))throw new PyException("OverflowError","complex exponentiation");
        return new PyComplex(r,i);
    }

    // ---------- Arithmetic ----------
    public static Object iadd(Object a,Object b) {
        if(a instanceof PyInstance instance){PyMethod method=instance.cls.lookupMethod("__iadd__");if(method!=null){Object value=invoke(instance,method,new Object[]{b});if(value!=NOT_IMPLEMENTED)return value;}}
        if(a instanceof PyByteArray array) {
            if((a==b || b instanceof PyMemoryView view && view.source==a) && ((PyByteSequence)b).byteSize()>0)
                throw new PyException("BufferError","Existing exports of data: object cannot be re-sized");
            if(!(b instanceof PyByteSequence seq))throw new PyException("TypeError","cannot concatenate bytearray and non-buffer object");
            byte[] data=seq.toByteArray(); for(byte value:data)array.data.add(value); return array;
        }
        if(a instanceof List<?>){listExtend(a,a==b?new ArrayList<>((List<?>)b):b);return a;}
        return add(a,b);
    }
    public static Object imul(Object a,Object b) {
        if(a instanceof PyInstance instance){PyMethod method=instance.cls.lookupMethod("__imul__");if(method!=null){Object value=invoke(instance,method,new Object[]{b});if(value!=NOT_IMPLEMENTED)return value;}}
        if(a instanceof PyByteArray array) {
            PyByteArray repeated=(PyByteArray)repeatBytes(array,bigInt(b),true);
            array.data.clear();array.data.addAll(repeated.data);return array;
        }
        if(a instanceof List<?>) {
            @SuppressWarnings("unchecked") List<Object> list=(List<Object>)a;
            List<Object> original=new ArrayList<>(list);
            int count=asIndex(b); if(count<=0){list.clear();return list;}
            for(int i=1;i<count;i++)list.addAll(original);
            return list;
        }
        return mul(a,b);
    }
    public static Object ipow(Object a,Object b) {
        if(a instanceof PyInstance instance){PyMethod method=instance.cls.lookupMethod("__ipow__");if(method!=null){Object value=invoke(instance,method,new Object[]{b});if(value!=NOT_IMPLEMENTED)return value;}}
        return pow(a,b);
    }

    public static Object add(Object a, Object b) {
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__add__","__radd__",false);if(result!=NOT_IMPLEMENTED)return result;}
        if(a instanceof PyComplex x){if(b instanceof PyComplex y)return new PyComplex(x.real+y.real,x.imag+y.imag);return new PyComplex(x.real+complexReal(b,false),x.imag);}
        if(b instanceof PyComplex y)return new PyComplex(complexReal(a,false)+y.real,y.imag);
        if (a instanceof String sa && b instanceof String sb) return sa + sb;
        if((a instanceof PyBytes || a instanceof PyByteArray) && b instanceof PyByteSequence bb) {
            byte[] x=((PyByteSequence)a).toByteArray(),y=bb.toByteArray(),out=new byte[x.length+y.length];
            System.arraycopy(x,0,out,0,x.length);System.arraycopy(y,0,out,x.length,y.length);
            return a instanceof PyByteArray?new PyByteArray(out):new PyBytes(out);
        }
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
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__sub__","__rsub__",false);if(result!=NOT_IMPLEMENTED)return result;}
        if(a instanceof PyComplex x){if(b instanceof PyComplex y)return new PyComplex(x.real-y.real,x.imag-y.imag);return new PyComplex(x.real-complexReal(b,false),x.imag);}
        if(b instanceof PyComplex y)return new PyComplex(complexReal(a,false)-y.real,-y.imag);
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
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__mul__","__rmul__",false);if(result!=NOT_IMPLEMENTED)return result;}
        if(a instanceof PyComplex x){if(b instanceof PyComplex y)return complexMultiply(x,y);double real=complexReal(b,false);return new PyComplex(x.real*real,x.imag*real);}
        if(b instanceof PyComplex y){double real=complexReal(a,false);return new PyComplex(real*y.real,real*y.imag);}
        if (a instanceof String sa && isIntLike(b)) return repeatString(sa, bigInt(b));
        if (b instanceof String sb && isIntLike(a)) return repeatString(sb, bigInt(a));
        if(a instanceof PyBytes bytes && isIntLike(b)) return repeatBytes(bytes,bigInt(b),false);
        if(b instanceof PyBytes bytes && isIntLike(a)) return repeatBytes(bytes,bigInt(a),false);
        if(a instanceof PyByteArray bytes && isIntLike(b)) return repeatBytes(bytes,bigInt(b),true);
        if(b instanceof PyByteArray bytes && isIntLike(a)) return repeatBytes(bytes,bigInt(a),true);
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

    private static Object repeatBytes(PyByteSequence seq,BigInteger count,boolean mutable){
        if(count.signum()<=0) return mutable?new PyByteArray():new PyBytes(new byte[0]);
        int n;
        try{n=count.intValueExact();}catch(ArithmeticException e){throw new PyException("OverflowError","repeated bytes are too long");}
        byte[] src=seq.toByteArray();
        int total;
        try{total=Math.multiplyExact(src.length,n);}catch(ArithmeticException e){throw new PyException("OverflowError","repeated bytes are too long");}
        byte[] out=new byte[total];
        for(int i=0;i<n;i++)System.arraycopy(src,0,out,i*src.length,src.length);
        return mutable?new PyByteArray(out):new PyBytes(out);
    }

    public static Object truediv(Object a, Object b) {
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__truediv__","__rtruediv__",false);if(result!=NOT_IMPLEMENTED)return result;}
        if(a instanceof PyComplex x){
            if(b instanceof PyComplex y)return complexDivide(x,y);
            double real=complexReal(b,false);if(real==0)throw new PyException("ZeroDivisionError","complex division by zero");return new PyComplex(x.real/real,x.imag/real);
        }
        if(b instanceof PyComplex y)return realComplexDivide(complexReal(a,false),y);
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
        if(a instanceof PyComplex z)return new PyComplex(-z.real,-z.imag);
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
        if(a instanceof PyComplex)return a;
        if (isIntLike(a)) return compact(bigInt(a));
        if (a instanceof Double d) return d;
        throw typeError("bad operand type for unary +", a);
    }
    public static Object invert(Object a) { return compact(bigInt(a).not()); }
    public static Object abs(Object a) {
        if(a instanceof PyComplex z){
            double result=Math.hypot(z.real,z.imag);
            if(Double.isInfinite(result) && Double.isFinite(z.real) && Double.isFinite(z.imag))throw new PyException("OverflowError","absolute value too large");
            return result;
        }
        if (isIntLike(a)) return compact(bigInt(a).abs());
        if (a instanceof Double d) return Math.abs(d);
        throw typeError("bad operand type for abs()", a);
    }
    public static Object pow(Object a, Object b) {
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__pow__","__rpow__",false);if(result!=NOT_IMPLEMENTED)return result;}
        if(a instanceof PyComplex || b instanceof PyComplex)return complexPower(complexValue(a),complexValue(b));
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
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__eq__","__eq__",true);if(result!=NOT_IMPLEMENTED)return result;}
        if(a instanceof PyComplex x){
            if(b instanceof PyComplex y)return x.real==y.real && x.imag==y.imag;
            return x.imag==0 && (isIntLike(b)?integerFloatEqual(bigInt(b),x.real):b instanceof Double d && x.real==d);
        }
        if(b instanceof PyComplex)return eq(b,a);
        if(isIntLike(a) && isIntLike(b))return bigInt(a).equals(bigInt(b));
        if(a instanceof Double x && b instanceof Double y)return x.doubleValue()==y.doubleValue();
        if(isIntLike(a) && b instanceof Double d)return integerFloatEqual(bigInt(a),d);
        if(a instanceof Double d && isIntLike(b))return integerFloatEqual(bigInt(b),d);
        if(a instanceof PySlice x && b instanceof PySlice y)return truth(eq(x.start,y.start)) && truth(eq(x.stop,y.stop)) && truth(eq(x.step,y.step));
        if(a instanceof PyTuple x && b instanceof PyTuple y)return sequenceEqual(x.items,y.items);
        if(a instanceof List<?> x && b instanceof List<?> y)return sequenceEqual(x,y);
        return Objects.equals(a,b);
    }
    private static boolean integerFloatEqual(BigInteger integer,double number){
        if(!Double.isFinite(number) || number!=Math.rint(number))return false;
        return integer.equals(new java.math.BigDecimal(number).toBigIntegerExact());
    }
    private static boolean sequenceEqual(List<?> left,List<?> right){
        if(left.size()!=right.size())return false;
        for(int i=0;i<left.size();i++)if(left.get(i)!=right.get(i) && !truth(eq(left.get(i),right.get(i))))return false;
        return true;
    }
    public static Object ne(Object a, Object b) {
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__ne__","__ne__",true);if(result!=NOT_IMPLEMENTED)return result;}
        return !truth(eq(a,b));
    }
    public static Object is_(Object a, Object b) { return a == b; }
    public static Object is_not(Object a, Object b) { return a != b; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int cmp(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) return bigInt(a).compareTo(bigInt(b));
        if (a instanceof Number && b instanceof Number) return Double.compare(number(a), number(b));
        if (a != null && b != null && a.getClass() == b.getClass() && a instanceof Comparable c) return c.compareTo(b);
        throw new IllegalArgumentException("comparison not supported between '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    private static Object richCompare(Object a,Object b,String left,String right,int fallback){
        if(a instanceof PyInstance ia){PyMethod m=ia.cls.lookupMethod(left);if(m!=null)return invoke(ia,m,new Object[]{b});}
        if(b instanceof PyInstance ib){PyMethod m=ib.cls.lookupMethod(right);if(m!=null)return invoke(ib,m,new Object[]{a});}
        int c=cmp(a,b); return switch(fallback){case -2->c<0;case -1->c<=0;case 1->c>=0;default->c>0;};
    }
    public static Object lt(Object a, Object b) { return richCompare(a,b,"__lt__","__gt__",-2); }
    public static Object le(Object a, Object b) { return richCompare(a,b,"__le__","__ge__",-1); }
    public static Object gt(Object a, Object b) { return richCompare(a,b,"__gt__","__lt__",2); }
    public static Object ge(Object a, Object b) { return richCompare(a,b,"__ge__","__le__",1); }

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
        if (value instanceof String s) {
            String text=s.trim().toLowerCase(Locale.ROOT);
            if(text.equals("nan") || text.equals("+nan") || text.equals("-nan"))return Double.NaN;
            if(text.equals("inf") || text.equals("infinity") || text.equals("+inf") || text.equals("+infinity"))return Double.POSITIVE_INFINITY;
            if(text.equals("-inf") || text.equals("-infinity"))return Double.NEGATIVE_INFINITY;
            try{return Double.valueOf(text);}catch(NumberFormatException error){throw new PyException("ValueError","could not convert string to float");}
        }
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
        else if (value instanceof PyByteSequence seq) n = seq.byteSize();
        else if (value instanceof List<?> xs) n = xs.size();
        else if (value instanceof Map<?, ?> xs) n = xs.size();
        else if (value instanceof Set<?> xs) n = xs.size();
        else if (value instanceof PyTuple t) n = t.items.size();
        else if (value instanceof PyRange r) return compact(r.length());
        else if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__len__");if(m==null)throw typeError("object has no len()",value);Object out=invoke(instance,m,new Object[0]);BigInteger bi=bigInt(out);if(bi.signum()<0)throw new PyException("ValueError","__len__() should return >= 0");return compact(bi);}
        else throw typeError("object has no len()", value);
        return n;
    }

    public static Object getitem(Object value, Object key) {
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__getitem__");if(m!=null)return invoke(instance,m,new Object[]{key});}
        if (key instanceof PySlice sl) return sliceGet(value, sl);
        if (value instanceof PyByteSequence seq) {
            int i=asIndex(key); return (long)seq.unsignedAt(normalizeIndex(i,seq.byteSize()));
        }
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
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__setitem__");if(m!=null){invoke(instance,m,new Object[]{key,item});return;}}
        if (value instanceof Map<?, ?> m) { ((Map<Object, Object>)m).put(key, item); return; }
        if(key instanceof PySlice sl && value instanceof PyByteArray array){byteArraySetSlice(array,sl,item);return;}
        int i = asIndex(key);
        if(value instanceof PyByteArray array){array.setUnsigned(normalizeIndex(i,array.byteSize()),byteValue(item));return;}
        if(value instanceof PyMemoryView view){view.setUnsigned(normalizeIndex(i,view.byteSize()),byteValue(item));return;}
        if (value instanceof List<?> xs) { ((List<Object>)xs).set(normalizeIndex(i, xs.size()), item); return; }
        throw typeError("object does not support item assignment", value);
    }
    @SuppressWarnings("unchecked")
    public static void delitem(Object value, Object key) {
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__delitem__");if(m!=null){invoke(instance,m,new Object[]{key});return;}}
        if(key instanceof PySlice sl && (value instanceof List<?> || value instanceof PyByteArray)) {
            List<?> list=value instanceof PyByteArray array?array.data:(List<?>)value;
            int size=list.size(),step=sl.step==null?1:asIndex(sl.step);
            if(step==0)throw new PyException("ValueError","slice step cannot be zero");
            int start=sl.start==null?(step>0?0:size-1):normalizeSliceIndex(asIndex(sl.start),size,step>0);
            int stop=sl.stop==null?(step>0?size:-1):normalizeSliceStop(asIndex(sl.stop),size,step>0);
            ArrayList<Integer> indexes=new ArrayList<>();
            for(int i=start;step>0?i<stop:i>stop;i+=step)if(i>=0 && i<size)indexes.add(i);
            indexes.sort(Collections.reverseOrder());for(int i:indexes)list.remove(i);return;
        }
        if(value instanceof PyByteArray array){int i=asIndex(key);array.data.remove(normalizeIndex(i,array.data.size()));return;}
        if (value instanceof Map<?,?> m) { if(!m.containsKey(key)) throw new NoSuchElementException("key not found"); ((Map<Object,Object>)m).remove(key); return; }
        int i=asIndex(key);
        if (value instanceof List<?> xs) { ((List<Object>)xs).remove(normalizeIndex(i,xs.size())); return; }
        throw typeError("object does not support item deletion",value);
    }

    public static Object contains(Object container, Object needle) {
        if(container instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__contains__");if(m!=null)return invoke(instance,m,new Object[]{needle});}
        if (container instanceof String s && needle instanceof String n) return s.contains(n);
        if(container instanceof PyByteSequence seq) {
            if(isIntLike(needle)) {
                int v=byteValue(needle); for(int i=0;i<seq.byteSize();i++) if(seq.unsignedAt(i)==v)return true; return false;
            }
            if(needle instanceof PyByteSequence sub) {
                byte[] hay=seq.toByteArray(), nd=sub.toByteArray();
                if(nd.length==0)return true;
                outer: for(int i=0;i+nd.length<=hay.length;i++){for(int j=0;j<nd.length;j++)if(hay[i+j]!=nd[j])continue outer;return true;}return false;
            }
            throw new PyException("TypeError","a bytes-like object is required");
        }
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
        if(value instanceof PyByteSequence seq) return binarySlice(seq,sl,true);
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
        if(value instanceof PyInstance instance) {
            PyMethod method=instance.cls.lookupMethod("__reversed__");
            if(method!=null)return invoke(instance,method,new Object[0]);
        }
        if(!(value instanceof List<?> || value instanceof PyTuple || value instanceof String || value instanceof PyByteSequence || value instanceof PyRange || value instanceof PyInstance))
            throw new PyException("TypeError","object is not reversible");
        long size=((Number)len(value)).longValue();
        return new Iterator<Object>() {
            long position=size-1; boolean done=false;
            public boolean hasNext(){return !done && position>=0;}
            public Object next(){
                if(!hasNext())throw new NoSuchElementException();
                try{return getitem(value,position--);}
                catch(PyException e){if(e.typeName.equals("IndexError") || e.typeName.equals("StopIteration")){done=true;throw new NoSuchElementException();}throw e;}
                catch(IndexOutOfBoundsException e){done=true;throw new NoSuchElementException();}
            }
        };
    }
    public static Object next_(Object iterator) {
        if(iterator instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__next__");if(m==null)throw new PyException("TypeError","object is not an iterator");return invoke(instance,m,new Object[0]);}
        if(!(iterator instanceof Iterator<?>))throw new PyException("TypeError","object is not an iterator");
        try { return ((Iterator<?>)iterator).next(); }
        catch(PyGeneratorEnd e) { throw new PyException("StopIteration",e.value); }
        catch(NoSuchElementException e) { throw new PyException("StopIteration",null); }
    }

    // ---------- Iteration / range ----------
    public static Object range1(Object stop) { return new PyRange(BigInteger.ZERO, bigInt(stop), BigInteger.ONE); }
    public static Object range2(Object start, Object stop) { return new PyRange(bigInt(start), bigInt(stop), BigInteger.ONE); }
    public static Object range3(Object start, Object stop, Object step) { return new PyRange(bigInt(start), bigInt(stop), bigInt(step)); }

    public static Object iter(Object value) {
        if(value instanceof Iterator<?>) return value;
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__iter__");if(m!=null)return invoke(instance,m,new Object[0]);}
        return iterable(value).iterator();
    }
    public static boolean iterHasNext(Object iterator) { return ((Iterator<?>) iterator).hasNext(); }
    public static Object iterNext(Object iterator) { return ((Iterator<?>) iterator).next(); }

    public static Object aiter(Object value) {
        if(value instanceof PyAsyncGenerator generator) return generator;
        if(value instanceof PyInstance instance){
            PyMethod method=instance.cls.lookupMethod("__aiter__");
            if(method!=null)return invoke(instance,method,new Object[0]);
        }
        throw new PyException("TypeError","object is not an async iterable");
    }
    public static Object anext_(Object iterator) {
        if(iterator instanceof PyAsyncGenerator generator) return generator.anext();
        if(iterator instanceof PyInstance instance){
            PyMethod method=instance.cls.lookupMethod("__anext__");
            if(method!=null)return invoke(instance,method,new Object[0]);
        }
        throw new PyException("TypeError","object is not an async iterator");
    }
    public static final class PyAsyncNextResult {
        final boolean done; final Object value;
        PyAsyncNextResult(boolean done,Object value){this.done=done;this.value=value;}
    }
    public static Object asyncIterNext(Object iterator) {
        try { return new PyAsyncNextResult(false,awaitValue(anext_(iterator))); }
        catch(PyException e) {
            if(e.typeName.equals("StopAsyncIteration")) return new PyAsyncNextResult(true,null);
            throw e;
        }
    }
    public static boolean asyncNextDone(Object result){return ((PyAsyncNextResult)result).done;}
    public static Object asyncNextValue(Object result){return ((PyAsyncNextResult)result).value;}

    private static Iterable<?> iterable(Object value) {
        if(value instanceof PyInstance instance){
            PyMethod m=instance.cls.lookupMethod("__iter__");
            if(m!=null){
                Object it=invoke(instance,m,new Object[0]);
                return () -> new Iterator<Object>() {
                    Object buffered=null; boolean hasBuffered=false,done=false;
                    public boolean hasNext(){if(done)return false;if(hasBuffered)return true;try{buffered=next_(it);hasBuffered=true;return true;}catch(PyException e){if(e.typeName.equals("StopIteration")){done=true;return false;}throw e;}}
                    public Object next(){if(!hasNext())throw new NoSuchElementException();Object out=buffered;buffered=null;hasBuffered=false;return out;}
                };
            }
        }
        if (value instanceof Iterable<?> x) return x;
        if (value instanceof Iterator<?> iterator) return () -> new Iterator<Object>() {
            public boolean hasNext(){return iterator.hasNext();}
            public Object next(){return iterator.next();}
        };
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


    // ---------- Binary sequence types ----------
    private interface PyByteSequence extends Iterable<Object> {
        int byteSize();
        int unsignedAt(int index);
        byte[] toByteArray();
    }

    public static final class PyBytes implements PyByteSequence {
        final byte[] data;
        PyBytes(byte[] data){this.data=Arrays.copyOf(data,data.length);}
        public int byteSize(){return data.length;}
        public int unsignedAt(int index){return data[index] & 0xff;}
        public byte[] toByteArray(){return Arrays.copyOf(data,data.length);}
        public Iterator<Object> iterator(){
            return new Iterator<>() {
                int i=0;
                public boolean hasNext(){return i<data.length;}
                public Object next(){if(!hasNext())throw new NoSuchElementException();return (long)(data[i++]&0xff);}
            };
        }
        @Override public boolean equals(Object other){return other instanceof PyByteSequence seq && Arrays.equals(data,seq.toByteArray());}
        @Override public int hashCode(){return Arrays.hashCode(data);}
        @Override public String toString(){return bytesRepr(data);}
    }

    public static final class PyByteArray implements PyByteSequence {
        final ArrayList<Byte> data=new ArrayList<>();
        PyByteArray(){}
        PyByteArray(byte[] raw){for(byte b:raw)data.add(b);}
        public int byteSize(){return data.size();}
        public int unsignedAt(int index){return data.get(index)&0xff;}
        public byte[] toByteArray(){byte[] out=new byte[data.size()];for(int i=0;i<data.size();i++)out[i]=data.get(i);return out;}
        void setUnsigned(int index,int value){checkByte(value);data.set(index,(byte)value);}
        void appendUnsigned(int value){checkByte(value);data.add((byte)value);}
        public Iterator<Object> iterator(){
            Iterator<Byte> it=data.iterator();
            return new Iterator<>() {
                public boolean hasNext(){return it.hasNext();}
                public Object next(){return (long)(it.next()&0xff);}
            };
        }
        @Override public boolean equals(Object other){return other instanceof PyByteSequence seq && Arrays.equals(toByteArray(),seq.toByteArray());}
        @Override public int hashCode(){return Arrays.hashCode(toByteArray());}
        @Override public String toString(){return "bytearray("+bytesRepr(toByteArray())+")";}
    }

    public static final class PyMemoryView implements PyByteSequence {
        final PyByteSequence source;
        final int start, length;
        PyMemoryView(PyByteSequence source){this(source,0,source.byteSize());}
        PyMemoryView(PyByteSequence source,int start,int length){this.source=source;this.start=start;this.length=length;}
        public int byteSize(){return length;}
        public int unsignedAt(int index){return source.unsignedAt(start+index);}
        public byte[] toByteArray(){byte[] out=new byte[length];for(int i=0;i<length;i++)out[i]=(byte)unsignedAt(i);return out;}
        void setUnsigned(int index,int value){
            if(!(source instanceof PyByteArray array)) throw new PyException("TypeError","cannot modify read-only memory");
            array.setUnsigned(start+index,value);
        }
        boolean readonly(){return !(source instanceof PyByteArray);}
        public Iterator<Object> iterator(){
            return new Iterator<>() {
                int i=0;
                public boolean hasNext(){return i<length;}
                public Object next(){if(!hasNext())throw new NoSuchElementException();return (long)unsignedAt(i++);}
            };
        }
        @Override public String toString(){return "<memory at 0x0>";}
    }

    private static int byteValue(Object value){
        if(value instanceof PyInstance instance && instance.cls.lookupMethod("__index__")!=null)
            value=invoke(instance,instance.cls.lookupMethod("__index__"),new Object[0]);
        if(!isIntLike(value))throw new PyException("TypeError","integer argument expected");
        BigInteger n=bigInt(value);
        if(n.signum()<0 || n.compareTo(BigInteger.valueOf(255))>0)
            throw new PyException("ValueError","bytes must be in range(0, 256)");
        return n.intValue();
    }
    private static void checkByte(int value){
        if(value<0 || value>255) throw new PyException("ValueError","byte must be in range(0, 256)");
    }
    private static byte[] bytesFromIterable(Object value){
        ArrayList<Byte> out=new ArrayList<>();
        for(Object item:iterable(value)) out.add((byte)byteValue(item));
        byte[] raw=new byte[out.size()]; for(int i=0;i<out.size();i++) raw[i]=out.get(i); return raw;
    }
    private static byte[] bytesFromObject(Object value){
        if(value instanceof PyByteSequence seq) return seq.toByteArray();
        if(value instanceof PyInstance instance && instance.cls.lookupMethod("__index__")!=null)
            value=invoke(instance,instance.cls.lookupMethod("__index__"),new Object[0]);
        if(isIntLike(value)) {
            int n=asIndex(value); if(n<0) throw new PyException("ValueError","negative count");
            return new byte[n];
        }
        return bytesFromIterable(value);
    }
    public static Object bytes0(){return new PyBytes(new byte[0]);}
    public static Object bytes1(Object value){return new PyBytes(bytesFromObject(value));}
    public static Object bytes2(Object value,Object encoding){
        if(!(value instanceof String text) || !(encoding instanceof String enc))
            throw new PyException("TypeError","encoding without a string argument");
        return new PyBytes(text.getBytes(Charset.forName(enc)));
    }
    public static Object bytearray0(){return new PyByteArray();}
    public static Object bytearray1(Object value){return new PyByteArray(bytesFromObject(value));}
    public static Object bytearray2(Object value,Object encoding){
        return new PyByteArray(((PyBytes)bytes2(value,encoding)).data);
    }
    public static Object memoryview1(Object value){
        if(value instanceof PyMemoryView view) return new PyMemoryView(view.source,view.start,view.length);
        if(value instanceof PyByteSequence seq) return new PyMemoryView(seq);
        throw new PyException("TypeError","memoryview: a bytes-like object is required");
    }
    public static Object bytesFromHexLiteral(Object hexObj){
        String hex=(String)hexObj; byte[] out=new byte[hex.length()/2];
        for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return new PyBytes(out);
    }
    private static String bytesHex(byte[] raw){
        StringBuilder out=new StringBuilder(raw.length*2);
        for(byte b:raw)out.append(String.format("%02x",b&0xff));
        return out.toString();
    }
    private static String bytesRepr(byte[] raw){
        StringBuilder out=new StringBuilder("b'");
        for(byte bb:raw){
            int v=bb&0xff;
            if(v=='\\' || v=='\'') out.append('\\').append((char)v);
            else if(v>=32 && v<127) out.append((char)v);
            else switch(v){
                case 9 -> out.append("\\t");
                case 10 -> out.append("\\n");
                case 13 -> out.append("\\r");
                default -> out.append(String.format("\\x%02x",v));
            }
        }
        return out.append('\'').toString();
    }
    private static byte[] requireBytesLike(Object value){
        if(value instanceof PyByteSequence seq) return seq.toByteArray();
        throw new PyException("TypeError","a bytes-like object is required");
    }
    private static Object binaryResult(PyByteSequence receiver,byte[] raw){
        return receiver instanceof PyByteArray ? new PyByteArray(raw) : new PyBytes(raw);
    }
    private static int clampSliceBound(Object bound,int size,int fallback){
        if(bound==null)return fallback;
        int i=asIndex(bound); if(i<0)i+=size;
        return Math.max(0,Math.min(size,i));
    }
    private static int binaryIndexOf(byte[] hay,byte[] needle,int start,int end){
        start=Math.max(0,Math.min(hay.length,start)); end=Math.max(start,Math.min(hay.length,end));
        if(needle.length==0)return start;
        outer: for(int i=start;i+needle.length<=end;i++){
            for(int j=0;j<needle.length;j++)if(hay[i+j]!=needle[j])continue outer;
            return i;
        }
        return -1;
    }
    private static int binaryLastIndexOf(byte[] hay,byte[] needle,int start,int end){
        start=Math.max(0,Math.min(hay.length,start)); end=Math.max(start,Math.min(hay.length,end));
        if(needle.length==0)return end;
        outer: for(int i=end-needle.length;i>=start;i--){
            for(int j=0;j<needle.length;j++)if(hay[i+j]!=needle[j])continue outer;
            return i;
        }
        return -1;
    }
    private static Object binarySearch(PyByteSequence seq,Object[] args,boolean reverse,boolean raising){
        if(args.length<1||args.length>3)throw new PyException("TypeError",(reverse?(raising?"rindex":"rfind"):(raising?"index":"find"))+"() takes from 1 to 3 arguments");
        byte[] hay=seq.toByteArray(), needle=requireBytesLike(args[0]);
        int start=args.length>=2?clampSliceBound(args[1],hay.length,0):0;
        int end=args.length>=3?clampSliceBound(args[2],hay.length,hay.length):hay.length;
        int found=reverse?binaryLastIndexOf(hay,needle,start,end):binaryIndexOf(hay,needle,start,end);
        if(raising&&found<0)throw new PyException("ValueError","subsection not found");
        return (long)found;
    }
    private static Object binaryPartition(PyByteSequence seq,Object[] args,boolean reverse){
        requireArgs(reverse?"rpartition":"partition",args,1);
        byte[] src=seq.toByteArray(), sep=requireBytesLike(args[0]);
        if(sep.length==0)throw new PyException("ValueError","empty separator");
        int found=reverse?binaryLastIndexOf(src,sep,0,src.length):binaryIndexOf(src,sep,0,src.length);
        PyTuple out=new PyTuple();
        if(found<0){
            if(reverse){
                out.items.add(binaryResult(seq,new byte[0]));
                out.items.add(binaryResult(seq,new byte[0]));
                out.items.add(binaryResult(seq,src));
            } else {
                out.items.add(binaryResult(seq,src));
                out.items.add(binaryResult(seq,new byte[0]));
                out.items.add(binaryResult(seq,new byte[0]));
            }
            return out;
        }
        out.items.add(binaryResult(seq,Arrays.copyOfRange(src,0,found)));
        out.items.add(binaryResult(seq,sep));
        out.items.add(binaryResult(seq,Arrays.copyOfRange(src,found+sep.length,src.length)));
        return out;
    }

    private static Object bytesMaketrans(Object fromObj,Object toObj){
        byte[] from=requireBytesLike(fromObj), to=requireBytesLike(toObj);
        if(from.length!=to.length)throw new PyException("ValueError","maketrans arguments must have same length");
        byte[] table=new byte[256]; for(int i=0;i<256;i++)table[i]=(byte)i;
        for(int i=0;i<from.length;i++)table[from[i]&0xff]=to[i];
        return new PyBytes(table);
    }
    private static Object binaryTranslate(PyByteSequence seq,Object[] args){
        if(args.length<1||args.length>2)throw new PyException("TypeError","translate() takes 1 or 2 arguments");
        byte[] table=null;
        if(args[0]!=null){
            table=requireBytesLike(args[0]);
            if(table.length!=256)throw new PyException("ValueError","translation table must be 256 characters long");
        }
        byte[] delete=args.length==2?requireBytesLike(args[1]):new byte[0];
        boolean[] remove=new boolean[256]; for(byte b:delete)remove[b&0xff]=true;
        ArrayList<Byte> out=new ArrayList<>();
        for(byte b:seq.toByteArray()){
            int v=b&0xff; if(remove[v])continue;
            out.add(table==null?b:table[v]);
        }
        byte[] raw=new byte[out.size()];for(int i=0;i<out.size();i++)raw[i]=out.get(i);
        return binaryResult(seq,raw);
    }
    private static boolean byteInSet(int value,byte[] chars){
        for(byte b:chars)if((b&0xff)==value)return true;
        return false;
    }
    private static Object binaryStrip(PyByteSequence seq,Object[] args,int mode){
        if(args.length>1)throw new PyException("TypeError","strip() takes at most 1 argument");
        byte[] src=seq.toByteArray();
        byte[] chars=args.length==0||args[0]==null?null:requireBytesLike(args[0]);
        int a=0,z=src.length;
        if(mode<=0){
            while(a<z && (chars==null?asciiWhitespace(src[a]&0xff):byteInSet(src[a]&0xff,chars)))a++;
        }
        if(mode>=0){
            while(z>a && (chars==null?asciiWhitespace(src[z-1]&0xff):byteInSet(src[z-1]&0xff,chars)))z--;
        }
        return binaryResult(seq,Arrays.copyOfRange(src,a,z));
    }
    private static int asciiLower(int v){return v>='A'&&v<='Z'?v+32:v;}
    private static int asciiUpper(int v){return v>='a'&&v<='z'?v-32:v;}
    private static boolean asciiAlpha(int v){return (v>='A'&&v<='Z')||(v>='a'&&v<='z');}
    private static Object binaryCase(PyByteSequence seq,String op){
        byte[] src=seq.toByteArray(),out=Arrays.copyOf(src,src.length);
        switch(op){
            case "lower" -> {for(int i=0;i<out.length;i++)out[i]=(byte)asciiLower(out[i]&0xff);}
            case "upper" -> {for(int i=0;i<out.length;i++)out[i]=(byte)asciiUpper(out[i]&0xff);}
            case "swapcase" -> {
                for(int i=0;i<out.length;i++){int v=out[i]&0xff;if(v>='a'&&v<='z')v-=32;else if(v>='A'&&v<='Z')v+=32;out[i]=(byte)v;}
            }
            case "capitalize" -> {
                if(out.length>0)out[0]=(byte)asciiUpper(out[0]&0xff);
                for(int i=1;i<out.length;i++)out[i]=(byte)asciiLower(out[i]&0xff);
            }
            case "title" -> {
                boolean wordStart=true;
                for(int i=0;i<out.length;i++){
                    int v=out[i]&0xff;
                    out[i]=(byte)(wordStart?asciiUpper(v):asciiLower(v));
                    wordStart=!asciiAlpha(v);
                }
            }
            default -> throw new PyException("RuntimeError","unknown binary case transform");
        }
        return binaryResult(seq,out);
    }

    private static Object binaryFind(PyByteSequence seq,Object[] args){
        if(args.length<1||args.length>3)throw new PyException("TypeError","find() takes from 1 to 3 arguments");
        byte[] hay=seq.toByteArray(), needle=requireBytesLike(args[0]);
        int start=args.length>=2?clampSliceBound(args[1],hay.length,0):0;
        int end=args.length>=3?clampSliceBound(args[2],hay.length,hay.length):hay.length;
        return (long)binaryIndexOf(hay,needle,start,end);
    }
    private static Object binaryCount(PyByteSequence seq,Object[] args){
        if(args.length<1||args.length>3)throw new PyException("TypeError","count() takes from 1 to 3 arguments");
        byte[] hay=seq.toByteArray();
        byte[] needle;
        if(isIntLike(args[0])) needle=new byte[]{(byte)byteValue(args[0])};
        else needle=requireBytesLike(args[0]);
        int start=args.length>=2?clampSliceBound(args[1],hay.length,0):0;
        int end=args.length>=3?clampSliceBound(args[2],hay.length,hay.length):hay.length;
        if(needle.length==0)return (long)Math.max(0,end-start+1);
        long n=0; int pos=start;
        while(pos<=end-needle.length){int found=binaryIndexOf(hay,needle,pos,end);if(found<0)break;n++;pos=found+needle.length;}
        return n;
    }
    private static Object binaryStartsEnds(PyByteSequence seq,Object[] args,boolean starts){
        if(args.length<1||args.length>3)throw new PyException("TypeError",(starts?"startswith":"endswith")+"() takes from 1 to 3 arguments");
        byte[] hay=seq.toByteArray(), needle=requireBytesLike(args[0]);
        int start=args.length>=2?clampSliceBound(args[1],hay.length,0):0;
        int end=args.length>=3?clampSliceBound(args[2],hay.length,hay.length):hay.length;
        if(end<start||needle.length>end-start)return false;
        int pos=starts?start:end-needle.length;
        for(int i=0;i<needle.length;i++)if(hay[pos+i]!=needle[i])return false;
        return true;
    }
    private static Object binaryReplace(PyByteSequence seq,Object[] args){
        if(args.length<2||args.length>3)throw new PyException("TypeError","replace() takes 2 or 3 arguments");
        byte[] src=seq.toByteArray(), old=requireBytesLike(args[0]), repl=requireBytesLike(args[1]);
        int limit=args.length==3?asIndex(args[2]):-1;
        if(limit==0)return binaryResult(seq,src);
        ArrayList<Byte> out=new ArrayList<>();
        int pos=0,replaced=0;
        if(old.length==0){
            int slots=src.length+1;
            for(int i=0;i<slots;i++){
                if(limit<0||replaced<limit){for(byte b:repl)out.add(b);replaced++;}
                if(i<src.length)out.add(src[i]);
            }
        } else {
            while(pos<src.length){
                int found=(limit<0||replaced<limit)?binaryIndexOf(src,old,pos,src.length):-1;
                if(found<0){while(pos<src.length)out.add(src[pos++]);break;}
                while(pos<found)out.add(src[pos++]);
                for(byte b:repl)out.add(b);pos+=old.length;replaced++;
            }
        }
        byte[] raw=new byte[out.size()];for(int i=0;i<out.size();i++)raw[i]=out.get(i);
        return binaryResult(seq,raw);
    }
    private static boolean asciiWhitespace(int v){return v==9||v==10||v==11||v==12||v==13||v==32;}
    private static Object binarySplit(PyByteSequence seq,Object[] args){
        if(args.length>2)throw new PyException("TypeError","split() takes at most 2 arguments");
        byte[] src=seq.toByteArray();
        Object sepObj=args.length>=1?args[0]:null;
        int maxsplit=args.length==2?asIndex(args[1]):-1;
        ArrayList<Object> out=new ArrayList<>();
        if(sepObj==null){
            int i=0,splits=0;
            while(i<src.length){
                while(i<src.length&&asciiWhitespace(src[i]&0xff))i++;
                if(i>=src.length)break;
                int start=i;
                if(maxsplit>=0&&splits>=maxsplit){i=src.length;}
                else {while(i<src.length&&!asciiWhitespace(src[i]&0xff))i++;splits++;}
                out.add(binaryResult(seq,Arrays.copyOfRange(src,start,i)));
            }
            return out;
        }
        byte[] sep=requireBytesLike(sepObj);
        if(sep.length==0)throw new PyException("ValueError","empty separator");
        int pos=0,splits=0;
        while(maxsplit<0||splits<maxsplit){
            int found=binaryIndexOf(src,sep,pos,src.length);if(found<0)break;
            out.add(binaryResult(seq,Arrays.copyOfRange(src,pos,found)));
            pos=found+sep.length;splits++;
        }
        out.add(binaryResult(seq,Arrays.copyOfRange(src,pos,src.length)));
        return out;
    }
    private static Object binaryJoin(PyByteSequence sep,Object iterableObj){
        ArrayList<byte[]> parts=new ArrayList<>();int total=0;
        for(Object value:iterable(iterableObj)){byte[] raw=requireBytesLike(value);parts.add(raw);total=Math.addExact(total,raw.length);}
        byte[] delimiter=sep.toByteArray();
        if(parts.size()>1)total=Math.addExact(total,Math.multiplyExact(delimiter.length,parts.size()-1));
        byte[] out=new byte[total];int pos=0;
        for(int i=0;i<parts.size();i++){
            if(i>0){System.arraycopy(delimiter,0,out,pos,delimiter.length);pos+=delimiter.length;}
            byte[] part=parts.get(i);System.arraycopy(part,0,out,pos,part.length);pos+=part.length;
        }
        return binaryResult(sep,out);
    }
    private static void byteArraySetSlice(PyByteArray array,PySlice sl,Object replacementObj){
        byte[] replacement=bytesFromObject(replacementObj);
        int size=array.byteSize();
        int step=sl.step==null?1:asIndex(sl.step);
        if(step==0)throw new PyException("ValueError","slice step cannot be zero");
        int start=sl.start==null?(step>0?0:size-1):normalizeSliceIndex(asIndex(sl.start),size,step>0);
        int stop=sl.stop==null?(step>0?size:-1):normalizeSliceStop(asIndex(sl.stop),size,step>0);
        ArrayList<Integer> indexes=new ArrayList<>();
        for(int i=start;step>0?i<stop:i>stop;i+=step)if(i>=0&&i<size)indexes.add(i);
        if(step!=1){
            if(indexes.size()!=replacement.length)throw new PyException("ValueError","attempt to assign bytes of size "+replacement.length+" to extended slice of size "+indexes.size());
            for(int i=0;i<indexes.size();i++)array.data.set(indexes.get(i),replacement[i]);
            return;
        }
        int a=Math.max(0,Math.min(size,start)), z=Math.max(a,Math.min(size,stop));
        for(int i=z-1;i>=a;i--)array.data.remove(i);
        for(int i=0;i<replacement.length;i++)array.data.add(a+i,replacement[i]);
    }

    private static Object binarySlice(PyByteSequence seq,PySlice sl,boolean asView){
        int size=seq.byteSize();
        int step=sl.step==null?1:asIndex(sl.step);
        if(step==0) throw new PyException("ValueError","slice step cannot be zero");
        int start=sl.start==null?(step>0?0:size-1):normalizeSliceIndex(asIndex(sl.start),size,step>0);
        int stop=sl.stop==null?(step>0?size:-1):normalizeSliceStop(asIndex(sl.stop),size,step>0);
        ArrayList<Byte> vals=new ArrayList<>();
        for(int i=start;step>0?i<stop:i>stop;i+=step) if(i>=0&&i<size) vals.add((byte)seq.unsignedAt(i));
        byte[] raw=new byte[vals.size()];for(int i=0;i<vals.size();i++)raw[i]=vals.get(i);
        if(asView && step==1 && seq instanceof PyMemoryView mv) return new PyMemoryView(mv.source,mv.start+start,raw.length);
        if(seq instanceof PyByteArray) return new PyByteArray(raw);
        if(seq instanceof PyMemoryView) return new PyMemoryView(new PyBytes(raw));
        return new PyBytes(raw);
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

    private static final Object UNBOUND = new Object();
    public static Object unbound() { return UNBOUND; }
    public static Object requireLocal(Object value,Object name) {
        if(value==UNBOUND) throw new PyException("UnboundLocalError","local variable '"+name+"' referenced before assignment");
        return value;
    }
    public static Object requireGlobal(Object value,Object name) {
        if(value==UNBOUND) throw new PyException("NameError","name '"+name+"' is not defined");
        return value;
    }
    public static Object globalGet(Object value,Object nameObj) {
        if(value!=UNBOUND) return value;
        String name=(String)nameObj;
        if(name.equals("NotImplemented")) return NOT_IMPLEMENTED;
        if(name.equals("Ellipsis")) return ELLIPSIS;
        if(Set.of("ord","chr","repr","print","hash","id","len","iter","next","reversed","getattr","hasattr","setattr","delattr","callable","isinstance","issubclass","pow","abs","any","all","sum").contains(name)) return builtinFunction(name);
        if(Set.of("object","int","bool","float","complex","str","bytes","bytearray","memoryview","list","tuple","dict","set","range","type","map","slice").contains(name) || exceptionIsSubclass(name,"BaseException")) return builtinType(name);
        return requireGlobal(value,name);
    }
    public static Object envGetLocal(Object envObj, Object nameObj) {
        PyEnv env=(PyEnv)envObj; String name=(String)nameObj;
        return requireLocal(env.values.getOrDefault(name,UNBOUND),name);
    }
    public static Object envGet(Object envObj, Object nameObj) {
        PyEnv env=(PyEnv)envObj; String name=(String)nameObj;
        while(env!=null) {
            if(env.values.containsKey(name)) return requireGlobal(env.values.get(name),name);
            env=env.parent;
        }
        throw new PyException("NameError","free variable '"+name+"' is not defined");
    }
    public static void envDelLocal(Object envObj,Object nameObj) {
        PyEnv env=(PyEnv)envObj; String name=(String)nameObj;
        requireLocal(env.values.getOrDefault(name,UNBOUND),name);
        env.values.put(name,UNBOUND);
    }
    public static void envDelNonlocal(Object envObj,Object nameObj) {
        PyEnv env=((PyEnv)envObj).parent; String name=(String)nameObj;
        while(env!=null) {
            if(env.values.containsKey(name)) { requireGlobal(env.values.get(name),name);env.values.put(name,UNBOUND);return; }
            env=env.parent;
        }
        throw new PyException("NameError","free variable '"+name+"' is not defined");
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
        String displayName, filename; long firstlineno;

        PyGenerator(String owner, String resumeMethod, PyEnv env) {
            this(owner,resumeMethod,env,resumeMethod,owner,0);
        }
        PyGenerator(String owner,String resumeMethod,PyEnv env,String displayName,String filename,long firstlineno) {
            this.owner=owner; this.resumeMethod=resumeMethod; this.env=env; this.displayName=displayName; this.filename=filename; this.firstlineno=firstlineno;
        }

        private Object advance() {
            if (finished) throw new PyGeneratorEnd(returnValue);
            started = true;
            ActiveFrame logical=new ActiveFrame(displayName,filename,firstlineno);
            LOGICAL_FRAMES.get().push(logical);
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
                    PyException wrapped=new PyException("RuntimeError", "generator raised StopIteration"); wrapped.addFrame(displayName,filename,logical.firstlineno,logical.line); throw wrapped;
                }
                if (cause instanceof PyException p) { finished=true; p.addFrame(displayName,filename,logical.firstlineno,logical.line); throw p; }
                if (cause instanceof RuntimeException r) { finished=true; throw r; }
                if (cause instanceof Error e) { finished=true; throw e; }
                throw new RuntimeException(cause);
            } catch (ReflectiveOperationException exc) {
                throw new RuntimeException(exc);
            } finally {
                ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get(); if(!stack.isEmpty() && stack.peek()==logical) stack.pop();
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
    public static Object makeGeneratorEx(Object ownerObj,Object methodObj,Object envObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        return new PyGenerator((String)ownerObj,(String)methodObj,(PyEnv)envObj,(String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue());
    }
    public static Object makeSuspendableCoroutineEx(Object ownerObj,Object methodObj,Object envObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        PyGenerator frame=new PyGenerator((String)ownerObj,(String)methodObj,(PyEnv)envObj,(String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue());
        return new PyCoroutine(frame,(String)nameObj);
    }
    public static final class PyAsyncGeneratorYield {
        final Object value;
        PyAsyncGeneratorYield(Object value){this.value=value;}
    }
    public static Object asyncGeneratorYield(Object value){return new PyAsyncGeneratorYield(value);}

    public static Object makeAsyncGeneratorEx(Object ownerObj,Object methodObj,Object envObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        PyGenerator frame=new PyGenerator((String)ownerObj,(String)methodObj,(PyEnv)envObj,(String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue());
        return new PyAsyncGenerator(frame,(String)nameObj);
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
            if(iteratorObj instanceof PyCoroutineAwaitIterator awaiter) {
                if(exceptionObjectIs(pending,"GeneratorExit")) {
                    awaiter.close();
                    raiseObject(pending);
                    return null;
                }
                try {
                    return new PyYieldFromResult(false,awaiter.throw_(pending));
                } catch(PyGeneratorEnd end) {
                    return new PyYieldFromResult(true,end.value);
                }
            }
            if(iteratorObj instanceof PyAsyncGenAwaitIterator awaiter) {
                if(exceptionObjectIs(pending,"GeneratorExit")) {
                    awaiter.close();
                    raiseObject(pending);
                    return null;
                }
                try {
                    return new PyYieldFromResult(false,awaiter.throw_(pending));
                } catch(PyGeneratorEnd end) {
                    return new PyYieldFromResult(true,end.value);
                }
            }
            raiseObject(pending);
            return null;
        }
        if(iteratorObj instanceof PyCoroutineAwaitIterator awaiter) {
            try { return new PyYieldFromResult(false,awaiter.send(parent.sentValue)); }
            catch(PyGeneratorEnd end) { return new PyYieldFromResult(true,end.value); }
        }
        if(iteratorObj instanceof PyAsyncGenAwaitIterator awaiter) {
            try { return new PyYieldFromResult(false,awaiter.send(parent.sentValue)); }
            catch(PyGeneratorEnd end) { return new PyYieldFromResult(true,end.value); }
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
        if (iteratorObj instanceof PyCoroutineAwaitIterator awaiter) return awaiter.coroutine.result;
        if (iteratorObj instanceof PyAsyncGenAwaitIterator awaiter) return awaiter.result;
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

    public static void setFunctionMeta(Object functionObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        PyFunction f=(PyFunction)functionObj; f.displayName=(String)nameObj; f.filename=(String)filenameObj; f.firstlineno=bigInt(firstlineObj).longValue();
    }
    public static void setFunctionAsync(Object functionObj) { ((PyFunction)functionObj).asyncMode=true; }
    public static void setFunctionAsyncGenerator(Object functionObj) { ((PyFunction)functionObj).asyncGeneratorMode=true; }

    private static List<String> splitNames(String csv) {
        if (csv == null || csv.isEmpty()) return List.of();
        return Arrays.asList(csv.split(",", -1));
    }

    public static Object callFunction(Object callable, Object argsObj, Object kwargsObj) {
        @SuppressWarnings("unchecked") List<Object> args = (List<Object>) argsObj;
        @SuppressWarnings("unchecked") Map<Object,Object> kwargs = (Map<Object,Object>) kwargsObj;
        if (callable instanceof PyFunction f) return f.call(args, kwargs);
        if (callable instanceof PyBuiltinFunction f) return callBuiltin(f.name,args,kwargs);
        if (callable instanceof BuiltinBoundMethod method) {
            if(!kwargs.isEmpty())throw new PyException("TypeError","method takes no keyword arguments");
            return callMethod(method.self,method.name,args.toArray());
        }
        if (callable instanceof PyBuiltinType type) {
            if(type.name.equals("complex"))return complexConstructor(args,kwargs);
            if(type.name.equals("map")) return callBuiltin("map",args,kwargs);
            if(type.name.equals("slice")) {
                if(!kwargs.isEmpty() || args.isEmpty() || args.size()>3) throw new PyException("TypeError","slice() requires 1 to 3 positional arguments");
                return new PySlice(args.size()==1?null:args.get(0),args.size()==1?args.get(0):args.get(1),args.size()<3?null:args.get(2));
            }
            if(exceptionIsSubclass(type.name,"BaseException")) {
                if(!kwargs.isEmpty() || args.size()>1) throw new PyException("TypeError","exception accepts zero or one argument in this runtime");
                return makeException(type.name,args.isEmpty()?null:args.get(0));
            }
            if(!kwargs.isEmpty() || args.size()>1) throw new PyException("TypeError","invalid builtin constructor arguments");
            Object value=args.isEmpty()?null:args.get(0);
            return switch(type.name) {
                case "str" -> args.isEmpty()?"":str_(value);
                case "int" -> args.isEmpty()?0L:int_(value);
                case "bool" -> args.isEmpty()?false:bool_(value);
                case "float" -> args.isEmpty()?0.0:float_(value);
                case "list" -> args.isEmpty()?list0():listFrom(value);
                case "tuple" -> args.isEmpty()?tuple0():tupleFrom(value);
                case "dict" -> args.isEmpty()?dict0():dictFrom(value);
                case "set" -> args.isEmpty()?set0():setFrom(value);
                case "bytes" -> args.isEmpty()?bytes0():bytes1(value);
                case "bytearray" -> args.isEmpty()?bytearray0():bytearray1(value);
                default -> throw new PyException("TypeError","unsupported builtin constructor "+type.name);
            };
        }
        if (callable instanceof BoundMethod bm) {
            PyMethod method=bm.self.cls.lookupMethod(bm.name);
            if(method==null) throw new PyException("AttributeError","method not found");
            return invokeKw(bm.self,method,args,kwargs);
        }
        if (callable instanceof BoundSuperMethod bm) {
            PyMethod method=bm.self.cls.lookupMethodAfter(bm.currentClass,bm.name);
            if(method==null) throw new PyException("AttributeError","super attribute not found");
            return invokeKw(bm.self,method,args,kwargs);
        }
        if(callable instanceof BoundClassMethod bm){PyMethod m=bm.cls.lookupMethod(bm.name);return invokeOnClassKw(bm.cls,m,args,kwargs);}
        if(callable instanceof BoundStaticMethod bm){PyMethod m=bm.cls.lookupMethod(bm.name);return invokeOnClassKw(bm.cls,m,args,kwargs);}
        if(callable instanceof UnboundMethod bm){PyMethod m=bm.cls.lookupMethod(bm.name);return invokeOnClassKw(bm.cls,m,args,kwargs);}
        if (callable instanceof PyClass cls) {
            return instantiate(cls, args.toArray(), kwargs);
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
        String displayName, filename; long firstlineno; boolean asyncMode=false, asyncGeneratorMode=false;

        PyFunction(String owner, String method, List<String> posonly, List<String> poskw, List<String> kwonly,
                   String vararg, String kwarg, LinkedHashMap<String,Object> defaults) {
            this(owner, method, posonly, poskw, kwonly, vararg, kwarg, defaults, null, false);
        }

        PyFunction(String owner, String method, List<String> posonly, List<String> poskw, List<String> kwonly,
                   String vararg, String kwarg, LinkedHashMap<String,Object> defaults, PyEnv closure, boolean envMode) {
            this.owner=owner; this.method=method; this.posonly=posonly; this.poskw=poskw; this.kwonly=kwonly;
            this.vararg=vararg; this.kwarg=kwarg; this.defaults=defaults; this.closure=closure; this.envMode=envMode;
            this.displayName=method; this.filename=owner; this.firstlineno=0;
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
            Object[] boundArray=bound.toArray();
            if(asyncMode) return new PyCoroutine(this,boundArray);
            return invokeStatic(boundArray);
        }

        private Object invokeStatic(Object[] bound) {
            ActiveFrame logical=new ActiveFrame(displayName,filename,firstlineno);
            List<String> localNames=new ArrayList<>(); localNames.addAll(posonly); localNames.addAll(poskw); localNames.addAll(kwonly);
            if(vararg!=null)localNames.add(vararg); if(kwarg!=null)localNames.add(kwarg);
            for(int localIndex=0;localIndex<Math.min(localNames.size(),bound.length);localIndex++) logical.locals.put(localNames.get(localIndex),bound[localIndex]);
            LOGICAL_FRAMES.get().push(logical);
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
                if (cause instanceof PyException p) { p.addFrame(logical.name,logical.filename,logical.firstlineno,logical.line); throw p; }
                if (cause instanceof RuntimeException r) throw r;
                if (cause instanceof Error e) throw e;
                throw new RuntimeException(cause);
            } catch (ReflectiveOperationException exc) {
                throw new RuntimeException(exc);
            } finally {
                ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get(); if(!stack.isEmpty() && stack.peek()==logical) stack.pop();
            }
        }

        @Override public String toString() { return "<function " + method + ">"; }
    }

    public static final class PyAsyncGenerator {
        final PyGenerator frame; final String displayName;
        PyAsyncGenerator(PyGenerator frame,String displayName){this.frame=frame;this.displayName=displayName;}
        Object anext(){return new PyAsyncGenAwaitable(this,"next",null);}
        Object asend(Object value){return new PyAsyncGenAwaitable(this,"send",value);}
        Object athrow(Object value){return new PyAsyncGenAwaitable(this,"throw",value);}
        Object aclose(){return new PyAsyncGenAwaitable(this,"close",null);}
        @Override public String toString(){return "<async_generator object "+displayName+">";}
    }

    private static final class PyAsyncGenAwaitable {
        final PyAsyncGenerator generator; final String action; final Object value;
        PyAsyncGenAwaitable(PyAsyncGenerator generator,String action,Object value){this.generator=generator;this.action=action;this.value=value;}
        Object iterator(){return new PyAsyncGenAwaitIterator(this);}
    }

    private static final class PyAsyncGenAwaitIterator implements Iterator<Object>, Iterable<Object> {
        final PyAsyncGenAwaitable awaitable;
        boolean started=false, done=false; Object result=null;
        PyAsyncGenAwaitIterator(PyAsyncGenAwaitable awaitable){this.awaitable=awaitable;}

        Object send(Object sent){
            if(done) throw new PyGeneratorEnd(result);
            Object out;
            try {
                if(!started) {
                    started=true;
                    out = switch(awaitable.action) {
                        case "next" -> awaitable.generator.frame.send(null);
                        case "send" -> awaitable.generator.frame.send(awaitable.value);
                        case "throw" -> awaitable.generator.frame.throw_(awaitable.value);
                        case "close" -> { awaitable.generator.frame.close(); yield null; }
                        default -> throw new PyException("RuntimeError","unknown async generator operation");
                    };
                    if(awaitable.action.equals("close")) {
                        done=true; result=null; throw new PyGeneratorEnd(null);
                    }
                } else {
                    out=awaitable.generator.frame.send(sent);
                }
            } catch(PyGeneratorEnd exhausted) {
                done=true;
                if(awaitable.action.equals("close")) { result=null; throw new PyGeneratorEnd(null); }
                throw new PyException("StopAsyncIteration",null);
            }
            if(out instanceof PyAsyncGeneratorYield item) {
                done=true; result=item.value; throw new PyGeneratorEnd(result);
            }
            return out;
        }

        Object throw_(Object thrown){
            if(done){raiseObject(thrown);return null;}
            if(!started) {
                started=true;
                try {
                    Object out=awaitable.generator.frame.throw_(thrown);
                    if(out instanceof PyAsyncGeneratorYield item) {done=true;result=item.value;throw new PyGeneratorEnd(result);}
                    return out;
                } catch(PyGeneratorEnd exhausted) {done=true;throw new PyException("StopAsyncIteration",null);}
            }
            try {
                Object out=awaitable.generator.frame.throw_(thrown);
                if(out instanceof PyAsyncGeneratorYield item) {done=true;result=item.value;throw new PyGeneratorEnd(result);}
                return out;
            } catch(PyGeneratorEnd exhausted) {done=true;throw new PyException("StopAsyncIteration",null);}
        }

        Object close(){done=true;return awaitable.generator.frame.close();}
        public boolean hasNext(){return !done;}
        public Object next(){return send(null);}
        public Iterator<Object> iterator(){return this;}
    }

    public static final class PyCoroutine {
        final PyFunction function; final Object[] bound; final String displayName;
        PyGenerator frame=null; PyCoroutine adopted=null;
        boolean started=false, finished=false, closed=false; Object result=null;

        PyCoroutine(PyFunction function,Object[] bound){
            this.function=function;this.bound=bound;this.displayName=function.displayName;
        }
        PyCoroutine(PyGenerator frame,String displayName){
            this.function=null;this.bound=null;this.frame=frame;this.displayName=displayName;
        }

        private Object resume(Object value,Object thrown,boolean closing){
            if(closed) throw new PyException("RuntimeError","cannot reuse already awaited coroutine");
            if(finished) throw new PyException("RuntimeError","cannot reuse already awaited coroutine");
            if(!started && value!=null && thrown==null)
                throw new PyException("TypeError","can't send non-None value to a just-started coroutine");
            started=true;

            if(adopted!=null) {
                try { return adopted.resume(value,thrown,closing); }
                catch(PyException e) {
                    if(e.typeName.equals("StopIteration")) { finished=true; result=e.value; }
                    throw e;
                }
            }
            if(frame!=null) {
                try {
                    Object yielded;
                    if(closing) { frame.close(); finished=true; result=null; throw new PyException("StopIteration",null); }
                    if(thrown!=null) yielded=frame.throw_(thrown);
                    else yielded=frame.send(value);
                    return yielded;
                } catch(PyGeneratorEnd end) {
                    finished=true; result=end.value; throw new PyException("StopIteration",result);
                }
            }

            Object out=function.invokeStatic(bound);
            if(out instanceof PyCoroutine inner) {
                adopted=inner;
                try { return adopted.resume(value,thrown,closing); }
                catch(PyException e) {
                    if(e.typeName.equals("StopIteration")) { finished=true; result=e.value; }
                    throw e;
                }
            }
            finished=true; result=out;
            throw new PyException("StopIteration",out);
        }

        Object run(){
            while(true) {
                try {
                    Object yielded=resume(null,null,false);
                    throw new PyException("RuntimeError","coroutine suspended while synchronous completion was required: "+pyRepr(yielded));
                } catch(PyException e) {
                    if(e.typeName.equals("StopIteration")) return e.value;
                    throw e;
                }
            }
        }
        Object send(Object value){return resume(value,null,false);}
        Object throw_(Object value){return resume(null,value,false);}
        Object close(){
            if(finished || closed){closed=true;return null;}
            try { resume(null,new PyExceptionValue("GeneratorExit",null),true); }
            catch(PyException e) {
                if(e.typeName.equals("StopIteration") || e.typeName.equals("GeneratorExit")) { closed=true;finished=true;return null; }
                throw e;
            }
            closed=true;finished=true;return null;
        }
        Object awaitIterator(){return new PyCoroutineAwaitIterator(this);}
        @Override public String toString(){return "<coroutine object "+displayName+">";}
    }
    private static final class PyCoroutineAwaitIterator implements Iterator<Object>, Iterable<Object> {
        final PyCoroutine coroutine; boolean done=false;
        PyCoroutineAwaitIterator(PyCoroutine coroutine){this.coroutine=coroutine;}
        Object send(Object value){
            if(done)throw new PyGeneratorEnd(coroutine.result);
            try { return coroutine.send(value); }
            catch(PyException e) {
                if(e.typeName.equals("StopIteration")) { done=true; throw new PyGeneratorEnd(e.value); }
                throw e;
            }
        }
        Object throw_(Object value){
            if(done){raiseObject(value);return null;}
            try { return coroutine.throw_(value); }
            catch(PyException e) {
                if(e.typeName.equals("StopIteration")) { done=true; throw new PyGeneratorEnd(e.value); }
                throw e;
            }
        }
        Object close(){done=true;return coroutine.close();}
        public boolean hasNext(){return !done && !coroutine.finished && !coroutine.closed;}
        public Object next(){return send(null);}
        public Iterator<Object> iterator(){return this;}
    }
    public static Object awaitIterator(Object value){
        if(value instanceof PyCoroutine coroutine) return coroutine.awaitIterator();
        if(value instanceof PyAsyncGenAwaitable awaitable) return awaitable.iterator();
        if(value instanceof PyInstance instance && instance.cls.lookupMethod("__await__")!=null)
            return invoke(instance,instance.cls.lookupMethod("__await__"),new Object[0]);
        throw new PyException("TypeError","object can't be used in 'await' expression");
    }
    public static Object awaitValue(Object value){
        if(value instanceof PyCoroutine coroutine) return coroutine.run();
        Object iterator=awaitIterator(value);
        while(true){
            try { next_(iterator); }
            catch(PyException e){if(e.typeName.equals("StopIteration"))return e.value;throw e;}
        }
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
        Object result=callMethod(manager, "__exit__", new Object[]{builtinType(pythonExceptionType(t)), exceptionInstance(t), null});
        return truth(result);
    }
    public static Object asyncWithEnter(Object manager) {
        return awaitValue(callMethod(manager,"__aenter__",new Object[0]));
    }
    public static void asyncWithExitNormal(Object manager) {
        awaitValue(callMethod(manager,"__aexit__",new Object[]{null,null,null}));
    }
    public static boolean asyncWithExitException(Object manager,Object throwableObj) {
        Throwable t=(Throwable)throwableObj;
        Object result=awaitValue(callMethod(manager,"__aexit__",new Object[]{builtinType(pythonExceptionType(t)),exceptionInstance(t),null}));
        return truth(result);
    }

    // ---------- Python-visible code/frame/traceback objects ----------
    private static final class ActiveFrame {
        final String name, filename; final long firstlineno; long line;
        final LinkedHashMap<Object,Object> locals = new LinkedHashMap<>();
        ActiveFrame(String name,String filename,long firstlineno){this.name=name;this.filename=filename;this.firstlineno=firstlineno;this.line=firstlineno;}
    }
    private static final ThreadLocal<ArrayDeque<ActiveFrame>> LOGICAL_FRAMES=ThreadLocal.withInitial(ArrayDeque::new);
    public static void pushLogicalFrame(Object nameObj,Object filenameObj,Object firstlineObj){
        LOGICAL_FRAMES.get().push(new ActiveFrame((String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue()));
    }
    public static void popLogicalFrame(){ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get();if(!stack.isEmpty())stack.pop();}
    public static void setCurrentLine(Object lineObj){ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get();if(!stack.isEmpty())stack.peek().line=bigInt(lineObj).longValue();}
    public static void frameSetLocal(Object nameObj,Object value){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.locals.put(nameObj,value);}
    public static Object frameSetLocalValue(Object value,Object nameObj){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.locals.put(nameObj,value);return value;}
    public static void frameDelLocal(Object nameObj){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.locals.remove(nameObj);}
    private static ActiveFrame currentLogicalFrame(){ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get();return stack.isEmpty()?null:stack.peek();}

    public static final class PyCode {
        final String coName, coFilename; final long coFirstlineno;
        PyCode(String name,String filename,long firstlineno){this.coName=name;this.coFilename=filename;this.coFirstlineno=firstlineno;}
        @Override public String toString(){return "<code object "+coName+">";}
    }
    public static final class PyFrame {
        final PyCode code; final LinkedHashMap<Object,Object> locals;
        PyFrame(PyCode code){this(code,new LinkedHashMap<Object,Object>());}
        PyFrame(PyCode code,LinkedHashMap<Object,Object> locals){this.code=code;this.locals=locals;}
        @Override public String toString(){return "<frame "+code.coName+">";}
    }
    public static final class PyTraceback {
        final PyFrame frame; final long lineno; final PyTraceback next;
        PyTraceback(PyFrame frame,long lineno,PyTraceback next){this.frame=frame;this.lineno=lineno;this.next=next;}
        @Override public String toString(){return "<traceback object>";}
    }
    private static PyTraceback prependTraceback(PyTraceback current,String name,String filename,long firstline,long line){
        ActiveFrame active=currentLogicalFrame();
        LinkedHashMap<Object,Object> locals = active!=null && active.name.equals(name) && active.filename.equals(filename)
            ? active.locals : new LinkedHashMap<Object,Object>();
        if(current!=null && current.frame.code.coName.equals(name) && current.frame.code.coFilename.equals(filename)) return current;
        PyCode code=new PyCode(name,filename,firstline);
        return new PyTraceback(new PyFrame(code,locals),line,current);
    }
    public static void tracebackAddFrame(Object throwableObj,Object nameObj,Object filenameObj,Object lineObj){
        if(!(throwableObj instanceof PyException p)) return;
        long line=bigInt(lineObj).longValue(); p.traceback=prependTraceback(p.traceback,(String)nameObj,(String)filenameObj,line,line);
    }
    public static void tracebackAddCurrentFrame(Object throwableObj){
        if(!(throwableObj instanceof PyException p)) return;
        ActiveFrame frame=currentLogicalFrame();
        if(frame!=null) p.traceback=prependTraceback(p.traceback,frame.name,frame.filename,frame.firstlineno,frame.line);
    }

    // ---------- Exceptions ----------
    public static Object makeException(Object typeObj, Object value) {
        return new PyExceptionValue((String)typeObj, value);
    }

    public static void raiseObject(Object value) {
        if (value instanceof PyExceptionValue e) throw new PyException(e.typeName, e.value, e.cause, e.context, e.suppressContext, e.traceback);
        if (value instanceof PyException e) throw e;
        if (value instanceof PyBuiltinType t && exceptionIsSubclass(t.name,"BaseException")) throw new PyException(t.name,null);
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
        if (value instanceof PyExceptionValue e) raised=new PyException(e.typeName,e.value,e.cause,e.context,e.suppressContext,e.traceback);
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

    private static final Map<String,String> EXCEPTION_PARENTS = Map.ofEntries(
        Map.entry("Exception","BaseException"), Map.entry("GeneratorExit","BaseException"),
        Map.entry("KeyboardInterrupt","BaseException"), Map.entry("SystemExit","BaseException"),
        Map.entry("ArithmeticError","Exception"), Map.entry("LookupError","Exception"),
        Map.entry("ZeroDivisionError","ArithmeticError"), Map.entry("OverflowError","ArithmeticError"),
        Map.entry("FloatingPointError","ArithmeticError"), Map.entry("IndexError","LookupError"),
        Map.entry("KeyError","LookupError"), Map.entry("NameError","Exception"),
        Map.entry("UnboundLocalError","NameError"), Map.entry("RuntimeError","Exception"),
        Map.entry("NotImplementedError","RuntimeError"), Map.entry("RecursionError","RuntimeError"),
        Map.entry("ValueError","Exception"), Map.entry("TypeError","Exception"),
        Map.entry("AttributeError","Exception"), Map.entry("AssertionError","Exception"),
        Map.entry("StopIteration","Exception"), Map.entry("StopAsyncIteration","Exception"),
        Map.entry("ImportError","Exception"), Map.entry("ModuleNotFoundError","ImportError"),
        Map.entry("MemoryError","Exception"), Map.entry("BufferError","Exception"),
        Map.entry("SyntaxError","Exception"), Map.entry("IndentationError","SyntaxError"),
        Map.entry("TabError","IndentationError"), Map.entry("UnicodeError","ValueError"),
        Map.entry("UnicodeEncodeError","UnicodeError"), Map.entry("UnicodeDecodeError","UnicodeError"),
        Map.entry("UnicodeTranslateError","UnicodeError"), Map.entry("Warning","Exception"),
        Map.entry("UserWarning","Warning"), Map.entry("DeprecationWarning","Warning"),
        Map.entry("PendingDeprecationWarning","Warning"), Map.entry("SyntaxWarning","Warning"),
        Map.entry("RuntimeWarning","Warning"), Map.entry("FutureWarning","Warning"),
        Map.entry("ImportWarning","Warning"), Map.entry("UnicodeWarning","Warning"),
        Map.entry("BytesWarning","Warning"), Map.entry("ResourceWarning","Warning"),
        Map.entry("OSError","Exception"), Map.entry("FileNotFoundError","OSError"),
        Map.entry("PermissionError","OSError"), Map.entry("TimeoutError","OSError"),
        Map.entry("ConnectionError","OSError"), Map.entry("BlockingIOError","OSError"),
        Map.entry("ChildProcessError","OSError"), Map.entry("InterruptedError","OSError"),
        Map.entry("IsADirectoryError","OSError"), Map.entry("NotADirectoryError","OSError"),
        Map.entry("ProcessLookupError","OSError"));
    private static boolean exceptionIsSubclass(String actual, String requested) {
        if(actual.equals(requested)) return actual.equals("BaseException") || EXCEPTION_PARENTS.containsKey(actual);
        for(String parent=EXCEPTION_PARENTS.get(actual);parent!=null;parent=EXCEPTION_PARENTS.get(parent))
            if(parent.equals(requested)) return true;
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
        if (t instanceof PyException p) return new PyExceptionValue(p.typeName,p.value,p.cause,p.context,p.suppressContext,p.traceback);
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
        final String typeName; final Object value; final Object cause; final Object context; final boolean suppressContext; final PyTraceback traceback;
        PyExceptionValue(String typeName, Object value) { this(typeName,value,null,null,false,null); }
        PyExceptionValue(String typeName, Object value, Object cause, Object context, boolean suppressContext) { this(typeName,value,cause,context,suppressContext,null); }
        PyExceptionValue(String typeName, Object value, Object cause, Object context, boolean suppressContext, PyTraceback traceback) { this.typeName=typeName; this.value=value; this.cause=cause; this.context=context; this.suppressContext=suppressContext; this.traceback=traceback; }
        @Override public String toString() { return typeName + (value == null ? "" : "(" + pyRepr(value) + ")"); }
    }

    public static final class PyException extends RuntimeException {
        final String typeName; final Object value; Object cause; Object context; boolean suppressContext; PyTraceback traceback;
        PyException(String typeName, Object value) { this(typeName,value,null,null,false,null); }
        PyException(String typeName, Object value, Object cause, Object context, boolean suppressContext) { this(typeName,value,cause,context,suppressContext,null); }
        PyException(String typeName, Object value, Object cause, Object context, boolean suppressContext, PyTraceback traceback) { super(value == null ? null : pyStr(value)); this.typeName=typeName; this.value=value; this.cause=cause; this.context=context; this.suppressContext=suppressContext; this.traceback=traceback; }
        void addFrame(String name,String filename,long firstline,long line){traceback=prependTraceback(traceback,name,filename,firstline,line);}
        void addFrame(String name,String filename,long line){addFrame(name,filename,line,line);}
        @Override public String toString() { return typeName + (value == null ? "" : ": " + pyStr(value)); }
    }

    // ---------- Python classes / instances ----------
    public static final class PyClassNamespace extends LinkedHashMap<Object,Object> {
        final PyClass prebuilt;
        PyClassNamespace(PyClass prebuilt){this.prebuilt=prebuilt;}
    }

    public static Object class0(Object name) { return new PyClass("__main__", (String) name); }
    public static Object classCreate(Object name,Object module) { return new PyClass((String)module,(String)name); }
    public static void classAddBase(Object cls, Object base) {
        PyClass target=(PyClass)cls;
        if(base instanceof PyClass pyBase){target.bases.add(pyBase);return;}
        if(base instanceof PyBuiltinType builtin && (builtin.name.equals("object") || builtin.name.equals("type"))) {
            target.builtinBaseName=builtin.name;
            return;
        }
        throw new PyException("TypeError","class base must be a class");
    }
    public static void classAddAttr(Object cls, Object pyName, Object value) {
        ((PyClass)cls).attrs.put((String)pyName, value);
    }

    private static PyTuple explicitBasesTuple(PyClass cls) {
        PyTuple tuple=new PyTuple(); tuple.items.addAll(cls.bases);
        if(cls.builtinBaseName!=null) tuple.items.add(new PyBuiltinType(cls.builtinBaseName));
        return tuple;
    }

    private static PyClass resolveMetaclass(PyClass cls,Object explicitMeta) {
        if(explicitMeta instanceof PyClass meta) return meta;
        if(explicitMeta instanceof PyBuiltinType builtin && builtin.name.equals("type")) return null;
        if(explicitMeta!=null) throw new PyException("TypeError","metaclass must be a class");
        PyClass selected=null;
        for(PyClass base:cls.bases) {
            if(base.metaclass==null) continue;
            if(selected==null) selected=base.metaclass;
            else if(selected!=base.metaclass && !selected.mro.contains(base.metaclass) && !base.metaclass.mro.contains(selected))
                throw new PyException("TypeError","metaclass conflict");
            else if(base.metaclass.mro.contains(selected)) selected=base.metaclass;
        }
        return selected;
    }

    public static Object classPrepare(Object clsObj,Object explicitMeta,Object kwargsObj) {
        PyClass cls=(PyClass)clsObj;
        @SuppressWarnings("unchecked") Map<Object,Object> kwargs=(Map<Object,Object>)kwargsObj;
        PyClass meta=resolveMetaclass(cls,explicitMeta);
        cls.metaclass=meta;
        PyClassNamespace namespace=new PyClassNamespace(cls);
        namespace.put("__module__",cls.moduleName);
        namespace.put("__qualname__",cls.qualname);

        if(meta!=null) {
            PyMethod prepare=meta.lookupMethod("__prepare__");
            if(prepare!=null) {
                ArrayList<Object> args=new ArrayList<>();
                args.add(cls.name); args.add(explicitBasesTuple(cls));
                Object prepared=invokeOnClassKw(meta,prepare,args,kwargs);
                if(!(prepared instanceof Map<?,?> map))
                    throw new PyException("TypeError","__prepare__() must return a mapping");
                namespace.clear();
                for(var e:map.entrySet()) namespace.put(e.getKey(),e.getValue());
                namespace.putIfAbsent("__module__",cls.moduleName);
                namespace.putIfAbsent("__qualname__",cls.qualname);
            }
        }
        return namespace;
    }

    public static void classNamespacePut(Object namespace,Object name,Object value) {
        @SuppressWarnings("unchecked") Map<Object,Object> map=(Map<Object,Object>)namespace;
        map.put(name,value);
    }

    public static void classNamespaceSyncMember(Object namespace,Object clsObj,Object nameObj) {
        @SuppressWarnings("unchecked") Map<Object,Object> map=(Map<Object,Object>)namespace;
        map.put(nameObj,getattr(clsObj,nameObj));
    }

    private static void syncNamespaceToClass(PyClass cls,Map<?,?> namespace) {
        for(var e:namespace.entrySet()) {
            if(!(e.getKey() instanceof String name)) continue;
            if(name.equals("__module__") || name.equals("__qualname__")) continue;
            if(cls.methods.containsKey(name) || cls.properties.containsKey(name)) continue;
            cls.attrs.put(name,e.getValue());
        }
    }

    private static Object invokeMetaclassInstance(PyMethod method,PyClass cls,List<Object> args,Map<Object,Object> kwargs) {
        if(method.function==null) throw new PyException("RuntimeError","metaclass method metadata unavailable");
        ArrayList<Object> actual=new ArrayList<>(); actual.add(cls); actual.addAll(args);
        return method.function.call(actual,kwargs);
    }

    public static Object classFinish(Object clsObj,Object explicitMeta,Object namespaceObj,Object kwargsObj) {
        PyClass prebuilt=(PyClass)clsObj;
        @SuppressWarnings("unchecked") Map<Object,Object> namespace=(Map<Object,Object>)namespaceObj;
        @SuppressWarnings("unchecked") Map<Object,Object> kwargs=(Map<Object,Object>)kwargsObj;
        PyClass meta=resolveMetaclass(prebuilt,explicitMeta);
        prebuilt.metaclass=meta;

        Object created=prebuilt;
        if(meta!=null) {
            PyMethod newMethod=meta.lookupMethod("__new__");
            if(newMethod!=null) {
                ArrayList<Object> args=new ArrayList<>();
                args.add(meta); args.add(prebuilt.name); args.add(explicitBasesTuple(prebuilt)); args.add(namespaceObj);
                created=invokeOnClassKw(meta,newMethod,args,kwargs);
            }
        }

        if(created instanceof PyClass cls) {
            cls.metaclass=meta;
            syncNamespaceToClass(cls,namespace);
            if(cls.methods.containsKey("__eq__") && !cls.methods.containsKey("__hash__") && !cls.attrs.containsKey("__hash__")) cls.attrs.put("__hash__",null);
            if(!cls.finalized) classFinalizeWithKeywords(cls,kwargs);
            if(meta!=null) {
                PyMethod init=meta.lookupMethod("__init__");
                if(init!=null) {
                    ArrayList<Object> initArgs=new ArrayList<>();
                    initArgs.add(cls.name); initArgs.add(explicitBasesTuple(cls)); initArgs.add(namespaceObj);
                    Object result=invokeMetaclassInstance(init,cls,initArgs,kwargs);
                    if(result!=null) throw new PyException("TypeError","metaclass __init__() should return None");
                }
            }
        }
        return created;
    }
    public static void classAddMethod(Object cls, Object pyName, Object owner, Object javaName) {
        classAddMethodKind(cls,pyName,owner,javaName,"instance");
    }
    public static void classAddMethodKind(Object cls, Object pyName, Object owner, Object javaName, Object kind) {
        ((PyClass)cls).methods.put((String)pyName, new PyMethod((String)owner, (String)javaName, (String)kind));
    }
    public static void classAddMethodEx(Object cls, Object pyName, Object owner, Object javaName, Object kind,
                                        Object posonlyObj, Object poskwObj, Object kwonlyObj,
                                        Object varargObj, Object kwargObj, Object defaultsObj, Object filenameObj, Object firstlineObj) {
        @SuppressWarnings("unchecked") Map<Object,Object> rawDefaults=(Map<Object,Object>)defaultsObj;
        LinkedHashMap<String,Object> defaults=new LinkedHashMap<>();
        for(var e:rawDefaults.entrySet()) defaults.put((String)e.getKey(),e.getValue());
        PyFunction function=new PyFunction((String)owner,(String)javaName,
            splitNames((String)posonlyObj),splitNames((String)poskwObj),splitNames((String)kwonlyObj),
            (String)varargObj,(String)kwargObj,defaults);
        function.displayName=(String)pyName; function.filename=(String)filenameObj; function.firstlineno=bigInt(firstlineObj).longValue();
        ((PyClass)cls).methods.put((String)pyName,new PyMethod((String)owner,(String)javaName,(String)kind,function));
    }
    public static void classAddMethodClosure(Object cls, Object pyName, Object owner, Object javaName, Object kind,
                                             Object posonly, Object poskw, Object kwonly,
                                             Object vararg, Object kwarg, Object defaults, Object filename, Object firstline,
                                             Object closure) {
        PyFunction function=(PyFunction)makeFunctionEx(owner,javaName,posonly,poskw,kwonly,
            vararg,kwarg,defaults,closure,true);
        function.displayName=(String)pyName; function.filename=(String)filename;
        function.firstlineno=bigInt(firstline).longValue();
        ((PyClass)cls).methods.put((String)pyName,
            new PyMethod((String)owner,(String)javaName,(String)kind,function));
    }
    public static void classSetQualname(Object cls,Object qualname) {
        ((PyClass)cls).qualname=(String)qualname;
    }
    public static void classSetMethodAsync(Object cls,Object pyName) {
        PyMethod method=((PyClass)cls).methods.get((String)pyName);
        if(method==null || method.function==null) throw new PyException("RuntimeError","method metadata not found");
        method.function.asyncMode=true;
    }
    public static void classSetMethodAsyncGenerator(Object cls,Object pyName) {
        PyMethod method=((PyClass)cls).methods.get((String)pyName);
        if(method==null || method.function==null) throw new PyException("RuntimeError","method metadata not found");
        method.function.asyncGeneratorMode=true;
    }
    public static void classAddProperty(Object cls, Object pyName, Object owner, Object getter, Object setter) {
        ((PyClass)cls).properties.put((String)pyName, new PyProperty((String)owner, (String)getter, setter == null ? null : (String)setter));
    }
    public static void classAddPropertyClosure(Object cls,Object pyName,Object owner,Object getter,Object setter,Object closure) {
        PyFunction get=(PyFunction)makeFunctionEx(owner,getter,"","self","",null,null,
            new LinkedHashMap<Object,Object>(),closure,true);
        PyFunction set=setter==null ? null : (PyFunction)makeFunctionEx(owner,setter,"","self,value","",null,null,
            new LinkedHashMap<Object,Object>(),closure,true);
        ((PyClass)cls).properties.put((String)pyName,
            new PyProperty((String)owner,(String)getter,setter==null ? null : (String)setter,get,set));
    }
    private static void configureSlots(PyClass cls) {
        Object spec=cls.attrs.get("__slots__");
        boolean inheritedDict=false;
        for(PyClass base:cls.bases) if(base.instanceDictAllowed) { inheritedDict=true; break; }

        if(spec==null) {
            cls.slotsDeclared=false;
            cls.instanceDictAllowed=true;
            return;
        }

        cls.slotsDeclared=true;
        cls.instanceDictAllowed=inheritedDict;
        ArrayList<Object> rawNames=new ArrayList<>();
        if(spec instanceof String one) rawNames.add(one);
        else if(spec instanceof PyTuple tuple) rawNames.addAll(tuple.items);
        else if(spec instanceof List<?> list) rawNames.addAll(list);
        else throw new PyException("TypeError","__slots__ must be a string or iterable of strings");

        LinkedHashSet<String> names=new LinkedHashSet<>();
        for(Object raw:rawNames) {
            if(!(raw instanceof String name)) throw new PyException("TypeError","__slots__ items must be strings");
            if(!isPythonIdentifier(name)) throw new PyException("TypeError","__slots__ must be identifiers");
            if(name.equals("__dict__")) { cls.instanceDictAllowed=true; continue; }
            if(name.equals("__weakref__")) continue;
            String slotName=mangleSlotName(cls.name,name);
            names.add(slotName);
        }

        for(String slotName:names) {
            if(cls.methods.containsKey(slotName) || cls.properties.containsKey(slotName) ||
               (cls.attrs.containsKey(slotName) && !slotName.equals("__slots__")))
                throw new PyException("ValueError","'"+slotName+"' in __slots__ conflicts with class variable");
            cls.ownSlots.add(slotName);
            cls.attrs.put(slotName,new PySlotDescriptor(cls,slotName));
        }
    }

    private static boolean isPythonIdentifier(String name) {
        if(name.isEmpty()) return false;
        int offset=0;
        int cp=name.codePointAt(offset);
        if(!(cp=='_' || Character.isUnicodeIdentifierStart(cp))) return false;
        offset+=Character.charCount(cp);
        while(offset<name.length()) {
            cp=name.codePointAt(offset);
            if(!(cp=='_' || Character.isUnicodeIdentifierPart(cp))) return false;
            offset+=Character.charCount(cp);
        }
        return true;
    }

    private static String mangleSlotName(String className,String name) {
        if(name.startsWith("__") && !name.endsWith("__")) {
            String stripped=className.replaceFirst("^_+","");
            if(stripped.isEmpty()) return name;
            return "_"+stripped+name;
        }
        return name;
    }

    private static void classFinalizeWithKeywords(PyClass cls,Map<Object,Object> kwargs) {
        if(cls.finalized) return;
        cls.computeMro();
        configureSlots(cls);

        // PEP 487: descriptors receive their owner/name before __init_subclass__.
        for(var entry:new ArrayList<>(cls.attrs.entrySet())) {
            Object value=entry.getValue();
            if(value instanceof PyInstance descriptor) {
                PyMethod setName=descriptor.cls.lookupMethod("__set_name__");
                if(setName!=null) invoke(descriptor,setName,new Object[]{cls,entry.getKey()});
            }
        }

        // Class declaration keywords that were not consumed by the metaclass are
        // forwarded to the inherited __init_subclass__ hook.
        if(!cls.bases.isEmpty()) {
            PyMethod hook=cls.lookupMethodAfter(cls,"__init_subclass__");
            if(hook!=null) invokeOnClassKw(cls,hook,new ArrayList<>(),kwargs);
        }
        cls.finalized=true;
    }

    public static void classFinalize(Object clsObj) {
        classFinalizeWithKeywords((PyClass)clsObj,new LinkedHashMap<>());
    }
    public static Object makeSuper(Object currentClass, Object self) {
        if (!(currentClass instanceof PyClass cls) || !(self instanceof PyInstance instance))
            throw new PyException("TypeError", "super() arguments must be a class and instance");
        if (!instance.cls.mro.contains(cls)) throw new PyException("TypeError", "super(type, obj): obj must be an instance or subtype of type");
        return new PySuper(cls, instance);
    }

    public static Object instantiate0(Object cls) { return instantiate((PyClass)cls, new Object[0]); }
    public static Object instantiate1(Object cls, Object a) { return instantiate((PyClass)cls, new Object[]{a}); }
    public static Object instantiate2(Object cls, Object a, Object b) { return instantiate((PyClass)cls, new Object[]{a,b}); }
    public static Object instantiate3(Object cls, Object a, Object b, Object c) { return instantiate((PyClass)cls, new Object[]{a,b,c}); }

    private static Object instantiate(PyClass cls, Object[] args) {
        return instantiate(cls,args,new LinkedHashMap<Object,Object>());
    }
    private static Object instantiate(PyClass cls, Object[] args, Map<Object,Object> kwargs) {
        if(cls.metaclass!=null) {
            PyMethod call=cls.metaclass.lookupMethod("__call__");
            if(call!=null) return invokeMetaclassInstance(call,cls,new ArrayList<Object>(Arrays.asList(args)),kwargs);
        }
        return instantiateDefault(cls,args,kwargs);
    }

    private static Object instantiateDefault(PyClass cls,Object[] args,Map<Object,Object> kwargs) {
        Object created;
        PyMethod newMethod=cls.lookupMethod("__new__");
        if(newMethod!=null) {
            ArrayList<Object> newArgs=new ArrayList<>();
            newArgs.add(cls);
            newArgs.addAll(Arrays.asList(args));
            created=invokeOnClassKw(cls,newMethod,newArgs,kwargs);
        } else {
            created=new PyInstance(cls);
        }

        // __init__ is called only when __new__ produced an instance of cls or a
        // subclass, matching Python's constructor protocol.
        if(created instanceof PyInstance instance && instance.cls.mro.contains(cls)) {
            PyMethod init=cls.lookupMethod("__init__");
            if(init!=null) {
                Object initResult=invokeKw(instance,init,new ArrayList<Object>(Arrays.asList(args)),kwargs);
                if(initResult!=null) throw new PyException("TypeError","__init__() should return None, not '"+typeName(initResult)+"'");
            } else if(args.length!=0 || !kwargs.isEmpty()) {
                throw new PyException("TypeError",cls.name+"() takes no arguments");
            }
        }
        return created;
    }

    private static void requireArgs(String name,Object[] args,int count) { if(args.length!=count) throw new PyException("TypeError",name+"() takes "+count+" arguments"); }

    public static Object callMethodDynamic(Object obj,Object nameObj,Object argsObj,Object kwargsObj) {
        @SuppressWarnings("unchecked") List<Object> args=(List<Object>)argsObj;
        @SuppressWarnings("unchecked") Map<Object,Object> kwargs=(Map<Object,Object>)kwargsObj;
        String name=(String)nameObj;
        if(kwargs.isEmpty()) return callMethod(obj,name,args.toArray());
        if(obj instanceof PyInstance || obj instanceof PyClass || obj instanceof PySuper) {
            Object callable=getattr(obj,name);
            return callFunction(callable,args,kwargs);
        }
        throw new PyException("TypeError",name+"() does not accept keyword arguments");
    }

    public static Object callMethod0(Object obj, Object name) { return callMethod(obj, (String)name, new Object[0]); }
    public static Object callMethod1(Object obj, Object name, Object a) { return callMethod(obj, (String)name, new Object[]{a}); }
    public static Object callMethod2(Object obj, Object name, Object a, Object b) { return callMethod(obj, (String)name, new Object[]{a,b}); }
    public static Object callMethod3(Object obj, Object name, Object a, Object b, Object c) { return callMethod(obj, (String)name, new Object[]{a,b,c}); }
    public static Object callMethod4(Object obj, Object name, Object a, Object b, Object c, Object d) { return callMethod(obj, (String)name, new Object[]{a,b,c,d}); }

    private static Object callMethod(Object obj, String name, Object[] args) {
        if(obj instanceof PyComplex z){
            requireArgs(name,args,0);
            return switch(name){
                case "conjugate" -> new PyComplex(z.real,-z.imag);
                case "__complex__", "__pos__" -> z;
                case "__neg__" -> neg(z);
                case "__abs__" -> abs(z);
                case "__getnewargs__" -> {PyTuple tuple=new PyTuple();tuple.items.add(z.real);tuple.items.add(z.imag);yield tuple;}
                default -> throw new PyException("AttributeError","complex has no attribute '"+name+"'");
            };
        }
        if (obj instanceof PySuper sup) {
            PyMethod method = sup.self.cls.lookupMethodAfter(sup.currentClass, name);
            if (method == null) throw new PyException("AttributeError", "'super' object has no attribute '"+name+"'");
            return invoke(sup.self, method, args);
        }
        if (obj instanceof PyBuiltinType builtin) {
            if(builtin.name.equals("object") && name.equals("__new__")) {
                requireArgs(name,args,1);
                if(!(args[0] instanceof PyClass cls)) throw new PyException("TypeError","object.__new__() argument must be a type");
                return new PyInstance(cls);
            }
            if(builtin.name.equals("type") && name.equals("__new__")) {
                requireArgs(name,args,4);
                if(!(args[0] instanceof PyClass meta)) throw new PyException("TypeError","type.__new__() argument 1 must be a metaclass");
                if(!(args[1] instanceof String className)) throw new PyException("TypeError","type.__new__() name must be str");
                if(!(args[2] instanceof PyTuple bases)) throw new PyException("TypeError","type.__new__() bases must be tuple");
                if(!(args[3] instanceof Map<?,?> namespace)) throw new PyException("TypeError","type.__new__() namespace must be mapping");
                PyClass cls;
                if(args[3] instanceof PyClassNamespace prepared) cls=prepared.prebuilt;
                else {
                    Object module=namespace.get("__module__");
                    cls=new PyClass(module instanceof String m ? m : "__main__",className);
                    for(Object base:bases.items) classAddBase(cls,base);
                }
                cls.metaclass=meta;
                syncNamespaceToClass(cls,namespace);
                classFinalize(cls);
                return cls;
            }
            if(builtin.name.equals("type") && name.equals("__init__")) {
                requireArgs(name,args,4); return null;
            }
            if(builtin.name.equals("type") && name.equals("__call__")) {
                if(args.length<1 || !(args[0] instanceof PyClass cls)) throw new PyException("TypeError","type.__call__() requires a class");
                return instantiateDefault(cls,Arrays.copyOfRange(args,1,args.length),new LinkedHashMap<Object,Object>());
            }
            if((builtin.name.equals("bytes")||builtin.name.equals("bytearray")) && name.equals("maketrans")) {
                requireArgs(name,args,2); return bytesMaketrans(args[0],args[1]);
            }
            throw new PyException("AttributeError","type object '"+builtin.name+"' has no method '"+name+"'");
        }
        if (obj instanceof PyClass cls) {
            PyMethod method=cls.lookupMethod(name);
            if(method==null) throw new PyException("AttributeError","type object '"+cls.name+"' has no method '"+name+"'");
            return invokeOnClass(cls,method,args);
        }
        if (obj instanceof PyAsyncGenerator generator) {
            return switch(name) {
                case "__aiter__" -> { requireArgs(name,args,0); yield generator; }
                case "__anext__" -> { requireArgs(name,args,0); yield generator.anext(); }
                case "asend" -> { requireArgs(name,args,1); yield generator.asend(args[0]); }
                case "athrow" -> { requireArgs(name,args,1); yield generator.athrow(args[0]); }
                case "aclose" -> { requireArgs(name,args,0); yield generator.aclose(); }
                default -> throw new PyException("AttributeError","'async_generator' object has no attribute '"+name+"'");
            };
        }
        if (obj instanceof PyCoroutine coroutine) {
            return switch(name) {
                case "send" -> { requireArgs(name,args,1); yield coroutine.send(args[0]); }
                case "throw" -> { requireArgs(name,args,1); yield coroutine.throw_(args[0]); }
                case "close" -> { requireArgs(name,args,0); yield coroutine.close(); }
                case "__await__" -> { requireArgs(name,args,0); yield coroutine.awaitIterator(); }
                default -> throw new PyException("AttributeError","'coroutine' object has no attribute '"+name+"'");
            };
        }
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
        if (obj instanceof PyBytes bytes) {
            return switch(name) {
                case "decode" -> {
                    if(args.length>1) throw new PyException("TypeError","decode() takes at most 1 argument");
                    String enc=args.length==0?"utf-8":(String)args[0];
                    yield new String(bytes.data,Charset.forName(enc));
                }
                case "hex" -> { requireArgs(name,args,0); yield bytesHex(bytes.data); }
                case "find" -> binaryFind(bytes,args);
                case "rfind" -> binarySearch(bytes,args,true,false);
                case "index" -> binarySearch(bytes,args,false,true);
                case "rindex" -> binarySearch(bytes,args,true,true);
                case "partition" -> binaryPartition(bytes,args,false);
                case "rpartition" -> binaryPartition(bytes,args,true);
                case "count" -> binaryCount(bytes,args);
                case "startswith" -> binaryStartsEnds(bytes,args,true);
                case "endswith" -> binaryStartsEnds(bytes,args,false);
                case "replace" -> binaryReplace(bytes,args);
                case "split" -> binarySplit(bytes,args);
                case "join" -> { requireArgs(name,args,1); yield binaryJoin(bytes,args[0]); }
                case "translate" -> binaryTranslate(bytes,args);
                case "strip" -> binaryStrip(bytes,args,0);
                case "lstrip" -> binaryStrip(bytes,args,-1);
                case "rstrip" -> binaryStrip(bytes,args,1);
                case "lower" -> { requireArgs(name,args,0); yield binaryCase(bytes,"lower"); }
                case "upper" -> { requireArgs(name,args,0); yield binaryCase(bytes,"upper"); }
                case "capitalize" -> { requireArgs(name,args,0); yield binaryCase(bytes,"capitalize"); }
                case "title" -> { requireArgs(name,args,0); yield binaryCase(bytes,"title"); }
                case "swapcase" -> { requireArgs(name,args,0); yield binaryCase(bytes,"swapcase"); }
                default -> throw new PyException("AttributeError","'bytes' object has no attribute '"+name+"'");
            };
        }
        if (obj instanceof PyByteArray bytes) {
            return switch(name) {
                case "append" -> { requireArgs(name,args,1); bytes.appendUnsigned(byteValue(args[0])); yield null; }
                case "extend" -> { requireArgs(name,args,1); for(Object x:iterable(args[0])) bytes.appendUnsigned(byteValue(x)); yield null; }
                case "clear" -> { requireArgs(name,args,0); bytes.data.clear(); yield null; }
                case "copy" -> { requireArgs(name,args,0); yield new PyByteArray(bytes.toByteArray()); }
                case "reverse" -> { requireArgs(name,args,0); Collections.reverse(bytes.data); yield null; }
                case "pop" -> {
                    if(args.length>1)throw new PyException("TypeError","pop expected at most 1 argument");
                    if(bytes.data.isEmpty())throw new PyException("IndexError","pop from empty bytearray");
                    int index=args.length==0?bytes.data.size()-1:normalizeIndex(asIndex(args[0]),bytes.data.size());
                    yield (long)(bytes.data.remove(index)&0xff);
                }
                case "remove" -> {
                    requireArgs(name,args,1); int value=byteValue(args[0]); int found=-1;
                    for(int i=0;i<bytes.data.size();i++)if((bytes.data.get(i)&0xff)==value){found=i;break;}
                    if(found<0)throw new PyException("ValueError","value not found in bytearray");
                    bytes.data.remove(found); yield null;
                }
                case "insert" -> {
                    requireArgs(name,args,2); int index=asIndex(args[0]); int size=bytes.data.size();
                    if(index<0)index=Math.max(0,index+size);
                    else index=Math.min(index,size);
                    bytes.data.add(index,(byte)byteValue(args[1])); yield null;
                }
                case "decode" -> {
                    if(args.length>1) throw new PyException("TypeError","decode() takes at most 1 argument");
                    String enc=args.length==0?"utf-8":(String)args[0];
                    yield new String(bytes.toByteArray(),Charset.forName(enc));
                }
                case "hex" -> { requireArgs(name,args,0); yield bytesHex(bytes.toByteArray()); }
                case "find" -> binaryFind(bytes,args);
                case "rfind" -> binarySearch(bytes,args,true,false);
                case "index" -> binarySearch(bytes,args,false,true);
                case "rindex" -> binarySearch(bytes,args,true,true);
                case "partition" -> binaryPartition(bytes,args,false);
                case "rpartition" -> binaryPartition(bytes,args,true);
                case "count" -> binaryCount(bytes,args);
                case "startswith" -> binaryStartsEnds(bytes,args,true);
                case "endswith" -> binaryStartsEnds(bytes,args,false);
                case "replace" -> binaryReplace(bytes,args);
                case "split" -> binarySplit(bytes,args);
                case "join" -> { requireArgs(name,args,1); yield binaryJoin(bytes,args[0]); }
                case "translate" -> binaryTranslate(bytes,args);
                case "strip" -> binaryStrip(bytes,args,0);
                case "lstrip" -> binaryStrip(bytes,args,-1);
                case "rstrip" -> binaryStrip(bytes,args,1);
                case "lower" -> { requireArgs(name,args,0); yield binaryCase(bytes,"lower"); }
                case "upper" -> { requireArgs(name,args,0); yield binaryCase(bytes,"upper"); }
                case "capitalize" -> { requireArgs(name,args,0); yield binaryCase(bytes,"capitalize"); }
                case "title" -> { requireArgs(name,args,0); yield binaryCase(bytes,"title"); }
                case "swapcase" -> { requireArgs(name,args,0); yield binaryCase(bytes,"swapcase"); }
                default -> throw new PyException("AttributeError","'bytearray' object has no attribute '"+name+"'");
            };
        }
        if (obj instanceof PyMemoryView view) {
            return switch(name) {
                case "tobytes" -> { requireArgs(name,args,0); yield new PyBytes(view.toByteArray()); }
                case "tolist" -> {
                    requireArgs(name,args,0); ArrayList<Object> out=new ArrayList<>();
                    for(Object x:view)out.add(x); yield out;
                }
                default -> throw new PyException("AttributeError","'memoryview' object has no attribute '"+name+"'");
            };
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
                case "encode" -> {
                    if(args.length>1) throw new PyException("TypeError","encode() takes at most 1 argument");
                    String enc=args.length==0?"utf-8":(String)args[0];
                    yield new PyBytes(str.getBytes(Charset.forName(enc)));
                }
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
        PyMethod method = instance.cls.lookupMethod(name);
        if (method == null) {
            Object callable=getattr(instance,name);
            return callFunction(callable,new ArrayList<Object>(Arrays.asList(args)),new LinkedHashMap<Object,Object>());
        }
        return invoke(instance, method, args);
    }

    private static Object invokeReflective(PyMethod method,Object[] actual) {
        try {
            Class<?> owner = Class.forName(method.owner);
            Class<?>[] types = new Class<?>[actual.length];
            Arrays.fill(types, Object.class);
            var reflected = owner.getDeclaredMethod(method.javaName, types);
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

    private static Object invoke(PyInstance self, PyMethod method, Object[] args) {
        return invokeKw(self,method,new ArrayList<Object>(Arrays.asList(args)),new LinkedHashMap<Object,Object>());
    }
    private static Object invokeKw(PyInstance self, PyMethod method, List<Object> args, Map<Object,Object> kwargs) {
        if(method.function != null) {
            ArrayList<Object> actual=new ArrayList<>();
            if(!method.kind.equals("static")) actual.add(method.kind.equals("class") ? self.cls : self);
            actual.addAll(args);
            return method.function.call(actual,kwargs);
        }
        if(!kwargs.isEmpty()) throw new PyException("TypeError","method does not accept keyword arguments");
        if(method.kind.equals("static")) return invokeReflective(method,args.toArray());
        Object receiver = method.kind.equals("class") ? self.cls : self;
        Object[] actual = new Object[args.size()+1]; actual[0]=receiver; for(int i=0;i<args.size();i++)actual[i+1]=args.get(i);
        return invokeReflective(method,actual);
    }

    private static Object invokeOnClass(PyClass cls,PyMethod method,Object[] args) {
        return invokeOnClassKw(cls,method,new ArrayList<Object>(Arrays.asList(args)),new LinkedHashMap<Object,Object>());
    }
    private static Object invokeOnClassKw(PyClass cls,PyMethod method,List<Object> args,Map<Object,Object> kwargs) {
        if(method.function != null) {
            ArrayList<Object> actual=new ArrayList<>();
            if(method.kind.equals("class")) actual.add(cls);
            actual.addAll(args);
            return method.function.call(actual,kwargs);
        }
        if(!kwargs.isEmpty()) throw new PyException("TypeError","method does not accept keyword arguments");
        if(method.kind.equals("static")) return invokeReflective(method,args.toArray());
        if(method.kind.equals("class")){Object[] actual=new Object[args.size()+1];actual[0]=cls;for(int i=0;i<args.size();i++)actual[i+1]=args.get(i);return invokeReflective(method,actual);}
        if(args.isEmpty() || !(args.get(0) instanceof PyInstance self)) throw new PyException("TypeError","unbound method requires an instance as first argument");
        Object[] rest=args.subList(1,args.size()).toArray(); return invoke(self,method,rest);
    }

    private static boolean isDescriptor(Object value) {
        return value instanceof PySlotDescriptor ||
            (value instanceof PyInstance descriptor && descriptor.cls.lookupMethod("__get__") != null);
    }
    private static boolean isDataDescriptor(Object value) {
        return value instanceof PySlotDescriptor ||
            (value instanceof PyInstance descriptor &&
            (descriptor.cls.lookupMethod("__set__") != null || descriptor.cls.lookupMethod("__delete__") != null));
    }
    private static Object descriptorGet(Object descriptor, Object instance, PyClass owner) {
        if(descriptor instanceof PySlotDescriptor slot) {
            if(instance==null) return slot;
            if(!(instance instanceof PyInstance pyInstance))
                throw new PyException("TypeError","slot descriptor requires an instance");
            if(!pyInstance.slotValues.containsKey(slot.name))
                throw new PyException("AttributeError","'"+pyInstance.cls.name+"' object has no attribute '"+slot.name+"'");
            return pyInstance.slotValues.get(slot.name);
        }
        if (!isDescriptor(descriptor)) return descriptor;
        return callMethod(descriptor, "__get__", new Object[]{instance, owner});
    }

    public static Object getattr(Object obj, Object nameObj) {
        if(!(nameObj instanceof String))throw new PyException("TypeError","attribute name must be string");
        String name = (String)nameObj;
        if(obj instanceof PyBuiltinType type && name.equals("__name__"))return type.name;
        if(obj instanceof PyComplex z){
            if(name.equals("real"))return z.real;
            if(name.equals("imag"))return z.imag;
            if(Set.of("conjugate","__complex__","__pos__","__neg__","__abs__","__getnewargs__").contains(name))return new BuiltinBoundMethod(obj,name);
            if(!name.equals("__class__"))throw new PyException("AttributeError","complex has no attribute '"+name+"'");
        }
        if(obj instanceof PySlice sl) return switch(name){case "start"->sl.start;case "stop"->sl.stop;case "step"->sl.step;case "__class__"->builtinType("slice");default->throw new PyException("AttributeError","slice has no attribute '"+name+"'");};
        if(name.equals("__class__") && !(obj instanceof PyInstance) && !(obj instanceof PyClass)) return typeOf(obj);
        if (obj instanceof PyModule) return moduleGetattr(obj,nameObj);
        if (obj instanceof PyExceptionValue exc) {
            if (name.equals("args")) { PyTuple t=new PyTuple(); if(exc.value!=null)t.items.add(exc.value); return t; }
            if (name.equals("value") && exc.typeName.equals("StopIteration")) return exc.value;
            if (name.equals("__cause__")) return exc.cause;
            if (name.equals("__context__")) return exc.context;
            if (name.equals("__suppress_context__")) return exc.suppressContext;
            if (name.equals("__traceback__")) return exc.traceback;
            throw new PyException("AttributeError", "'"+exc.typeName+"' object has no attribute '"+name+"'");
        }
        if(obj instanceof PyMemoryView view) {
            if(name.equals("readonly")) return view.readonly();
        }
        if(obj instanceof PyModuleSpec spec) {
            return switch(name) {
                case "name" -> spec.name;
                case "origin" -> spec.origin;
                case "parent" -> spec.parent;
                case "loader" -> spec.loader;
                case "submodule_search_locations" -> spec.submoduleSearchLocations;
                default -> throw new PyException("AttributeError","ModuleSpec has no attribute '"+name+"'");
            };
        }
        if(obj instanceof PyCompiledLoader loader) {
            return switch(name) {
                case "name" -> loader.name;
                case "path" -> loader.path;
                default -> throw new PyException("AttributeError","loader has no attribute '"+name+"'");
            };
        }
        if(obj instanceof PyTraceback tb) {
            return switch(name){case "tb_next" -> tb.next; case "tb_frame" -> tb.frame; case "tb_lineno" -> tb.lineno; default -> throw new PyException("AttributeError","traceback has no attribute '"+name+"'");};
        }
        if(obj instanceof PyFrame frame) {
            return switch(name){case "f_code" -> frame.code; case "f_locals" -> frame.locals; default -> throw new PyException("AttributeError","frame has no attribute '"+name+"'");};
        }
        if(obj instanceof PyCode code) {
            return switch(name){case "co_name" -> code.coName; case "co_filename" -> code.coFilename; case "co_firstlineno" -> code.coFirstlineno; default -> throw new PyException("AttributeError","code has no attribute '"+name+"'");};
        }
        if (obj instanceof PySuper sup) {
            PyProperty prop=sup.self.cls.lookupPropertyAfter(sup.currentClass,name);
            if(prop!=null) return invoke(sup.self,prop.getterMethod(),new Object[0]);
            PyMethod method=sup.self.cls.lookupMethodAfter(sup.currentClass,name);
            if(method!=null) return new BoundSuperMethod(sup.self,sup.currentClass,name);
            Object attr=sup.self.cls.lookupAttrAfter(sup.currentClass,name);
            if(attr!=MISSING) return attr;
            throw new PyException("AttributeError", "'super' object has no attribute '"+name+"'");
        }
        if (obj instanceof PyClass cls) {
            if(name.equals("__class__")) return cls.metaclass != null ? cls.metaclass : builtinType("type");
            if(name.equals("__name__")) return cls.name;
            if(name.equals("__qualname__")) return cls.qualname;
            if(name.equals("__module__")) return cls.moduleName;
            if(name.equals("__bases__")) {
                PyTuple t=new PyTuple();
                if(!cls.bases.isEmpty()) t.items.addAll(cls.bases);
                else if(cls.builtinBaseName!=null) t.items.add(new PyBuiltinType(cls.builtinBaseName));
                else t.items.add(new PyBuiltinType("object"));
                return t;
            }
            if(name.equals("__mro__")) {
                PyTuple t=new PyTuple(); t.items.addAll(cls.mro);
                if(cls.builtinBaseName!=null) t.items.add(new PyBuiltinType(cls.builtinBaseName));
                if(cls.builtinBaseName==null || !cls.builtinBaseName.equals("object")) t.items.add(new PyBuiltinType("object"));
                return t;
            }
            if(name.equals("__dict__")) {
                LinkedHashMap<Object,Object> out=new LinkedHashMap<>();
                out.putAll(cls.attrs);
                out.put("__module__",cls.moduleName);
                out.put("__name__",cls.name);
                return out;
            }
            Object attr=cls.lookupAttr(name);
            if(attr!=MISSING) return descriptorGet(attr, null, cls);
            PyProperty prop=cls.lookupProperty(name);
            if(prop!=null) return prop;
            PyMethod method=cls.lookupMethod(name);
            if(method!=null){
                if(method.kind.equals("class")) return new BoundClassMethod(cls,name);
                if(method.kind.equals("static")) return new BoundStaticMethod(cls,name);
                return new UnboundMethod(cls,name);
            }
            throw new PyException("AttributeError", "type object '"+cls.name+"' has no attribute '"+name+"'");
        }
        if (obj instanceof PyInstance instance) {
            if(name.equals("__class__")) return instance.cls;
            if(name.equals("__dict__")) {
                if(!instance.cls.instanceDictAllowed)
                    throw new PyException("AttributeError","'"+instance.cls.name+"' object has no attribute '__dict__'");
                return instance.fields;
            }
            PyProperty prop=instance.cls.lookupProperty(name);
            if(prop!=null) return invoke(instance,prop.getterMethod(),new Object[0]);
            Object classAttr=instance.cls.lookupAttr(name);
            if(classAttr!=MISSING && isDataDescriptor(classAttr)) return descriptorGet(classAttr, instance, instance.cls);
            if (instance.fields.containsKey(name)) return instance.fields.get(name);
            PyMethod method=instance.cls.lookupMethod(name);
            if (method != null) return new BoundMethod(instance, name);
            if(classAttr!=MISSING) return descriptorGet(classAttr, instance, instance.cls);
            PyMethod getattrMethod=instance.cls.lookupMethod("__getattr__");
            if(getattrMethod!=null) return invoke(instance,getattrMethod,new Object[]{name});
            throw new PyException("AttributeError", "'" + instance.cls.name + "' object has no attribute '" + name + "'");
        }
        throw typeError("attribute access on unsupported object", obj);
    }

    public static Object getattrDefault(Object obj,Object nameObj,Object defaultValue) {
        try { return getattr(obj,nameObj); } catch(PyException e) { if(e.typeName.equals("AttributeError")) return defaultValue; throw e; }
    }
    public static Object hasattr(Object obj,Object nameObj) {
        try { getattr(obj,nameObj); return true; } catch(PyException e) { if(e.typeName.equals("AttributeError")) return false; throw e; }
    }

    public static void setattr(Object obj, Object nameObj, Object value) {
        String name=(String)nameObj;
        if(obj instanceof PyComplex)throw new PyException("AttributeError","complex attributes are read-only");
        if (obj instanceof PyInstance instance) {
            PyProperty prop=instance.cls.lookupProperty(name);
            if(prop!=null) {
                if(prop.setter==null) throw new PyException("AttributeError", "property '"+name+"' of '"+instance.cls.name+"' object has no setter");
                invoke(instance,prop.setterMethod(),new Object[]{value}); return;
            }
            Object descriptor=instance.cls.lookupAttr(name);
            if(descriptor instanceof PySlotDescriptor slot) {
                instance.slotValues.put(slot.name,value); return;
            }
            if(descriptor instanceof PyInstance d && d.cls.lookupMethod("__set__") != null) {
                callMethod(descriptor,"__set__",new Object[]{instance,value}); return;
            }
            if(!instance.cls.instanceDictAllowed)
                throw new PyException("AttributeError","'"+instance.cls.name+"' object has no attribute '"+name+"'");
            instance.fields.put(name, value); return;
        }
        if(obj instanceof PyClass cls){cls.attrs.put(name,value);return;}
        throw typeError("attribute assignment on unsupported object", obj);
    }
    public static void delattr(Object obj,Object nameObj) {
        if(obj instanceof PyComplex)throw new PyException("AttributeError","complex attributes are read-only");
        if(obj instanceof PyInstance instance) {
            String name=(String)nameObj;
            if(instance.cls.lookupProperty(name)!=null) throw new PyException("AttributeError","property '"+name+"' has no deleter");
            Object descriptor=instance.cls.lookupAttr(name);
            if(descriptor instanceof PySlotDescriptor slot) {
                if(!instance.slotValues.containsKey(slot.name))
                    throw new PyException("AttributeError","attribute not found");
                instance.slotValues.remove(slot.name); return;
            }
            if(descriptor instanceof PyInstance d && d.cls.lookupMethod("__delete__") != null) {
                callMethod(descriptor,"__delete__",new Object[]{instance}); return;
            }
            if(!instance.cls.instanceDictAllowed || !instance.fields.containsKey(name))
                throw new PyException("AttributeError","attribute not found");
            instance.fields.remove(name); return;
        }
        if(obj instanceof PyClass cls){String name=(String)nameObj;if(!cls.attrs.containsKey(name))throw new PyException("AttributeError","attribute not found");cls.attrs.remove(name);return;}
        throw typeError("attribute deletion on unsupported object",obj);
    }

    public static Object isInstance(Object obj, Object clsObj) {
        if (clsObj instanceof PyTuple tuple) {
            for(Object c:tuple.items) if((Boolean)isInstance(obj,c)) return true;
            return false;
        }
        if (clsObj instanceof PyClass cls) return obj instanceof PyInstance instance && instance.cls.mro.contains(cls);
        if (clsObj instanceof PyBuiltinType bt) {
            if(obj instanceof PyExceptionValue error) return bt.name.equals("object") || exceptionIsSubclass(error.typeName,bt.name);
            if(obj instanceof PyException error) return bt.name.equals("object") || exceptionIsSubclass(error.typeName,bt.name);
            return switch(bt.name) {
                case "object" -> true;
                case "int" -> isIntLike(obj);
                case "bool" -> obj instanceof Boolean;
                case "float" -> obj instanceof Double;
                case "complex" -> obj instanceof PyComplex;
                case "str" -> obj instanceof String;
                case "bytes" -> obj instanceof PyBytes;
                case "bytearray" -> obj instanceof PyByteArray;
                case "memoryview" -> obj instanceof PyMemoryView;
                case "list" -> obj instanceof List<?>;
                case "tuple" -> obj instanceof PyTuple;
                case "dict" -> obj instanceof Map<?,?>;
                case "set" -> obj instanceof Set<?>;
                case "range" -> obj instanceof PyRange;
                case "map" -> obj instanceof PyMap;
                case "slice" -> obj instanceof PySlice;
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
            return exceptionIsSubclass(a.name,b.name);
        }
        if(subObj instanceof PyClass a && clsObj instanceof PyClass b) return a.mro.contains(b);
        if(subObj instanceof PyClass a && clsObj instanceof PyBuiltinType b && b.name.equals("type"))
            return "type".equals(a.builtinBaseName);
        if(subObj instanceof PyClass && clsObj instanceof PyBuiltinType b && b.name.equals("object")) return true;
        throw new PyException("TypeError","issubclass() arg 1 must be a class");
    }

    private static final Object MISSING = new Object();
    private record PyMethod(String owner, String javaName, String kind, PyFunction function) {
        PyMethod(String owner,String javaName){this(owner,javaName,"instance",null);}
        PyMethod(String owner,String javaName,String kind){this(owner,javaName,kind,null);}
    }
    private record PyProperty(String owner, String getter, String setter, PyFunction getFunction, PyFunction setFunction) {
        PyProperty(String owner,String getter,String setter){this(owner,getter,setter,null,null);}
        PyMethod getterMethod(){return new PyMethod(owner,getter,"instance",getFunction);}
        PyMethod setterMethod(){return new PyMethod(owner,setter,"instance",setFunction);}
    }
    private static final class PySlotDescriptor {
        final PyClass owner; final String name;
        PySlotDescriptor(PyClass owner,String name){this.owner=owner;this.name=name;}
        @Override public String toString(){return "<member '"+name+"' of '"+owner.name+"' objects>";}
    }
    private record BoundMethod(PyInstance self, String name) {}
    private record BoundSuperMethod(PyInstance self, PyClass currentClass, String name) {}
    private record BoundClassMethod(PyClass cls,String name) {}
    private record BoundStaticMethod(PyClass cls,String name) {}
    private record UnboundMethod(PyClass cls,String name) {}
    private record PySuper(PyClass currentClass, PyInstance self) {}

    public static final class PyInstance {
        final PyClass cls;
        final LinkedHashMap<String,Object> fields = new LinkedHashMap<>();
        final LinkedHashMap<String,Object> slotValues = new LinkedHashMap<>();
        PyInstance(PyClass cls) { this.cls = cls; }
        @Override public String toString() { return "<" + cls.name + " object>"; }
    }

    public static final class PyClass {
        final String moduleName;
        final String name;
        String qualname;
        final ArrayList<PyClass> bases = new ArrayList<>();
        final LinkedHashMap<String,PyMethod> methods = new LinkedHashMap<>();
        final LinkedHashMap<String,PyProperty> properties = new LinkedHashMap<>();
        final LinkedHashMap<String,Object> attrs = new LinkedHashMap<>();
        List<PyClass> mro = List.of(this);
        PyClass metaclass = null;
        String builtinBaseName = null;
        final LinkedHashSet<String> ownSlots = new LinkedHashSet<>();
        boolean slotsDeclared = false;
        boolean instanceDictAllowed = true;
        boolean finalized = false;
        PyClass(String moduleName, String name) { this.moduleName=moduleName; this.name = name; this.qualname=name; }

        PyMethod lookupMethod(String name) { for (PyClass c:mro){PyMethod m=c.methods.get(name);if(m!=null)return m;} return null; }
        PyProperty lookupProperty(String name) { for (PyClass c:mro){PyProperty p=c.properties.get(name);if(p!=null)return p;} return null; }
        Object lookupAttr(String name) { for (PyClass c:mro) if(c.attrs.containsKey(name)) return c.attrs.get(name); return MISSING; }
        int mroIndex(PyClass current){int i=mro.indexOf(current);if(i<0)throw new PyException("TypeError","super(type, obj): type is not in MRO");return i;}
        PyMethod lookupMethodAfter(PyClass current,String name){for(int i=mroIndex(current)+1;i<mro.size();i++){PyMethod m=mro.get(i).methods.get(name);if(m!=null)return m;}return null;}
        PyProperty lookupPropertyAfter(PyClass current,String name){for(int i=mroIndex(current)+1;i<mro.size();i++){PyProperty p=mro.get(i).properties.get(name);if(p!=null)return p;}return null;}
        Object lookupAttrAfter(PyClass current,String name){for(int i=mroIndex(current)+1;i<mro.size();i++){PyClass c=mro.get(i);if(c.attrs.containsKey(name))return c.attrs.get(name);}return MISSING;}

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
