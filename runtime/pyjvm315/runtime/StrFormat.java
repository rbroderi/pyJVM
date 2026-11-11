// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;

import java.util.List;
import java.util.Map;

/** str.format / str.format_map: replacement fields with auto/manual numbering, attribute and index access, conversions and nested specs. */
final class StrFormat {
    private StrFormat() {}

    private static final class State {
        final List<Object> args; final Map<Object,Object> kwargs; int next = 0; int mode = 0; // mode: 0 unset, 1 auto, 2 manual
        State(List<Object> args, Map<Object,Object> kwargs) { this.args = args; this.kwargs = kwargs; }
    }

    static String format(String fmt, List<Object> args, Map<Object,Object> kwargs) { return render(fmt, new State(args, kwargs), 2); }

    private static String render(String fmt, State st, int depth) {
        if (depth < 0) throw new PyException("ValueError", "Max string recursion exceeded");
        StringBuilder out = new StringBuilder();
        int n = fmt.length();
        for (int i = 0; i < n; i++) {
            char c = fmt.charAt(i);
            if (c == '{') {
                if (i + 1 < n && fmt.charAt(i + 1) == '{') { out.append('{'); i++; continue; }
                int depthBraces = 1, j = i + 1;
                for (; j < n && depthBraces > 0; j++) { if (fmt.charAt(j) == '{') depthBraces++; else if (fmt.charAt(j) == '}') depthBraces--; }
                if (depthBraces != 0) throw new PyException("ValueError", "expected '}' before end of string");
                out.append(field(fmt.substring(i + 1, j - 1), st, depth));
                i = j - 1;
            } else if (c == '}') {
                if (i + 1 < n && fmt.charAt(i + 1) == '}') { out.append('}'); i++; continue; }
                throw new PyException("ValueError", "Single '}' encountered in format string");
            } else out.append(c);
        }
        return out.toString();
    }

    private static String field(String body, State st, int depth) {
        String spec = "", conv = null, name = body;
        int bang = -1, colon = -1, bracket = 0;
        for (int k = 0; k < body.length(); k++) {   // '!' and ':' count only outside [...] in the field name
            char c = body.charAt(k);
            if (c == '[') bracket++; else if (c == ']') bracket--;
            else if (bracket == 0 && c == '!' && bang < 0 && colon < 0) bang = k;
            else if (bracket == 0 && c == ':') { colon = k; break; }
        }
        if (colon >= 0) { spec = body.substring(colon + 1); name = body.substring(0, colon); }
        if (bang >= 0) { conv = name.substring(bang + 1); name = name.substring(0, bang); }
        Object value = resolve(name, st);
        if (conv != null) {
            value = switch (conv) { case "r" -> repr_(value); case "s" -> str_(value); case "a" -> repr_(value);
                default -> throw new PyException("ValueError", "Unknown conversion specifier " + conv); };
        }
        if (spec.indexOf('{') >= 0) spec = render(spec, st, depth - 1);
        return pyStr(formatValue(value, spec));
    }

    private static Object resolve(String name, State st) {
        int end = 0;
        while (end < name.length() && name.charAt(end) != '.' && name.charAt(end) != '[') end++;
        String first = name.substring(0, end);
        Object value;
        if (first.isEmpty() || first.chars().allMatch(Character::isDigit)) {
            int index;
            if (first.isEmpty()) {
                if (st.mode == 2) throw new PyException("ValueError", "cannot switch from manual field specification to automatic field numbering");
                st.mode = 1; index = st.next++;
            } else {
                if (st.mode == 1) throw new PyException("ValueError", "cannot switch from automatic field numbering to manual field specification");
                st.mode = 2; index = Integer.parseInt(first);
            }
            if (index >= st.args.size()) throw new PyException("IndexError", "Replacement index " + index + " out of range for positional args tuple");
            value = st.args.get(index);
        } else {
            if (!st.kwargs.containsKey(first)) throw new PyException("KeyError", first);
            value = st.kwargs.get(first);
        }
        int i = end;
        while (i < name.length()) {
            if (name.charAt(i) == '.') {
                int j = i + 1; while (j < name.length() && name.charAt(j) != '.' && name.charAt(j) != '[') j++;
                value = getattr(value, name.substring(i + 1, j)); i = j;
            } else if (name.charAt(i) == '[') {
                int j = name.indexOf(']', i);
                if (j < 0) throw new PyException("ValueError", "Missing ']' in format string");
                String key = name.substring(i + 1, j);
                value = getitem(value, key.chars().allMatch(Character::isDigit) && !key.isEmpty() ? (Object) Long.parseLong(key) : key);
                i = j + 1;
            } else throw new PyException("ValueError", "Only '.' or '[' may follow ']' in format field specifier");
        }
        return value;
    }
}
