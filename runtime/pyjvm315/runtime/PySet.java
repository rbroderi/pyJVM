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

class PySet extends java.util.AbstractSet<Object> {
    final LinkedHashMap<SetKey,Object> entries=new LinkedHashMap<>();
    PySet(){}
    PySet(Object values){if(values instanceof PySet set)entries.putAll(set.entries);else for(Object value:iterable(values))entries.putIfAbsent(new SetKey(value),value);}
    @Override public int size(){if(!SyncSupport.MT)return entries.size();synchronized(this){return entries.size();}}
    @Override public Iterator<Object> iterator(){return new PySetIterator(this);}
    SetKey lookupKey(Object value){return new SetKey(value instanceof Set<?> && !(value instanceof PyFrozenSet)?new PyFrozenSet(value):value);}
    @Override public boolean contains(Object value){SetKey key=lookupKey(value);if(!SyncSupport.MT)return entries.containsKey(key);synchronized(this){return entries.containsKey(key);}}
    @Override public boolean add(Object value){SetKey key=new SetKey(value);if(!SyncSupport.MT)return add0(key,value);synchronized(this){return add0(key,value);}}
    private boolean add0(SetKey key,Object value){if(entries.containsKey(key))return false;entries.put(key,value);return true;}
    @Override public boolean remove(Object value){SetKey key=lookupKey(value);if(!SyncSupport.MT)return remove0(key);synchronized(this){return remove0(key);}}
    private boolean remove0(SetKey key){if(!entries.containsKey(key))return false;entries.remove(key);return true;}
    @Override public void clear(){if(!SyncSupport.MT){entries.clear();return;}synchronized(this){entries.clear();}}
    @Override public int hashCode(){throw new PyException("TypeError","unhashable type: 'set'");}
}
