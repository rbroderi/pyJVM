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

final class PyManagedProperty {
    final Object getter,setter,deleter; Object doc; String name; boolean getterDoc;
    PyManagedProperty(Object getter,Object setter,Object deleter,Object doc){
        this.getter=getter;this.setter=setter;this.deleter=deleter;this.doc=doc;
        this.getterDoc=doc==null;
        if(doc==null && getter!=null){try{this.doc=getattr(getter,"__doc__");}catch(PyException error){if(!error.typeName.equals("AttributeError"))throw error;}}
    }
}
