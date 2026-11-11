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

final class PyInstanceMethods {
    static Object call(Object obj, String name, Object[] args) {
        PyInstance instance=(PyInstance)obj;
        if(instance.fields.containsKey(name))return callFunction(getattr(instance,name),new ArrayList<>(Arrays.asList(args)),new LinkedHashMap<>());
        PyMethod method = instance.cls.lookupMethod(name);
        if (method == null) {
            Object callable=getattr(instance,name);
            return callFunction(callable,new ArrayList<Object>(Arrays.asList(args)),new LinkedHashMap<Object,Object>());
        }
        return invoke(instance, method, args);
    }
}
