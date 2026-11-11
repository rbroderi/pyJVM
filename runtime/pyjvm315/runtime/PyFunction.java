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

final class PyFunction {
    final LinkedHashMap<Object,Object> attrs=new LinkedHashMap<>();
    final LinkedHashMap<Object,Object> metadata=new LinkedHashMap<>();
    final Object owner; final String method;
    final List<String> posonly, poskw, kwonly;
    final String vararg, kwarg;
    final LinkedHashMap<String,Object> defaults;
    final PyEnv closure;
    final boolean envMode;
    String displayName, filename; long firstlineno; boolean asyncMode=false, asyncGeneratorMode=false;
    boolean hasAnnotations=false,hasClassCell=false;
    List<String> freeNames=List.of();
    PyTuple closureTuple;

    PyFunction(Object owner, String method, List<String> posonly, List<String> poskw, List<String> kwonly,
               String vararg, String kwarg, LinkedHashMap<String,Object> defaults) {
        this(owner, method, posonly, poskw, kwonly, vararg, kwarg, defaults, null, false);
    }

    PyFunction(Object owner, String method, List<String> posonly, List<String> poskw, List<String> kwonly,
               String vararg, String kwarg, LinkedHashMap<String,Object> defaults, PyEnv closure, boolean envMode) {
        this.owner=owner; this.method=method; this.posonly=posonly; this.poskw=poskw; this.kwonly=kwonly;
        this.vararg=vararg; this.kwarg=kwarg; this.defaults=defaults; this.closure=closure; this.envMode=envMode;
        this.displayName=method; this.filename=String.valueOf(owner); this.firstlineno=0;
    }

    Object call(List<Object> args, Map<Object,Object> kwargsRaw) {
        LinkedHashMap<String,Object> assigned = new LinkedHashMap<>();
        ArrayList<Object> extraPos = new ArrayList<>();
        LinkedHashMap<String,Object> extraKw = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        positional.addAll(posonly); positional.addAll(poskw);

        int i=0;
        for (Object arg : args) {
            if (i < positional.size()) assigned.put(positional.get(i++), arg);
            else extraPos.add(arg);
        }
        if (!extraPos.isEmpty() && vararg == null)
            throw new PyException("TypeError", method + "() takes " + positional.size() + " positional arguments but more were given");

        for (var e : kwargsRaw.entrySet()) {
            if (!(e.getKey() instanceof String key)) throw new PyException("TypeError", "keywords must be strings");
            Object value=e.getValue();
            if (posonly.contains(key)) {
                if (kwarg != null) extraKw.put(key, value);
                else throw new PyException("TypeError", method + "() got some positional-only arguments passed as keyword arguments: '" + key + "'");
            } else if (poskw.contains(key) || kwonly.contains(key)) {
                if (assigned.containsKey(key)) throw new PyException("TypeError", method + "() got multiple values for argument '" + key + "'");
                assigned.put(key, value);
            } else if (kwarg != null) {
                extraKw.put(key, value);
            } else {
                throw new PyException("TypeError", method + "() got an unexpected keyword argument '" + key + "'");
            }
        }

        for (String name : positional) {
            if (!assigned.containsKey(name)) {
                if (defaults.containsKey(name)) assigned.put(name, defaults.get(name));
                else throw new PyException("TypeError", method + "() missing required positional argument: '" + name + "'");
            }
        }
        for (String name : kwonly) {
            if (!assigned.containsKey(name)) {
                if (defaults.containsKey(name)) assigned.put(name, defaults.get(name));
                else throw new PyException("TypeError", method + "() missing required keyword-only argument: '" + name + "'");
            }
        }

        ArrayList<Object> bound = new ArrayList<>();
        for (String name : positional) bound.add(assigned.get(name));
        for (String name : kwonly) bound.add(assigned.get(name));
        if (vararg != null) { PyTuple tuple=new PyTuple(); tuple.items.addAll(extraPos); bound.add(tuple); }
        if (kwarg != null) bound.add(new PyDict(extraKw));
        Object[] boundArray=bound.toArray();
        if(asyncMode) return new PyCoroutine(this,boundArray);
        return invokeStatic(boundArray);
    }

    Object invokeStatic(Object[] bound) {
        ActiveFrame logical=new ActiveFrame(displayName,filename,firstlineno);
        logical.firstArgument=!posonly.isEmpty()?posonly.get(0):!poskw.isEmpty()?poskw.get(0):null;
        logical.classEnv=hasClassCell?closure:null;
        logical.closure=closure; logical.freeNames=freeNames; logical.owner=owner;
        List<String> localNames=new ArrayList<>(); localNames.addAll(posonly); localNames.addAll(poskw); localNames.addAll(kwonly);
        if(vararg!=null)localNames.add(vararg); if(kwarg!=null)localNames.add(kwarg);
        for(int localIndex=0;localIndex<Math.min(localNames.size(),bound.length);localIndex++) logical.locals.put(localNames.get(localIndex),bound[localIndex]);
        LOGICAL_FRAMES.get().push(logical);
        try {
            Class<?> cls=ownerClass(owner);
            int hidden = envMode ? 1 : 0;
            Class<?>[] types=new Class<?>[bound.length + hidden]; Arrays.fill(types, Object.class);
            var reflected=cls.getDeclaredMethod(method, types);
            Object[] actual;
            if (envMode) {
                actual = new Object[bound.length + 1];
                actual[0] = closure;
                System.arraycopy(bound, 0, actual, 1, bound.length);
            } else actual = bound;
            return reflected.invoke(null, actual);
        } catch (java.lang.reflect.InvocationTargetException exc) {
            Throwable cause=exc.getCause();
            if (cause instanceof PyException p) { p.addFrame(logical.name,logical.filename,logical.firstlineno,logical.line); throw p; }
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error e) throw e;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException exc) {
            throw new RuntimeException(exc);
        } finally {
            ArrayDeque<ActiveFrame> stack=LOGICAL_FRAMES.get(); if(!stack.isEmpty() && stack.peek()==logical) stack.pop();
        }
    }

    @Override public String toString() { return "<function " + method + ">"; }
}
