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

final class PyMemoryView implements PyByteSequence {
    final PyByteSequence source;
    final int start, length;
    PyMemoryView(PyByteSequence source){this(source,0,source.byteSize());}
    PyMemoryView(PyByteSequence source,int start,int length){this.source=source;this.start=start;this.length=length;}
    public int byteSize(){return length;}
    public int unsignedAt(int index){return source.unsignedAt(start+index);}
    public byte[] toByteArray(){byte[] out=new byte[length];for(int i=0;i<length;i++)out[i]=(byte)unsignedAt(i);return out;}
    void setUnsigned(int index,int value){
        if(!(source instanceof PyByteArray array)) throw new PyException("TypeError","cannot modify read-only memory");
        array.setUnsigned(start+index,value);
    }
    boolean readonly(){return !(source instanceof PyByteArray);}
    public Iterator<Object> iterator(){
        return new Iterator<>() {
            int i=0;
            public boolean hasNext(){return i<length;}
            public Object next(){if(!hasNext())throw new NoSuchElementException();return (long)unsignedAt(i++);}
        };
    }
    @Override public String toString(){return "<memory at 0x0>";}
}
