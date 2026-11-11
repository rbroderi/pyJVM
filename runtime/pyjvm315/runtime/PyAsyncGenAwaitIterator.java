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

final class PyAsyncGenAwaitIterator implements Iterator<Object>, Iterable<Object> {
    final PyAsyncGenAwaitable awaitable;
    boolean started=false, done=false; Object result=null;
    PyAsyncGenAwaitIterator(PyAsyncGenAwaitable awaitable){this.awaitable=awaitable;}

    Object send(Object sent){
        if(done) throw new PyGeneratorEnd(result);
        Object out;
        try {
            if(!started) {
                started=true;
                out = switch(awaitable.action) {
                    case "next" -> awaitable.generator.frame.send(null);
                    case "send" -> awaitable.generator.frame.send(awaitable.value);
                    case "throw" -> awaitable.generator.frame.throw_(awaitable.value);
                    case "close" -> { awaitable.generator.frame.close(); yield null; }
                    default -> throw new PyException("RuntimeError","unknown async generator operation");
                };
                if(awaitable.action.equals("close")) {
                    done=true; result=null; throw new PyGeneratorEnd(null);
                }
            } else {
                out=awaitable.generator.frame.send(sent);
            }
        } catch(PyGeneratorEnd exhausted) {
            done=true;
            if(awaitable.action.equals("close")) { result=null; throw new PyGeneratorEnd(null); }
            throw new PyException("StopAsyncIteration",null);
        }
        if(out instanceof PyAsyncGeneratorYield item) {
            done=true; result=item.value; throw new PyGeneratorEnd(result);
        }
        return out;
    }

    Object throw_(Object thrown){
        if(done){raiseObject(thrown);return null;}
        if(!started) {
            started=true;
            try {
                Object out=awaitable.generator.frame.throw_(thrown);
                if(out instanceof PyAsyncGeneratorYield item) {done=true;result=item.value;throw new PyGeneratorEnd(result);}
                return out;
            } catch(PyGeneratorEnd exhausted) {done=true;throw new PyException("StopAsyncIteration",null);}
        }
        try {
            Object out=awaitable.generator.frame.throw_(thrown);
            if(out instanceof PyAsyncGeneratorYield item) {done=true;result=item.value;throw new PyGeneratorEnd(result);}
            return out;
        } catch(PyGeneratorEnd exhausted) {done=true;throw new PyException("StopAsyncIteration",null);}
    }

    Object close(){done=true;return awaitable.generator.frame.close();}
    public boolean hasNext(){return !done;}
    public Object next(){return send(null);}
    public Iterator<Object> iterator(){return this;}
}
