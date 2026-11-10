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

final class SortItem {
    final Object value;
    final Object key;
    SortItem(Object value, Object key){this.value=value;this.key=key;}
    @Override public boolean equals(Object o){return o instanceof SortItem r && java.util.Objects.equals(value,r.value) && java.util.Objects.equals(key,r.key);}
    @Override public int hashCode(){return java.util.Objects.hash(value,key);}
    @Override public String toString(){return "SortItem["+"value="+value+", "+"key="+key+"]";}
}
