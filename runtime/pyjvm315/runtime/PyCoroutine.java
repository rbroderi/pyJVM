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

final class PyCoroutine {
    final PyFunction function; final Object[] bound; final String displayName;
    PyGenerator frame=null; PyCoroutine adopted=null;
    boolean started=false, finished=false, closed=false; Object result=null;

    PyCoroutine(PyFunction function,Object[] bound){
        this.function=function;this.bound=bound;this.displayName=function.displayName;
    }
    PyCoroutine(PyGenerator frame,String displayName){
        this.function=null;this.bound=null;this.frame=frame;this.displayName=displayName;
    }

    Object resume(Object value,Object thrown,boolean closing){
        if(closed) throw new PyException("RuntimeError","cannot reuse already awaited coroutine");
        if(finished) throw new PyException("RuntimeError","cannot reuse already awaited coroutine");
        if(!started && value!=null && thrown==null)
            throw new PyException("TypeError","can't send non-None value to a just-started coroutine");
        started=true;

        if(adopted!=null) {
            try { return adopted.resume(value,thrown,closing); }
            catch(PyException e) {
                if(e.typeName.equals("StopIteration")) { finished=true; result=e.value; }
                throw e;
            }
        }
        if(frame!=null) {
            try {
                Object yielded;
                if(closing) { frame.close(); finished=true; result=null; throw new PyException("StopIteration",null); }
                if(thrown!=null) yielded=frame.throw_(thrown);
                else yielded=frame.send(value);
                return yielded;
            } catch(PyGeneratorEnd end) {
                finished=true; result=end.value; throw new PyException("StopIteration",result);
            }
        }

        Object out=function.invokeStatic(bound);
        if(out instanceof PyCoroutine inner) {
            adopted=inner;
            try { return adopted.resume(value,thrown,closing); }
            catch(PyException e) {
                if(e.typeName.equals("StopIteration")) { finished=true; result=e.value; }
                throw e;
            }
        }
        finished=true; result=out;
        throw new PyException("StopIteration",out);
    }

    Object run(){
        while(true) {
            try {
                Object yielded=resume(null,null,false);
                throw new PyException("RuntimeError","coroutine suspended while synchronous completion was required: "+pyRepr(yielded));
            } catch(PyException e) {
                if(e.typeName.equals("StopIteration")) return e.value;
                throw e;
            }
        }
    }
    Object send(Object value){return resume(value,null,false);}
    Object throw_(Object value){return resume(null,value,false);}
    Object close(){
        if(finished || closed){closed=true;return null;}
        try { resume(null,new PyExceptionValue("GeneratorExit",null),true); }
        catch(PyException e) {
            if(e.typeName.equals("StopIteration") || e.typeName.equals("GeneratorExit")) { closed=true;finished=true;return null; }
            throw e;
        }
        closed=true;finished=true;return null;
    }
    Object awaitIterator(){return new PyCoroutineAwaitIterator(this);}
    @Override public String toString(){return "<coroutine object "+displayName+">";}
}
