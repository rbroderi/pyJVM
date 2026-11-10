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

final class BoundSuperMethod {
    final PyInstance self;
    final PyClass currentClass;
    final String name;
    BoundSuperMethod(PyInstance self, PyClass currentClass, String name){this.self=self;this.currentClass=currentClass;this.name=name;}
    @Override public boolean equals(Object o){return o instanceof BoundSuperMethod r && java.util.Objects.equals(self,r.self) && java.util.Objects.equals(currentClass,r.currentClass) && java.util.Objects.equals(name,r.name);}
    @Override public int hashCode(){return java.util.Objects.hash(self,currentClass,name);}
    @Override public String toString(){return "BoundSuperMethod["+"self="+self+", "+"currentClass="+currentClass+", "+"name="+name+"]";}
}
