package pyjvm315.runtime;

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

final class PyModuleSpec {
    final String name,origin,parent;
    final Object loader;
    final Object submoduleSearchLocations;
    PyModuleSpec(String name,String origin,String parent,boolean isPackage,Object loader){
        this.name=name;this.origin=origin;this.parent=parent;this.loader=loader;
        this.submoduleSearchLocations=isPackage?new ArrayList<Object>():null;
    }
    @Override public String toString(){return "ModuleSpec(name='"+name+"', origin='"+origin+"')";}
}
