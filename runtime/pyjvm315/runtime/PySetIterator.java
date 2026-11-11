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

final class PySetIterator implements Iterator<Object> {
    PySet source;
    Iterator<Object> cursor;
    final int expectedSize;
    int position;
    boolean invalid;
    PySetIterator(PySet source){this.source=source;expectedSize=source.size();cursor=source.entries.values().iterator();}
    void checkSize(){
        if(invalid || source.size()!=expectedSize){invalid=true;throw new PyException("RuntimeError","Set changed size during iteration");}
    }
    long lengthHint(){return source==null || invalid || source.size()!=expectedSize?0:Math.max(0,expectedSize-position);}
    public boolean hasNext(){
        PySet src=source;
        if(SyncSupport.MT && src!=null) synchronized(src){return hasNext0();}
        return hasNext0();
    }
    private boolean hasNext0(){
        if(source==null)return false;
        checkSize();
        if(cursor.hasNext())return true;
        source=null;cursor=null;return false;
    }
    public Object next(){
        PySet src=source;
        if(SyncSupport.MT && src!=null) synchronized(src){return next0();}
        return next0();
    }
    private Object next0(){
        if(!hasNext0())throw new NoSuchElementException();
        Object result;
        try{result=cursor.next();}
        catch(java.util.ConcurrentModificationException changed){
            // Python checks size, not Java's structural modification count.
            // Resume in the live contents after a same-size mutation. Set
            // traversal order across such mutations is unspecified.
            cursor=source.entries.values().iterator();
            for(int i=0;i<position && cursor.hasNext();i++)cursor.next();
            if(!cursor.hasNext()){source=null;cursor=null;throw new NoSuchElementException();}
            result=cursor.next();
        }
        position++;return result;
    }
}
