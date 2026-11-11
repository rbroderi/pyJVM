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
final class PyZip extends PyLazyIterator {
    final ArrayList<Object> iterators=new ArrayList<>(); final boolean strict;
    PyZip(List<Object> sources,boolean strict) {
        this.strict=strict;
        for(Object source:sources) iterators.add(iter(source));
    }
    String pyTypeName(){return "zip";}
    Object compute() {
        if(iterators.isEmpty()) throw new NoSuchElementException();
        PyTuple out=new PyTuple();
        for(int i=0;i<iterators.size();i++) {
            Object item;
            try { item=pull(iterators.get(i)); }
            catch(NoSuchElementException end) {
                if(strict) {
                    if(i>0) throw new PyException("ValueError","zip() argument "+(i+1)+" is shorter than argument"+(i>1?"s 1-"+i:" 1"));
                    for(int j=1;j<iterators.size();j++) {
                        try { pull(iterators.get(j)); }
                        catch(NoSuchElementException ignored) { continue; }
                        throw new PyException("ValueError","zip() argument "+(j+1)+" is longer than argument"+(j>1?"s 1-"+j:" 1"));
                    }
                }
                throw end;
            }
            out.items.add(item);
        }
        return out;
    }
}
