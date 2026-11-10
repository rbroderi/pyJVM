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

final class PyAsyncGenAwaitable {
    final PyAsyncGenerator generator; final String action; final Object value;
    PyAsyncGenAwaitable(PyAsyncGenerator generator,String action,Object value){this.generator=generator;this.action=action;this.value=value;}
    Object iterator(){return new PyAsyncGenAwaitIterator(this);}
}
