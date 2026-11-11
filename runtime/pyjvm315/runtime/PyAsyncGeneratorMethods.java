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

/** Method-call slot for PyAsyncGenerator receivers (split out of PyRuntime.callMethod). */
final class PyAsyncGeneratorMethods {
    static Object call(Object obj, String name, Object[] args) {
        PyAsyncGenerator generator=(PyAsyncGenerator)obj;
        return switch(name) {
            case "__aiter__" -> { requireArgs(name,args,0); yield generator; }
            case "__anext__" -> { requireArgs(name,args,0); yield generator.anext(); }
            case "asend" -> { requireArgs(name,args,1); yield generator.asend(args[0]); }
            case "athrow" -> { requireArgs(name,args,1); yield generator.athrow(args[0]); }
            case "aclose" -> { requireArgs(name,args,0); yield generator.aclose(); }
            default -> throw new PyException("AttributeError","'async_generator' object has no attribute '"+name+"'");
        };
    }
}
