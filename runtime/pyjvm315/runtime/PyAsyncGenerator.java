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

final class PyAsyncGenerator {
    final PyGenerator frame; final String displayName;
    PyAsyncGenerator(PyGenerator frame,String displayName){this.frame=frame;this.displayName=displayName;}
    Object anext(){return new PyAsyncGenAwaitable(this,"next",null);}
    Object asend(Object value){return new PyAsyncGenAwaitable(this,"send",value);}
    Object athrow(Object value){return new PyAsyncGenAwaitable(this,"throw",value);}
    Object aclose(){return new PyAsyncGenAwaitable(this,"close",null);}
    @Override public String toString(){return "<async_generator object "+displayName+">";}
}
