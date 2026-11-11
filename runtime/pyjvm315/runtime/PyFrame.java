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

final class PyFrame {
    final PyCode code; final LinkedHashMap<Object,Object> locals;
    PyFrame(PyCode code){this(code,new LinkedHashMap<Object,Object>());}
    PyFrame(PyCode code,LinkedHashMap<Object,Object> locals){this.code=code;this.locals=locals;}
    /** Live frame: line and locals come from the active frame; {@code back} is set when the frame chain is captured. */
    ActiveFrame active; PyFrame back; long fixedLine=-1; Object trace;
    long lineno(){return active!=null?active.line:fixedLine>=0?fixedLine:code.coFirstlineno;}
    @Override public String toString(){return "<frame "+code.coName+">";}
}
