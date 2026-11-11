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

final class PyExceptionValue {
    final String typeName; final Object value; SyntaxInfo syntax; final Object cause; final Object context; final boolean suppressContext; final PyTraceback traceback;
    PyExceptionValue(String typeName, Object value) { this(typeName,value,null,null,false,null); }
    PyExceptionValue(String typeName, Object value, Object cause, Object context, boolean suppressContext) { this(typeName,value,cause,context,suppressContext,null); }
    PyExceptionValue(String typeName, Object value, Object cause, Object context, boolean suppressContext, PyTraceback traceback) { this.typeName=typeName; this.value=value; this.cause=cause; this.context=context; this.suppressContext=suppressContext; this.traceback=traceback; }
    @Override public String toString() { return typeName + (value == null ? "" : "(" + pyRepr(value) + ")"); }
}
