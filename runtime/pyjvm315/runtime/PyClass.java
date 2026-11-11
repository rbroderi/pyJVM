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

final class PyClass {
    final String moduleName;
    final String name;
    String qualname;
    final ArrayList<PyClass> bases = new ArrayList<>();
    final LinkedHashMap<String,PyMethod> methods = new LinkedHashMap<>();
    final LinkedHashMap<String,PyProperty> properties = new LinkedHashMap<>();
    final LinkedHashMap<String,Object> attrs = new LinkedHashMap<>();
    List<PyClass> mro = List.of(this);
    PyClass metaclass = null;
    String builtinBaseName = null;
    /** Position of the builtin base among the declared bases (for __bases__ ordering). */
    int builtinBasePos = -1;
    /** Builtin value type this class derives from (int, str, list, dict, ...; see NativeSubclass), resolved at finalization, else null. */
    String nativeKind = null;
    /** Name of the built-in exception type this class ultimately derives from (user-defined exception classes), else null. */
    String exceptionBase = null;
    final LinkedHashSet<String> ownSlots = new LinkedHashSet<>();
    boolean slotsDeclared = false;
    boolean instanceDictAllowed = true;
    boolean finalized = false;
    /** A __del__ is visible through the MRO (set at finalization and when __del__ is assigned): instances are allocated as GcFinalizable. */
    boolean hasDel = false;
    /** Instances accept weak references (no __slots__ without __weakref__, not an int/tuple/bytes subclass). */
    boolean weakrefOk = false;
    /** Instances are weakref.proxy objects: attribute access and isinstance forward to the referent. */
    boolean weakProxy = false;
    private static final java.util.concurrent.CopyOnWriteArrayList<WeakReference<PyClass>> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();
    PyClass(String moduleName, String name) { this.moduleName=moduleName; this.name = name; this.qualname=name; ALL.add(new WeakReference<>(this)); }

    /** type.__subclasses__(): live classes that list {@code parent} among their direct bases, oldest first. */
    static ArrayList<Object> directSubclasses(PyClass parent) {
        ArrayList<Object> out = new ArrayList<>();
        ArrayList<WeakReference<PyClass>> dead = new ArrayList<>();
        for (WeakReference<PyClass> r : ALL) {
            PyClass c = r.get();
            if (c == null) dead.add(r); else if (c != parent && c.finalized && c.bases.contains(parent)) out.add(c);
        }
        if (dead.size() > 256) ALL.removeAll(dead);
        return out;
    }

    PyMethod lookupMethod(String name) { for (PyClass c:mro){PyMethod m=c.methods.get(name);if(m!=null)return m;if(c.attrs.containsKey(name))return null;} return null; }
    PyProperty lookupProperty(String name) { for (PyClass c:mro){PyProperty p=c.properties.get(name);if(p!=null)return p;} return null; }
    Object lookupAttr(String name) { for (PyClass c:mro) if(c.attrs.containsKey(name)) return c.attrs.get(name); return MISSING; }
    int mroIndex(PyClass current){int i=mro.indexOf(current);if(i<0)throw new PyException("TypeError","super(type, obj): type is not in MRO");return i;}
    PyMethod lookupMethodAfter(PyClass current,String name){for(int i=mroIndex(current)+1;i<mro.size();i++){PyClass cls=mro.get(i);PyMethod m=cls.methods.get(name);if(m!=null)return m;if(cls.attrs.containsKey(name))return null;}return null;}
    PyProperty lookupPropertyAfter(PyClass current,String name){for(int i=mroIndex(current)+1;i<mro.size();i++){PyProperty p=mro.get(i).properties.get(name);if(p!=null)return p;}return null;}
    Object lookupAttrAfter(PyClass current,String name){for(int i=mroIndex(current)+1;i<mro.size();i++){PyClass c=mro.get(i);if(c.attrs.containsKey(name))return c.attrs.get(name);}return MISSING;}

    void computeMro() {
        if (bases.isEmpty()) { mro = List.of(this); return; }
        ArrayList<List<PyClass>> seqs = new ArrayList<>();
        for (PyClass base : bases) seqs.add(new ArrayList<>(base.mro));
        seqs.add(new ArrayList<>(bases));
        ArrayList<PyClass> out = new ArrayList<>();
        out.add(this);
        while (true) {
            seqs.removeIf(List::isEmpty);
            if (seqs.isEmpty()) break;
            PyClass candidate = null;
            outer:
            for (List<PyClass> seq : seqs) {
                PyClass head = seq.get(0);
                boolean inTail = false;
                for (List<PyClass> other : seqs) {
                    if (other.size() > 1 && other.subList(1, other.size()).contains(head)) { inTail = true; break; }
                }
                if (!inTail) { candidate = head; break outer; }
            }
            if (candidate == null) throw new IllegalArgumentException("inconsistent method resolution order for class " + name);
            out.add(candidate);
            for (List<PyClass> seq : seqs) if (!seq.isEmpty() && seq.get(0) == candidate) seq.remove(0);
        }
        mro = List.copyOf(out);
    }
    @Override public String toString() { return "<class '" + (moduleName.equals("builtins") ? "" : moduleName + ".") + name + "'>"; }
}
