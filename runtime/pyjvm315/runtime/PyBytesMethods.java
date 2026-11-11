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

/** Method-call slot for PyBytes receivers (split out of PyRuntime.callMethod). */
final class PyBytesMethods {
    static Object call(Object obj, String name, Object[] args) {
        PyBytes bytes=(PyBytes)obj;
        return switch(name) {
            case "decode" -> {
                if(args.length>1) throw new PyException("TypeError","decode() takes at most 1 argument");
                String enc=args.length==0?"utf-8":(String)args[0];
                yield new String(bytes.data,PyRuntime.pyCharset(enc));
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
}
