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
final class PyEnumerate extends PyLazyIterator {
    final Object source; Object index;
    PyEnumerate(Object iterable,Object start) {
        this.source=iter(iterable);
        if(!isIntLike(start)) throw new PyException("TypeError","'"+typeName(start)+"' object cannot be interpreted as an integer");
        this.index=start;
    }
    String pyTypeName(){return "enumerate";}
    Object compute() {
        Object item=pull(source);
        PyTuple out=new PyTuple(); out.items.add(index); out.items.add(item);
        index=add(index,1L);
        return out;
    }
}
