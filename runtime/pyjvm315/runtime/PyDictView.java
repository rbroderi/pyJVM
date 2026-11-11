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

final class PyDictView implements Iterable<Object> {
    final Map<Object,Object> map; final String kind;
    PyDictView(Map<Object,Object> map,String kind){this.map=map;this.kind=kind;}
    @Override public Iterator<Object> iterator() {
        if(kind.equals("keys")) return map.keySet().iterator();
        if(kind.equals("values")) return map.values().iterator();
        Iterator<Map.Entry<Object,Object>> base=map.entrySet().iterator();
        return new Iterator<>() {
            public boolean hasNext(){return base.hasNext();}
            public Object next(){var e=base.next();PyTuple t=new PyTuple();t.items.add(e.getKey());t.items.add(e.getValue());return t;}
        };
    }
    String pyRepr(){return "dict_"+kind+"("+seqRepr(this,"[","]")+")";}
}
