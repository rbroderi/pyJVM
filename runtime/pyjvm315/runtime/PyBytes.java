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

final class PyBytes implements PyByteSequence {
    final byte[] data;
    PyBytes(byte[] data){this.data=Arrays.copyOf(data,data.length);}
    public int byteSize(){return data.length;}
    public int unsignedAt(int index){return data[index] & 0xff;}
    public byte[] toByteArray(){return Arrays.copyOf(data,data.length);}
    public Iterator<Object> iterator(){
        return new Iterator<>() {
            int i=0;
            public boolean hasNext(){return i<data.length;}
            public Object next(){if(!hasNext())throw new NoSuchElementException();return (long)(data[i++]&0xff);}
        };
    }
    @Override public boolean equals(Object other){return other instanceof PyByteSequence seq && Arrays.equals(data,seq.toByteArray());}
    @Override public int hashCode(){return Arrays.hashCode(data);}
    @Override public String toString(){return bytesRepr(data);}
}
