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
    final LinkedHashMap<String,PyMethod> methods = new SyncMap<>();
    final LinkedHashMap<String,PyProperty> properties = new SyncMap<>();
    final LinkedHashMap<String,Object> attrs = new SyncMap<>();
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
    /** 0 unknown, 1 no, 2 yes: instances keep a separate __dict__ per thread (derives from _thread._local). */
    private byte localKind;
    boolean threadLocalKind() {
        byte k = localKind;
        if (k == 0) {
            k = 1;
            for (PyClass c : mro) if (c == ThreadModule.localClass()) { k = 2; break; }
            if (finalized) localKind = k;
        }
        return k == 2;
    }
    PyClass(String moduleName, String name) { this.moduleName=moduleName; this.name = name; this.qualname=name; }

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
    @Override public String toString() { return "<class '" + moduleName + "." + name + "'>"; }
}
