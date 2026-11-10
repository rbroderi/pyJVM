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

final class PyMap implements Iterator<Object>, Iterable<Object> {
    final Object function; final ArrayList<Object> iterators=new ArrayList<>();
    Object buffered; boolean hasBuffered=false;
    PyMap(Object function,List<Object> sources) {
        this.function=function;
        for(Object source:sources) iterators.add(iter(source));
    }
    public Iterator<Object> iterator(){return this;}
    public boolean hasNext() {
        if(hasBuffered) return true;
        try { buffered=compute(); hasBuffered=true; return true; }
        catch(NoSuchElementException end){return false;}
        catch(PyException end){if(end.typeName.equals("StopIteration"))return false;throw end;}
    }
    Object compute() {
        ArrayList<Object> args=new ArrayList<>();
        for(Object iterator:iterators) {
            try { args.add(next_(iterator)); }
            catch(PyException end) {if(end.typeName.equals("StopIteration"))throw new NoSuchElementException();throw end;}
        }
        return callFunction(function,args,new LinkedHashMap<Object,Object>());
    }
    public Object next() {
        if(hasBuffered){Object out=buffered;buffered=null;hasBuffered=false;return out;}
        return compute();
    }
}
