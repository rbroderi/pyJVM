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

/** Method-call slot for PyComplex receivers (split out of PyRuntime.callMethod). */
final class PyComplexMethods {
    static Object call(Object obj, String name, Object[] args) {
        PyComplex z=(PyComplex)obj;
        requireArgs(name,args,0);
        return switch(name){
            case "conjugate" -> new PyComplex(z.real,-z.imag);
            case "__complex__", "__pos__" -> z;
            case "__neg__" -> neg(z);
            case "__abs__" -> abs(z);
            case "__getnewargs__" -> {PyTuple tuple=new PyTuple();tuple.items.add(z.real);tuple.items.add(z.imag);yield tuple;}
            default -> throw new PyException("AttributeError","complex has no attribute '"+name+"'");
        };
    }
}
