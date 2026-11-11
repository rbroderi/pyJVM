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
final class PyFilter extends PyLazyIterator {
    final Object function, source;
    PyFilter(Object function,Object iterable) { this.function=function; this.source=iter(iterable); }
    String pyTypeName(){return "filter";}
    Object compute() {
        while(true) {
            Object item=pull(source);
            Object keep=function==null?item:callFunction(function,new ArrayList<Object>(List.of(item)),new LinkedHashMap<Object,Object>());
            if(truth(keep)) return item;
        }
    }
}
