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

/** Method-call slot for Set<?> receivers (split out of PyRuntime.callMethod). */
final class SetMethods {
    static Object call(Object obj, String name, Object[] args) {
        if (!SyncSupport.MT) return call0(obj, name, args);
        synchronized (obj) { return call0(obj, name, args); }
    }

    private static Object call0(Object obj, String name, Object[] args) {
        Set<?> set=(Set<?>)obj;
        return setMethod(set,name,args);
    }
}
