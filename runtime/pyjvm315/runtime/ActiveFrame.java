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

final class ActiveFrame {
    final String name, filename; final long firstlineno; long line;
    final LinkedHashMap<Object,Object> locals = new LinkedHashMap<>();
    boolean inspectSupported=true; String firstArgument; PyEnv classEnv,argumentEnv,closure; List<String> freeNames=List.of(); Map<Object,Object> namespace; Object owner;
    ActiveFrame(String name,String filename,long firstlineno){this.name=name;this.filename=filename;this.firstlineno=firstlineno;this.line=firstlineno;}
}
