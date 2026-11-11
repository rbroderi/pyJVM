// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
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
    static final java.util.Set<String> FROZEN_ALLOWED=PyRuntime.FROZENDICT_METHODS;
    static Object call(Object obj, String name, Object[] args) {
        if (!SyncSupport.MT) return call0(obj, name, args);
        synchronized (obj) { return call0(obj, name, args); }
    }

    private static Object call0(Object obj, String name, Object[] args) {
        Map<?,?> raw=(Map<?,?>)obj;
        @SuppressWarnings("unchecked") Map<Object,Object> map=(Map<Object,Object>)raw;
        if(map instanceof PyDict fd && fd.isFrozen()) {
            if(!FROZEN_ALLOWED.contains(name)) throw new PyException("AttributeError","'frozendict' object has no attribute '"+name+"'");
            if(name.equals("copy")) { requireArgs(name,args,0); return map; }
        }
        return switch(name) {
            case "copy" -> { requireArgs(name,args,0); yield new PyDict(map); }
            case "get" -> { if(args.length<1||args.length>2) throw new PyException("TypeError","get expected 1 or 2 arguments"); yield map.getOrDefault(args[0],args.length==2?args[1]:null); }
            case "keys" -> { requireArgs(name,args,0); yield new PyDictView(map, "keys"); }
            case "values" -> { requireArgs(name,args,0); yield new PyDictView(map, "values"); }
            case "items" -> { requireArgs(name,args,0); yield new PyDictView(map, "items"); }
            case "update" -> { if(args.length>1) throw new PyException("TypeError","update expected at most 1 argument, got "+args.length); if(args.length==1) dictUpdate(map,args[0]); yield null; }
            case "pop" -> {
                if(args.length<1||args.length>2) throw new PyException("TypeError","pop expected 1 or 2 arguments");
                if(map.containsKey(args[0])) yield map.remove(args[0]);
                if(args.length==2) yield args[1];
                throw new PyException("KeyError",args[0]);
            }
            case "popitem" -> {
                requireArgs(name,args,0);
                if(!(map instanceof PyDict d)) throw new PyException("TypeError","popitem requires a dict");
                var last=d.popLast();
                if(last==null) throw new PyException("KeyError","popitem(): dictionary is empty");
                PyTuple pair=new PyTuple(); pair.items.add(last.getKey()); pair.items.add(last.getValue()); yield pair;
            }
            case "setdefault" -> {
                if(args.length<1||args.length>2) throw new PyException("TypeError","setdefault expected 1 or 2 arguments");
                if(map.containsKey(args[0])) yield map.get(args[0]);
                Object value=args.length==2?args[1]:null; map.put(args[0],value); yield value;
            }
            case "clear" -> { requireArgs(name,args,0); map.clear(); yield null; }
            case "__contains__" -> { requireArgs(name,args,1); yield map.containsKey(args[0]); }
            case "__len__" -> { requireArgs(name,args,0); yield (long)map.size(); }
            case "__getitem__" -> { requireArgs(name,args,1); yield getitem(map,args[0]); }
            case "__setitem__" -> { requireArgs(name,args,2); map.put(args[0],args[1]); yield null; }
            case "__delitem__" -> { requireArgs(name,args,1); if(!map.containsKey(args[0])) throw new PyException("KeyError",args[0]); map.remove(args[0]); yield null; }
            case "__iter__" -> { requireArgs(name,args,0); yield iter(map); }
            case "__reversed__" -> { requireArgs(name,args,0); yield reversed(map); }
            case "__eq__" -> { requireArgs(name,args,1); yield args[0] instanceof Map ? (Object)map.equals(args[0]) : NOT_IMPLEMENTED; }
            case "__ne__" -> { requireArgs(name,args,1); yield args[0] instanceof Map ? (Object)!map.equals(args[0]) : NOT_IMPLEMENTED; }
            case "__or__" -> { requireArgs(name,args,1); yield args[0] instanceof Map ? bitOr(map,args[0]) : NOT_IMPLEMENTED; }
            case "__ror__" -> { requireArgs(name,args,1); yield args[0] instanceof Map ? bitOr(args[0],map) : NOT_IMPLEMENTED; }
            case "__ior__" -> { requireArgs(name,args,1); yield ibitOr(map,args[0]); }
            default -> throw new PyException("AttributeError","'dict' object has no attribute '"+name+"'");
        };
    }
}
