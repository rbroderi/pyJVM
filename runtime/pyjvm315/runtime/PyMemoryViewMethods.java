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
                for(Object x:view)out.add(x); yield out;
            }
            default -> throw new PyException("AttributeError","'memoryview' object has no attribute '"+name+"'");
        };
    }
}
