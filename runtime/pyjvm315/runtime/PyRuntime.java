// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
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
    PyRuntime() {}

    static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

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

    static Object compact(BigInteger value) {
        if (value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
            return value.longValue();
        }
        return value;
    }

    static boolean isIntLike(Object value) {
        return value instanceof Long || value instanceof BigInteger || value instanceof Boolean;
    }

    static BigInteger bigInt(Object value) {
        if (value instanceof BigInteger n) return n;
        if (value instanceof Long n) return BigInteger.valueOf(n);
        if (value instanceof Boolean b) return b ? BigInteger.ONE : BigInteger.ZERO;
        if (value instanceof PyInstance w && w.payload != null && isIntLike(w.payload)) return bigInt(w.payload);
        throw typeError("integer operand required", value);
    }

    static double number(Object value) {
        if (value instanceof Long n) return n.doubleValue();
        if (value instanceof BigInteger n) return n.doubleValue();
        if (value instanceof Double n) return n;
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        if (value instanceof PyInstance w && w.payload instanceof Number) return number(w.payload);
        throw typeError("numeric operand required", value);
    }

    static RuntimeException typeError(String message, Object value) {
        return new IllegalArgumentException(message + ": " + typeName(value));
    }

    static String typeName(Object value) {
        if(value instanceof PyObject)return "object";
        if(value instanceof TypeParamsObject tp)return tp.typeName();
        if(value instanceof PyCell)return "cell";
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
        if (value instanceof PyDict d && d.isFrozen()) return "frozendict";
        if (value instanceof Map) return "dict";
        if(value instanceof PyFrozenSet)return "frozenset";
        if(value instanceof PySetIterator)return "set_iterator";
        if (value instanceof Set) return "set";
        if (value instanceof PyRange) return "range";
        if (value instanceof PyFunction) return "function";
        if (value instanceof PyMethodDescriptor d) return d.kind.equals("class")?"classmethod":"staticmethod";
        if (value instanceof PyManagedProperty) return "property";
        if (value instanceof BoundCallable) return "method";
        if (value instanceof PyBuiltinFunction) return "builtin_function_or_method";
        if (value instanceof BuiltinBoundMethod) return "builtin_function_or_method";
        if (value instanceof PyCode) return "code";
        if (value instanceof NativeFunction) return "builtin_function_or_method";
        if (value instanceof PyLazyIterator it) return it.pyTypeName();
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
    public static Object makeModuleLoader(Object nameObj,Object pathObj){
        return new PyCompiledLoader((String)nameObj,(String)pathObj);
    }
    public static Object makeModuleSpec(Object nameObj,Object originObj,Object parentObj,Object packageObj,Object loaderObj){
        return new PyModuleSpec((String)nameObj,(String)originObj,(String)parentObj,(Boolean)packageObj,loaderObj);
    }

    static final LinkedHashMap<String,PyModule> MODULE_CACHE = new LinkedHashMap<>();
    /** `import ast` / `import _ast`: modules implemented in Java rather than compiled from Python. */
    public static Object importNative(Object nameObj) {
        String name=(String)nameObj;
        PyModule cached=MODULE_CACHE.get(name); if(cached!=null) return cached;
        PyModule module = switch(name){
            case "ast" -> AstModule.module();
            case "_ast" -> AstModule.privateModule();
            case "sys" -> NativeModules.sys();
            case "builtins" -> Namespaces.builtinsModule();
            case "jvm_io" -> NativeModules.jvmIo();
            case "_string" -> NativeModules.stringHelpers();
            default -> throw new PyException("ImportError","No module named '"+name+"'");
        };
        MODULE_CACHE.put(name,module);
        return module;
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
    static Object moduleDict(PyModule module) {
        if(module.name.equals("builtins") && module.className.equals("pyjvm315.runtime.Namespaces"))return Namespaces.builtinsDict();
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

    /** module.name = value: a statically compiled module's own field when it has one, else the module's attribute table. */
    static void moduleSetattr(PyModule module,String name,Object value) {
        if(!module.attrs.containsKey(name) && !module.className.startsWith("pyjvm315.runtime.")) {
            try {
                Class<?> cls=Class.forName(module.className);
                for(String prefix:new String[]{"__py_global_","__py_function_","__py_class_"}) {
                    try { java.lang.reflect.Field f=cls.getField(prefix+name);if(f.get(null)!=UNBOUND){f.set(null,value);return;} } catch(NoSuchFieldException ignored) {}
                }
            } catch(ReflectiveOperationException exc) { throw new RuntimeException(exc); }
        }
        module.attrs.put(name,value);
    }

    // ---------- Builtin type objects / introspection ----------
    public static Object methodDescriptor(Object function,Object kind){return new PyMethodDescriptor(function,(String)kind);}
    static final Map<String,PyBuiltinFunction> BUILTIN_FUNCTIONS=new java.util.concurrent.ConcurrentHashMap<>();
    public static Object builtinFunction(Object name) { return BUILTIN_FUNCTIONS.computeIfAbsent((String)name,PyBuiltinFunction::new); }
    static Object callBuiltin(String name,List<Object> args,Map<Object,Object> kwargs) {
        if(AnnotationSupport.isHelper(name))return AnnotationSupport.callHelper(name,args);
        if(name.equals("print")) return BuiltinsExtra.printKw(args,kwargs);
        if(name.equals("min") || name.equals("max")) return extreme(args,kwargs,name.equals("min"));
        if(name.equals("sorted")) return sortedCall(args,kwargs);
        if(name.equals("round"))return roundCall(args,kwargs);
        if(name.equals("exec") || name.equals("eval")) {
            for(Object key:kwargs.keySet()) if(!key.equals("globals") && !key.equals("locals") && !key.equals("closure"))
                throw new PyException("TypeError",name+"() got an unexpected keyword argument '"+key+"'");
            if(args.isEmpty() || args.size()>3) throw new PyException("TypeError",name+" expected at most 3 arguments, got "+args.size());
            Object globals=args.size()>1?args.get(1):kwargs.get("globals"), locals=args.size()>2?args.get(2):kwargs.get("locals");
            Object result=ExecSupport.run(name,args.get(0),globals,locals,name);
            return name.equals("exec")?null:result;
        }
        if(name.equals("compile")) return ExecSupport.compileBuiltin(args,kwargs);
        if(name.equals("zip")) {
            for(Object key:kwargs.keySet()) if(!key.equals("strict")) throw new PyException("TypeError","zip() got an unexpected keyword argument '"+key+"'");
            return new PyZip(args,truth(kwargs.getOrDefault("strict",false)));
        }
        if(name.equals("enumerate")) {
            for(Object key:kwargs.keySet()) if(!key.equals("start") && !key.equals("iterable")) throw new PyException("TypeError","enumerate() got an unexpected keyword argument '"+key+"'");
            Object iterable=args.size()>0?args.get(0):kwargs.get("iterable");
            Object start=args.size()>1?args.get(1):kwargs.getOrDefault("start",0L);
            if(args.size()>2 || iterable==null && !kwargs.containsKey("iterable")) throw new PyException("TypeError","enumerate() takes at most 2 arguments");
            return new PyEnumerate(iterable,start);
        }
        if(BuiltinsExtra.handles(name)) return BuiltinsExtra.call(name,args,kwargs);
        if(!kwargs.isEmpty()) throw new PyException("TypeError",name+"() does not accept keyword arguments");
        if(name.equals("filter")) {
            if(args.size()!=2) throw new PyException("TypeError","filter expected 2 arguments, got "+args.size());
            return new PyFilter(args.get(0),args.get(1));
        }
        if(name.equals("aiter") && args.size()==1) return callMethod(args.get(0),"__aiter__",new Object[0]);
        if(name.equals("anext")) {
            if(args.size()!=1) throw new PyException("NotImplementedError","anext() with a default value");
            return callMethod(args.get(0),"__anext__",new Object[0]);
        }
        if(name.equals("map")) {
            if(args.size()<2) throw new PyException("TypeError","map() must have at least two arguments");
            return new PyMap(args.get(0),args.subList(1,args.size()));
        }
        int n=args.size();
        if(name.equals("dir") && n<=1)return n==0?dirLocals():dirValue(args.get(0));
        if(name.equals("vars") && n<=1)return n==0?varsLocals():varsValue(args.get(0));
        if((name.equals("globals") || name.equals("locals")) && n==0)return name.equals("globals")?Namespaces.globalsCall():Namespaces.localsCall();
        if(name.equals("format")){
            if(n<1 || n>2)throw new PyException("TypeError",n<1?"format expected at least 1 argument, got 0":"format expected at most 2 arguments, got "+n);
            return formatValue(args.get(0),n==1?"":args.get(1));
        }
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
            case "hex" -> integerBase(value,16,"0x");
            case "oct" -> integerBase(value,8,"0o");
            case "bin" -> integerBase(value,2,"0b");
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

    static Object callableIterator(Object function,Object sentinel) {
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

    static Object powMod(Object base,Object exponent,Object modulus) {
        if(modulus==null)return pow(base,exponent);
        base=NativeSubclass.indexValue(base);exponent=NativeSubclass.indexValue(exponent);modulus=NativeSubclass.indexValue(modulus);
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
        value=NativeSubclass.indexValue(value);
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
    static final java.util.concurrent.ConcurrentHashMap<String,PyBuiltinType> BUILTIN_TYPES = new java.util.concurrent.ConcurrentHashMap<>();
    public static Object builtinType(Object nameObj){return BUILTIN_TYPES.computeIfAbsent((String)nameObj,PyBuiltinType::new);}
    public static Object typeOf(Object value){
        if(value instanceof PySuper sup && sup.cls==SUPER_CLASS)return builtinType("super");
        if(value instanceof PyInstance i) return i.cls;
        if(value instanceof PyClass cls) return cls.metaclass != null ? cls.metaclass : builtinType("type");
        return builtinType(typeName(value));
    }
    public static Object callable_(Object value){return value instanceof NativeFunction || value instanceof PyFunction || value instanceof BoundCallable || value instanceof PyMethodDescriptor d && d.kind.equals("static") || value instanceof PyBuiltinFunction || value instanceof BuiltinBoundMethod || value instanceof BoundMethod || value instanceof BoundSuperMethod || value instanceof BoundClassMethod || value instanceof BoundStaticMethod || value instanceof UnboundMethod || value instanceof PyClass || value instanceof PyBuiltinType || value instanceof TypeParamsGeneric || value instanceof PyInstance instance && instance.cls.lookupAttr("__call__")!=MISSING;}

    static final Object NOT_IMPLEMENTED=Singleton.NOT_IMPLEMENTED, ELLIPSIS=Singleton.ELLIPSIS;
    public static Object notImplemented() { return NOT_IMPLEMENTED; }
    public static Object ellipsis() { return ELLIPSIS; }
    static final ReferenceQueue<Object> ID_QUEUE=new ReferenceQueue<>();
    static final Map<IdentityRef,Long> IDENTITIES=new HashMap<>();
    static long nextIdentity=2;
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
    static final BigInteger HASH_MODULUS=BigInteger.ONE.shiftLeft(61).subtract(BigInteger.ONE);
    static final long[] HASH_SECRET=hashSecret();
    static long[] hashSecret(){
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
    static long normalizeHash(long value){return value==-1?-2:value;}
    static long numericHash(BigInteger value){return normalizeHash(value.abs().mod(HASH_MODULUS).longValue() * value.signum());}
    static long littleEndian(byte[] bytes,int start,int count){
        long value=0;for(int i=0;i<count;i++)value|=(bytes[start+i]&255L)<<(8*i);return value;
    }
    static void sipRound(long[] v){
        v[0]+=v[1];v[1]=Long.rotateLeft(v[1],13);v[1]^=v[0];v[0]=Long.rotateLeft(v[0],32);
        v[2]+=v[3];v[3]=Long.rotateLeft(v[3],16);v[3]^=v[2];
        v[0]+=v[3];v[3]=Long.rotateLeft(v[3],21);v[3]^=v[0];
        v[2]+=v[1];v[1]=Long.rotateLeft(v[1],17);v[1]^=v[2];v[2]=Long.rotateLeft(v[2],32);
    }
    static long byteHash(byte[] bytes){
        if(bytes.length==0)return 0;
        long k0=HASH_SECRET[0],k1=HASH_SECRET[1];
        long[] v={k0^0x736f6d6570736575L,k1^0x646f72616e646f6dL,k0^0x6c7967656e657261L,k1^0x7465646279746573L};
        int offset=0;
        while(offset+8<=bytes.length){long word=littleEndian(bytes,offset,8);v[3]^=word;sipRound(v);v[0]^=word;offset+=8;}
        long tail=((long)bytes.length<<56)|littleEndian(bytes,offset,bytes.length-offset);
        v[3]^=tail;sipRound(v);v[0]^=tail;v[2]^=255;sipRound(v);sipRound(v);sipRound(v);
        return normalizeHash(v[0]^v[1]^v[2]^v[3]);
    }
    static byte[] unicodeHashBytes(String text){
        int[] points=text.codePoints().toArray();int maximum=0;for(int point:points)maximum=Math.max(maximum,point);
        int width=maximum<=255?1:maximum<=65535?2:4;byte[] bytes=new byte[points.length*width];
        for(int i=0;i<points.length;i++)for(int j=0;j<width;j++)bytes[i*width+j]=(byte)(points[i]>>>(8*j));
        return bytes;
    }
    public static Object hash(Object value) {
        if(value instanceof PyFrozenSet frozen)return frozen.pythonHash();
        if(value instanceof TypeParamsObject tp)return tp.pyHash();
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
        if(value instanceof PyDict fd && fd.isFrozen())return normalizeHash((long)fd.hashCode());
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
            if(instance.payload!=null)return NativeSubclass.hash(instance);
        }
        return id(value);
    }
    static Object hashResult(Object result){
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
        if (value instanceof TypeParamsObject tp) return tp.repr();
        if (value instanceof PyComplex z) return z.toString();
        if (value instanceof Double number)return shortestFloat(number);
        if(value instanceof BoundCallable bound){
            String name=bound.function instanceof PyFunction function?(String)function.metadata.getOrDefault("__qualname__",function.displayName):"?";
            return "<bound method "+name+" of "+pyRepr(bound.self)+">";
        }
        if(value==NOT_IMPLEMENTED)return "NotImplemented";
        if(value==ELLIPSIS)return "Ellipsis";
        if (value instanceof Boolean b) return b ? "True" : "False";
        if (value instanceof String s) return s;
        if (value instanceof PyBytes bytes) return bytesRepr(bytes.data);
        if (value instanceof PyByteArray bytes) return "bytearray("+bytesRepr(bytes.toByteArray())+")";
        if (value instanceof PyMemoryView view) return view.toString();
        if (value instanceof PyTuple t) return t.pyRepr();
        if (value instanceof PyDictView v) return v.pyRepr();
        if (value instanceof PyExcArgs a) return a.str();
        if (value instanceof PyExceptionValue e) return e.syntax!=null?e.syntax.describe(pyStr(e.value)):e.value==null?"":e.typeName.equals("KeyError")?pyRepr(e.value):pyStr(e.value);
        if (value instanceof PyException e) return e.value==null?"":e.typeName.equals("KeyError")?pyRepr(e.value):e.getMessage() == null ? "" : e.getMessage();
        if (value instanceof Throwable t) return t.getMessage() == null ? "" : t.getMessage();
        if (value instanceof PyInstance instance) {
            PyMethod m=instance.cls.lookupMethod("__str__");
            if(m!=null){Object out=invoke(instance,m,new Object[0]);if(!(out instanceof String))throw new PyException("TypeError","__str__ returned non-string");return (String)out;}
            if(instance.payload!=null && (instance.cls.nativeKind.equals("str") || instance.cls.nativeKind.equals("bytes"))) return pyStr(instance.payload);
            m=instance.cls.lookupMethod("__repr__");
            if(m!=null){Object out=invoke(instance,m,new Object[0]);if(!(out instanceof String))throw new PyException("TypeError","__repr__ returned non-string");return (String)out;}
            if(instance.cls.exceptionBase!=null && instance.fields.get("args") instanceof PyTuple a) {
                if(a.items.isEmpty()) return "";
                return a.items.size()==1 ? pyStr(a.items.get(0)) : pyRepr(a);
            }
            if(instance.payload!=null) return NativeSubclass.repr(instance);
            if(instance.payload!=null && (instance.cls.nativeKind.equals("str") || instance.cls.nativeKind.equals("bytes"))) return pyStr(instance.payload);
        }
        if (value instanceof List<?> xs) return seqRepr(xs, "[", "]");
        if(value instanceof PyFrozenSet frozen)return frozen.isEmpty()?"frozenset()":"frozenset("+seqRepr(frozen,"{","}")+")";
        if (value instanceof Set<?> xs) {
            if (xs.isEmpty()) return "set()";
            return seqRepr(xs, "{", "}");
        }
        if (value instanceof Map<?, ?> m) {
            boolean frozenDict = m instanceof PyDict fd && fd.isFrozen();
            if (frozenDict && m.isEmpty()) return "frozendict()";
            StringBuilder out = new StringBuilder(frozenDict ? "frozendict({" : "{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append(pyRepr(e.getKey())).append(": ").append(pyRepr(e.getValue()));
            }
            return out.append(frozenDict ? "})" : "}").toString();
        }
        return String.valueOf(value);
    }

    static String stringRepr(String text) {
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
        if (value instanceof PyExceptionValue exc) return BuiltinsExtra.exceptionRepr(exc);
        if (value instanceof PyBytes bytes) return bytesRepr(bytes.data);
        if (value instanceof PyByteArray bytes) return "bytearray("+bytesRepr(bytes.toByteArray())+")";
        if(value instanceof PyInstance exc && exc.cls.exceptionBase!=null && exc.cls.lookupMethod("__repr__")==null && exc.fields.get("args") instanceof PyTuple a) {
            String items=seqRepr(a.items,"","");
            return exc.cls.name+"("+(a.items.size()==1?items:items)+")";
        }
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__repr__");if(m!=null){Object out=invoke(instance,m,new Object[0]);if(!(out instanceof String))throw new PyException("TypeError","__repr__ returned non-string");return (String)out;}if(instance.payload!=null)return NativeSubclass.repr(instance);}
        return pyStr(value);
    }

    static String seqRepr(Iterable<?> xs, String left, String right) {
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
            if(instance.payload!=null)return truth(instance.payload);
        }
        return true;
    }

    static Object binarySpecial(Object a,Object b,String leftName,String rightName,boolean comparison) {
        PyInstance left=a instanceof PyInstance i?i:null, right=b instanceof PyInstance i?i:null;
        PyMethod lm=left==null?null:left.cls.lookupMethod(leftName), rm=right==null?null:right.cls.lookupMethod(rightName);
        boolean rightFirst=left!=null && right!=null && left.cls!=right.cls && right.cls.mro.contains(left.cls)
            && rm!=null && (comparison || !rm.equals(left.cls.lookupMethod(rightName)));
        if(rightFirst){Object result=invoke(right,rm,new Object[]{a});if(result!=NOT_IMPLEMENTED)return result;}
        if(lm!=null){Object result=invoke(left,lm,new Object[]{b});if(result!=NOT_IMPLEMENTED)return result;}
        if(!rightFirst && rm!=null && (comparison || left==null || left.cls!=right.cls)) {
            Object result=invoke(right,rm,new Object[]{a});if(result!=NOT_IMPLEMENTED)return result;
        }
        return NativeSubclass.binary(leftName,a,b);
    }

    // ---------- Complex numbers ----------
    static String complexComponent(double value){
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
    static double complexReal(Object value,boolean protocol){
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
    static PyComplex complexValue(Object value){return value instanceof PyComplex z?z:new PyComplex(complexReal(value,false),0.0);}
    static Object complexConstructor(List<Object> args,Map<Object,Object> kwargs){
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
    static final String COMPLEX_NUMBER="(?:[0-9](?:_?[0-9])*(?:\\.(?:[0-9](?:_?[0-9])*)?)?|\\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?|inf(?:inity)?|nan";
    static final java.util.regex.Pattern COMPLEX_TEXT=java.util.regex.Pattern.compile("^([+-]?(?:"+COMPLEX_NUMBER+"))?(?:([+-])((?:"+COMPLEX_NUMBER+"))?)?([jJ])?$",java.util.regex.Pattern.CASE_INSENSITIVE);
    static PyComplex parseComplex(String text){
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
    static PyComplex complexMultiply(PyComplex z,PyComplex w){
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
    static PyComplex complexDivide(PyComplex a,PyComplex b){
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
    static PyComplex realComplexDivide(double a,PyComplex b){
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
    static PyComplex complexPower(PyComplex a,PyComplex b){
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
        if(a instanceof PyInstance instance){PyMethod method=instance.cls.lookupMethod("__iadd__");if(method!=null){Object value=invoke(instance,method,new Object[]{b});if(value!=NOT_IMPLEMENTED)return value;}if(instance.payload!=null)return NativeSubclass.inplace(instance,"__iadd__",b);}
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
        if(a instanceof PyInstance instance){PyMethod method=instance.cls.lookupMethod("__imul__");if(method!=null){Object value=invoke(instance,method,new Object[]{b});if(value!=NOT_IMPLEMENTED)return value;}if(instance.payload!=null)return NativeSubclass.inplace(instance,"__imul__",b);}
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
        if (a instanceof PyTuple ta && b instanceof PyTuple tb) {
            PyTuple out = new PyTuple();
            out.items.addAll(ta.items); out.items.addAll(tb.items); return out;
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

    public static Object sub(Object a, Object b) {if(a instanceof PyDictView || b instanceof PyDictView){Object[] ops=viewOperands(a,b);if(ops!=null){a=ops[0];b=ops[1];}} 
        if(a instanceof Set<?> && b instanceof Set<?>)return setAlgebra(a,b,"difference");
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
        if (a instanceof PyTuple ta && isIntLike(b)) return NativeSubclassMethods.repeatTuple(ta, bigInt(b));
        if (b instanceof PyTuple tb && isIntLike(a)) return NativeSubclassMethods.repeatTuple(tb, bigInt(a));
        if (isIntLike(a) && isIntLike(b)) {
            if (a instanceof Long x && b instanceof Long y) {
                try { return Math.multiplyExact(x, y); }
                catch (ArithmeticException ignored) {}
            }
            return compact(bigInt(a).multiply(bigInt(b)));
        }
        return number(a) * number(b);
    }

    static Object repeatBytes(PyByteSequence seq,BigInteger count,boolean mutable){
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
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__floordiv__","__rfloordiv__",false);if(result!=NOT_IMPLEMENTED)return result;}
        double x = number(a), y = number(b);
        if (y == 0.0) throw new ArithmeticException("float floor division by zero");
        return Math.floor(x / y);
    }

    static Object roundCall(List<Object> args,Map<Object,Object> kwargs){
        if(args.size()>2)throw new PyException("TypeError","round() takes at most 2 arguments");
        Object value=args.isEmpty()?MISSING:args.get(0),digits=args.size()>1?args.get(1):null;
        for(var entry:kwargs.entrySet()){
            if(entry.getKey().equals("number")){
                if(value!=MISSING)throw new PyException("TypeError","round() got multiple values for argument 'number'");
                value=entry.getValue();
            }else if(entry.getKey().equals("ndigits")){
                if(args.size()>1)throw new PyException("TypeError","round() got multiple values for argument 'ndigits'");
                digits=entry.getValue();
            }else throw new PyException("TypeError","invalid keyword argument for round()");
        }
        if(value==MISSING)throw new PyException("TypeError","round() missing required argument 'number'");
        return roundValue(value,digits);
    }
    static Object roundValue(Object value,Object digits){
        PyClass owner=value instanceof PyInstance instance?instance.cls:value instanceof PyClass cls?cls.metaclass:null;
        if(owner!=null){
            Object method=owner.lookupAttr("__round__");
            if(method!=MISSING){
                ArrayList<Object> arguments=new ArrayList<>();if(digits!=null)arguments.add(digits);
                return callFunction(descriptorGet(method,value,owner),arguments,new LinkedHashMap<>());
            }
        }
        if(value instanceof PyInstance w && (w.payload instanceof Double || isIntLike(w.payload)))value=w.payload;
        if(!isIntLike(value) && !(value instanceof Double))throw new PyException("TypeError","type "+typeName(value)+" doesn't define __round__ method");
        return roundNative(value,digits);
    }
    static BigInteger roundIndex(Object digits){
        Object value=NativeSubclass.indexValue(digits);
        PyClass owner=value instanceof PyInstance instance?instance.cls:value instanceof PyClass cls?cls.metaclass:null;
        if(owner!=null){
            Object method=owner.lookupAttr("__index__");
            if(method==MISSING)throw new PyException("TypeError","object cannot be interpreted as an integer");
            value=callFunction(descriptorGet(method,value,owner),new ArrayList<>(),new LinkedHashMap<>());
        }
        if(!isIntLike(value))throw new PyException("TypeError","__index__ must return an integer");
        return bigInt(value);
    }
    static Object roundNative(Object value,Object digits){
        if(isIntLike(value)){
            BigInteger integer=bigInt(value);
            if(digits==null)return compact(integer);
            BigInteger places=roundIndex(digits);
            if(places.signum()>=0 || integer.signum()==0)return compact(integer);
            int length=integer.abs().toString().length();
            if(places.compareTo(BigInteger.valueOf(-length))<0)return 0L;
            return compact(new java.math.BigDecimal(integer).setScale(places.intValue(),java.math.RoundingMode.HALF_EVEN).toBigIntegerExact());
        }
        double number=(Double)value;
        if(digits==null){
            if(Double.isNaN(number))throw new PyException("ValueError","cannot convert float NaN to integer");
            if(Double.isInfinite(number))throw new PyException("OverflowError","cannot convert float infinity to integer");
            return compact(new java.math.BigDecimal(number).setScale(0,java.math.RoundingMode.HALF_EVEN).toBigIntegerExact());
        }
        BigInteger places=roundIndex(digits);
        if(!Double.isFinite(number) || places.compareTo(BigInteger.valueOf(323))>0)return number;
        if(places.compareTo(BigInteger.valueOf(-308))<0)return Math.copySign(0.0,number);
        double result=new java.math.BigDecimal(number).setScale(places.intValue(),java.math.RoundingMode.HALF_EVEN).doubleValue();
        if(Double.isInfinite(result))throw new PyException("OverflowError","rounded value too large to represent");
        return result==0?Math.copySign(0.0,number):result;
    }

    // Keep namespace inspection out of shared arithmetic/collection dispatch paths.
    static Object varsLocals(){
        return Namespaces.localsCall();
    }
    static LinkedHashMap<Object,Object> frameLocalSnapshot(ActiveFrame frame){
        LinkedHashMap<Object,Object> result=new LinkedHashMap<>(frame.locals);
        if(frame.closure!=null)for(String name:frame.freeNames){
            PyEnv environment=frame.argumentEnv!=null?frame.argumentEnv:frame.closure;
            while(environment!=null && !environment.values.containsKey(name))environment=environment.parent;
            Object value=environment==null?UNBOUND:environment.values.get(name);
            if(value!=UNBOUND)result.put(name,value);else result.remove(name);
        }
        return result;
    }
    static Object dirLocals(){
        ActiveFrame frame=currentLogicalFrame();
        if(frame!=null && frame.namespace==null && (frame.name.equals("<module>") || !frame.inspectSupported))Namespaces.localsCall();
        Object names=frame==null?List.of():frame.namespace!=null?frame.namespace.keySet():frameLocalSnapshot(frame).keySet();
        return sorted(names);
    }
    static Object varsValue(Object value){
        if(value instanceof PyModule module)return moduleDict(module);
        if(value instanceof PyClass cls){ // a snapshot of the class namespace: writes to it do not reach the class
            LinkedHashMap<Object,Object> view=new LinkedHashMap<>();
            for(Map.Entry<String,Object> e:cls.attrs.entrySet())view.put(e.getKey(),e.getValue());
            for(Map.Entry<String,PyMethod> e:cls.methods.entrySet())view.putIfAbsent(e.getKey(),e.getValue());
            for(Map.Entry<String,PyProperty> e:cls.properties.entrySet())view.putIfAbsent(e.getKey(),e.getValue());
            return new PyDict(view);
        }
        if(value instanceof PyInstance instance){
            Object access=instance.cls.lookupAttr("__getattribute__");
            if(access!=MISSING){
                try{return callFunction(descriptorGet(access,value,instance.cls),new ArrayList<>(List.of("__dict__")),new LinkedHashMap<>());}
                catch(PyException error){if(!error.typeName.equals("AttributeError"))throw error;throw new PyException("TypeError","vars() argument must have __dict__ attribute");}
            }
            Object custom=instance.cls.lookupAttr("__dict__");
            if(custom!=MISSING){
                if(isDataDescriptor(custom))return descriptorGet(custom,value,instance.cls);
                if(instance.fields.containsKey("__dict__"))return instance.fields.get("__dict__");
                return descriptorGet(custom,value,instance.cls);
            }
        }
        try{return getattr(value,"__dict__");}
        catch(PyException error){if(!error.typeName.equals("AttributeError"))throw error;throw new PyException("TypeError","vars() argument must have __dict__ attribute");}
    }
    static Object dirValue(Object value){
        PyClass owner=value instanceof PyInstance instance?instance.cls:value instanceof PyClass cls?cls.metaclass:null;
        if(owner!=null){
            Object method=owner.lookupAttr("__dir__");
            if(method!=MISSING)return sorted(callFunction(descriptorGet(method,value,owner),new ArrayList<>(),new LinkedHashMap<>()));
        }
        return sorted(defaultDir(value));
    }
    static Object defaultDir(Object value){
        LinkedHashSet<Object> names=new LinkedHashSet<>();
        if(value instanceof PyModule module){names.addAll(((Map<?,?>)moduleDict(module)).keySet());return new ArrayList<>(names);}
        if(value instanceof PyClass cls){classDirNames(cls,names);return new ArrayList<>(names);}
        if(value instanceof PyInstance instance){
            try{Object dictionary=varsValue(value);if(dictionary instanceof Map<?,?> map)names.addAll(map.keySet());}
            catch(PyException error){if(!error.typeName.equals("TypeError"))throw error;}
            classDirNames(instance.cls,names);
        }else if(value instanceof PyFunction function){names.addAll(function.attrs.keySet());names.addAll(function.metadata.keySet());names.addAll(List.of("__closure__","__dict__","__get__"));}
        else if(value instanceof BoundCallable bound){names.addAll((List<?>)defaultDir(bound.function));names.addAll(List.of("__self__","__func__"));}
        else if(value instanceof PyManagedProperty){names.addAll(List.of("fget","fset","fdel","getter","setter","deleter","__get__","__set__","__delete__","__set_name__","__name__"));}
        else if(value instanceof PyTraceback){names.addAll(List.of("tb_frame","tb_lasti","tb_lineno","tb_next"));return new ArrayList<>(names);}
        else if(value instanceof PyBuiltinType type){nativeDirNames(type.name,names);return new ArrayList<>(names);}
        else nativeDirNames(typeName(value),names);
        names.addAll(List.of("__class__","__doc__","__format__","__dir__"));
        return new ArrayList<>(names);
    }
    static void classDirNames(PyClass cls,Set<Object> names){
        for(PyClass base:cls.mro){names.addAll(base.attrs.keySet());names.addAll(base.methods.keySet());names.addAll(base.properties.keySet());}
        names.addAll(List.of("__module__","__doc__","__class__","__format__","__dir__"));
        if(cls.instanceDictAllowed)names.add("__dict__");
        if(cls.builtinBaseName!=null)nativeDirNames(cls.builtinBaseName,names);
    }
    static void nativeDirNames(String type,Set<Object> names){
        names.addAll(List.of("__class__","__doc__","__format__","__dir__"));
        switch(type){
            case "str" -> names.addAll(List.of("strip","lstrip","rstrip","split","rsplit","join","replace","find","rfind","index","rindex","count","startswith","endswith","upper","lower","capitalize","swapcase","title","encode","__mod__"));
            case "list" -> names.addAll(List.of("append","extend","insert","pop","remove","clear","copy","count","index","reverse","sort"));
            case "dict" -> names.addAll(List.of("keys","values","items","get","pop","popitem","setdefault","update","clear","copy"));
            case "set" -> names.addAll(List.of("add","remove","discard","pop","clear","copy","union","intersection","difference","symmetric_difference","update","intersection_update","difference_update","symmetric_difference_update","issubset","issuperset","isdisjoint"));
            case "frozenset" -> names.addAll(List.of("copy","union","intersection","difference","symmetric_difference","issubset","issuperset","isdisjoint","__contains__","__len__","__iter__"));
            case "set_iterator" -> names.addAll(List.of("__iter__","__next__","__length_hint__"));
            case "int", "bool", "float" -> names.add("__round__");
            case "complex" -> names.addAll(List.of("real","imag","conjugate"));
            case "object" -> names.addAll(List.of("__new__","__init__","__init_subclass__","__setattr__","__delattr__"));
        }
    }

    public static Object ascii_(Object value){return asciiRepr(value);}
    public static Object formatValue(Object value,Object specObj){
        if(!(specObj instanceof String spec))throw new PyException("TypeError","format() argument 2 must be str, not "+(specObj==null?"None":typeName(specObj)));
        PyClass owner=value instanceof PyInstance instance?instance.cls:value instanceof PyClass cls?cls.metaclass:null;
        if(owner!=null){
            Object method=owner.lookupAttr("__format__");
            if(method!=MISSING){
                Object result=callFunction(descriptorGet(method,value,owner),new ArrayList<>(List.of(spec)),new LinkedHashMap<>());
                if(!(result instanceof String))throw new PyException("TypeError",owner.qualname+".__format__() must return a str, not "+typeName(result));
                return result;
            }
        }
        String text=FormatSpec.formatNative(value,spec);
        return text!=null?text:objectFormat(value,spec);
    }
    static Object objectFormat(Object value,Object specObj){
        if(!(specObj instanceof String spec))throw new PyException("TypeError","__format__() argument must be str");
        if(!spec.isEmpty())throw new PyException("TypeError","unsupported format string passed to "+typeName(value)+".__format__");
        return pyStr(value);
    }

    // Percent formatting is kept off the shared method dispatcher/JIT hot paths.
    static String asciiRepr(Object value){
        String repr=pyRepr(value);StringBuilder out=new StringBuilder();
        repr.codePoints().forEach(code->{
            if(code<128)out.appendCodePoint(code);
            else out.append(String.format(Locale.ROOT,code<256?"\\x%02x":code<65536?"\\u%04x":"\\U%08x",code));
        });
        return out.toString();
    }
    static BigInteger percentInteger(Object value,char conversion,boolean decimal){
        Object original=value;
        value=NativeSubclass.unwrap(value);
        if(value instanceof PyInstance instance){
            PyMethod method=decimal?instance.cls.lookupMethod("__int__"):null;
            if(method==null)method=instance.cls.lookupMethod("__index__");
            if(method!=null){
                try{
                    Object result=invoke(instance,method,new Object[0]);
                    if(isIntLike(result))return bigInt(result);
                }catch(PyException error){if(!error.typeName.equals("TypeError"))throw error;}
            }
        }
        if(isIntLike(value))return bigInt(value);
        if(decimal && value instanceof Double number){
            if(Double.isNaN(number))throw new PyException("ValueError","cannot convert float NaN to integer");
            if(Double.isInfinite(number))throw new PyException("OverflowError","cannot convert float infinity to integer");
            return new java.math.BigDecimal(number).toBigInteger();
        }
        throw new PyException("TypeError","format argument: %"+conversion+" requires "+(decimal?"a real number":conversion=='c'?"an integer or a unicode character":"an integer")+", not "+typeName(original));
    }
    static double percentFloat(Object value,char conversion){
        Object original=value;
        value=NativeSubclass.unwrap(value);
        if(value instanceof PyInstance instance){
            try{
                PyMethod method=instance.cls.lookupMethod("__float__");
                if(method!=null){
                    Object result=invoke(instance,method,new Object[0]);
                    if(result instanceof Double number)return number;
                }else{
                    method=instance.cls.lookupMethod("__index__");
                    if(method!=null){Object result=invoke(instance,method,new Object[0]);if(isIntLike(result))value=result;}
                }
            }catch(PyException error){if(!error.typeName.equals("TypeError"))throw error;}
        }
        if(!(isIntLike(value) || value instanceof Double))throw new PyException("TypeError","format argument: %"+conversion+" requires a real number, not "+typeName(original));
        double result=number(value);
        if(value instanceof BigInteger && !Double.isFinite(result))throw new PyException("OverflowError","int too large to convert to float");
        return result;
    }
    static String floatDigits(double number,char conversion,int precision,boolean alternate){return FormatFloat.digits(number,conversion,precision,alternate,false);}
    static String shortestFloat(double value){return FormatFloat.repr(value);}

    public static Object mod(Object a, Object b) {
        if(a instanceof String format)return PercentFormat.formatString(format,b);
        if(a instanceof PyByteSequence)return PercentFormat.formatBytes(a,b);
        if (isIntLike(a) && isIntLike(b)) {
            BigInteger x = bigInt(a), y = bigInt(b);
            if (y.signum() == 0) throw new ArithmeticException("integer division or modulo by zero");
            BigInteger r = x.remainder(y);
            if (r.signum() != 0 && r.signum() != y.signum()) r = r.add(y);
            return compact(r);
        }
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__mod__","__rmod__",false);if(result!=NOT_IMPLEMENTED)return result;}
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
        {Object user=NativeSubclass.unary(a,"__neg__");if(user!=MISSING)return user;}
        throw typeError("bad operand type for unary -", a);
    }

    public static Object not_(Object a) { return !truth(a); }
    public static Object pos(Object a) {
        if(a instanceof PyComplex)return a;
        if (isIntLike(a)) return compact(bigInt(a));
        if (a instanceof Double d) return d;
        {Object user=NativeSubclass.unary(a,"__pos__");if(user!=MISSING)return user;}
        throw typeError("bad operand type for unary +", a);
    }
    public static Object invert(Object a) { if(a instanceof PyInstance){Object user=NativeSubclass.unary(a,"__invert__");if(user!=MISSING)return user;} return compact(bigInt(a).not()); }
    public static Object abs(Object a) {
        if(a instanceof PyComplex z){
            double result=Math.hypot(z.real,z.imag);
            if(Double.isInfinite(result) && Double.isFinite(z.real) && Double.isFinite(z.imag))throw new PyException("OverflowError","absolute value too large");
            return result;
        }
        if (isIntLike(a)) return compact(bigInt(a).abs());
        if (a instanceof Double d) return Math.abs(d);
        {Object user=NativeSubclass.unary(a,"__abs__");if(user!=MISSING)return user;}
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
    public static Object isub(Object a,Object b){return inplaceSetBinary(a,b,"difference","__isub__");}
    public static Object ibitAnd(Object a,Object b){return inplaceSetBinary(a,b,"intersection","__iand__");}
    public static Object ibitOr(Object a,Object b){if(a instanceof PyDict fa && fa.isFrozen())return bitOr(a,b);if(a instanceof Map<?,?>){dictUpdate(a,b);return a;}return inplaceSetBinary(a,b,"union","__ior__");}
    public static Object ibitXor(Object a,Object b){return inplaceSetBinary(a,b,"symmetric_difference","__ixor__");}
    static Object inplaceSetBinary(Object a,Object b,String operation,String special){
        if(a instanceof PyInstance instance){
            Object method=instance.cls.lookupAttr(special);
            if(method!=MISSING){Object result=callFunction(descriptorGet(method,a,instance.cls),new ArrayList<>(Arrays.asList(b)),new LinkedHashMap<>());if(result!=NOT_IMPLEMENTED)return result;}
            if(instance.payload!=null)return NativeSubclass.inplace(instance,special,b);
        }
        if(a instanceof Set<?> set && !(a instanceof PyFrozenSet) && b instanceof Set<?>){
            replaceSetContents(set,(Set<?>)setAlgebra(a,b,operation));return a;
        }
        return switch(operation){case "difference"->sub(a,b);case "intersection"->bitAnd(a,b);case "union"->bitOr(a,b);default->bitXor(a,b);};
    }
    @SuppressWarnings("unchecked")
    static void replaceSetContents(Set<?> destination,Set<?> source){
        if(destination instanceof PySet target && source instanceof PySet values){target.entries.clear();target.entries.putAll(values.entries);}
        else {Set<Object> target=(Set<Object>)destination;target.clear();target.addAll(source);}
    }

    public static Object bitAnd(Object a, Object b) { if(a instanceof PyDictView || b instanceof PyDictView){Object[] ops=viewOperands(a,b);if(ops!=null){a=ops[0];b=ops[1];}} if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__and__","__rand__",false);if(result!=NOT_IMPLEMENTED)return result;}if(a instanceof Set<?> && b instanceof Set<?>)return setAlgebra(a,b,"intersection");return compact(bigInt(a).and(bigInt(b))); }
    public static Object bitOr(Object a, Object b) { if(a instanceof PyDictView || b instanceof PyDictView){Object[] ops=viewOperands(a,b);if(ops!=null){a=ops[0];b=ops[1];}} if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__or__","__ror__",false);if(result!=NOT_IMPLEMENTED)return result;}if(a instanceof Set<?> && b instanceof Set<?>)return setAlgebra(a,b,"union");if(a instanceof Map<?,?> && b instanceof Map<?,?>){PyDict out=new PyDict((Map<?,?>)a);dictUpdate(out,b);return a instanceof PyDict fa && fa.isFrozen()?out.freeze():out;}if(!(a instanceof Long)){Object union=TypeParamsUnion.tryOr(a,b);if(union!=null)return union;}return compact(bigInt(a).or(bigInt(b))); }
    public static Object bitXor(Object a, Object b) { if(a instanceof PyDictView || b instanceof PyDictView){Object[] ops=viewOperands(a,b);if(ops!=null){a=ops[0];b=ops[1];}} if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__xor__","__rxor__",false);if(result!=NOT_IMPLEMENTED)return result;}if(a instanceof Set<?> && b instanceof Set<?>)return setAlgebra(a,b,"symmetric_difference");return compact(bigInt(a).xor(bigInt(b))); }
    public static Object lshift(Object a, Object b) {
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__lshift__","__rlshift__",false);if(result!=NOT_IMPLEMENTED)return result;}
        BigInteger n = bigInt(b); if (n.signum() < 0) throw new ArithmeticException("negative shift count");
        if (n.bitLength() > 31) throw new ArithmeticException("shift count too large");
        return compact(bigInt(a).shiftLeft(n.intValue()));
    }
    public static Object rshift(Object a, Object b) {
        if(a instanceof PyInstance || b instanceof PyInstance){Object result=binarySpecial(a,b,"__rshift__","__rrshift__",false);if(result!=NOT_IMPLEMENTED)return result;}
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
        if((a instanceof PyDictView || b instanceof PyDictView) && (a instanceof Set<?> || b instanceof Set<?> || (a instanceof PyDictView && b instanceof PyDictView))){
            boolean aKind=!(a instanceof PyDictView v) || !v.kind.equals("values"), bKind=!(b instanceof PyDictView w) || !w.kind.equals("values");
            if(aKind && bKind){
                Set<?> left=a instanceof Set<?> s0?s0:new PySet(a), right=b instanceof Set<?> s1?s1:new PySet(b);
                return left.size()==right.size() && left.containsAll(right);
            }
            return a==b;
        }
        if(isIntLike(a) && isIntLike(b))return bigInt(a).equals(bigInt(b));
        if(a instanceof Double x && b instanceof Double y)return x.doubleValue()==y.doubleValue();
        if(isIntLike(a) && b instanceof Double d)return integerFloatEqual(bigInt(a),d);
        if(a instanceof Double d && isIntLike(b))return integerFloatEqual(bigInt(b),d);
        if(a instanceof PySlice x && b instanceof PySlice y)return truth(eq(x.start,y.start)) && truth(eq(x.stop,y.stop)) && truth(eq(x.step,y.step));
        if(a instanceof PyTuple x && b instanceof PyTuple y)return sequenceEqual(x.items,y.items);
        if(a instanceof List<?> x && b instanceof List<?> y)return sequenceEqual(x,y);
        return Objects.equals(a,b);
    }
    static boolean integerFloatEqual(BigInteger integer,double number){
        if(!Double.isFinite(number) || number!=Math.rint(number))return false;
        return integer.equals(new java.math.BigDecimal(number).toBigIntegerExact());
    }
    static boolean sequenceEqual(List<?> left,List<?> right){
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
    static int cmp(Object a, Object b) {
        if (isIntLike(a) && isIntLike(b)) return bigInt(a).compareTo(bigInt(b));
        if(isIntLike(a) && b instanceof Double number)return compareIntegerFloat(bigInt(a),number);
        if(a instanceof Double number && isIntLike(b))return -compareIntegerFloat(bigInt(b),number);
        if(a instanceof Double x && b instanceof Double y)return x.doubleValue()==y.doubleValue()?0:Double.compare(x,y);
        if (a instanceof Number && b instanceof Number) return Double.compare(number(a), number(b));
        if(a instanceof String x && b instanceof String y) {
            java.util.PrimitiveIterator.OfInt xi=x.codePoints().iterator(),yi=y.codePoints().iterator();
            while(xi.hasNext() && yi.hasNext()){int c=Integer.compare(xi.nextInt(),yi.nextInt());if(c!=0)return c;}
            return Boolean.compare(xi.hasNext(),yi.hasNext());
        }
        if(a instanceof PyByteSequence x && b instanceof PyByteSequence y && !(a instanceof PyMemoryView) && !(b instanceof PyMemoryView)) {
            for(int i=0;i<Math.min(x.byteSize(),y.byteSize());i++){int c=Integer.compare(x.unsignedAt(i),y.unsignedAt(i));if(c!=0)return c;}
            return Integer.compare(x.byteSize(),y.byteSize());
        }
        if (a != null && b != null && a.getClass() == b.getClass() && a instanceof Comparable c) return c.compareTo(b);
        throw new IllegalArgumentException("comparison not supported between '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    static int compareIntegerFloat(BigInteger integer,double number) {
        if(Double.isInfinite(number))return number>0?-1:1;
        if(Double.isNaN(number))return -1;
        return new java.math.BigDecimal(integer).compareTo(new java.math.BigDecimal(number));
    }

    static Object richCompare(Object a,Object b,String left,String right,int fallback){
        if(a instanceof PyInstance || b instanceof PyInstance) {
            Object result=binarySpecial(a,b,left,right,true);
            if(result!=NOT_IMPLEMENTED)return result;
        }
        if(a instanceof Set<?> x && b instanceof Set<?> y)return switch(fallback){case -2->x.size()<y.size() && y.containsAll(x);case -1->y.containsAll(x);case 1->x.containsAll(y);default->x.size()>y.size() && x.containsAll(y);};
        List<?> xs=a instanceof PyTuple tuple?tuple.items:a instanceof List<?> list?list:null;
        List<?> ys=b instanceof PyTuple tuple?tuple.items:b instanceof List<?> list?list:null;
        if(xs!=null && ys!=null && (a instanceof PyTuple)==(b instanceof PyTuple)) {
            int size=Math.min(xs.size(),ys.size());
            for(int i=0;i<size;i++) {
                Object x=xs.get(i),y=ys.get(i);
                if(x!=y && !truth(eq(x,y)))return richCompare(x,y,left,right,fallback);
            }
            a=(long)xs.size();b=(long)ys.size();
        }
        if((isIntLike(a)||a instanceof Double) && (isIntLike(b)||b instanceof Double)
            && (a instanceof Double x && Double.isNaN(x) || b instanceof Double y && Double.isNaN(y)))return false;
        int c;
        try{c=cmp(a,b);}catch(IllegalArgumentException e){
            if(e.getMessage()!=null && e.getMessage().startsWith("comparison not supported"))
                throw new PyException("TypeError","'"+switch(fallback){case -2->"<";case -1->"<=";case 1->">=";default->">";}+"' not supported between instances of '"+typeName(a)+"' and '"+typeName(b)+"'");
            throw e;
        }
        return switch(fallback){case -2->c<0;case -1->c<=0;case 1->c>=0;default->c>0;};
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
        if (value instanceof PyInstance w) return NativeSubclass.intOf(w);
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
        if (value instanceof PyInstance w) return NativeSubclass.floatOf(w);
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
    public static Object min(Object value) { return extreme(Collections.singletonList(value),Collections.emptyMap(),true); }
    public static Object max(Object value) { return extreme(Collections.singletonList(value),Collections.emptyMap(),false); }
    static Object unaryCall(Object callable,Object value) {
        return callFunction(callable,new ArrayList<>(Collections.singletonList(value)),new LinkedHashMap<>());
    }
    static Object extreme(List<Object> args,Map<Object,Object> kwargs,boolean wantMin) {
        String name=wantMin?"min":"max";
        if(args.isEmpty()) throw new PyException("TypeError",name+" expected at least 1 argument, got 0");
        for(Object keyword:kwargs.keySet()) if(!keyword.equals("key") && !keyword.equals("default"))
            throw new PyException("TypeError","invalid keyword argument for "+name+"()");
        boolean hasDefault=kwargs.containsKey("default");
        if(args.size()>1 && hasDefault) throw new PyException("TypeError","Cannot specify a default for "+name+"() with multiple positional arguments");
        Object key=kwargs.get("key"),best=null,bestKey=null;
        Iterator<?> it=(args.size()==1?iterable(args.get(0)):args).iterator();
        if(!it.hasNext()) {
            if(hasDefault)return kwargs.get("default");
            throw new PyException("ValueError",name+"() iterable argument is empty");
        }
        best=it.next();bestKey=key==null?best:unaryCall(key,best);
        while(it.hasNext()) {
            Object candidate=it.next(),candidateKey=key==null?candidate:unaryCall(key,candidate);
            if(truth(wantMin?lt(candidateKey,bestKey):gt(candidateKey,bestKey))) {
                best=candidate;bestKey=candidateKey;
            }
        }
        return best;
    }
    static Object integerBase(Object value,int radix,String prefix) {
        value=NativeSubclass.indexValue(value);
        if(value instanceof PyInstance instance) {
            PyMethod index=instance.cls.lookupMethod("__index__");
            if(index==null)throw new PyException("TypeError","object cannot be interpreted as an integer");
            value=invoke(instance,index,new Object[0]);
        }
        if(!isIntLike(value))throw new PyException("TypeError","__index__ must return an integer");
        BigInteger integer=bigInt(value);
        return (integer.signum()<0?"-":"")+prefix+integer.abs().toString(radix);
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

    static String repeatString(String s, BigInteger count) {
        if (count.signum() <= 0) return "";
        if (count.bitLength() > 31) throw new OutOfMemoryError("repeated string is too long");
        return s.repeat(count.intValue());
    }
    static Object repeatList(List<?> xs, BigInteger count) {
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

    public static Object dict0() { return new PyDict(); }
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
        Map<Object,Object> target = (Map<Object,Object>) dict;
        if (other instanceof PyInstance w && w.payload instanceof Map<?,?> pm && w.cls.lookupMethod("__iter__") == null) other = pm;
        if (other instanceof Map<?,?> src) { target.putAll((Map<Object,Object>) src); return; }
        if (other instanceof PyInstance && hasattr(other, "keys") == Boolean.TRUE) {
            for (Object key : iterable(callMethod(other, "keys", new Object[0]))) target.put(key, getitem(other, key));
            return;
        }
        long index = 0;
        for (Object item : iterable(other)) {
            ArrayList<Object> pair = new ArrayList<>();
            try { for (Object part : iterable(item)) pair.add(part); }
            catch (PyException | IllegalArgumentException e) {
                if (e instanceof PyException pe && !pe.typeName.equals("TypeError")) throw e;
                throw new PyException("TypeError", "cannot convert dictionary update sequence element #" + index + " to a sequence");
            }
            if (pair.size() != 2)
                throw new PyException("ValueError", "dictionary update sequence element #" + index + " has length " + pair.size() + "; 2 is required");
            target.put(pair.get(0), pair.get(1));
            index++;
        }
    }
    public static Object dictFrom(Object value) { Object out=dict0(); dictUpdate(out,value); return out; }

    /** dict(mapping_or_iterable, **kwargs) shared by dict and frozendict. */
    @SuppressWarnings("unchecked")
    static PyDict dictConstruct(List<Object> args,Map<Object,Object> kwargs){
        if(args.size()>1) throw new PyException("TypeError","dict expected at most 1 argument, got "+args.size());
        PyDict out=new PyDict();
        if(args.size()==1) dictUpdate(out,args.get(0));
        for(Map.Entry<Object,Object> e:kwargs.entrySet()) out.put(e.getKey(),e.getValue());
        return out;
    }
    static final Set<String> DICT_METHODS=Set.of("copy","get","keys","values","items","update","pop","popitem","setdefault","clear",
        "__contains__","__len__","__getitem__","__setitem__","__delitem__","__iter__","__reversed__","__eq__","__ne__","__or__","__ror__","__ior__");
    static final Set<String> FROZENDICT_METHODS=Set.of("copy","get","keys","values","items","__contains__","__len__","__getitem__","__iter__",
        "__reversed__","__eq__","__ne__","__or__","__ror__");
    static boolean setMethodKnown(String name,boolean frozen){
        return Set.of("copy","union","intersection","difference","symmetric_difference","issubset","issuperset","isdisjoint","__contains__","__len__","__iter__").contains(name)
            || !frozen && Set.of("add","remove","discard","clear","pop","update","intersection_update","difference_update","symmetric_difference_update","__ior__","__iand__","__ixor__","__isub__").contains(name);
    }
    /** keys()/items() views are set-like: operate on them (and any iterable partner) as sets. */
    static Object[] viewOperands(Object a,Object b){
        boolean aSetLike=a instanceof PyDictView v && !v.kind.equals("values"), bSetLike=b instanceof PyDictView w && !w.kind.equals("values");
        if(!aSetLike && !bSetLike)return null;
        if((a instanceof PyDictView v2 && v2.kind.equals("values")) || (b instanceof PyDictView w2 && w2.kind.equals("values")))return null;
        Object left=aSetLike?new PySet(a):a, right=bSetLike?new PySet(b):b;
        if(!(left instanceof Set<?>)){ if(!(left instanceof Iterable<?>) && !(left instanceof List<?>) && !(left instanceof String)) return null; left=new PySet(left); }
        if(!(right instanceof Set<?>)){ if(!(right instanceof Iterable<?>) && !(right instanceof List<?>) && !(right instanceof String)) return null; right=new PySet(right); }
        return new Object[]{left,right};
    }
    static Object setAlgebra(Object left,Object right,String operation){
        PySet a=new PySet(left),b=new PySet(right),result=new PySet();
        if(operation.equals("union")){result.entries.putAll(a.entries);for(var entry:b.entries.entrySet())result.entries.putIfAbsent(entry.getKey(),entry.getValue());}
        else {
            for(var entry:a.entries.entrySet())if(b.entries.containsKey(entry.getKey())==operation.equals("intersection"))result.entries.put(entry.getKey(),entry.getValue());
            if(operation.equals("symmetric_difference"))for(var entry:b.entries.entrySet())if(!a.entries.containsKey(entry.getKey()))result.entries.put(entry.getKey(),entry.getValue());
        }
        return left instanceof PyFrozenSet?new PyFrozenSet(result):result;
    }
    static Object setMethod(Set<?> raw,String name,Object[] args){
        boolean frozen=raw instanceof PyFrozenSet;
        if(!setMethodKnown(name,frozen))throw new PyException("AttributeError",typeName(raw)+" has no attribute '"+name+"'");
        @SuppressWarnings("unchecked") Set<Object> set=(Set<Object>)raw;
        switch(name){
            case "copy":requireArgs(name,args,0);return frozen?raw:new PySet(raw);
            case "__len__":requireArgs(name,args,0);return (long)set.size();
            case "__iter__":requireArgs(name,args,0);return set.iterator();
            case "__contains__":requireArgs(name,args,1);return set.contains(args[0]);
            case "add":requireArgs(name,args,1);set.add(args[0]);return null;
            case "remove":case "discard":requireArgs(name,args,1);boolean removed=set.remove(args[0]);if(!removed && name.equals("remove"))throw new PyException("KeyError",args[0]);return null;
            case "__ior__":case "__iand__":case "__ixor__":case "__isub__":
                requireArgs(name,args,1);if(!(args[0] instanceof Set<?>))return NOT_IMPLEMENTED;
                return inplaceSetBinary(set,args[0],name.equals("__ior__")?"union":name.equals("__iand__")?"intersection":name.equals("__ixor__")?"symmetric_difference":"difference",name);
            case "difference_update":
                for(Object argument:args){
                    if(argument==set){set.clear();continue;}
                    if(set instanceof PySet target && argument instanceof PySet other){for(SetKey key:other.entries.keySet())target.entries.remove(key);}
                    else for(Object value:iterable(argument)){SetKey key=new SetKey(value);if(set instanceof PySet target)target.entries.remove(key);else set.remove(value);}
                }
                return null;
            case "update":
                for(Object argument:args)if(set instanceof PySet target && argument instanceof PySet other){for(var entry:other.entries.entrySet())target.entries.putIfAbsent(entry.getKey(),entry.getValue());}
                else for(Object value:iterable(argument))set.add(value);
                return null;
            case "clear":requireArgs(name,args,0);set.clear();return null;
            case "pop":requireArgs(name,args,0);if(set.isEmpty())throw new PyException("KeyError","pop from an empty set");Iterator<?> iterator=set instanceof PySet nativeSet?nativeSet.entries.values().iterator():set.iterator();Object value=iterator.next();iterator.remove();return value;
            case "issubset":case "issuperset":case "isdisjoint":requireArgs(name,args,1);PySet other=new PySet(args[0]);return name.equals("issubset")?other.containsAll(set):name.equals("issuperset")?set.containsAll(other):Collections.disjoint(set,other);
            default:
                String operation=name.equals("update")?"union":name.endsWith("_update")?name.substring(0,name.length()-7):name;
                if(operation.equals("symmetric_difference"))requireArgs(name,args,1);
                boolean mutate=name.equals("update") || name.endsWith("_update");
                Object result=new PySet(set);for(Object argument:args)result=setAlgebra(result,argument,operation);
                if(mutate){replaceSetContents(set,(Set<?>)result);return null;}
                return frozen?new PyFrozenSet(result):result;
        }
    }

    public static Object set0() { return new PySet(); }
    @SuppressWarnings("unchecked")
    public static void setAdd(Object set, Object value) { ((Set<Object>) set).add(value); }
    @SuppressWarnings("unchecked")
    public static void setUpdate(Object set, Object values) { for(Object x:iterable(values)) ((Set<Object>)set).add(x); }
    public static Object setFrom(Object values) { return new PySet(values); }

    public static Object len(Object value) {
        long n;
        if (value instanceof String s) n = s.codePointCount(0, s.length());
        else if (value instanceof PyByteSequence seq) n = seq.byteSize();
        else if (value instanceof List<?> xs) n = xs.size();
        else if (value instanceof Map<?, ?> xs) n = xs.size();
        else if (value instanceof Set<?> xs) n = xs.size();
        else if (value instanceof PyDictView view) n = view.map.size();
        else if (value instanceof PyTuple t) n = t.items.size();
        else if (value instanceof PyRange r) return compact(r.length());
        else if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__len__");if(m==null){if(instance.payload!=null)return len(instance.payload);throw typeError("object has no len()",value);}Object out=invoke(instance,m,new Object[0]);BigInteger bi=bigInt(out);if(bi.signum()<0)throw new PyException("ValueError","__len__() should return >= 0");return compact(bi);}
        else throw typeError("object has no len()", value);
        return n;
    }

    public static Object getitem(Object value, Object key) {
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__getitem__");if(m!=null)return invoke(instance,m,new Object[]{key});if(instance.payload instanceof Map<?,?>)return NativeSubclass.dictGet(instance,key);if(instance.payload!=null)value=instance.payload;}
        if(value instanceof TypeParamsObject || value instanceof PyClass || value instanceof PyBuiltinType)return TypeParamsSupport.subscript(value,key);
        if (key instanceof PySlice sl) return sliceGet(value, sl);
        if (value instanceof PyByteSequence seq) {
            int i=asIndex(key); return (long)seq.unsignedAt(normalizeIndex(i,seq.byteSize()));
        }
        if (value instanceof Map<?, ?> m) {
            if (!m.containsKey(key)) throw new PyException("KeyError",key);
            return m.get(key);
        }
        if(key instanceof String || key instanceof Double || key==null || !(value instanceof List || value instanceof PyTuple || value instanceof PyRange || value instanceof String))throw indexTypeError(value,key);
        int i = asIndex(key);
        if (value instanceof List<?> xs) return xs.get(normalizeIndex(i, xs.size()));
        if (value instanceof PyTuple t) return t.items.get(normalizeIndex(i, t.items.size()));
        if (value instanceof PyRange r) {
            BigInteger n = r.length();
            if (n.bitLength() > 31) throw new PyException("OverflowError", "range index too large");
            return compact(r.start.add(r.step.multiply(BigInteger.valueOf(normalizeIndex(i, n.intValue())))));
        }
        if (value instanceof String s) {
            int count = s.codePointCount(0, s.length());
            int p = normalizeIndex(i, count);
            int start = s.offsetByCodePoints(0, p);
            int end = s.offsetByCodePoints(start, 1);
            return s.substring(start, end);
        }
        throw indexTypeError(value,key);
    }
    static PyException indexTypeError(Object value,Object key){
        String kind=typeName(value),given=typeName(key);
        if(value instanceof List || value instanceof PyTuple || value instanceof PyRange)return new PyException("TypeError",kind+" indices must be integers or slices, not "+given);
        if(value instanceof String)return new PyException("TypeError","string indices must be integers, not '"+given+"'");
        return new PyException("TypeError","'"+kind+"' object is not subscriptable");
    }

    @SuppressWarnings("unchecked")
    public static void setitem(Object value, Object key, Object item) {
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__setitem__");if(m!=null){invoke(instance,m,new Object[]{key,item});return;}if(instance.payload!=null)value=instance.payload;}
        if (value instanceof Map<?, ?> m) { ((Map<Object, Object>)m).put(key, item); return; }
        if(key instanceof PySlice sl && value instanceof PyByteArray array){byteArraySetSlice(array,sl,item);return;}
        if(key instanceof PySlice sl && value instanceof List<?>){NativeSubclassMethods.listSetSlice((List<Object>)value,sl,item);return;}
        int i = asIndex(key);
        if(value instanceof PyByteArray array){array.setUnsigned(normalizeIndex(i,array.byteSize()),byteValue(item));return;}
        if(value instanceof PyMemoryView view){view.setUnsigned(normalizeIndex(i,view.byteSize()),byteValue(item));return;}
        if (value instanceof List<?> xs) { ((List<Object>)xs).set(normalizeIndex(i, xs.size()), item); return; }
        throw typeError("object does not support item assignment", value);
    }
    @SuppressWarnings("unchecked")
    public static void delitem(Object value, Object key) {
        if(value instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__delitem__");if(m!=null){invoke(instance,m,new Object[]{key});return;}if(instance.payload!=null)value=instance.payload;}
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
        if(container instanceof PyInstance instance){PyMethod m=instance.cls.lookupMethod("__contains__");if(m!=null)return invoke(instance,m,new Object[]{needle});if(instance.payload!=null){container=instance.payload;if(container instanceof String || container instanceof PyByteSequence)needle=NativeSubclass.unwrap(needle);}}
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

    static int asIndex(Object value) {
        BigInteger n = bigInt(value);
        try { return n.intValueExact(); }
        catch (ArithmeticException exc) { throw new IndexOutOfBoundsException("cannot fit index into JVM list index"); }
    }

    static int normalizeIndex(int index, int size) {
        int out = index < 0 ? index + size : index;
        if (out < 0 || out >= size) throw new IndexOutOfBoundsException("index out of range");
        return out;
    }

    public static Object slice(Object start, Object stop, Object step) { return new PySlice(start, stop, step); }
    static Object sliceGet(Object value, PySlice sl) {
        if(value instanceof PyByteSequence seq) return binarySlice(seq,sl,true);
        if(value instanceof PyRange r) return r.slice(sl);
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
    static int normalizeSliceIndex(int i, int size, boolean positive) {
        if (i < 0) i += size; if (positive) return Math.max(0, Math.min(size, i)); return Math.max(-1, Math.min(size-1, i));
    }
    static int normalizeSliceStop(int i, int size, boolean positive) {
        if (i < 0) i += size; if (positive) return Math.max(0, Math.min(size, i)); return Math.max(-1, Math.min(size-1, i));
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

    public static Object sorted(Object value) { return sortedCall(Collections.singletonList(value),Collections.emptyMap()); }
    static Object sortedCall(List<Object> args,Map<Object,Object> kwargs) {
        if(args.size()!=1)throw new PyException("TypeError","sorted expected 1 argument, got "+args.size());
        ArrayList<Object> values=new ArrayList<>();for(Object value:iterable(args.get(0)))values.add(value);
        ListMethods.sortInPlace(values,kwargs);
        return values;
    }
    public static Object reversed(Object value) {
        if(value instanceof Map<?,?> || value instanceof PyDictView) {
            ArrayList<Object> items=new ArrayList<>();
            for(Object item:iterable(value)) items.add(item);
            Collections.reverse(items);
            return items;
        }
        if(value instanceof PyInstance instance) {
            PyMethod method=instance.cls.lookupMethod("__reversed__");
            if(method!=null)return invoke(instance,method,new Object[0]);
            if(instance.payload!=null && instance.cls.lookupMethod("__getitem__")==null && instance.cls.lookupMethod("__len__")==null)return reversed(instance.payload);
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
    public static Object range1(Object stop) { return new PyRange(BigInteger.ZERO, BuiltinsExtra.rangeIndex(stop), BigInteger.ONE); }
    public static Object range2(Object start, Object stop) { return new PyRange(BuiltinsExtra.rangeIndex(start), BuiltinsExtra.rangeIndex(stop), BigInteger.ONE); }
    public static Object range3(Object start, Object stop, Object step) {
        BigInteger a=BuiltinsExtra.rangeIndex(start), b=BuiltinsExtra.rangeIndex(stop), c=BuiltinsExtra.rangeIndex(step);
        if(c.signum()==0) throw new PyException("ValueError","range() arg 3 must not be zero");
        return new PyRange(a,b,c);
    }

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
    public static Object asyncIterNext(Object iterator) {
        try { return new PyAsyncNextResult(false,awaitValue(anext_(iterator))); }
        catch(PyException e) {
            if(e.typeName.equals("StopAsyncIteration")) return new PyAsyncNextResult(true,null);
            throw e;
        }
    }
    public static boolean asyncNextDone(Object result){return ((PyAsyncNextResult)result).done;}
    public static Object asyncNextValue(Object result){return ((PyAsyncNextResult)result).value;}

    static Iterable<?> iterable(Object value) {
        if(value instanceof PyInstance instance){
            PyMethod m=instance.cls.lookupMethod("__iter__");
            Object special=instance.cls.lookupAttr("__iter__");
            if(m!=null || special!=MISSING){
                Object it=special!=MISSING?callFunction(descriptorGet(special,instance,instance.cls),new ArrayList<>(),new LinkedHashMap<>()):invoke(instance,m,new Object[0]);
                return () -> new Iterator<Object>() {
                    Object buffered=null; boolean hasBuffered=false,done=false;
                    public boolean hasNext(){if(done)return false;if(hasBuffered)return true;try{buffered=next_(it);hasBuffered=true;return true;}catch(PyException e){if(e.typeName.equals("StopIteration")){done=true;return false;}throw e;}}
                    public Object next(){if(!hasNext())throw new NoSuchElementException();Object out=buffered;buffered=null;hasBuffered=false;return out;}
                };
            }
            if(instance.payload!=null)return iterable(instance.payload);
            if(instance.cls.lookupAttr("__getitem__")!=MISSING || instance.cls.lookupMethod("__getitem__")!=null)
                return () -> sequenceIterator(instance);
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
        throw new PyException("TypeError", "'" + typeName(value) + "' object is not iterable");
    }

    static Iterator<Object> sequenceIterator(PyInstance instance){
        return new Iterator<>() {
            long index=0;Object buffered;boolean ready=false,done=false;
            public boolean hasNext(){
                if(done)return false;if(ready)return true;
                try{
                    Object method=instance.cls.lookupAttr("__getitem__");
                    buffered=method!=MISSING?callFunction(descriptorGet(method,instance,instance.cls),new ArrayList<>(List.of(index)),new LinkedHashMap<>()):invoke(instance,instance.cls.lookupMethod("__getitem__"),new Object[]{index});
                    ready=true;index++;return true;
                }catch(PyException error){if(exceptionIsSubclass(error.typeName,"IndexError") || exceptionIsSubclass(error.typeName,"StopIteration")){done=true;return false;}throw error;}
            }
            public Object next(){if(!hasNext())throw new NoSuchElementException();Object value=buffered;buffered=null;ready=false;return value;}
        };
    }





    // ---------- Binary sequence types ----------




    static int byteValue(Object value){
        if(value instanceof PyInstance instance && instance.cls.lookupMethod("__index__")!=null)
            value=invoke(instance,instance.cls.lookupMethod("__index__"),new Object[0]);
        if(!isIntLike(value))throw new PyException("TypeError","integer argument expected");
        BigInteger n=bigInt(value);
        if(n.signum()<0 || n.compareTo(BigInteger.valueOf(255))>0)
            throw new PyException("ValueError","bytes must be in range(0, 256)");
        return n.intValue();
    }
    static void checkByte(int value){
        if(value<0 || value>255) throw new PyException("ValueError","byte must be in range(0, 256)");
    }
    static byte[] bytesFromIterable(Object value){
        ArrayList<Byte> out=new ArrayList<>();
        for(Object item:iterable(value)) out.add((byte)byteValue(item));
        byte[] raw=new byte[out.size()]; for(int i=0;i<out.size();i++) raw[i]=out.get(i); return raw;
    }
    static byte[] bytesFromObject(Object value){
        if(value instanceof PyInstance w && w.payload!=null && w.cls.lookupMethod("__iter__")==null && w.cls.lookupMethod("__index__")==null) value=w.payload;
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
        return new PyBytes(text.getBytes(pyCharset(enc)));
    }
    public static Object bytearray0(){return new PyByteArray();}
    public static Object bytearray1(Object value){return new PyByteArray(bytesFromObject(value));}
    public static Object bytearray2(Object value,Object encoding){
        return new PyByteArray(((PyBytes)bytes2(value,encoding)).data);
    }
    public static Object memoryview1(Object value){
        value=NativeSubclass.unwrap(value);
        if(value instanceof PyMemoryView view) return new PyMemoryView(view.source,view.start,view.length);
        if(value instanceof PyByteSequence seq) return new PyMemoryView(seq);
        throw new PyException("TypeError","memoryview: a bytes-like object is required");
    }
    public static Object bytesFromHexLiteral(Object hexObj){
        String hex=(String)hexObj; byte[] out=new byte[hex.length()/2];
        for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return new PyBytes(out);
    }
    static String bytesHex(byte[] raw){
        StringBuilder out=new StringBuilder(raw.length*2);
        for(byte b:raw)out.append(String.format("%02x",b&0xff));
        return out.toString();
    }
    static String bytesRepr(byte[] raw){
        boolean hasSingle=false,hasDouble=false;
        for(byte bb:raw){if(bb=='\'')hasSingle=true;else if(bb=='"')hasDouble=true;}
        char quote=hasSingle && !hasDouble?'"':'\'';
        StringBuilder out=new StringBuilder("b").append(quote);
        for(byte bb:raw){
            int v=bb&0xff;
            if(v=='\\' || v==quote) out.append('\\').append((char)v);
            else if(v>=32 && v<127) out.append((char)v);
            else switch(v){
                case 9 -> out.append("\\t");
                case 10 -> out.append("\\n");
                case 13 -> out.append("\\r");
                default -> out.append(String.format("\\x%02x",v));
            }
        }
        return out.append(quote).toString();
    }
    static byte[] requireBytesLike(Object value){
        if(value instanceof PyInstance w && w.payload instanceof PyByteSequence seq) return seq.toByteArray();
        if(value instanceof PyByteSequence seq) return seq.toByteArray();
        throw new PyException("TypeError","a bytes-like object is required");
    }
    static Object binaryResult(PyByteSequence receiver,byte[] raw){
        return receiver instanceof PyByteArray ? new PyByteArray(raw) : new PyBytes(raw);
    }
    static int clampSliceBound(Object bound,int size,int fallback){
        if(bound==null)return fallback;
        int i=asIndex(bound); if(i<0)i+=size;
        return Math.max(0,Math.min(size,i));
    }
    static int binaryIndexOf(byte[] hay,byte[] needle,int start,int end){
        start=Math.max(0,Math.min(hay.length,start)); end=Math.max(start,Math.min(hay.length,end));
        if(needle.length==0)return start;
        outer: for(int i=start;i+needle.length<=end;i++){
            for(int j=0;j<needle.length;j++)if(hay[i+j]!=needle[j])continue outer;
            return i;
        }
        return -1;
    }
    static int binaryLastIndexOf(byte[] hay,byte[] needle,int start,int end){
        start=Math.max(0,Math.min(hay.length,start)); end=Math.max(start,Math.min(hay.length,end));
        if(needle.length==0)return end;
        outer: for(int i=end-needle.length;i>=start;i--){
            for(int j=0;j<needle.length;j++)if(hay[i+j]!=needle[j])continue outer;
            return i;
        }
        return -1;
    }
    static Object binarySearch(PyByteSequence seq,Object[] args,boolean reverse,boolean raising){
        if(args.length<1||args.length>3)throw new PyException("TypeError",(reverse?(raising?"rindex":"rfind"):(raising?"index":"find"))+"() takes from 1 to 3 arguments");
        byte[] hay=seq.toByteArray(), needle=requireBytesLike(args[0]);
        int start=args.length>=2?clampSliceBound(args[1],hay.length,0):0;
        int end=args.length>=3?clampSliceBound(args[2],hay.length,hay.length):hay.length;
        int found=reverse?binaryLastIndexOf(hay,needle,start,end):binaryIndexOf(hay,needle,start,end);
        if(raising&&found<0)throw new PyException("ValueError","subsection not found");
        return (long)found;
    }
    static Object binaryPartition(PyByteSequence seq,Object[] args,boolean reverse){
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

    static Object bytesMaketrans(Object fromObj,Object toObj){
        byte[] from=requireBytesLike(fromObj), to=requireBytesLike(toObj);
        if(from.length!=to.length)throw new PyException("ValueError","maketrans arguments must have same length");
        byte[] table=new byte[256]; for(int i=0;i<256;i++)table[i]=(byte)i;
        for(int i=0;i<from.length;i++)table[from[i]&0xff]=to[i];
        return new PyBytes(table);
    }
    static Object binaryTranslate(PyByteSequence seq,Object[] args){
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
    static boolean byteInSet(int value,byte[] chars){
        for(byte b:chars)if((b&0xff)==value)return true;
        return false;
    }
    static Object binaryStrip(PyByteSequence seq,Object[] args,int mode){
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
    static int asciiLower(int v){return v>='A'&&v<='Z'?v+32:v;}
    static int asciiUpper(int v){return v>='a'&&v<='z'?v-32:v;}
    static boolean asciiAlpha(int v){return (v>='A'&&v<='Z')||(v>='a'&&v<='z');}
    static Object binaryCase(PyByteSequence seq,String op){
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

    static Object binaryFind(PyByteSequence seq,Object[] args){
        if(args.length<1||args.length>3)throw new PyException("TypeError","find() takes from 1 to 3 arguments");
        byte[] hay=seq.toByteArray(), needle=requireBytesLike(args[0]);
        int start=args.length>=2?clampSliceBound(args[1],hay.length,0):0;
        int end=args.length>=3?clampSliceBound(args[2],hay.length,hay.length):hay.length;
        return (long)binaryIndexOf(hay,needle,start,end);
    }
    static Object binaryCount(PyByteSequence seq,Object[] args){
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
    static Object binaryStartsEnds(PyByteSequence seq,Object[] args,boolean starts){
        if(args.length<1||args.length>3)throw new PyException("TypeError",(starts?"startswith":"endswith")+"() takes from 1 to 3 arguments");
        byte[] hay=seq.toByteArray(), needle=requireBytesLike(args[0]);
        int start=args.length>=2?clampSliceBound(args[1],hay.length,0):0;
        int end=args.length>=3?clampSliceBound(args[2],hay.length,hay.length):hay.length;
        if(end<start||needle.length>end-start)return false;
        int pos=starts?start:end-needle.length;
        for(int i=0;i<needle.length;i++)if(hay[pos+i]!=needle[i])return false;
        return true;
    }
    static Object binaryReplace(PyByteSequence seq,Object[] args){
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
    static boolean asciiWhitespace(int v){return v==9||v==10||v==11||v==12||v==13||v==32;}
    static Object binarySplit(PyByteSequence seq,Object[] args){
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
    static Object binaryJoin(PyByteSequence sep,Object iterableObj){
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
    static void byteArraySetSlice(PyByteArray array,PySlice sl,Object replacementObj){
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

    static Object binarySlice(PyByteSequence seq,PySlice sl,boolean asView){
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

    // Cells share the existing lexical binding, including writes and deletion.
    static PyCell envCell(PyEnv env,String name) {
        while(env!=null && !env.values.containsKey(name))env=env.parent;
        if(env==null)throw new PyException("RuntimeError","closure binding unavailable: "+name);
        PyEnv binding=env;
        return env.cells.computeIfAbsent(name,key->new PyCell(binding,key));
    }
    public static void classPublishCell(Object namespace,Object env) {
        classNamespacePut(namespace,"__classcell__",envCell((PyEnv)env,"__class__"));
    }
    public static void classValidateCell(Object env,Object created,Object required) {
        if(!truth(required) || !(created instanceof PyClass))return;
        Object value=envCell((PyEnv)env,"__class__").value();
        if(value==UNBOUND)throw new PyException("RuntimeError","__class__ not set defining class. Was __classcell__ propagated to type.__new__?");
        if(value!=created)throw new PyException("TypeError","__class__ set to a different class defining class");
    }
    static void populateClassCell(PyClass cls,Map<?,?> namespace) {
        if(!namespace.containsKey("__classcell__"))return;
        Object value=namespace.get("__classcell__");
        if(!(value instanceof PyCell cell))throw new PyException("TypeError","__classcell__ must be a nonlocal cell");
        cell.set(cls);
    }
    static PyTuple functionClosure(PyFunction function) {
        if(function.closureTuple==null){
            PyTuple result=new PyTuple();
            for(String name:function.freeNames)result.items.add(envCell(function.closure,name));
            function.closureTuple=result;
        }
        return function.closureTuple;
    }

    public static Object envParent(Object env){return ((PyEnv)env).parent;}

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

    static final Object UNBOUND = new Object();
    public static Object unbound() { return UNBOUND; }
    public static Object requireLocal(Object value,Object name) {
        if(value==UNBOUND) throw new PyException("UnboundLocalError","local variable '"+name+"' referenced before assignment");
        return value;
    }
    public static Object requireGlobal(Object value,Object name) {
        if(value==UNBOUND) throw new PyException("NameError","name '"+name+"' is not defined");
        return value;
    }
    // ---- namespaces for exec()/eval() code (see ExecSupport) ----
    /** Python codec name -> Java charset ("latin-1", "utf_8", ...); unknown names raise LookupError. */
    static Charset pyCharset(String name){
        String n=name.toLowerCase(Locale.ROOT).replace('_','-').trim();
        switch(n){
            case "utf-8","utf8","u8","utf" -> { return java.nio.charset.StandardCharsets.UTF_8; }
            case "latin-1","latin1","iso-8859-1","iso8859-1","l1","latin","8859","cp819" -> { return java.nio.charset.StandardCharsets.ISO_8859_1; }
            case "ascii","us-ascii","646" -> { return java.nio.charset.StandardCharsets.US_ASCII; }
            case "utf-16","utf16" -> { return java.nio.charset.StandardCharsets.UTF_16; }
            case "utf-16le","utf-16-le" -> { return java.nio.charset.StandardCharsets.UTF_16LE; }
            case "utf-16be","utf-16-be" -> { return java.nio.charset.StandardCharsets.UTF_16BE; }
            default -> {
                try { return Charset.forName(n); }
                catch(RuntimeException e){ throw new PyException("LookupError","unknown encoding: "+name); }
            }
        }
    }
    static Class<?> ownerClass(Object owner) throws ClassNotFoundException { return owner instanceof Class<?> c ? c : Class.forName((String)owner); }
    public static Object nsLoad(Object locals,Object globals,Object nameObj) {
        Map<?,?> l=(Map<?,?>)locals, g=(Map<?,?>)globals;
        Object value=UNBOUND;
        if(l!=g && l.containsKey(nameObj)) value=l.get(nameObj);
        else if(g.containsKey(nameObj)) value=g.get(nameObj);
        return value!=UNBOUND?value:Namespaces.builtinOrError(globals,nameObj);
    }
    @SuppressWarnings("unchecked")
    public static void nsStore(Object value,Object namespace,Object name) { ((Map<Object,Object>)namespace).put(name,value); }
    public static void nsDelete(Object namespace,Object name) {
        Map<?,?> map=(Map<?,?>)namespace;
        if(!map.containsKey(name)) throw new PyException("NameError","name '"+name+"' is not defined");
        map.remove(name);
    }
    // ---- implicit namespaces for exec()/eval() called from statically compiled code ----
    @SuppressWarnings("unchecked")
    public static void snapshotPut(Object dict,Object value,Object name){ if(value!=UNBOUND) ((Map<Object,Object>)dict).put(name,value); }
    /** After exec: names the executed code created that the module does not know statically live in the extras dict. */
    @SuppressWarnings("unchecked")
    public static void snapshotExtras(Object extras,Object dict,Object knownCsv){
        java.util.Set<String> known=new java.util.HashSet<>(Arrays.asList(((String)knownCsv).split(",")));
        Map<Object,Object> out=(Map<Object,Object>)extras; out.clear();
        for(Map.Entry<?,?> e:((Map<?,?>)dict).entrySet()) if(!(e.getKey() instanceof String k && known.contains(k))) out.put(e.getKey(),e.getValue());
    }
    public static Object snapshotGet(Object dict,Object name){ Map<?,?> map=(Map<?,?>)dict; return map.containsKey(name)?map.get(name):UNBOUND; }
    /** The calling function's locals as a fresh dict (read-only snapshot, as for locals() in CPython 3.13+). */
    public static Object snapshotLocals(){ return new PyDict((Map<?,?>)varsLocals()); }
    public static Object execBuiltin(Object name,Object source,Object globals,Object locals){
        return callBuiltin((String)name,new ArrayList<Object>(Arrays.asList(source,globals,locals)),new LinkedHashMap<Object,Object>());
    }
    static int logicalFrameDepth(){ return LOGICAL_FRAMES.get().size(); }
    static void truncateLogicalFrames(int depth){ ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get(); while(stack.size()>depth) stack.pop(); }
    /** Value of globals["__builtins__"]: builtins are resolved by name in the runtime, so this is a marker. */
    static Object builtinModuleStandIn(){ return builtinFunction("print"); }
    public static Object globalGet(Object value,Object nameObj) {
        if(value!=UNBOUND) return value;
        String name=(String)nameObj;
        if(name.equals("NotImplemented")) return NOT_IMPLEMENTED;
        if(name.equals("Ellipsis")) return ELLIPSIS;
        if(AnnotationSupport.isHelper(name))return builtinFunction(name);
        if(Set.of("exec","eval","compile","aiter","anext","ord","chr","repr","print","hash","id","len","iter","next","reversed","getattr","hasattr","setattr","delattr","callable","isinstance","issubclass","pow","abs","any","all","sum","min","max","sorted","hex","oct","bin","format","dir","vars","round","globals","locals").contains(name)) return builtinFunction(name);
        if(Set.of("object","int","bool","float","complex","classmethod","staticmethod","property","str","bytes","bytearray","memoryview","list","tuple","dict","set","frozenset","frozendict","sentinel","range","type","map","zip","enumerate","filter","slice","super").contains(name) || exceptionIsSubclass(name,"BaseException")) return builtinType(name);
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
    static final Object GENERATOR_DONE = new Object();



    public static Object makeGenerator(Object ownerObj, Object methodObj, Object envObj) {
        return new PyGenerator(ownerObj,(String)methodObj,(PyEnv)envObj);
    }
    public static Object makeGeneratorEx(Object ownerObj,Object methodObj,Object envObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        return new PyGenerator(ownerObj,(String)methodObj,(PyEnv)envObj,(String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue());
    }
    public static Object makeSuspendableCoroutineEx(Object ownerObj,Object methodObj,Object envObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        PyGenerator frame=new PyGenerator(ownerObj,(String)methodObj,(PyEnv)envObj,(String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue());
        return new PyCoroutine(frame,(String)nameObj);
    }
    public static Object asyncGeneratorYield(Object value){return new PyAsyncGeneratorYield(value);}

    public static Object makeAsyncGeneratorEx(Object ownerObj,Object methodObj,Object envObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        PyGenerator frame=new PyGenerator(ownerObj,(String)methodObj,(PyEnv)envObj,(String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue());
        return new PyAsyncGenerator(frame,(String)nameObj);
    }
    public static void setGeneratorFirstArgument(Object generator,Object name){
        PyGenerator frame=generator instanceof PyAsyncGenerator async?async.frame:(PyGenerator)generator;
        frame.firstArgument=(String)name;
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

    static boolean exceptionObjectIs(Object value,String name) {
        if(value instanceof PyExceptionValue e) return exceptionIsSubclass(e.typeName,name);
        if(value instanceof PyException e) return exceptionIsSubclass(e.typeName,name);
        if(value instanceof PyInstance inst && inst.cls.exceptionBase!=null) return exceptionIsSubclass(inst.cls.name,name);
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
            ownerObj, (String)methodObj,
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
            ownerObj, (String)methodObj,
            splitNames((String)posonlyObj), splitNames((String)poskwObj), splitNames((String)kwonlyObj),
            (String)varargObj, (String)kwargObj, defaults, (PyEnv)closureObj, (Boolean)envModeObj
        );
    }

    public static void setFunctionMeta(Object functionObj,Object nameObj,Object filenameObj,Object firstlineObj) {
        PyFunction f=(PyFunction)functionObj; f.displayName=(String)nameObj; f.filename=(String)filenameObj; f.firstlineno=bigInt(firstlineObj).longValue();
    }
    public static void setFunctionAttributes(Object functionObj,Object module,Object qualname,Object doc){
        PyFunction function=(PyFunction)functionObj;
        function.metadata.put("__module__",module);function.metadata.put("__qualname__",qualname);
        function.metadata.put("__name__",function.displayName);function.metadata.put("__doc__",doc);
    }
    public static void setFunctionClassCell(Object function,Object present){((PyFunction)function).hasClassCell=truth(present);}
    public static void setFunctionFreeNames(Object function,Object names){((PyFunction)function).freeNames=splitNames((String)names);}
    public static void setFunctionAsync(Object functionObj) { ((PyFunction)functionObj).asyncMode=true; }
    public static void setFunctionAsyncGenerator(Object functionObj) { ((PyFunction)functionObj).asyncGeneratorMode=true; }

    static List<String> splitNames(String csv) {
        if (csv == null || csv.isEmpty()) return List.of();
        return Arrays.asList(csv.split(",", -1));
    }

    public static Object callFunction(Object callable, Object argsObj, Object kwargsObj) {
        @SuppressWarnings("unchecked") List<Object> args = (List<Object>) argsObj;
        @SuppressWarnings("unchecked") Map<Object,Object> kwargs = (Map<Object,Object>) kwargsObj;
        if (callable instanceof PyFunction f) return f.call(args, kwargs);
        if(callable instanceof BoundCallable method){
            ArrayList<Object> actual=new ArrayList<>();actual.add(method.self);actual.addAll(args);
            return callFunction(method.function,actual,kwargs);
        }
        if(callable instanceof PyMethodDescriptor method && method.kind.equals("static"))return callFunction(method.function,args,kwargs);
        if (callable instanceof NativeFunction nf) return nf.body.call(args,kwargs);
        if (callable instanceof TypeParamsGeneric generic) return generic.call(args,kwargs);
        if (callable instanceof PyBuiltinFunction f) return callBuiltin(f.name,args,kwargs);
        if (callable instanceof BuiltinBoundMethod method) {
            if(!kwargs.isEmpty()) {
                if(method.self instanceof PyBuiltinType nativeType && NativeSubclass.isKind(nativeType.name) && (method.name.equals("__init__") || method.name.equals("__new__")))
                    return NativeSubclassMethods.callInitNew(nativeType.name,method.name,args,kwargs);
                if(method.self instanceof Map<?,?> && method.name.equals("update")) return callMethodDynamic(method.self,method.name,args,kwargs);
                if(method.self instanceof List<?> && method.name.equals("sort")) return callMethodDynamic(method.self,method.name,args,kwargs);
                if(method.self instanceof PyBuiltinType listType && listType.name.equals("list") && method.name.equals("sort") && !args.isEmpty() && args.get(0) instanceof List<?>)
                    return callMethodDynamic(args.get(0),method.name,new ArrayList<>(args.subList(1,args.size())),kwargs);
                if(method.self instanceof String fmt && method.name.equals("format")) return FormatTemplate.format(fmt,args,kwargs);
                List<String> params=method.self instanceof String ? StringMethods.PARAMS.get(method.name) : isIntLike(method.self) ? IntMethods.PARAMS.get(method.name) : null;
                if(params==null) throw new PyException("TypeError","method takes no keyword arguments");
                Object[] positional=new Object[Math.max(args.size(),params.size())]; int highest=args.size();
                for(int i=0;i<args.size();i++) positional[i]=args.get(i);
                for(Map.Entry<Object,Object> e:kwargs.entrySet()) {
                    int at=params.indexOf((String)e.getKey());
                    if(at<0) throw new PyException("TypeError",method.name+"() got an unexpected keyword argument '"+e.getKey()+"'");
                    if(at<args.size()) throw new PyException("TypeError",method.name+"() got multiple values for argument '"+e.getKey()+"'");
                    positional[at]=e.getValue(); highest=Math.max(highest,at+1);
                }
                // skipped parameters keep their defaults (None here); none of these methods has a required parameter
                return callMethod(method.self,method.name,Arrays.copyOf(positional,highest));
            }
            return callMethod(method.self,method.name,args.toArray());
        }
        if (callable instanceof PyBuiltinType type) {
            if(!args.isEmpty() && args.get(0) instanceof PyInstance wrapped && wrapped.payload!=null) args=NativeSubclass.nativeArgs(type.name,args);
            if(type.name.equals("object")){
                if(!args.isEmpty() || !kwargs.isEmpty())throw new PyException("TypeError","object() takes no arguments");
                return new PyObject();
            }
            if(type.name.equals("super"))return superConstructor(args,kwargs);
            if(type.name.equals("classmethod") || type.name.equals("staticmethod")){
                if(!kwargs.isEmpty() || args.size()!=1)throw new PyException("TypeError",type.name+"() requires one positional argument");
                return methodDescriptor(args.get(0),type.name.equals("classmethod")?"class":"static");
            }
            if(type.name.equals("property")){
                if(args.size()>4)throw new PyException("TypeError","property() takes at most 4 arguments");
                Object[] values=new Object[4];for(int i=0;i<args.size();i++)values[i]=args.get(i);
                List<String> names=List.of("fget","fset","fdel","doc");
                for(var entry:kwargs.entrySet()){
                    int index=names.indexOf(entry.getKey());
                    if(index<0 || index<args.size())throw new PyException("TypeError","invalid property() argument");values[index]=entry.getValue();
                }
                return new PyManagedProperty(values[0],values[1],values[2],values[3]);
            }
            if(type.name.equals("range")){
                return BuiltinsExtra.rangeCall(args,kwargs);
            }
            if(type.name.equals("type")){
                if(kwargs.isEmpty() && args.size()==1)return typeOf(args.get(0));
                if(args.size()==3)return typeNew(null,args.get(0),args.get(1),args.get(2),kwargs);
                throw new PyException("TypeError","type() takes 1 or 3 arguments");
            }
            if(type.name.equals("memoryview") && kwargs.isEmpty() && args.size()==1)return memoryview1(args.get(0));
            if(type.name.equals("sentinel"))return TypeParamsSentinel.construct(args,kwargs);
            if(type.name.equals("complex"))return complexConstructor(args,kwargs);
            if(Set.of("map","zip","enumerate","filter").contains(type.name)) return callBuiltin(type.name,args,kwargs);
            if(type.name.equals("frozendict")) return dictConstruct(args,kwargs).freeze();
            if(type.name.equals("dict")) return dictConstruct(args,kwargs);
            if(type.name.equals("slice")) {
                if(!kwargs.isEmpty() || args.isEmpty() || args.size()>3) throw new PyException("TypeError","slice() requires 1 to 3 positional arguments");
                return new PySlice(args.size()==1?null:args.get(0),args.size()==1?args.get(0):args.get(1),args.size()<3?null:args.get(2));
            }
            if(exceptionIsSubclass(type.name,"BaseException")) {
                return BuiltinsExtra.newExceptionCall(type.name,args,kwargs);
            }
            if((!kwargs.isEmpty() || args.size()>1) && NativeSubclass.isKind(type.name)) return NativeSubclass.construct(type.name,args.toArray(),kwargs);
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
                case "frozenset" -> args.isEmpty()?new PyFrozenSet(List.of()):value instanceof PyFrozenSet?value:new PyFrozenSet(value);
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
        if(callable instanceof PyInstance instance){
            PyMethod method=instance.cls.lookupMethod("__call__");
            if(method!=null)return invokeKw(instance,method,args,kwargs);
        }
        throw new PyException("TypeError", "'"+typeName(callable)+"' object is not callable");
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
    static final ThreadLocal<ArrayDeque<ActiveFrame>> LOGICAL_FRAMES=ThreadLocal.withInitial(ArrayDeque::new);
    public static void pushLogicalFrame(Object nameObj,Object filenameObj,Object firstlineObj){
        LOGICAL_FRAMES.get().push(new ActiveFrame((String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue()));
    }
    public static void pushModuleFrame(Object nameObj,Object filenameObj,Object firstlineObj,Object owner){
        ActiveFrame frame=new ActiveFrame((String)nameObj,(String)filenameObj,bigInt(firstlineObj).longValue()); frame.owner=owner;
        LOGICAL_FRAMES.get().push(frame);
    }
    public static void setFunctionGenerator(Object functionObj){((PyFunction)functionObj).generatorLike=true;}
    public static void traceLine(Object lineObj){Tracing.traceLine(lineObj);}
    public static void traceHandler(Object throwable){Tracing.traceHandler(throwable);}
    public static void popLogicalFrame(){ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get();if(!stack.isEmpty())stack.pop();}
    public static void setCurrentLine(Object lineObj){ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get();if(!stack.isEmpty())stack.peek().line=bigInt(lineObj).longValue();}
    @SuppressWarnings("unchecked")
    public static void frameSetNamespace(Object namespace){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.namespace=(Map<Object,Object>)namespace;}
    public static void frameSetArgumentEnv(Object env){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.argumentEnv=(PyEnv)env;}
    public static void frameSetLocal(Object nameObj,Object value){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.locals.put(nameObj,value);}
    public static Object frameSetLocalValue(Object value,Object nameObj){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.locals.put(nameObj,value);return value;}
    public static void frameDelLocal(Object nameObj){ActiveFrame frame=currentLogicalFrame();if(frame!=null)frame.locals.remove(nameObj);}
    static ActiveFrame currentLogicalFrame(){ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get();return stack.isEmpty()?null:stack.peek();}

    static PyTraceback prependTraceback(PyTraceback current,String name,String filename,long firstline,long line){
        ActiveFrame active=currentLogicalFrame();
        LinkedHashMap<Object,Object> locals = active!=null && active.name.equals(name) && active.filename.equals(filename)
            ? active.locals : new LinkedHashMap<Object,Object>();
        if(current!=null && current.frame.code.coName.equals(name) && current.frame.code.coFilename.equals(filename)) return current;
        PyCode code=new PyCode(name,filename,firstline);
        PyFrame shown=new PyFrame(code,locals); shown.fixedLine=line;
        return new PyTraceback(shown,line,current);
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

    /** A PyException for a user-defined exception class or instance, or null when {@code value} is neither. */
    static PyException userException(Object value) {
        if(value instanceof PyClass cls && cls.exceptionBase!=null) value=instantiate(cls,new Object[0]);
        if(value instanceof PyInstance inst && inst.cls.exceptionBase!=null) return new PyException(inst.cls.name,inst);
        return null;
    }
    public static void raiseObject(Object value) {
        PyException user=userException(value);
        if(user!=null) throw user;
        if (value instanceof PyExceptionValue e) { PyException again = new PyException(e.typeName, e.value, e.cause, e.context, e.suppressContext, e.traceback); again.syntax = e.syntax; throw again; }
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
        PyException raised=userException(value);
        if (raised!=null) {}
        else if (value instanceof PyExceptionValue e) { raised=new PyException(e.typeName,e.value,e.cause,e.context,e.suppressContext,e.traceback); raised.syntax=e.syntax; }
        else if (value instanceof PyException e) raised=e;
        else throw new PyException("TypeError", "exceptions must derive from BaseException");
        if(!raised.suppressContext && contextObj instanceof Throwable t) {
            raised.context=exceptionInstance(t);
        }
        throw raised;
    }
    public static void raiseObjectFrom(Object value, Object causeObj) {
        PyException raised=userException(value);
        if(raised!=null) {}
        else if(value instanceof PyExceptionValue e) { raised=new PyException(e.typeName,e.value); raised.syntax=e.syntax; }
        else if(value instanceof PyException e) raised=e;
        else throw new PyException("TypeError","exceptions must derive from BaseException");
        if(causeObj == null) { raised.cause=null; raised.suppressContext=true; throw raised; }
        if(causeObj instanceof PyExceptionValue e) raised.cause=e;
        else if(causeObj instanceof PyException e) raised.cause=exceptionInstance(e);
        else if(causeObj instanceof PyInstance ci && ci.cls.exceptionBase!=null) raised.cause=ci;
        else throw new PyException("TypeError","exception causes must derive from BaseException");
        raised.suppressContext=true;
        if(raised.value instanceof PyInstance user) user.fields.put("__cause__",raised.cause);
        throw raised;
    }

    public static boolean exceptionMatches(Object throwableObj, Object typeObj) {
        Throwable t = (Throwable)throwableObj;
        String requested = typeObj instanceof PyBuiltinType bt ? bt.name : (String)typeObj;
        String actual = pythonExceptionType(t);
        return exceptionIsSubclass(actual, requested);
    }

    static final Map<String,String> EXCEPTION_PARENTS = new java.util.concurrent.ConcurrentHashMap<>(Map.ofEntries(
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
        Map.entry("ProcessLookupError","OSError")));
    static { BuiltinsExtra.registerExceptionTypes(EXCEPTION_PARENTS); }
    static boolean exceptionIsSubclass(String actual, String requested) {
        if(actual.equals(requested)) return actual.equals("BaseException") || EXCEPTION_PARENTS.containsKey(actual);
        if(actual.equals("ExceptionGroup") && requested.equals("BaseExceptionGroup")) return true;
        for(String parent=EXCEPTION_PARENTS.get(actual);parent!=null;parent=EXCEPTION_PARENTS.get(parent))
            if(parent.equals(requested) || parent.equals("ExceptionGroup") && requested.equals("BaseExceptionGroup")) return true;
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
        if (t instanceof PyException p && p.value instanceof PyInstance user && user.cls.exceptionBase!=null) return user;
        if (t instanceof PyException p) { PyExceptionValue v = new PyExceptionValue(p.typeName,p.value,p.cause,p.context,p.suppressContext,p.traceback); v.syntax = p.syntax; return v; }
        return new PyExceptionValue(pythonExceptionType(t), exceptionValue(t));
    }

    static String pythonExceptionType(Throwable t) {
        if (t instanceof PyException p) return p.typeName;
        if (t instanceof ArithmeticException) return "ZeroDivisionError";
        if (t instanceof IndexOutOfBoundsException) return "IndexError";
        if (t instanceof NoSuchElementException) return "KeyError";
        if (t instanceof IllegalArgumentException || t instanceof ClassCastException) return "TypeError";
        if (t instanceof AssertionError) return "AssertionError";
        if (t instanceof NoSuchElementException) return "KeyError";
        return "RuntimeError";
    }




    // ---------- Python classes / instances ----------

    public static Object class0(Object name) { return new PyClass("__main__", (String) name); }
    public static Object classCreate(Object name,Object module) { return new PyClass((String)module,(String)name); }
    public static void classAddBase(Object cls, Object base) {
        PyClass target=(PyClass)cls;
        if(base instanceof PyClass pyBase){
            target.bases.add(pyBase);
            if(pyBase.exceptionBase!=null && target.exceptionBase==null){ target.exceptionBase=pyBase.exceptionBase; EXCEPTION_PARENTS.put(target.name,pyBase.name); }
            return;
        }
        if(base instanceof TypeParamsGeneric generic && generic.origin instanceof PyClass origin){ classAddBase(cls,origin); return; }
        if(base instanceof PyBuiltinType sealed && sealed.name.equals("sentinel")) throw new PyException("TypeError","type 'sentinel' is not an acceptable base type");
        if(base instanceof PyBuiltinType exc && exceptionIsSubclass(exc.name,"BaseException")) {
            if(target.exceptionBase==null){ target.exceptionBase=exc.name; target.builtinBaseName=exc.name; EXCEPTION_PARENTS.put(target.name,exc.name); }
            return;
        }
        if(base instanceof PyBuiltinType builtin && (builtin.name.equals("object") || builtin.name.equals("type") || builtin.name.equals("super"))) {
            target.builtinBaseName=builtin.name;
            return;
        }
        if(base instanceof PyBuiltinType nativeBase && NativeSubclass.isKind(nativeBase.name)){ NativeSubclass.addBase(target,nativeBase); return; }
        throw new PyException("TypeError","class base must be a class");
    }
    public static void classAddAttr(Object cls, Object pyName, Object value) {
        PyClass target=(PyClass)cls;String name=(String)pyName;
        if(value instanceof PyFunction && Set.of("__new__","__init_subclass__","__class_getitem__").contains(name))
            value=methodDescriptor(value,name.equals("__new__")?"static":"class");
        target.attrs.put(name, value);
        target.methods.remove(name);target.properties.remove(name);
        PyMethod method=methodFromDescriptor(value);
        if(method!=null)target.methods.put(name,method);
    }
    static PyMethod methodFromDescriptor(Object value){
        String kind="instance";
        if(value instanceof PyMethodDescriptor descriptor){kind=descriptor.kind;value=descriptor.function;}
        if(value instanceof PyFunction function)return new PyMethod(function.owner,function.method,kind,function);
        return null;
    }

    static PyTuple explicitBasesTuple(PyClass cls) {
        PyTuple tuple=new PyTuple(); tuple.items.addAll(cls.bases);
        if(cls.builtinBaseName!=null) tuple.items.add(new PyBuiltinType(cls.builtinBaseName));
        return tuple;
    }

    static PyClass resolveMetaclass(PyClass cls,Object explicitMeta) {
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
    public static void classNamespaceDelete(Object namespace,Object name) {
        Map<?,?> values=(Map<?,?>)namespace;
        if(!values.containsKey(name)) throw new PyException("NameError", "name '"+name+"' is not defined");
        values.remove(name);
    }
    public static boolean classNamespaceHas(Object namespace,Object name){return ((Map<?,?>)namespace).containsKey(name);}

    public static void classNamespaceSyncMember(Object namespace,Object clsObj,Object nameObj) {
        @SuppressWarnings("unchecked") Map<Object,Object> map=(Map<Object,Object>)namespace;
        map.put(nameObj,getattr(clsObj,nameObj));
    }

    static void syncNamespaceToClass(PyClass cls,Map<?,?> namespace) {
        cls.attrs.clear();cls.methods.clear();cls.properties.clear();
        for(var e:namespace.entrySet()) {
            if(!(e.getKey() instanceof String name) || name.equals("__classcell__")) continue;
            classAddAttr(cls,name,e.getValue());
        }
    }

    static Object invokeMetaclassInstance(PyMethod method,PyClass cls,List<Object> args,Map<Object,Object> kwargs) {
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
            if(!cls.finalized){
                syncNamespaceToClass(cls,namespace);
                populateClassCell(cls,namespace);
            }
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
        ((PyClass)cls).methods.put((String)pyName, new PyMethod(owner, (String)javaName, (String)kind));
    }
    public static void classAddMethodEx(Object cls, Object pyName, Object owner, Object javaName, Object kind,
                                        Object posonlyObj, Object poskwObj, Object kwonlyObj,
                                        Object varargObj, Object kwargObj, Object defaultsObj, Object filenameObj, Object firstlineObj) {
        @SuppressWarnings("unchecked") Map<Object,Object> rawDefaults=(Map<Object,Object>)defaultsObj;
        LinkedHashMap<String,Object> defaults=new LinkedHashMap<>();
        for(var e:rawDefaults.entrySet()) defaults.put((String)e.getKey(),e.getValue());
        PyFunction function=new PyFunction(owner,(String)javaName,
            splitNames((String)posonlyObj),splitNames((String)poskwObj),splitNames((String)kwonlyObj),
            (String)varargObj,(String)kwargObj,defaults);
        function.displayName=(String)pyName; function.filename=(String)filenameObj; function.firstlineno=bigInt(firstlineObj).longValue();
        ((PyClass)cls).methods.put((String)pyName,new PyMethod(owner,(String)javaName,(String)kind,function));
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
            new PyMethod(owner,(String)javaName,(String)kind,function));
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
    static void configureSlots(PyClass cls) {
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

    static boolean isPythonIdentifier(String name) {
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

    static String mangleSlotName(String className,String name) {
        if(name.startsWith("__") && !name.endsWith("__")) {
            String stripped=className.replaceFirst("^_+","");
            if(stripped.isEmpty()) return name;
            return "_"+stripped+name;
        }
        return name;
    }

    static void classFinalizeWithKeywords(PyClass cls,Map<Object,Object> kwargs) {
        if(cls.finalized) return;
        cls.computeMro();
        cls.nativeKind=NativeSubclass.resolveKind(cls);
        configureSlots(cls);

        // PEP 487: descriptors receive their owner/name before __init_subclass__.
        for(var entry:new ArrayList<>(cls.attrs.entrySet())) {
            Object value=entry.getValue();
            if(value instanceof PyManagedProperty property)property.name=entry.getKey();
            if(value instanceof PyInstance descriptor) {
                PyMethod setName=descriptor.cls.lookupMethod("__set_name__");
                if(setName!=null) invoke(descriptor,setName,new Object[]{cls,entry.getKey()});
            }
        }

        // Class declaration keywords that were not consumed by the metaclass are
        // forwarded to the inherited __init_subclass__ hook.
        PyMethod hook=cls.bases.isEmpty()?null:cls.lookupMethodAfter(cls,"__init_subclass__");
        if(hook!=null) invokeOnClassKw(cls,hook,new ArrayList<>(),kwargs);
        else if(!kwargs.isEmpty() && cls.metaclass==null) throw new PyException("TypeError",cls.name+".__init_subclass__() takes no keyword arguments");
        cls.finalized=true;
    }

    public static void classFinalize(Object clsObj) {
        classFinalizeWithKeywords((PyClass)clsObj,new LinkedHashMap<>());
    }
    static boolean isType(Object value){return value instanceof PyClass || value instanceof PyBuiltinType;}
    static List<Object> typeMro(Object type) {
        ArrayList<Object> mro=new ArrayList<>();
        String builtin=null;
        if(type instanceof PyClass cls){mro.addAll(cls.mro);for(PyClass base:cls.mro)if(base.builtinBaseName!=null){builtin=base.builtinBaseName;break;}}
        else if(type instanceof PyBuiltinType nativeType)builtin=nativeType.name;
        else throw new PyException("TypeError","expected a type");
        if(builtin!=null){
            mro.add(builtinType(builtin));
            if(builtin.equals("ExceptionGroup"))mro.add(builtinType("BaseExceptionGroup"));
            if(builtin.equals("bool"))mro.add(builtinType("int"));
            for(String parent=EXCEPTION_PARENTS.get(builtin);parent!=null;parent=EXCEPTION_PARENTS.get(parent))mro.add(builtinType(parent));
        }
        if(!mro.contains(builtinType("object")))mro.add(builtinType("object"));
        return mro;
    }
    static Object superOwner(Object currentClass,Object self) {
        if(!isType(currentClass))throw new PyException("TypeError","super() argument 1 must be a type");
        if(isType(self) && typeMro(self).contains(currentClass))return self;
        Object actual=typeOf(self);
        if(typeMro(actual).contains(currentClass))return actual;
        if(self instanceof PyInstance){
            Object claimed=getattr(self,"__class__");
            if(claimed!=actual && isType(claimed) && typeMro(claimed).contains(currentClass))return claimed;
        }
        throw new PyException("TypeError","super(type, obj): obj must be an instance or subtype of type");
    }
    static List<Object> zeroSuperArguments() {
        ActiveFrame frame=currentLogicalFrame();
        if(frame==null || frame.firstArgument==null)throw new PyException("RuntimeError","super(): no arguments");
        Object self=frame.argumentEnv==null?frame.locals.getOrDefault(frame.firstArgument,UNBOUND):frame.argumentEnv.values.getOrDefault(frame.firstArgument,UNBOUND);
        if(self==UNBOUND)throw new PyException("RuntimeError","super(): arg[0] deleted");
        PyEnv env=frame.classEnv;
        while(env!=null && !env.values.containsKey("__class__"))env=env.parent;
        if(env==null)throw new PyException("RuntimeError","super(): __class__ cell not found");
        Object current=env.values.get("__class__");
        if(current==UNBOUND)throw new PyException("RuntimeError","super(): empty __class__ cell");
        if(!isType(current))throw new PyException("RuntimeError","super(): __class__ is not a type");
        return new ArrayList<>(Arrays.asList(current,self));
    }
    static void initializeSuper(PySuper sup,List<Object> args,Map<Object,Object> kwargs) {
        if(!kwargs.isEmpty())throw new PyException("TypeError","super() takes no keyword arguments");
        if(args.size()>2)throw new PyException("TypeError","super() expected at most 2 arguments");
        if(args.isEmpty())args=zeroSuperArguments();
        Object current=args.get(0),self=args.size()==1?null:args.get(1);
        if(!isType(current))throw new PyException("TypeError","super() argument 1 must be a type");
        Object owner=self==null?null:superOwner(current,self);
        sup.currentClass=current;sup.self=self;sup.owner=owner;
    }
    static Object superConstructor(List<Object> args,Map<Object,Object> kwargs) {
        PySuper sup=new PySuper(SUPER_CLASS);initializeSuper(sup,args,kwargs);return sup;
    }
    public static Object makeSuper(Object currentClass,Object self) {
        return superConstructor(new ArrayList<>(Arrays.asList(currentClass,self)),new LinkedHashMap<>());
    }
    static Object bindSuper(PySuper sup,Object instance) {
        if(instance==null || sup.self!=null)return sup;
        if(sup.cls==SUPER_CLASS)return makeSuper(sup.currentClass,instance);
        return instantiate(sup.cls,new Object[]{sup.currentClass,instance});
    }
    static boolean builtinHasMethod(String type,String name) {
        if(NativeSubclass.isKind(type) && NativeSubclassMethods.hasMember(type,name))return true;
        if(type.equals("set") || type.equals("frozenset"))return setMethodKnown(name,type.equals("frozenset"));
        if(name.equals("__round__") && Set.of("int","bool","float").contains(type))return true;
        if(name.equals("__dir__") && type.equals("object"))return true;
        if(exceptionIsSubclass(type,"BaseException") && name.equals("__init__"))return true;
        if(name.equals("__format__") && Set.of("object","str","int","bool","float").contains(type))return true;
        return switch(type){
            case "str" -> name.equals("__mod__");
            case "object" -> Set.of("__new__","__init__","__init_subclass__","__setattr__","__delattr__","__getattribute__").contains(name);
            case "type" -> Set.of("__new__","__init__","__call__","__setattr__","__delattr__").contains(name);
            case "super" -> Set.of("__new__","__init__","__get__").contains(name);
            default -> false;
        };
    }
    static Object superAttribute(PySuper sup,String name) {
        if(sup.owner==null)return MISSING;
        List<Object> mro=typeMro(sup.owner);int start=mro.indexOf(sup.currentClass);
        for(int i=start+1;i<mro.size();i++){
            Object base=mro.get(i);
            if(base instanceof PyClass cls && cls.attrs.containsKey(name))
                return descriptorGet(cls.attrs.get(name),sup.self==sup.owner?null:sup.self,sup.owner);
            if(base instanceof PyBuiltinType builtin && builtinHasMethod(builtin.name,name)) {
                Object callable=new BuiltinBoundMethod(base,name);
                return name.equals("__new__") || name.equals("__init_subclass__") || sup.self==sup.owner?callable:new BoundCallable(callable,sup.self);
            }
        }
        return MISSING;
    }

    public static Object instantiate0(Object cls) { return instantiate((PyClass)cls, new Object[0]); }
    public static Object instantiate1(Object cls, Object a) { return instantiate((PyClass)cls, new Object[]{a}); }
    public static Object instantiate2(Object cls, Object a, Object b) { return instantiate((PyClass)cls, new Object[]{a,b}); }
    public static Object instantiate3(Object cls, Object a, Object b, Object c) { return instantiate((PyClass)cls, new Object[]{a,b,c}); }

    static Object instantiate(PyClass cls, Object[] args) {
        return instantiate(cls,args,new LinkedHashMap<Object,Object>());
    }
    static Object instantiate(PyClass cls, Object[] args, Map<Object,Object> kwargs) {
        if(cls.metaclass!=null) {
            PyMethod call=cls.metaclass.lookupMethod("__call__");
            if(call!=null) return invokeMetaclassInstance(call,cls,new ArrayList<Object>(Arrays.asList(args)),kwargs);
        }
        return instantiateDefault(cls,args,kwargs);
    }

    static Object instantiateDefault(PyClass cls,Object[] args,Map<Object,Object> kwargs) {
        Object created;
        PyMethod newMethod=cls.lookupMethod("__new__");
        if(newMethod!=null) {
            ArrayList<Object> newArgs=new ArrayList<>();
            newArgs.add(cls);
            newArgs.addAll(Arrays.asList(args));
            created=invokeOnClassKw(cls,newMethod,newArgs,kwargs);
        } else {
            created=cls.nativeKind!=null?NativeSubclass.allocate(cls,cls.nativeKind,args,kwargs):typeMro(cls).contains(builtinType("super"))?new PySuper(cls):new PyInstance(cls);
            if(cls.exceptionBase!=null && created instanceof PyInstance exc) { PyTuple t=new PyTuple(); t.items.addAll(Arrays.asList(args)); exc.fields.put("args",t); if(BuiltinsExtra.isGroupType(cls.exceptionBase)) BuiltinsExtra.initGroupInstance(exc,args); }
        }

        // __init__ is called only when __new__ produced an instance of cls or a
        // subclass, matching Python's constructor protocol.
        if(created instanceof PyInstance instance && instance.cls.mro.contains(cls)) {
            PyMethod init=cls.lookupMethod("__init__");
            if(init!=null) {
                Object initResult=invokeKw(instance,init,new ArrayList<Object>(Arrays.asList(args)),kwargs);
                if(initResult!=null) throw new PyException("TypeError","__init__() should return None, not '"+typeName(initResult)+"'");
            } else if(instance.payload!=null) {
                NativeSubclass.defaultInit(instance,args,kwargs);
            } else if(instance instanceof PySuper sup) {
                initializeSuper(sup,new ArrayList<>(Arrays.asList(args)),kwargs);
            } else if((args.length!=0 || !kwargs.isEmpty()) && cls.exceptionBase==null) {
                throw new PyException("TypeError",cls.name+"() takes no arguments");
            }
        }
        return created;
    }

    static void requireArgs(String name,Object[] args,int count) { if(args.length!=count) throw new PyException("TypeError",name+"() takes "+count+" arguments"); }

    public static Object callMethodDynamic(Object obj,Object nameObj,Object argsObj,Object kwargsObj) {
        @SuppressWarnings("unchecked") List<Object> args=(List<Object>)argsObj;
        @SuppressWarnings("unchecked") Map<Object,Object> kwargs=(Map<Object,Object>)kwargsObj;
        String name=(String)nameObj;
        if(kwargs.isEmpty()) return callMethod(obj,name,args.toArray());
        if(obj instanceof String fmt && name.equals("format")) return FormatTemplate.format(fmt,args,kwargs);
        if(obj instanceof List<?> && name.equals("sort") && args.isEmpty()) { ListMethods.sortInPlace((List<Object>)obj,kwargs); return null; }
        if(obj instanceof Map<?,?> && name.equals("update")) {
            callMethod(obj,name,args.toArray());
            for(Map.Entry<Object,Object> e:kwargs.entrySet()) ((Map<Object,Object>)obj).put(e.getKey(),e.getValue());
            return null;
        }
        if(obj instanceof PyInstance || obj instanceof PyClass || obj instanceof PySuper) {
            Object callable=getattr(obj,name);
            return callFunction(callable,args,kwargs);
        }
        if(obj instanceof PyBuiltinType t && t.name.equals("int") && name.equals("from_bytes")) {
            List<String> params=IntMethods.STATIC_PARAMS.get(name);
            Object[] positional=new Object[Math.max(args.size(),params.size())]; int highest=args.size();
            for(int i=0;i<args.size();i++) positional[i]=args.get(i);
            for(Map.Entry<Object,Object> e:kwargs.entrySet()) {
                int at=params.indexOf((String)e.getKey());
                if(at<0) throw new PyException("TypeError","from_bytes() got an unexpected keyword argument '"+e.getKey()+"'");
                positional[at]=e.getValue(); highest=Math.max(highest,at+1);
            }
            return IntMethods.fromBytes(Arrays.copyOf(positional,highest));
        }
        throw new PyException("TypeError",name+"() does not accept keyword arguments");
    }

    public static Object callMethod0(Object obj, Object name) { return callMethod(obj, (String)name, new Object[0]); }
    public static Object callMethod1(Object obj, Object name, Object a) { return callMethod(obj, (String)name, new Object[]{a}); }
    public static Object callMethod2(Object obj, Object name, Object a, Object b) { return callMethod(obj, (String)name, new Object[]{a,b}); }
    public static Object callMethod3(Object obj, Object name, Object a, Object b, Object c) { return callMethod(obj, (String)name, new Object[]{a,b,c}); }
    public static Object callMethod4(Object obj, Object name, Object a, Object b, Object c, Object d) { return callMethod(obj, (String)name, new Object[]{a,b,c,d}); }

    static Object typeNew(PyClass meta,Object nameObj,Object basesObj,Object namespaceObj,Map<Object,Object> kwargs) {
        if(!(nameObj instanceof String name))throw new PyException("TypeError","type.__new__() name must be str");
        if(!(basesObj instanceof PyTuple bases))throw new PyException("TypeError","type.__new__() bases must be tuple");
        if(!(namespaceObj instanceof Map<?,?> namespace))throw new PyException("TypeError","type.__new__() namespace must be mapping");
        Object module=namespace.get("__module__");
        PyClass cls=new PyClass(module instanceof String m?m:"__main__",name);
        for(Object base:bases.items)classAddBase(cls,base);
        cls.metaclass=meta;
        if(namespace.get("__qualname__") instanceof String qualname)cls.qualname=qualname;
        syncNamespaceToClass(cls,namespace);
        populateClassCell(cls,namespace);
        classFinalizeWithKeywords(cls,kwargs);
        return cls;
    }

    static Object callBuiltinTypeMethod(PyBuiltinType builtin,String name,Object[] args) {
        if(NativeSubclass.isKind(builtin.name)){Object nativeResult=NativeSubclassMethods.callUnbound(builtin,name,args);if(nativeResult!=MISSING)return nativeResult;}
        if((builtin.name.equals("set") || builtin.name.equals("frozenset")) && setMethodKnown(name,builtin.name.equals("frozenset"))){
            if(args.length==0 || !(args[0] instanceof Set<?> set) || builtin.name.equals("frozenset")!=(args[0] instanceof PyFrozenSet))throw new PyException("TypeError","set descriptor requires a "+builtin.name+" object");
            return setMethod(set,name,Arrays.copyOfRange(args,1,args.length));
        }
        if(name.equals("__round__")){
            if(args.length<1 || args.length>2)throw new PyException("TypeError","__round__ takes one or two arguments");
            if(builtin.name.equals("float")?!(args[0] instanceof Double):!isIntLike(args[0]))throw new PyException("TypeError","__round__ descriptor requires a "+builtin.name+" object");
            return roundNative(args[0],args.length==1?null:args[1]);
        }
        if(name.equals("__getattribute__") && builtin.name.equals("object")){requireArgs(name,args,2);return getattr(args[0],args[1]);}
        if(name.equals("__dir__")){requireArgs(name,args,1);return defaultDir(args[0]);}
        if(name.equals("__format__")){
            requireArgs(name,args,2);
            if(builtin.name.equals("object"))return objectFormat(args[0],args[1]);
            if(!truth(isInstance(args[0],builtin)))throw new PyException("TypeError","format descriptor requires a "+builtin.name+" object");
            if(!(args[1] instanceof String sp))throw new PyException("TypeError","__format__() argument must be str, not "+typeName(args[1]));
            String text=FormatSpec.formatNative(args[0],sp);
            return text!=null?text:objectFormat(args[0],args[1]);
        }
        if(builtin.name.equals("str") && name.equals("__mod__")){
            requireArgs(name,args,2);
            if(!(args[0] instanceof String str))throw new PyException("TypeError","str.__mod__ requires a str object");
            return PercentFormat.formatString(str,args[1]);
        }
        if(builtin.name.equals("super") && name.equals("__get__")){
            if(args.length<2 || args.length>3 || !(args[0] instanceof PySuper sup))throw new PyException("TypeError","super.__get__ requires a super object and instance");
            return bindSuper(sup,args[1]);
        }
        if(builtin.name.equals("super") && name.equals("__init__")){
            if(args.length<1 || !(args[0] instanceof PySuper sup))throw new PyException("TypeError","super.__init__ requires a super object");
            initializeSuper(sup,new ArrayList<>(Arrays.asList(args).subList(1,args.length)),new LinkedHashMap<>());return null;
        }
        if(builtin.name.equals("super") && name.equals("__new__")){
            if(args.length<1)throw new PyException("TypeError","super.__new__ requires a type");
            if(args[0] instanceof PyBuiltinType type && type.name.equals("super"))return new PySuper(SUPER_CLASS);
            if(args[0] instanceof PyClass cls && typeMro(cls).contains(builtinType("super")))return new PySuper(cls);
            throw new PyException("TypeError","super.__new__ requires a super subtype");
        }
        if(builtin.name.equals("object") && name.equals("__init__")){requireArgs(name,args,1);return null;}
        if((builtin.name.equals("object") || builtin.name.equals("type")) && name.equals("__setattr__")){requireArgs(name,args,3);setattr(args[0],args[1],args[2]);return null;}
        if((builtin.name.equals("object") || builtin.name.equals("type")) && name.equals("__delattr__")){requireArgs(name,args,2);delattr(args[0],args[1]);return null;}
        if(builtin.name.equals("object") && name.equals("__init_subclass__")){requireArgs(name,args,0);return null;}
        if(builtin.name.equals("object") && name.equals("__new__")) {
            requireArgs(name,args,1);
            if(!(args[0] instanceof PyClass cls)) throw new PyException("TypeError","object.__new__() argument must be a type");
            return new PyInstance(cls);
        }
        if(builtin.name.equals("type") && name.equals("__new__")) {
            requireArgs(name,args,4);
            PyClass meta=null;
            if(args[0] instanceof PyClass cls && typeMro(cls).contains(builtinType("type")))meta=cls;
            else if(!(args[0] instanceof PyBuiltinType type && type.name.equals("type")))
                throw new PyException("TypeError","type.__new__() argument 1 must be a metaclass");
            return typeNew(meta,args[1],args[2],args[3],new LinkedHashMap<>());
        }
        if(builtin.name.equals("type") && name.equals("__init__")) {
            requireArgs(name,args,4); return null;
        }
        if(builtin.name.equals("type") && name.equals("__call__")) {
            if(args.length<1 || !(args[0] instanceof PyClass cls)) throw new PyException("TypeError","type.__call__() requires a class");
            return instantiateDefault(cls,Arrays.copyOfRange(args,1,args.length),new LinkedHashMap<Object,Object>());
        }
        if(builtin.name.equals("int") && name.equals("from_bytes")) return IntMethods.fromBytes(args);
        if(exceptionIsSubclass(builtin.name,"BaseException") && name.equals("__init__")) {   // BaseException.__init__(self, *args)
            if(args.length<1 || !(args[0] instanceof PyInstance self)) throw new PyException("TypeError","descriptor '__init__' requires an exception instance");
            PyTuple t=new PyTuple(); t.items.addAll(Arrays.asList(args).subList(1,args.length)); self.fields.put("args",t);
            return null;
        }
        if((builtin.name.equals("dict")||builtin.name.equals("frozendict")) && name.equals("fromkeys")) {
            if(args.length<1||args.length>2) throw new PyException("TypeError","fromkeys expected 1 or 2 arguments");
            PyDict out=new PyDict();
            for(Object key:iterable(args[0])) out.put(key,args.length==2?args[1]:null);
            return builtin.name.equals("frozendict")?out.freeze():out;
        }
        if((builtin.name.equals("bytes")||builtin.name.equals("bytearray")) && name.equals("maketrans")) {
            requireArgs(name,args,2); return bytesMaketrans(args[0],args[1]);
        }
        throw new PyException("AttributeError","type object '"+builtin.name+"' has no method '"+name+"'");
    }

    static Object callMethod(Object obj, String name, Object[] args) {
        if(obj instanceof PySetIterator iterator && Set.of("__iter__","__next__","__length_hint__").contains(name)){
            requireArgs(name,args,0);
            return name.equals("__iter__")?iterator:name.equals("__next__")?next_(iterator):iterator.lengthHint();
        }
        if(name.equals("__round__") && (obj instanceof PyInstance || isIntLike(obj) || obj instanceof Double)){
            if(obj instanceof PyInstance)return callFunction(getattr(obj,name),new ArrayList<>(Arrays.asList(args)),new LinkedHashMap<>());
            if(args.length>1)throw new PyException("TypeError","__round__ takes at most one argument");
            return roundValue(obj,args.length==0?null:args[0]);
        }
        if(name.equals("__dir__") && !(obj instanceof PyBuiltinType) && !(obj instanceof PyClass)){
            if(obj instanceof PyInstance)return callFunction(getattr(obj,name),new ArrayList<>(Arrays.asList(args)),new LinkedHashMap<>());
            requireArgs(name,args,0);return defaultDir(obj);
        }
        if(name.equals("__format__") && !(obj instanceof PyBuiltinType) && !(obj instanceof PyClass)){
            if(obj instanceof PyInstance)return callFunction(getattr(obj,name),new ArrayList<>(Arrays.asList(args)),new LinkedHashMap<>());
            requireArgs(name,args,1);return formatValue(obj,args[0]);
        }
        if(obj instanceof PySuper sup && name.equals("__get__")){
            if(args.length<1 || args.length>2)throw new PyException("TypeError","__get__ requires instance and optional owner");
            return bindSuper(sup,args[0]);
        }
        if(name.equals("__get__") && (obj instanceof PyFunction || obj instanceof PyMethodDescriptor || obj instanceof PyManagedProperty)){
            if(args.length<1 || args.length>2)throw new PyException("TypeError","__get__() requires instance and optional owner");
            Object owner=args.length==2?args[1]:args[0]==null?null:typeOf(args[0]);
            if(args[0]==null && owner==null)throw new PyException("TypeError","__get__(None, None) is invalid");
            return descriptorGet(obj,args[0],owner);
        }
        if(obj instanceof PyManagedProperty property){
            if(Set.of("getter","setter","deleter").contains(name)){
                requireArgs(name,args,1);
                PyManagedProperty copy=new PyManagedProperty(name.equals("getter")?args[0]:property.getter,
                    name.equals("setter")?args[0]:property.setter,name.equals("deleter")?args[0]:property.deleter,property.getterDoc?null:property.doc);
                copy.name=property.name;copy.getterDoc=property.getterDoc;return copy;
            }
            if(name.equals("__set_name__")){requireArgs(name,args,2);property.name=(String)args[1];return null;}
            if(name.equals("__set__")){requireArgs(name,args,2);if(property.setter==null)throw new PyException("AttributeError","property has no setter");return callFunction(property.setter,new ArrayList<>(Arrays.asList(args)),new LinkedHashMap<>());}
            if(name.equals("__delete__")){requireArgs(name,args,1);if(property.deleter==null)throw new PyException("AttributeError","property has no deleter");return callFunction(property.deleter,new ArrayList<>(Arrays.asList(args)),new LinkedHashMap<>());}
        }
        if(obj instanceof PyMethodDescriptor || obj instanceof PyManagedProperty || obj instanceof PyFunction || obj instanceof BoundCallable)
            return callFunction(getattr(obj,name),new ArrayList<>(Arrays.asList(args)),new LinkedHashMap<>());
        TypeSlots.CallMethod slot = TypeSlots.callMethodSlot(obj);
        if (slot == null) throw typeError("method call on non-instance", obj);
        return slot.call(obj, name, args);
    }

    static Object invokeReflective(PyMethod method,Object[] actual) {
        try {
            Class<?> owner = ownerClass(method.owner);
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

    static Object invoke(PyInstance self, PyMethod method, Object[] args) {
        return invokeKw(self,method,new ArrayList<Object>(Arrays.asList(args)),new LinkedHashMap<Object,Object>());
    }
    static Object invokeKw(PyInstance self, PyMethod method, List<Object> args, Map<Object,Object> kwargs) {
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

    static Object invokeOnClass(PyClass cls,PyMethod method,Object[] args) {
        return invokeOnClassKw(cls,method,new ArrayList<Object>(Arrays.asList(args)),new LinkedHashMap<Object,Object>());
    }
    static Object invokeOnClassKw(PyClass cls,PyMethod method,List<Object> args,Map<Object,Object> kwargs) {
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

    static boolean isDescriptor(Object value) {
        return value instanceof PySuper || value instanceof PyFunction || value instanceof PyMethodDescriptor || value instanceof PyManagedProperty || value instanceof PySlotDescriptor ||
            (value instanceof PyInstance descriptor && descriptor.cls.lookupMethod("__get__") != null);
    }
    static boolean isDataDescriptor(Object value) {
        return value instanceof PyManagedProperty || value instanceof PySlotDescriptor ||
            (value instanceof PyInstance descriptor &&
            (descriptor.cls.lookupMethod("__set__") != null || descriptor.cls.lookupMethod("__delete__") != null));
    }
    static Object descriptorGet(Object descriptor, Object instance, Object owner) {
        if(descriptor instanceof PySuper sup)return bindSuper(sup,instance);
        if(descriptor instanceof PyFunction)return instance==null?descriptor:new BoundCallable(descriptor,instance);
        if(descriptor instanceof PyMethodDescriptor method){
            if(method.kind.equals("static"))return method.function;
            if(owner==null){if(instance==null)throw new PyException("TypeError","__get__(None, None) is invalid");owner=typeOf(instance);}
            return new BoundCallable(method.function,owner);
        }
        if(descriptor instanceof PyManagedProperty property){
            if(instance==null)return property;
            if(property.getter==null)throw new PyException("AttributeError","property has no getter");
            return callFunction(property.getter,new ArrayList<>(Arrays.asList(instance)),new LinkedHashMap<>());
        }
        if(descriptor instanceof PySlotDescriptor slot) {
            if(instance==null) return slot;
            if(!(instance instanceof PyInstance pyInstance))
                throw new PyException("TypeError","slot descriptor requires an instance");
            if(!pyInstance.slotValues.containsKey(slot.name))
                throw new PyException("AttributeError","'"+pyInstance.cls.name+"' object has no attribute '"+slot.name+"'");
            return pyInstance.slotValues.get(slot.name);
        }
        if (descriptor instanceof BuiltinBoundMethod nativeMethod && instance != null && nativeMethod.self instanceof PyBuiltinType nativeType
            && NativeSubclass.isKind(nativeType.name) && NativeSubclassMethods.isInstanceMethod(nativeType.name, nativeMethod.name)) return new BoundCallable(descriptor, instance);
        if (!isDescriptor(descriptor)) return descriptor;
        return callMethod(descriptor, "__get__", new Object[]{instance, owner});
    }

    public static Object getattr(Object obj, Object nameObj) {
        if(obj instanceof PyBuiltinType bt && bt.name.equals("int") && "from_bytes".equals(nameObj))
            return new NativeFunction("from_bytes",(a,k)->callMethodDynamic(obj,"from_bytes",a,k));
        if(!(nameObj instanceof String))throw new PyException("TypeError","attribute name must be string");
        String name = (String)nameObj;
        if(obj instanceof TypeParamsObject tp)return tp.getattr(name);
        if(obj==null && name.equals("__doc__"))return "The type of the None singleton.";
        if(obj instanceof PyCell cell && name.equals("cell_contents")){
            Object value=cell.value();
            if(value==UNBOUND)throw new PyException("ValueError","Cell is empty");
            return value;
        }
        if(obj instanceof PyFunction function){
            if(name.equals("__closure__"))return function.freeNames.isEmpty()?null:functionClosure(function);
            if(name.equals("__dict__"))return function.attrs;
            if(AnnotationSupport.isAnnotationName(name))return AnnotationSupport.functionGet(function,name);
            if(name.equals("__get__"))return new BuiltinBoundMethod(obj,name);
            if(function.metadata.containsKey(name))return function.metadata.get(name);
            if(function.attrs.containsKey(name))return function.attrs.get(name);
            if(name.equals("__type_params__"))return TypeParamsSupport.emptyTypeParams();
            if(!name.equals("__class__"))throw new PyException("AttributeError","function has no attribute '"+name+"'");
        }
        if(obj instanceof PyMethodDescriptor method){
            if(name.equals("__dict__"))return method.attrs;
            if(name.equals("__func__") || name.equals("__wrapped__"))return method.function;
            if(name.equals("__get__"))return new BuiltinBoundMethod(obj,name);
            if(method.attrs.containsKey(name))return method.attrs.get(name);
            if(name.equals("__annotations__") || name.equals("__annotate__")){
                Object value=getattr(method.function,name);method.attrs.put(name,value);return value;
            }
            if(!name.equals("__class__"))throw new PyException("AttributeError","method descriptor has no attribute '"+name+"'");
        }
        if(obj instanceof BoundCallable bound){
            if(name.equals("__self__"))return bound.self;
            if(name.equals("__func__"))return bound.function;
            if(!name.equals("__class__"))return getattr(bound.function,name);
        }
        if(obj instanceof PyManagedProperty property){
            switch(name){
                case "fget": return property.getter;
                case "fset": return property.setter;
                case "fdel": return property.deleter;
                case "__doc__": return property.doc;
                case "__name__": if(property.name!=null)return property.name;if(property.getter!=null)return getattr(property.getter,"__name__");break;
                case "getter", "setter", "deleter", "__get__", "__set__", "__delete__", "__set_name__": return new BuiltinBoundMethod(obj,name);
            }
            if(!name.equals("__class__"))throw new PyException("AttributeError","property has no attribute '"+name+"'");
        }
        if(obj instanceof Set<?> && setMethodKnown(name,obj instanceof PyFrozenSet))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof Map<?,?> && (obj instanceof PyDict fd && fd.isFrozen() ? FROZENDICT_METHODS : DICT_METHODS).contains(name))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof String && StringMethods.NAMES.contains(name))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof List<?> && ListMethods.NAMES.contains(name))return new BuiltinBoundMethod(obj,name);
        if((obj instanceof Long || obj instanceof BigInteger || obj instanceof Boolean) && IntMethods.NAMES.contains(name))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof PySetIterator && Set.of("__iter__","__next__","__length_hint__").contains(name))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof PyBuiltinType type && name.equals("__name__"))return type.name;
        if(name.equals("__round__") && (isIntLike(obj) || obj instanceof Double))return new BuiltinBoundMethod(obj,name);
        if(name.equals("__dir__") && !(obj instanceof PyInstance) && !(obj instanceof PyClass) && !(obj instanceof PyBuiltinType))return new BuiltinBoundMethod(obj,name);
        if(name.equals("__format__") && !(obj instanceof PyInstance) && !(obj instanceof PyClass) && !(obj instanceof PyBuiltinType))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof String && name.equals("__mod__"))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof PyBuiltinType type && builtinHasMethod(type.name,name))return new BuiltinBoundMethod(obj,name);
        if(obj instanceof PyComplex z){
            if(name.equals("real"))return z.real;
            if(name.equals("imag"))return z.imag;
            if(Set.of("conjugate","__complex__","__pos__","__neg__","__abs__","__getnewargs__").contains(name))return new BuiltinBoundMethod(obj,name);
            if(!name.equals("__class__"))throw new PyException("AttributeError","complex has no attribute '"+name+"'");
        }
        if(obj instanceof PySlice sl) return switch(name){case "start"->sl.start;case "stop"->sl.stop;case "step"->sl.step;case "__class__"->builtinType("slice");default->throw new PyException("AttributeError","slice has no attribute '"+name+"'");};
        if(name.equals("__class__") && !(obj instanceof PyInstance) && !(obj instanceof PyClass)) return typeOf(obj);
        if (obj instanceof PyModule) return moduleGetattr(obj,nameObj);
        if (obj instanceof PyRange range && Set.of("start","stop","step").contains(name)) return compact(name.equals("start")?range.start:name.equals("stop")?range.stop:range.step);
        if (obj instanceof PyExceptionValue exc) {
            if (exc.syntax!=null) {
                if (name.equals("args")) {
                    PyTuple where=new PyTuple(); SyntaxInfo si=exc.syntax;
                    where.items.addAll(java.util.Arrays.asList(si.filename, si.lineno, si.offset, si.text, si.endLineno, si.endOffset));
                    PyTuple t=new PyTuple(); t.items.add(exc.value); t.items.add(where); return t;
                }
                Object found=exc.syntax.attribute(name, exc.value);
                if (found!=SyntaxInfo.NONE) return found;
            }
            if (name.equals("args")) return BuiltinsExtra.exceptionArgs(exc.value);
            Object groupAttr=BuiltinsExtra.groupAttribute(exc,name); if(groupAttr!=MISSING) return groupAttr;
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
        if(obj instanceof PyFrame frame) return PyFrameSupport.frameAttr(frame,name);
        if(obj instanceof PyCode code) return PyFrameSupport.codeAttr(code,name);
        if(obj instanceof PyGenerator gen && (name.equals("gi_frame") || name.equals("gi_running") || name.equals("gi_code"))) return PyFrameSupport.generatorAttr(gen,name);
        if(obj instanceof PyCoroutine co && (name.equals("cr_frame") || name.equals("cr_running") || name.equals("cr_code"))) return PyFrameSupport.coroutineAttr(co,name);
        if(obj instanceof PyAsyncGenerator ag && (name.equals("ag_frame") || name.equals("ag_running") || name.equals("ag_code"))) return PyFrameSupport.generatorAttr(ag.frame,name.replace("ag_","gi_"));
        if (obj instanceof PySuper sup) {
            switch(name){
                case "__class__": return typeOf(sup);
                case "__thisclass__": return sup.currentClass;
                case "__self__": return sup.self;
                case "__self_class__": return sup.owner;
            }
            Object value=superAttribute(sup,name);if(value!=MISSING)return value;
            if(name.equals("__get__"))return new BuiltinBoundMethod(sup,name);
            if(name.equals("__init__"))return new BoundCallable(new BuiltinBoundMethod(builtinType("super"),name),sup);
            if(sup.fields.containsKey(name))return sup.fields.get(name);
            Object own=sup.cls.lookupAttr(name);if(own!=MISSING)return descriptorGet(own,sup,sup.cls);
            throw new PyException("AttributeError", "'super' object has no attribute '"+name+"'");
        }
        if (obj instanceof PyClass cls) {
            if(cls.metaclass==null && AnnotationSupport.deferred() && AnnotationSupport.isAnnotationName(name))return AnnotationSupport.classGet(cls,name);
            Object metaAttr=cls.metaclass==null?MISSING:cls.metaclass.lookupAttr(name);
            if(metaAttr!=MISSING && isDataDescriptor(metaAttr))return descriptorGet(metaAttr,cls,cls.metaclass);
            if(name.equals("__class__")) return cls.metaclass != null ? cls.metaclass : builtinType("type");
            if(name.equals("__name__")) return cls.name;
            if(name.equals("__qualname__")) return cls.qualname;
            if(name.equals("__module__")) return cls.moduleName;
            if(cls.nativeKind!=null && name.equals("__bases__")) return explicitBasesTuple(cls);
            if(cls.nativeKind!=null && name.equals("__mro__")) {PyTuple t=new PyTuple(); t.items.addAll(typeMro(cls)); return t;}
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
                for(var entry:cls.methods.entrySet())out.putIfAbsent(entry.getKey(),entry.getValue().function);
                out.put("__module__",cls.moduleName);
                out.put("__name__",cls.name);
                return out;
            }
            if(name.equals("__type_params__"))return cls.attrs.containsKey(name)?cls.attrs.get(name):TypeParamsSupport.emptyTypeParams();
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
            if(cls.nativeKind!=null || name.equals("__base__")){Object nativeValue=NativeSubclassMethods.classAttribute(cls,name);if(nativeValue!=MISSING)return nativeValue;}
            if(metaAttr!=MISSING)return descriptorGet(metaAttr,cls,cls.metaclass);
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
            if(classAttr!=MISSING) return descriptorGet(classAttr, instance, instance.cls);
            PyMethod method=instance.cls.lookupMethod(name);
            if (method != null) return new BoundMethod(instance, name);
            if(instance.payload!=null){Object nativeValue=NativeSubclassMethods.instanceAttribute(instance,name);if(nativeValue!=MISSING)return nativeValue;}
            PyMethod getattrMethod=instance.cls.lookupMethod("__getattr__");
            if(getattrMethod!=null) return invoke(instance,getattrMethod,new Object[]{name});
            if(name.equals("__dir__"))return new BoundCallable(new BuiltinBoundMethod(builtinType("object"),name),obj);
            if(name.equals("__format__"))return new BoundCallable(new BuiltinBoundMethod(builtinType("object"),name),obj);
            throw new PyException("AttributeError", "'" + instance.cls.name + "' object has no attribute '" + name + "'");
        }
        {Object nativeValue=NativeSubclassMethods.plainAttribute(obj,name);if(nativeValue!=MISSING)return nativeValue;}
        throw new PyException("AttributeError","'"+typeName(obj)+"' object has no attribute '"+name+"'");
    }

    public static Object getattrDefault(Object obj,Object nameObj,Object defaultValue) {
        try { return getattr(obj,nameObj); } catch(PyException e) { if(e.typeName.equals("AttributeError")) return defaultValue; throw e; }
    }
    public static Object hasattr(Object obj,Object nameObj) {
        try { getattr(obj,nameObj); return true; } catch(PyException e) { if(e.typeName.equals("AttributeError")) return false; throw e; }
    }

    public static void setattr(Object obj, Object nameObj, Object value) {
        if(obj instanceof PySuper && Set.of("__thisclass__","__self__","__self_class__").contains(nameObj))
            throw new PyException("AttributeError","readonly attribute");
        String name=(String)nameObj;
        if(obj instanceof PyFrame frame){PyFrameSupport.setFrameAttr(frame,name,value);return;}
        if(obj instanceof TypeParamsObject tp){tp.setattr(name,value);return;}
        if(obj instanceof PyCell cell && name.equals("cell_contents")){cell.set(value);return;}
        if(obj instanceof PyFunction function){
            if(name.equals("__closure__"))throw new PyException("AttributeError","readonly attribute");
            if(AnnotationSupport.isAnnotationName(name))AnnotationSupport.functionSet(function,name,value);
            else if(Set.of("__module__","__qualname__","__name__","__doc__").contains(name))function.metadata.put(name,value);
            else function.attrs.put(name,value);return;
        }
        if(obj instanceof PyMethodDescriptor method){
            if(Set.of("__func__","__wrapped__").contains(name))throw new PyException("AttributeError","readonly attribute");
            method.attrs.put(name,value);return;
        }
        if(obj instanceof PyManagedProperty property && (name.equals("__doc__") || name.equals("__name__"))){
            if(name.equals("__doc__"))property.doc=value;else property.name=(String)value;return;
        }
        if(obj instanceof PyManagedProperty)throw new PyException("AttributeError","readonly attribute");
        if(obj instanceof PyComplex)throw new PyException("AttributeError","complex attributes are read-only");
        if (obj instanceof PyInstance instance) {
            PyProperty prop=instance.cls.lookupProperty(name);
            if(prop!=null) {
                if(prop.setter==null) throw new PyException("AttributeError", "property '"+name+"' of '"+instance.cls.name+"' object has no setter");
                invoke(instance,prop.setterMethod(),new Object[]{value}); return;
            }
            Object descriptor=instance.cls.lookupAttr(name);
            if(descriptor instanceof PyManagedProperty property){
                if(property.setter==null)throw new PyException("AttributeError","property has no setter");
                callFunction(property.setter,new ArrayList<>(Arrays.asList(instance,value)),new LinkedHashMap<>());return;
            }
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
        if(obj instanceof PyClass cls){
            if(cls.metaclass==null && AnnotationSupport.deferred() && AnnotationSupport.isAnnotationName(name))AnnotationSupport.classSet(cls,name,value);
            else classAddAttr(cls,name,value);
            return;
        }
        if(obj instanceof PyModule module){moduleSetattr(module,name,value);return;}
        throw typeError("attribute assignment on unsupported object", obj);
    }
    public static void delattr(Object obj,Object nameObj) {
        if(obj instanceof TypeParamsObject tp){tp.delattr((String)nameObj);return;}
        if(obj instanceof PySuper && Set.of("__thisclass__","__self__","__self_class__").contains(nameObj))
            throw new PyException("AttributeError","readonly attribute");
        if(obj instanceof PyCell cell && nameObj.equals("cell_contents")){cell.set(UNBOUND);return;}
        if(obj instanceof PyFunction function){
            if(nameObj.equals("__closure__"))throw new PyException("AttributeError","readonly attribute");
            if(AnnotationSupport.isAnnotationName((String)nameObj)){AnnotationSupport.functionDelete(function,(String)nameObj);return;}
            if(function.metadata.containsKey(nameObj)){function.metadata.remove(nameObj);return;}
            if(!function.attrs.containsKey(nameObj))throw new PyException("AttributeError","attribute not found");function.attrs.remove(nameObj);return;
        }
        if(obj instanceof PyMethodDescriptor method){if(!method.attrs.containsKey(nameObj))throw new PyException("AttributeError","attribute not found");method.attrs.remove(nameObj);return;}
        if(obj instanceof PyComplex)throw new PyException("AttributeError","complex attributes are read-only");
        if(obj instanceof PyModule module){
            if(!module.attrs.containsKey(nameObj)) throw new PyException("AttributeError","module '"+module.name+"' has no attribute '"+nameObj+"'");
            module.attrs.remove(nameObj);return;
        }
        if(obj instanceof PyInstance instance) {
            String name=(String)nameObj;
            if(instance.cls.lookupProperty(name)!=null) throw new PyException("AttributeError","property '"+name+"' has no deleter");
            Object descriptor=instance.cls.lookupAttr(name);
            if(descriptor instanceof PyManagedProperty property){
                if(property.deleter==null)throw new PyException("AttributeError","property has no deleter");
                callFunction(property.deleter,new ArrayList<>(Arrays.asList(instance)),new LinkedHashMap<>());return;
            }
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
        if(obj instanceof PyClass cls){String name=(String)nameObj;
            if(cls.metaclass==null && AnnotationSupport.deferred() && AnnotationSupport.isAnnotationName(name)){AnnotationSupport.classDelete(cls,name);return;}
            if(!cls.attrs.containsKey(name))throw new PyException("AttributeError","attribute not found");cls.attrs.remove(name);cls.methods.remove(name);cls.properties.remove(name);return;}
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
            if(obj instanceof PyInstance exc && exc.cls.exceptionBase!=null) return bt.name.equals("object") || exceptionIsSubclass(exc.cls.name,bt.name);
            if(obj instanceof PyInstance nativeInst && nativeInst.payload!=null) return bt.name.equals("object") || bt.name.equals(nativeInst.cls.nativeKind);
            return switch(bt.name) {
                case "object" -> true;
                case "super" -> obj instanceof PySuper;
                case "int" -> isIntLike(obj);
                case "bool" -> obj instanceof Boolean;
                case "float" -> obj instanceof Double;
                case "complex" -> obj instanceof PyComplex;
                case "classmethod" -> obj instanceof PyMethodDescriptor descriptor && descriptor.kind.equals("class");
                case "staticmethod" -> obj instanceof PyMethodDescriptor descriptor && descriptor.kind.equals("static");
                case "property" -> obj instanceof PyManagedProperty;
                case "str" -> obj instanceof String;
                case "bytes" -> obj instanceof PyBytes;
                case "bytearray" -> obj instanceof PyByteArray;
                case "memoryview" -> obj instanceof PyMemoryView;
                case "list" -> obj instanceof List<?>;
                case "tuple" -> obj instanceof PyTuple;
                case "dict" -> obj instanceof Map<?,?> && !(obj instanceof PyDict fd && fd.isFrozen());
                case "frozendict" -> obj instanceof PyDict fd && fd.isFrozen();
                case "set" -> obj instanceof Set<?> && !(obj instanceof PyFrozenSet);
                case "frozenset" -> obj instanceof PyFrozenSet;
                case "range" -> obj instanceof PyRange;
                case "map" -> obj instanceof PyMap;
                case "zip" -> obj instanceof PyZip;
                case "enumerate" -> obj instanceof PyEnumerate;
                case "filter" -> obj instanceof PyFilter;
                case "slice" -> obj instanceof PySlice;
                case "sentinel" -> obj instanceof TypeParamsSentinel;
                case "type" -> obj instanceof PyClass || obj instanceof PyBuiltinType;
                case "BaseException", "Exception" -> obj instanceof PyExceptionValue || obj instanceof PyException;
                default -> false;
            };
        }
        throw new PyException("TypeError","isinstance() arg 2 must be a type or tuple of types");
    }

    public static Object isSubclass(Object subObj,Object clsObj) {
        if(clsObj instanceof PyTuple tuple){for(Object value:tuple.items)if(truth(isSubclass(subObj,value)))return true;return false;}
        if(!isType(subObj) || !isType(clsObj))throw new PyException("TypeError","issubclass() arguments must be classes");
        return typeMro(subObj).contains(clsObj);
    }

    static final Object MISSING = new Object();
    static PyClass superClass(){PyClass cls=new PyClass("builtins","super");cls.instanceDictAllowed=false;return cls;}
    static final PyClass SUPER_CLASS=superClass();



}
