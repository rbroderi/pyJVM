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

final class PyTuple implements Iterable<Object> {
    final ArrayList<Object> items = new ArrayList<>();
    @Override public Iterator<Object> iterator() { return items.iterator(); }
    public String pyRepr() {
        String body = seqRepr(items, "(", ")");
        if (items.size() == 1) return body.substring(0, body.length() - 1) + ",)";
        return body;
    }
    @Override public boolean equals(Object other) { return other instanceof PyTuple t && items.equals(t.items); }
    @Override public int hashCode() { return items.hashCode(); }
}
