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

final class PyCoroutineAwaitIterator implements Iterator<Object>, Iterable<Object> {
    final PyCoroutine coroutine; boolean done=false;
    PyCoroutineAwaitIterator(PyCoroutine coroutine){this.coroutine=coroutine;}
    Object send(Object value){
        if(done)throw new PyGeneratorEnd(coroutine.result);
        try { return coroutine.send(value); }
        catch(PyException e) {
            if(e.typeName.equals("StopIteration")) { done=true; throw new PyGeneratorEnd(e.value); }
            throw e;
        }
    }
    Object throw_(Object value){
        if(done){raiseObject(value);return null;}
        try { return coroutine.throw_(value); }
        catch(PyException e) {
            if(e.typeName.equals("StopIteration")) { done=true; throw new PyGeneratorEnd(e.value); }
            throw e;
        }
    }
    Object close(){done=true;return coroutine.close();}
    public boolean hasNext(){return !done && !coroutine.finished && !coroutine.closed;}
    public Object next(){return send(null);}
    public Iterator<Object> iterator(){return this;}
}
