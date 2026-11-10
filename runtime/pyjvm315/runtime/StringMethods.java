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

/** Method-call slot for String receivers (split out of PyRuntime.callMethod). */
final class StringMethods {
    static Object call(Object obj, String name, Object[] args) {
        String str=(String)obj;
        return switch(name) {
            case "__mod__" -> { requireArgs(name,args,1); yield new PercentFormatter(str,args[0]).run(); }
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
}
