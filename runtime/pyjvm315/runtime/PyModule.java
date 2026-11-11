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

final class PyModule {
    final String name, className;
    final LinkedHashMap<String,Object> attrs = new LinkedHashMap<>();
    /** The live {@code __dict__} of a module created at run time (types.ModuleType(name), import system); null for compiled/native modules. */
    PyDict dict;
    /** Run once before the first read of one of these attribute names (lazily installed state such as sys.meta_path); null for most modules. */
    java.util.function.Consumer<String> lazyAttrs;
    PyModule(String name,String className){this.name=name;this.className=className;}
    @Override public String toString(){return ImportSupport.moduleRepr(this);}
}
