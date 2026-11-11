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

final class PyByteArray implements PyByteSequence {
    final ArrayList<Byte> data=new ArrayList<>();
    PyByteArray(){}
    PyByteArray(byte[] raw){for(byte b:raw)data.add(b);}
    public int byteSize(){if(!SyncSupport.MT)return data.size();synchronized(this){return data.size();}}
    public int unsignedAt(int index){if(!SyncSupport.MT)return data.get(index)&0xff;synchronized(this){return data.get(index)&0xff;}}
    public byte[] toByteArray(){if(!SyncSupport.MT)return toByteArray0();synchronized(this){return toByteArray0();}}
    private byte[] toByteArray0(){byte[] out=new byte[data.size()];for(int i=0;i<data.size();i++)out[i]=data.get(i);return out;}
    void setUnsigned(int index,int value){checkByte(value);if(!SyncSupport.MT){data.set(index,(byte)value);return;}synchronized(this){data.set(index,(byte)value);}}
    void appendUnsigned(int value){checkByte(value);if(!SyncSupport.MT){data.add((byte)value);return;}synchronized(this){data.add((byte)value);}}
    public Iterator<Object> iterator(){
        // index based, like CPython: bytes appended during iteration are seen, removals end the loop early
        return new Iterator<>() {
            int at=0;
            public boolean hasNext(){return at<byteSize();}
            public Object next(){
                synchronized(PyByteArray.this){
                    if(at>=data.size())throw new NoSuchElementException();
                    return (long)(data.get(at++)&0xff);
                }
            }
        };
    }
    @Override public boolean equals(Object other){return other instanceof PyByteSequence seq && Arrays.equals(toByteArray(),seq.toByteArray());}
    @Override public int hashCode(){return Arrays.hashCode(toByteArray());}
    @Override public String toString(){return "bytearray("+bytesRepr(toByteArray())+")";}
}
