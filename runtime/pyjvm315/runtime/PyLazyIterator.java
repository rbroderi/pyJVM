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
/** Base for lazy builtin iterators (map, zip, enumerate, filter): subclasses produce items via compute(). */
abstract class PyLazyIterator implements Iterator<Object>, Iterable<Object> {
    private Object buffered; private boolean hasBuffered=false; private boolean done=false;
    /** Next item, or throw NoSuchElementException when exhausted. */
    abstract Object compute();
    abstract String pyTypeName();
    public Iterator<Object> iterator(){return this;}
    public boolean hasNext() {
        if(SyncSupport.MT) synchronized(this) { return hasNext0(); }
        return hasNext0();
    }
    private boolean hasNext0() {
        if(hasBuffered) return true;
        if(done) return false;
        try { buffered=compute(); hasBuffered=true; return true; }
        catch(NoSuchElementException end){done=true;return false;}
        catch(PyException end){if(end.typeName.equals("StopIteration")){done=true;return false;}throw end;}
    }
    public Object next() {
        if(SyncSupport.MT) synchronized(this) { return next0(); }
        return next0();
    }
    private Object next0() {
        if(hasBuffered){Object out=buffered;buffered=null;hasBuffered=false;return out;}
        if(done) throw new NoSuchElementException();
        try { return compute(); }
        catch(NoSuchElementException end){done=true;throw end;}
    }
    /** next_() on a source iterator, mapping StopIteration to exhaustion. */
    static Object pull(Object iterator) {
        try { return next_(iterator); }
        catch(PyException end) {if(end.typeName.equals("StopIteration"))throw new NoSuchElementException();throw end;}
    }
}
