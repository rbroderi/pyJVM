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

/** Method-call slot for PyMemoryView receivers (split out of PyRuntime.callMethod). */
final class PyMemoryViewMethods {
    /** Views are byte-addressed, so only the one-byte formats can be cast to. */
    private static Object cast(PyMemoryView view,Object[] args) {
        if(args.length<1||args.length>2) throw new PyException("TypeError","cast() takes 1 or 2 arguments ("+args.length+" given)");
        if(args[0] instanceof String f && (f.equals("B")||f.equals("b")||f.equals("c"))&&args.length==1) return view;
        throw new PyException("NotImplementedError","memoryview.cast to formats other than B, b and c is not supported");
    }


    static Object call(Object obj, String name, Object[] args) {
        PyMemoryView view=(PyMemoryView)obj;
        return switch(name) {
            case "tobytes" -> { requireArgs(name,args,0); yield new PyBytes(view.toByteArray()); }
            case "tolist" -> {
                requireArgs(name,args,0); ArrayList<Object> out=new ArrayList<>();
                if(view.itemsize==1){for(Object x:view)out.add(x); yield out;}
                for(int i=0;i+view.itemsize<=view.length;i+=view.itemsize){
                    long v=0; for(int k=view.itemsize-1;k>=0;k--) v=(v<<8)|view.unsignedAt(i+k);
                    boolean signed=Character.isLowerCase(view.fmt);
                    if(signed&&view.itemsize<8){long sign=1L<<(8*view.itemsize-1); if((v&sign)!=0) v-=sign<<1;}
                    out.add(!signed&&view.itemsize==8&&v<0?new BigInteger(Long.toUnsignedString(v)):(Object)v);
                }
                yield out;
            }
            case "__enter__" -> { requireArgs(name,args,0); yield view; }
            case "__exit__" -> null;
            case "release" -> { requireArgs(name,args,0); yield null; }
            case "hex" -> callMethod(new PyBytes(view.toByteArray()),"hex",args);
            case "cast" -> cast(view,args);
            default -> throw new PyException("AttributeError","'memoryview' object has no attribute '"+name+"'");
        };
    }
}
