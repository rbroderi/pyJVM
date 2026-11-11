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

class PyInstance {
    final PyClass cls;
    final LinkedHashMap<String,Object> fields = new LinkedHashMap<>();
    final LinkedHashMap<String,Object> slotValues = new LinkedHashMap<>();
    /** Native value of a builtin-backed instance (class derives from int/str/list/dict/...): BigInteger/Long, String, ArrayList, PyDict, ...; null for plain instances. */
    Object payload;
    /** __cause__/__context__/__traceback__ of an exception instance; null for every other object. */
    ExcState exc;
    PyInstance(PyClass cls) { this.cls = cls; }
    @Override public String toString() { return "<" + cls.name + " object>"; }
}
