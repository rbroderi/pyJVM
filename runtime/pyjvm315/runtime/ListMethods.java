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

/** Method-call slot for List<?> receivers (split out of PyRuntime.callMethod). */
final class ListMethods {
    static final java.util.Set<String> NAMES = java.util.Set.of("append", "extend", "pop", "clear", "copy", "count", "index", "insert", "remove", "reverse", "sort");

    /** list.sort(key=None, reverse=False): stable, keyed, in place. */
    @SuppressWarnings("unchecked")
    static void sortInPlace(List<Object> list, Map<Object,Object> kwargs) {
        for(Object k:kwargs.keySet()) if(!k.equals("key") && !k.equals("reverse")) throw new PyException("TypeError","sort() got an unexpected keyword argument '"+k+"'");
        Timsort.sortList(list,kwargs.get("key"),truth(kwargs.get("reverse")));
    }

    static Object call(Object obj, String name, Object[] args) {
        List<?> raw=(List<?>)obj;
        @SuppressWarnings("unchecked") List<Object> list=(List<Object>)raw;
        return switch(name) {
            case "append" -> { requireArgs(name,args,1); list.add(args[0]); yield null; }
            case "extend" -> { requireArgs(name,args,1); ArrayList<Object> items=new ArrayList<>(); for(Object x:iterable(args[0])) items.add(x); list.addAll(items); yield null; }
            case "pop" -> { if(args.length>1) throw new PyException("TypeError","pop expected at most 1 argument"); int i=args.length==0?list.size()-1:normalizeIndex(asIndex(args[0]),list.size()); yield list.remove(i); }
            case "clear" -> { requireArgs(name,args,0); list.clear(); yield null; }
            case "copy" -> { requireArgs(name,args,0); yield new ArrayList<Object>(list); }
            case "count" -> { requireArgs(name,args,1); long n=0; for(Object x:list) if((Boolean)eq(x,args[0])) n++; yield n; }
            case "index" -> {
                if(args.length<1||args.length>3) throw new PyException("TypeError","index expected 1 to 3 arguments");
                int n=list.size(); long from=args.length>1?asIndex(args[1]):0, to=args.length>2?asIndex(args[2]):n;
                if(from<0) from=Math.max(0,from+n); if(to<0) to=Math.max(0,to+n); to=Math.min(to,n);
                for(int i=(int)from;i<to;i++) if(list.get(i)==args[0] || truth(eq(list.get(i),args[0]))) yield (long)i;
                throw new PyException("ValueError",LangVersion.atLeast(3,14)?"list.index(x): x not in list":pyRepr(args[0])+" is not in list");
            }
            case "insert" -> {
                requireArgs(name,args,2); long at=asIndex(args[0]); int n=list.size();
                if(at<0) at=Math.max(0,at+n); at=Math.min(at,n);
                list.add((int)at,args[1]); yield null;
            }
            case "remove" -> {
                requireArgs(name,args,1);
                for(int i=0;i<list.size();i++) if(list.get(i)==args[0] || truth(eq(list.get(i),args[0]))) { list.remove(i); yield null; }
                throw new PyException("ValueError","list.remove(x): x not in list");
            }
            case "reverse" -> { requireArgs(name,args,0); Collections.reverse(list); yield null; }
            case "sort" -> { if(args.length!=0) throw new PyException("TypeError","sort() takes no positional arguments"); sortInPlace(list,new LinkedHashMap<Object,Object>()); yield null; }
            default -> throw new PyException("AttributeError","'list' object has no attribute '"+name+"'");
        };
    }
}
