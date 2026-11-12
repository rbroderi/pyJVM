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

/** Method-call slot for PyByteArray receivers (split out of PyRuntime.callMethod). */
final class PyByteArrayMethods {
    static Object call(Object obj, String name, Object[] args) {
        if (!SyncSupport.MT) return call0(obj, name, args);
        synchronized (obj) { return call0(obj, name, args); }
    }

    private static Object call0(Object obj, String name, Object[] args) {
        PyByteArray bytes=(PyByteArray)obj;
        if(!name.equals("join") && !name.equals("extend")) args=NativeSubclass.unwrapArgs(args);
        return switch(name) {
            case "append" -> { requireArgs(name,args,1); bytes.appendUnsigned(byteValue(args[0])); yield null; }
            case "extend" -> { requireArgs(name,args,1); for(Object x:iterable(args[0])) bytes.appendUnsigned(byteValue(x)); yield null; }
            case "clear" -> { requireArgs(name,args,0); bytes.data.clear(); yield null; }
            case "take_bytes" -> {
                if(!LangVersion.atLeast(3,15)) throw new PyException("AttributeError","'bytearray' object has no attribute 'take_bytes'");
                if(args.length>1)throw new PyException("TypeError","take_bytes expected at most 1 argument, got "+args.length);
                int size=bytes.data.size(), n=size;
                if(args.length==1&&args[0]!=null){
                    long k=asIndex(args[0]); if(k<0)k+=size;
                    if(k<0||k>size)throw new PyException("IndexError","index out of range");
                    n=(int)k;
                }
                byte[] taken=new byte[n]; for(int i=0;i<n;i++)taken[i]=bytes.data.get(i);
                bytes.data.subList(0,n).clear();
                yield new PyBytes(taken);
            }
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
                if(args.length>2) throw new PyException("TypeError","decode() takes at most 2 arguments");
                String enc=args.length==0||args[0]==null?"utf-8":(String)args[0];
                String err=args.length<2||args[1]==null?"strict":(String)args[1];
                yield Codecs.decode(bytes.toByteArray(),enc,err);
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
}
