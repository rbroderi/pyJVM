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

final class PyMethod {
    final Object owner;
    final String javaName;
    final String kind;
    final PyFunction function;
    PyMethod(Object owner, String javaName, String kind, PyFunction function){this.owner=owner;this.javaName=javaName;this.kind=kind;this.function=function;}
    @Override public boolean equals(Object o){return o instanceof PyMethod r && java.util.Objects.equals(owner,r.owner) && java.util.Objects.equals(javaName,r.javaName) && java.util.Objects.equals(kind,r.kind) && java.util.Objects.equals(function,r.function);}
    @Override public int hashCode(){return java.util.Objects.hash(owner,javaName,kind,function);}
    @Override public String toString(){return "PyMethod["+"owner="+owner+", "+"javaName="+javaName+", "+"kind="+kind+", "+"function="+function+"]";}

    PyMethod(Object owner,String javaName){this(owner,javaName,"instance",null);}
    PyMethod(Object owner,String javaName,String kind){this(owner,javaName,kind,null);}
}