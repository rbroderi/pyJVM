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

final class PyGenerator implements Iterator<Object>, Iterable<Object> {
    final Object owner; final String resumeMethod;
    final PyEnv env;
    long state = 0;
    boolean finished = false;
    Object returnValue = null;
    Object sentValue = null;
    Object pendingException = null;
    boolean started = false;
    boolean buffered = false;
    Object bufferedValue = null;
    String displayName, filename; long firstlineno;
    String firstArgument; boolean hasClassCell;

    PyGenerator(Object owner, String resumeMethod, PyEnv env) {
        this(owner,resumeMethod,env,resumeMethod,String.valueOf(owner),0);
    }
    PyGenerator(Object owner,String resumeMethod,PyEnv env,String displayName,String filename,long firstlineno) {
        this.owner=owner; this.resumeMethod=resumeMethod; this.env=env; this.displayName=displayName; this.filename=filename; this.firstlineno=firstlineno;
        ActiveFrame frame=currentLogicalFrame();
        if(frame!=null){firstArgument=frame.firstArgument;hasClassCell=frame.classEnv!=null;}
    }

    Object advance() {
        if (finished) throw new PyGeneratorEnd(returnValue);
        started = true;
        ActiveFrame logical=new ActiveFrame(displayName,filename,firstlineno);
        logical.inspectSupported=false;logical.owner=owner;logical.firstArgument=firstArgument;logical.argumentEnv=env;logical.classEnv=hasClassCell?env:null;
        LOGICAL_FRAMES.get().push(logical);
        try {
            Class<?> cls=ownerClass(owner);
            var reflected=cls.getDeclaredMethod(resumeMethod, Object.class);
            Object value=reflected.invoke(null, this);
            if (value == GENERATOR_DONE) { finished=true; throw new PyGeneratorEnd(returnValue); }
            return value;
        } catch (java.lang.reflect.InvocationTargetException exc) {
            Throwable cause=exc.getCause();
            if (cause instanceof PyGeneratorEnd e) { finished=true; throw e; }
            if (cause instanceof PyException p && p.typeName.equals("StopIteration")) {
                finished=true;
                PyException wrapped=new PyException("RuntimeError", "generator raised StopIteration"); wrapped.addFrame(displayName,filename,logical.firstlineno,logical.line); throw wrapped;
            }
            if (cause instanceof PyException p) { finished=true; p.addFrame(displayName,filename,logical.firstlineno,logical.line); throw p; }
            if (cause instanceof RuntimeException r) { finished=true; throw r; }
            if (cause instanceof Error e) { finished=true; throw e; }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException exc) {
            throw new RuntimeException(exc);
        } finally {
            ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get(); if(!stack.isEmpty() && stack.peek()==logical) stack.pop();
        }
    }

    @Override public boolean hasNext() {
        if (finished) return false;
        if (buffered) return true;
        try { bufferedValue=advance(); buffered=true; return true; }
        catch (PyGeneratorEnd e) { return false; }
    }

    @Override public Object next() {
        if (buffered) { Object v=bufferedValue; bufferedValue=null; buffered=false; return v; }
        return advance();
    }

    Object send(Object value) {
        if (!started && value != null) throw new PyException("TypeError", "can't send non-None value to a just-started generator");
        sentValue=value;
        try { return next(); } finally { sentValue=null; }
    }
    Object throw_(Object value) {
        if (finished) { raiseObject(value); return null; }
        if (!started) { finished=true; raiseObject(value); return null; }
        pendingException=value;
        try { return next(); } finally { pendingException=null; }
    }
    Object close() {
        if (finished) return null;
        if (!started) { finished=true; buffered=false; bufferedValue=null; return null; }
        pendingException=new PyExceptionValue("GeneratorExit",null);
        try {
            Object yielded=next();
            throw new PyException("RuntimeError","generator ignored GeneratorExit");
        } catch (PyGeneratorEnd end) { return null; }
          catch (PyException exc) { if(exc.typeName.equals("GeneratorExit")) { finished=true; return null; } throw exc; }
        finally { pendingException=null; buffered=false; bufferedValue=null; }
    }

    @Override public Iterator<Object> iterator() { return this; }
    @Override public String toString() { return "<generator object>"; }
}
