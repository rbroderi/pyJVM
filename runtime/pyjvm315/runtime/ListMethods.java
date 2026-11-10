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
    static Object call(Object obj, String name, Object[] args) {
        List<?> raw=(List<?>)obj;
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
}
