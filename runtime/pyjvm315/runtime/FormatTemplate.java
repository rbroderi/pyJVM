// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * str.format / str.format_map and the C helpers behind string.Formatter: a port of CPython's
 * MarkupIterator and field-name splitter (Objects/stringlib/unicode_format.h).
 */
final class FormatTemplate {
    private FormatTemplate() {}

    /** One parsed chunk: literal text, then an optional replacement field. */
    static final class Chunk {
        String literal, fieldName, spec, conversion;
        boolean hasField;
    }

    /** Scanner over the template, yielding chunks exactly like MarkupIterator_next. */
    static final class Markup {
        final String s; int pos = 0;
        Markup(String s) { this.s = s; }

        /** Next chunk or null at the end. */
        Chunk next() {
            int n = s.length();
            if (pos >= n) return null;
            int start = pos;
            char c = 0;
            boolean markup = false;
            while (pos < n) {
                c = s.charAt(pos++);
                if (c == '{' || c == '}') { markup = true; break; }
            }
            boolean atEnd = pos >= n;
            int len = pos - start;
            if (c == '}' && markup && (atEnd || s.charAt(pos) != '}')) throw new PyException("ValueError", "Single '}' encountered in format string");
            if (atEnd && c == '{' && markup) throw new PyException("ValueError", "Single '{' encountered in format string");
            if (!atEnd) {
                if (s.charAt(pos) == c) { pos++; markup = false; } else len--;
            }
            Chunk chunk = new Chunk();
            chunk.literal = s.substring(start, start + len);
            if (markup) parseField(chunk);
            return chunk;
        }

        private void parseField(Chunk chunk) {
            chunk.hasField = true;
            int n = s.length(), nameStart = pos;
            char c = 0;
            while (pos < n) {
                c = s.charAt(pos++);
                if (c == '{') throw new PyException("ValueError", "unexpected '{' in field name");
                if (c == '[') { while (pos < n && s.charAt(pos) != ']') pos++; continue; }
                if (c == '}' || c == ':' || c == '!') break;
            }
            chunk.fieldName = s.substring(nameStart, Math.max(nameStart, pos - 1));
            chunk.spec = ""; chunk.conversion = null;
            if (c == '!' || c == ':') {
                if (c == '!') {
                    if (pos >= n) throw new PyException("ValueError", "end of string while looking for conversion specifier");
                    chunk.conversion = String.valueOf(s.charAt(pos++));
                    if (pos < n) {
                        c = s.charAt(pos++);
                        if (c == '}') return;
                        if (c != ':') throw new PyException("ValueError", "expected ':' after conversion specifier");
                    }
                }
                int specStart = pos, count = 1;
                while (pos < n) {
                    c = s.charAt(pos++);
                    if (c == '{') count++;
                    else if (c == '}' && --count == 0) { chunk.spec = s.substring(specStart, pos - 1); return; }
                }
                throw new PyException("ValueError", "unmatched '{' in format spec");
            }
            if (c != '}') throw new PyException("ValueError", "expected '}' before end of string");
        }
    }

    // ------------------------------------------------------------------ rendering

    private static final class State {
        final List<Object> args; final Object kwargs; int next = 0, mode = 0; // mode 1 auto, 2 manual
        State(List<Object> args, Object kwargs) { this.args = args; this.kwargs = kwargs; }
    }

    static String format(String fmt, List<Object> args, Map<Object, Object> kwargs) { return render(fmt, new State(args, kwargs), 2); }

    /** str.format_map: {@code mapping} is any object supporting __getitem__; positional fields are an error. */
    static String formatMap(String fmt, Object mapping) { return render(fmt, new State(null, mapping), 2); }

    private static String render(String fmt, State st, int depth) {
        if (depth <= 0) throw new PyException("ValueError", "Max string recursion exceeded");
        StringBuilder out = new StringBuilder();
        Markup it = new Markup(fmt);
        for (Chunk c = it.next(); c != null; c = it.next()) {
            out.append(c.literal);
            if (!c.hasField) continue;
            Object value = resolve(c.fieldName, st);
            if (c.conversion != null) value = convert(value, c.conversion.charAt(0));
            String spec = c.spec.indexOf('{') >= 0 ? render(c.spec, st, depth - 1) : c.spec;
            out.append(pyStr(formatValue(value, spec)));
        }
        return out.toString();
    }

    static Object convert(Object value, char conv) {
        return switch (conv) {
            case 'r' -> pyRepr(value);
            case 's' -> pyStr(value);
            case 'a' -> asciiRepr(value);
            default -> throw new PyException("ValueError", conv > 32 && conv < 127
                ? "Unknown conversion specifier " + conv : "Unknown conversion specifier \\x" + Integer.toHexString(conv));
        };
    }

    // ------------------------------------------------------------------ field names

    /** Split "first.attr[idx]..." : returns the first part; (isAttr, text) pairs of the rest are added to {@code rest}. */
    static String splitFieldName(String name, List<Object[]> rest) {
        int n = name.length(), i = 0;
        while (i < n && name.charAt(i) != '.' && name.charAt(i) != '[') i++;
        String first = name.substring(0, i);
        while (i < n) {
            char ch = name.charAt(i++);
            int j = i;
            boolean attr = ch == '.';
            if (attr) {
                while (j < n && name.charAt(j) != '.' && name.charAt(j) != '[') j++;
            } else if (ch == '[') {
                while (j < n && name.charAt(j) != ']') j++;
                if (j >= n) throw new PyException("ValueError", "Missing ']' in format string");
            } else throw new PyException("ValueError", "Only '.' or '[' may follow ']' in format field specifier");
            if (j == i) throw new PyException("ValueError", "Empty attribute in format string");
            rest.add(new Object[]{attr, name.substring(i, j)});
            i = attr ? j : j + 1;
        }
        return first;
    }

    /** Index for an all-decimal-digit text, -1 for anything else; absurdly long numbers raise like CPython. */
    static long fieldIndex(String text) {
        if (text.isEmpty()) return -1;
        long v = 0;
        for (int i = 0; i < text.length(); i++) {
            int d = Character.digit(text.charAt(i), 10);
            if (d < 0) return -1;
            if (v > (Long.MAX_VALUE - d) / 10) throw new PyException("ValueError", "Too many decimal digits in format string");
            v = v * 10 + d;
        }
        return v;
    }

    private static Object resolve(String name, State st) {
        List<Object[]> rest = new ArrayList<>();
        String first = splitFieldName(name, rest);
        long index = fieldIndex(first);
        boolean empty = first.isEmpty();
        Object value;
        if (empty || index >= 0) {
            if (st.mode == 0) st.mode = empty ? 1 : 2;
            if (empty && st.mode == 2) throw new PyException("ValueError", "cannot switch from manual field specification to automatic field numbering");
            if (!empty && st.mode == 1) throw new PyException("ValueError", "cannot switch from automatic field numbering to manual field specification");
            if (empty) index = st.next++;
            if (st.args == null) throw new PyException("ValueError", "Format string contains positional fields");
            if (index >= st.args.size())
                throw new PyException("IndexError", "Replacement index " + index + " out of range for positional args tuple");
            value = st.args.get((int) index);
        } else if (st.kwargs instanceof Map<?, ?> m) {
            if (!m.containsKey(first)) throw new PyException("KeyError", first);
            value = m.get(first);
        } else value = getitem(st.kwargs, first);
        for (Object[] part : rest) {
            String text = (String) part[1];
            if ((Boolean) part[0]) { value = getattr(value, text); continue; }
            long k = fieldIndex(text);
            if (k >= 0 && NativeSubclass.unwrap(value) instanceof Map)
                throw new PyException("TypeError", "'" + typeName(value) + "' object does not support indexing");
            value = getitem(value, k >= 0 ? (Object) k : text);
        }
        return value;
    }

    // ------------------------------------------------------------------ _string helpers

    /** _string.formatter_parser(s): list of (literal, field_name, format_spec, conversion) tuples. */
    static List<Object> parserTuples(String s) {
        List<Object> out = new ArrayList<>();
        Markup it = new Markup(s);
        for (Chunk c = it.next(); c != null; c = it.next()) {
            PyTuple t = new PyTuple();
            t.items.add(c.literal);
            t.items.add(c.hasField ? c.fieldName : null);
            t.items.add(c.hasField ? c.spec : null);
            t.items.add(c.hasField ? c.conversion : null);
            out.add(t);
        }
        return out;
    }

    /** _string.formatter_field_name_split(s): (first, iterator of (is_attr, name_or_index)). */
    static PyTuple fieldNameSplit(String name) {
        List<Object[]> rest = new ArrayList<>();
        String first = splitFieldName(name, rest);
        List<Object> items = new ArrayList<>();
        for (Object[] part : rest) {
            PyTuple t = new PyTuple();
            t.items.add(part[0]);
            long k = (Boolean) part[0] ? -1 : fieldIndex((String) part[1]);
            t.items.add(k >= 0 ? (Object) k : part[1]);
            items.add(t);
        }
        PyTuple result = new PyTuple();
        long k = fieldIndex(first);
        result.items.add(k >= 0 ? (Object) k : first);
        result.items.add(iter(items));
        return result;
    }
}
