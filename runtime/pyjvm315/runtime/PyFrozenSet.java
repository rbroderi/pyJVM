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

final class PyFrozenSet extends PySet {
    Long cachedHash;
    PyFrozenSet(Object values){super(values);}
    long pythonHash(){
        if(cachedHash!=null)return cachedHash;long result=0;
        for(SetKey key:entries.keySet())result^=((key.hash^89869747L)^(key.hash<<16))*3644798167L;
        result^=(size()+1L)*1927868237L;result^=(result>>>11)^(result>>>25);result=result*69069L+907133923L;
        cachedHash=result==-1?590923713L:result;return cachedHash;
    }
    @Override public int hashCode(){return Long.hashCode(pythonHash());}
    @Override public boolean add(Object value){throw new PyException("AttributeError","frozenset has no attribute 'add'");}
    @Override public boolean remove(Object value){throw new PyException("AttributeError","frozenset has no attribute 'remove'");}
    @Override public void clear(){throw new PyException("AttributeError","frozenset has no attribute 'clear'");}
}
