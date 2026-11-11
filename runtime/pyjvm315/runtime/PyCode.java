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

final class PyCode {
    final String coName, coFilename; final long coFirstlineno;
    /** Class file for code created by compile(); null for function code objects. */
    byte[] classBytes; String mode="exec";
    PyCode(String name,String filename,long firstlineno){this.coName=name;this.coFilename=filename;this.coFirstlineno=firstlineno;}
    @Override public String toString(){return "<code object "+coName+">";}
}
