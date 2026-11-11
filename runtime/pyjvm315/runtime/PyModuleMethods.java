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

/** Method-call slot for PyModule receivers (split out of PyRuntime.callMethod). */
final class PyModuleMethods {
    static Object call(Object obj, String name, Object[] args) {
        PyModule module=(PyModule)obj;
        Object callable=moduleGetattr(module,name);
        ArrayList<Object> positional=new ArrayList<>(Arrays.asList(args));
        return callFunction(callable,positional,new LinkedHashMap<Object,Object>());
    }
}
