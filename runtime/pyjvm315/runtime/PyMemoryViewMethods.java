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
            case "cast" -> {
                // only the integer formats of the struct module, in native (little endian) layout; one dimension
                if(args.length<1||args.length>2||!(args[0] instanceof String f))throw new PyException("TypeError","memoryview: format argument must be a string");
                int size=switch(f){case "b","B","c" -> 1; case "h","H" -> 2; case "i","I" -> 4; case "l","L","q","Q","n","N" -> 8;
                    default -> throw new PyException("ValueError","memoryview: destination format must be a native single character format prefixed with an optional '@'");};
                if(view.length%size!=0)throw new PyException("TypeError","memoryview: length is not a multiple of itemsize");
                PyMemoryView out=new PyMemoryView(view.source,view.start,view.length);
                out.fmt=f.charAt(0); out.itemsize=size; yield out;
            }
            case "__enter__" -> { requireArgs(name,args,0); yield view; }
            case "__exit__" -> null;
            case "release" -> { requireArgs(name,args,0); yield null; }
            case "hex" -> callMethod(new PyBytes(view.toByteArray()),"hex",args);
            default -> throw new PyException("AttributeError","'memoryview' object has no attribute '"+name+"'");
        };
    }
}
