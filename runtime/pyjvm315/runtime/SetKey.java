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

final class SetKey {
    final Object value;final long hash;
    SetKey(Object value){this.value=value;hash=((Number)PyRuntime.hash(value)).longValue();}
    @Override public int hashCode(){return Long.hashCode(hash);}
    @Override public boolean equals(Object other){return other instanceof SetKey key && hash==key.hash && (value==key.value || truth(eq(value,key.value)));}
}
