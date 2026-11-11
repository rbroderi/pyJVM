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

final class PyException extends RuntimeException {
    final String typeName; final Object value; Object instance; Object tracedFrame; SyntaxInfo syntax; Object cause; Object context; boolean suppressContext; PyTraceback traceback;
    PyException(String typeName, Object value) { this(typeName,value,null,null,false,null); }
    PyException(String typeName, Object value, Object cause, Object context, boolean suppressContext) { this(typeName,value,cause,context,suppressContext,null); }
    PyException(String typeName, Object value, Object cause, Object context, boolean suppressContext, PyTraceback traceback) { super(value == null ? null : pyStr(value)); this.typeName=typeName; this.value=value; this.cause=cause; this.context=context; this.suppressContext=suppressContext; this.traceback=traceback; }
    void addFrame(String name,String filename,long firstline,long line){traceback=prependTraceback(traceback,name,filename,firstline,line);}
    void addFrame(String name,String filename,long line){addFrame(name,filename,line,line);}
    @Override public String toString() { return typeName + (value == null ? "" : ": " + pyStr(value)); }
}
