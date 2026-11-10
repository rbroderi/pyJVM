package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
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

/** Method-call slot for Map<?,?> receivers (split out of PyRuntime.callMethod). */
final class MapMethods {
    static Object call(Object obj, String name, Object[] args) {
        Map<?,?> raw=(Map<?,?>)obj;
        @SuppressWarnings("unchecked") Map<Object,Object> map=(Map<Object,Object>)raw;
        return switch(name) {
            case "copy" -> { requireArgs(name,args,0); yield new LinkedHashMap<Object,Object>(map); }
            case "get" -> { if(args.length<1||args.length>2) throw new PyException("TypeError","get expected 1 or 2 arguments"); yield map.getOrDefault(args[0],args.length==2?args[1]:null); }
            case "keys" -> { requireArgs(name,args,0); yield new PyDictView(map, "keys"); }
            case "values" -> { requireArgs(name,args,0); yield new PyDictView(map, "values"); }
            case "items" -> { requireArgs(name,args,0); yield new PyDictView(map, "items"); }
            case "update" -> { requireArgs(name,args,1); dictUpdate(map,args[0]); yield null; }
            case "pop" -> { if(args.length<1||args.length>2) throw new PyException("TypeError","pop expected 1 or 2 arguments"); if(map.containsKey(args[0])) yield map.remove(args[0]); if(args.length==2) yield args[1]; throw new PyException("KeyError",args[0]); }
            default -> throw new PyException("AttributeError","'dict' object has no attribute '"+name+"'");
        };
    }
}
