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

/** Method-call slot for PyGenerator receivers (split out of PyRuntime.callMethod). */
final class PyGeneratorMethods {
    static Object call(Object obj, String name, Object[] args) {
        PyGenerator gen=(PyGenerator)obj;
        return switch(name) {
            case "send" -> { requireArgs(name,args,1); yield gen.send(args[0]); }
            case "throw" -> { requireArgs(name,args,1); yield gen.throw_(args[0]); }
            case "close" -> { requireArgs(name,args,0); yield gen.close(); }
            case "__iter__" -> { requireArgs(name,args,0); yield gen; }
            case "__next__" -> { requireArgs(name,args,0); yield next_(gen); }
            default -> throw new PyException("AttributeError","'generator' object has no attribute '"+name+"'");
        };
    }
}
