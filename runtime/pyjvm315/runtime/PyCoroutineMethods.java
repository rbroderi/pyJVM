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

/** Method-call slot for PyCoroutine receivers (split out of PyRuntime.callMethod). */
final class PyCoroutineMethods {
    static Object call(Object obj, String name, Object[] args) {
        PyCoroutine coroutine=(PyCoroutine)obj;
        return switch(name) {
            case "send" -> { requireArgs(name,args,1); yield coroutine.send(args[0]); }
            case "throw" -> { requireArgs(name,args,1); yield coroutine.throw_(args[0]); }
            case "close" -> { requireArgs(name,args,0); yield coroutine.close(); }
            case "__await__" -> { requireArgs(name,args,0); yield coroutine.awaitIterator(); }
            default -> throw new PyException("AttributeError","'coroutine' object has no attribute '"+name+"'");
        };
    }
}
