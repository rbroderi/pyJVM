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

final class BoundCallable {
    final Object function;
    final Object self;
    BoundCallable(Object function, Object self){this.function=function;this.self=self;}
    @Override public boolean equals(Object o){return o instanceof BoundCallable r && java.util.Objects.equals(function,r.function) && java.util.Objects.equals(self,r.self);}
    @Override public int hashCode(){return java.util.Objects.hash(function,self);}
    @Override public String toString(){return "BoundCallable["+"function="+function+", "+"self="+self+"]";}
}
