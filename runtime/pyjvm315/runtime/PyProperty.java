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

final class PyProperty {
    final String owner;
    final String getter;
    final String setter;
    final PyFunction getFunction;
    final PyFunction setFunction;
    PyProperty(String owner, String getter, String setter, PyFunction getFunction, PyFunction setFunction){this.owner=owner;this.getter=getter;this.setter=setter;this.getFunction=getFunction;this.setFunction=setFunction;}
    @Override public boolean equals(Object o){return o instanceof PyProperty r && java.util.Objects.equals(owner,r.owner) && java.util.Objects.equals(getter,r.getter) && java.util.Objects.equals(setter,r.setter) && java.util.Objects.equals(getFunction,r.getFunction) && java.util.Objects.equals(setFunction,r.setFunction);}
    @Override public int hashCode(){return java.util.Objects.hash(owner,getter,setter,getFunction,setFunction);}
    @Override public String toString(){return "PyProperty["+"owner="+owner+", "+"getter="+getter+", "+"setter="+setter+", "+"getFunction="+getFunction+", "+"setFunction="+setFunction+"]";}

    PyProperty(String owner,String getter,String setter){this(owner,getter,setter,null,null);}
    PyMethod getterMethod(){return new PyMethod(owner,getter,"instance",getFunction);}
    PyMethod setterMethod(){return new PyMethod(owner,setter,"instance",setFunction);}
}