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
    /** Python's str.isspace() code points (Unicode White_Space plus the ASCII separators U+001C..U+001F). */
    static boolean isPySpace(int cp) {
        return (cp >= 0x09 && cp <= 0x0D) || (cp >= 0x1C && cp <= 0x20) || cp == 0x85 || cp == 0xA0 || cp == 0x1680
            || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }
    static Object stripCall(String str, String name, Object[] args) {
        if (args.length > 1) throw new PyException("TypeError", name + " expected at most 1 argument, got " + args.length);
        String chars = null;
        if (args.length == 1 && args[0] != null) {
            if (!(args[0] instanceof String c)) throw new PyException("TypeError", name + " arg must be None or str");
            chars = c;
        }
        int[] cps = str.codePoints().toArray();
        int start = 0, end = cps.length;
        boolean left = !name.equals("rstrip"), right = !name.equals("lstrip");
        while (left && start < end && strippable(cps[start], chars)) start++;
        while (right && end > start && strippable(cps[end - 1], chars)) end--;
        return new String(cps, start, end - start);
    }
    private static boolean strippable(int cp, String chars) { return chars == null ? isPySpace(cp) : chars.indexOf(cp) >= 0; }

    static Object call(Object obj, String name, Object[] args) {
        String str=(String)obj;
        return switch(name) {
            case "__mod__" -> { requireArgs(name,args,1); yield new PercentFormatter(str,args[0]).run(); }
            case "upper" -> { requireArgs(name,args,0); yield str.toUpperCase(Locale.ROOT); }
            case "lower" -> { requireArgs(name,args,0); yield str.toLowerCase(Locale.ROOT); }
            case "strip", "lstrip", "rstrip" -> stripCall(str,name,args);
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
                yield new PyBytes(str.getBytes(PyRuntime.pyCharset(enc)));
            }
            default -> throw new PyException("AttributeError","'str' object has no attribute '"+name+"'");
        };
    }
}
