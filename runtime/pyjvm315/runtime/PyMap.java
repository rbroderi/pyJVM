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
final class PyMap extends PyLazyIterator {
    final Object function; final ArrayList<Object> iterators=new ArrayList<>();
    PyMap(Object function,List<Object> sources) {
        this.function=function;
        for(Object source:sources) iterators.add(iter(source));
    }
    String pyTypeName(){return "map";}
    Object compute() {
        ArrayList<Object> args=new ArrayList<>();
        for(Object iterator:iterators) args.add(pull(iterator));
        return callFunction(function,args,new LinkedHashMap<Object,Object>());
    }
}
