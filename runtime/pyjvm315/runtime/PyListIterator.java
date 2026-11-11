// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * {@code list_iterator}: index based like CPython's, so a list that is mutated while it is being iterated (by the
 * loop body or by another thread) is followed rather than failing with ConcurrentModificationException. Elements
 * appended meanwhile are seen; once the end is reached the iterator stays exhausted.
 */
final class PyListIterator extends PyLazyIterator {
    private ArrayList<Object> list;
    private int at;

    @SuppressWarnings("unchecked")
    PyListIterator(ArrayList<?> list) { this.list = (ArrayList<Object>) list; }

    @Override String pyTypeName() { return "list_iterator"; }

    @Override public boolean hasNext() {
        ArrayList<Object> l = list;
        if (l == null) return false;
        if (at < l.size()) return true;
        list = null;
        return false;
    }

    @Override public Object next() {
        ArrayList<Object> l = list;
        if (l == null) throw new NoSuchElementException();
        if (SyncSupport.MT) {
            synchronized (l) {
                if (at >= l.size()) { list = null; throw new NoSuchElementException(); }
                return l.get(at++);
            }
        }
        if (at >= l.size()) { list = null; throw new NoSuchElementException(); }
        return l.get(at++);
    }

    @Override Object compute() { return next(); }
}
