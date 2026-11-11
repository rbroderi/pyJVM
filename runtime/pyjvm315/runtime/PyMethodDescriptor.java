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

final class PyMethodDescriptor {
    final Object function; final String kind;
    final LinkedHashMap<Object,Object> attrs=new LinkedHashMap<>();
    PyMethodDescriptor(Object function,String kind){
        this.function=function;this.kind=kind;
        for(String name:List.of("__module__","__name__","__qualname__","__doc__")){
            try{attrs.put(name,getattr(function,name));}catch(PyException error){if(!error.typeName.equals("AttributeError"))throw error;}
        }
    }
}
