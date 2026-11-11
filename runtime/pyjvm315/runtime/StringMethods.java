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

/** Method-call slot for String receivers (split out of PyRuntime.callMethod). */
final class StringMethods {
    static final java.util.Set<String> NAMES = java.util.Set.of("__mod__", "upper", "lower", "casefold", "capitalize", "swapcase", "title",
        "strip", "lstrip", "rstrip", "startswith", "endswith", "removeprefix", "removesuffix", "replace", "split", "rsplit", "splitlines",
        "join", "find", "rfind", "index", "rindex", "count", "partition", "rpartition", "center", "ljust", "rjust", "zfill", "expandtabs",
        "translate", "isalpha", "isdecimal", "isdigit", "isnumeric", "isalnum", "isspace", "isascii", "isupper", "islower", "istitle",
        "isidentifier", "isprintable", "encode", "format", "format_map");
    /** Parameter names of methods that take keyword arguments (positional order). */
    static final java.util.Map<String, java.util.List<String>> PARAMS = java.util.Map.of(
        "encode", java.util.List.of("encoding", "errors"), "split", java.util.List.of("sep", "maxsplit"),
        "rsplit", java.util.List.of("sep", "maxsplit"), "splitlines", java.util.List.of("keepends"),
        "expandtabs", java.util.List.of("tabsize"), "replace", java.util.List.of("old", "new", "count"));

    /** Python's str.isspace() code points (Unicode White_Space plus the ASCII separators U+001C..U+001F). */
    static boolean isPySpace(int cp) {
        return (cp >= 0x09 && cp <= 0x0D) || (cp >= 0x1C && cp <= 0x20) || cp == 0x85 || cp == 0xA0 || cp == 0x1680
            || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }
    static Object stripCall(String str, String name, Object[] args) {
        if (args.length > 1) throw new PyException("TypeError", name + " expected at most 1 argument, got " + args.length);
        String chars = null;
        if (args.length == 1 && args[0] != null) {
            if (!(args[0] instanceof String c)) throw new PyException("TypeError", name + " arg must be None or str");
            chars = c;
        }
        int[] cps = str.codePoints().toArray();
        int start = 0, end = cps.length;
        boolean left = !name.equals("rstrip"), right = !name.equals("lstrip");
        while (left && start < end && strippable(cps[start], chars)) start++;
        while (right && end > start && strippable(cps[end - 1], chars)) end--;
        return new String(cps, start, end - start);
    }
    private static boolean strippable(int cp, String chars) { return chars == null ? isPySpace(cp) : chars.indexOf(cp) >= 0; }

    // ---- argument helpers --------------------------------------------------------------------------
    private static String strArg(String method, Object[] args, int i) {
        if (!(args[i] instanceof String s)) throw new PyException("TypeError", method + "() argument must be str, not " + PyRuntime.typeName(args[i]));
        return s;
    }
    private static int cpLen(String s) { return s.codePointCount(0, s.length()); }
    /** Slice bounds (start, end) in code points, clamped like Python's slice semantics. */
    private static int[] bounds(String s, Object[] args, int from) {
        int n = cpLen(s);
        long start = args.length > from && args[from] != null ? (long) PyRuntime.asIndex(args[from]) : 0;
        long end = args.length > from + 1 && args[from + 1] != null ? (long) PyRuntime.asIndex(args[from + 1]) : n;
        if (start < 0) start = Math.max(0, start + n);
        if (end < 0) end = Math.max(0, end + n);
        return new int[]{(int) Math.min(start, n + 1L), (int) Math.min(end, (long) n)};
    }
    private static int off(String s, int cp) { return s.offsetByCodePoints(0, cp); }
    private static long cpIndex(String s, int utf16) { return s.codePointCount(0, utf16); }

    private static Object search(String s, String name, Object[] args, boolean last, boolean raise) {
        if (args.length < 1 || args.length > 3) throw new PyException("TypeError", name + "() takes 1 to 3 arguments");
        String sub = strArg(name, args, 0);
        int[] b = bounds(s, args, 1);
        if (b[0] > cpLen(s)) { if (raise) throw new PyException("ValueError", "substring not found"); return -1L; }
        int from = off(s, b[0]), to = off(s, Math.max(b[0], b[1]));
        String window = s.substring(from, to);
        int idx = last ? window.lastIndexOf(sub) : window.indexOf(sub);
        if (idx < 0) { if (raise) throw new PyException("ValueError", "substring not found"); return -1L; }
        return cpIndex(s, from + idx);
    }

    private static Object affix(String s, String name, Object[] args, boolean prefix) {
        if (args.length < 1 || args.length > 3) throw new PyException("TypeError", name + " expected 1 to 3 arguments");
        int[] b = bounds(s, args, 1);
        if (b[0] > cpLen(s) || b[0] > b[1] && !(args[0] instanceof String e && e.isEmpty() && b[0] <= cpLen(s))) return false;
        String window = s.substring(off(s, b[0]), off(s, Math.max(b[0], b[1])));
        List<Object> options = args[0] instanceof PyTuple t ? t.items : List.of(args[0]);
        if (!(args[0] instanceof PyTuple) && !(args[0] instanceof String))
            throw new PyException("TypeError", name + " first arg must be str or a tuple of str, not " + PyRuntime.typeName(args[0]));
        for (Object o : options) {
            if (!(o instanceof String p)) throw new PyException("TypeError", "tuple for " + name + " must only contain str, not " + PyRuntime.typeName(o));
            if (prefix ? window.startsWith(p) : window.endsWith(p)) return true;
        }
        return false;
    }

    private static ArrayList<Object> split(String s, Object[] args, boolean right) {
        Object sepArg = args.length > 0 ? args[0] : null;
        long max = args.length > 1 ? (long) PyRuntime.asIndex(args[1]) : -1;
        if (sepArg != null && !(sepArg instanceof String)) throw new PyException("TypeError", "must be str or None, not " + PyRuntime.typeName(sepArg));
        ArrayList<Object> out = new ArrayList<>();
        if (sepArg == null) {
            int[] cps = s.codePoints().toArray();
            int n = cps.length;
            if (!right) {
                int i = 0;
                while (i < n) {
                    while (i < n && isPySpace(cps[i])) i++;
                    if (i >= n) break;
                    if (max >= 0 && out.size() >= max) { out.add(new String(cps, i, n - i)); return out; }
                    int j = i; while (j < n && !isPySpace(cps[j])) j++;
                    out.add(new String(cps, i, j - i)); i = j;
                }
            } else {
                int i = n;
                while (i > 0) {
                    while (i > 0 && isPySpace(cps[i - 1])) i--;
                    if (i <= 0) break;
                    if (max >= 0 && out.size() >= max) { out.add(new String(cps, 0, i)); break; }
                    int j = i; while (j > 0 && !isPySpace(cps[j - 1])) j--;
                    out.add(new String(cps, j, i - j)); i = j;
                }
                java.util.Collections.reverse(out);
            }
            return out;
        }
        String sep = (String) sepArg;
        if (sep.isEmpty()) throw new PyException("ValueError", "empty separator");
        if (!right) {
            int from = 0;
            while (max < 0 || out.size() < max) {
                int idx = s.indexOf(sep, from);
                if (idx < 0) break;
                out.add(s.substring(from, idx)); from = idx + sep.length();
            }
            out.add(s.substring(from));
        } else {
            int end = s.length();
            while (max < 0 || out.size() < max) {
                int idx = s.lastIndexOf(sep, end - sep.length());
                if (idx < 0 || end - sep.length() < 0) break;
                out.add(s.substring(idx + sep.length(), end)); end = idx;
            }
            out.add(s.substring(0, end));
            java.util.Collections.reverse(out);
        }
        return out;
    }

    private static boolean isCased(int cp) { return Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp); }
    private static boolean allChars(String s, java.util.function.IntPredicate p) { return !s.isEmpty() && s.codePoints().allMatch(p); }
    private static boolean isDigitCp(int cp) {
        return Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER || cp == 0xB2 || cp == 0xB3 || cp == 0xB9
            || (cp >= 0x2070 && cp <= 0x2079 && cp != 0x2071 && cp != 0x2072 && cp != 0x2073) || (cp >= 0x2080 && cp <= 0x2089) || (cp >= 0x2460 && cp <= 0x2468);
    }
    private static boolean isNumericCp(int cp) {
        int t = Character.getType(cp);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    private static String pad(String s, Object[] args, String name, int mode) {
        if (args.length < 1 || args.length > 2) throw new PyException("TypeError", name + "() takes 1 or 2 arguments");
        long width = (long) PyRuntime.asIndex(args[0]);
        String fill = args.length > 1 ? strArg(name, args, 1) : " ";
        if (cpLen(fill) != 1) throw new PyException("TypeError", "The fill character must be exactly one character long");
        long n = cpLen(s), total = width - n;
        if (total <= 0) return s;
        long left = mode == 0 ? 0 : mode == 1 ? total : total / 2 + (total & width & 1);
        if (mode == 2) left = total / 2 + (total & width & 1);
        return fill.repeat((int) left) + s + fill.repeat((int) (total - left));
    }

    private static String titleCase(String s) {
        StringBuilder out = new StringBuilder(); boolean prevCased = false;
        for (int cp : s.codePoints().toArray()) {
            if (prevCased) out.appendCodePoint(Character.toLowerCase(cp)); else out.appendCodePoint(Character.toTitleCase(cp));
            prevCased = isCased(cp);
        }
        return out.toString();
    }

    private static Object partition(String s, Object[] args, boolean right, String name) {
        PyRuntime.requireArgs(name, args, 1);
        String sep = strArg(name, args, 0);
        if (sep.isEmpty()) throw new PyException("ValueError", "empty separator");
        int idx = right ? s.lastIndexOf(sep) : s.indexOf(sep);
        PyTuple t = new PyTuple();
        if (idx < 0) { if (right) { t.items.add(""); t.items.add(""); t.items.add(s); } else { t.items.add(s); t.items.add(""); t.items.add(""); } return t; }
        t.items.add(s.substring(0, idx)); t.items.add(sep); t.items.add(s.substring(idx + sep.length()));
        return t;
    }

    /** str.encode with the error handlers the runtime and compiler rely on. */
    static PyBytes encode(String str, String encoding, String errors) {
        String enc = encoding.toLowerCase(Locale.ROOT).replace('_', '-');
        boolean be = enc.equals("utf-16-be") || enc.equals("utf-16be"), le = enc.equals("utf-16-le") || enc.equals("utf-16le");
        if (be || le) { // code units are emitted as they are; a lone surrogate needs the surrogatepass handler
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                boolean lone = Character.isHighSurrogate(c) ? !(i + 1 < str.length() && Character.isLowSurrogate(str.charAt(i + 1))) : Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(str.charAt(i - 1)));
                if (lone && !errors.equals("surrogatepass")) {
                    if (errors.equals("ignore")) continue;
                    if (errors.equals("replace")) c = '?'; else throw new PyException("UnicodeEncodeError", "'" + encoding + "' codec can't encode character '\\u" + Integer.toHexString(c) + "': surrogates not allowed");
                }
                if (be) { out.write(c >> 8); out.write(c & 0xFF); } else { out.write(c & 0xFF); out.write(c >> 8); }
            }
            return new PyBytes(out.toByteArray());
        }
        java.nio.charset.Charset cs = PyRuntime.pyCharset(encoding);
        java.nio.charset.CharsetEncoder encoder = cs.newEncoder();
        if (!errors.equals("strict")) return new PyBytes(str.getBytes(cs));
        try { java.nio.ByteBuffer b = encoder.encode(java.nio.CharBuffer.wrap(str)); byte[] data = new byte[b.remaining()]; b.get(data); return new PyBytes(data); }
        catch (java.nio.charset.CharacterCodingException e) { throw new PyException("UnicodeEncodeError", "'" + encoding + "' codec can't encode characters: " + e.getMessage()); }
    }

    static Object call(Object obj, String name, Object[] args) {
        String str=(String)obj;
        return switch(name) {
            case "__mod__" -> { requireArgs(name,args,1); yield new PercentFormatter(str,args[0]).run(); }
            case "upper" -> { requireArgs(name,args,0); yield str.toUpperCase(Locale.ROOT); }
            case "lower" -> { requireArgs(name,args,0); yield str.toLowerCase(Locale.ROOT); }
            case "casefold" -> { requireArgs(name,args,0); yield str.toLowerCase(Locale.ROOT).replace("\u00df", "ss"); }
            case "capitalize" -> { requireArgs(name,args,0); int[] c = str.codePoints().toArray(); StringBuilder sb = new StringBuilder();
                for (int i = 0; i < c.length; i++) sb.appendCodePoint(i == 0 ? Character.toTitleCase(c[i]) : Character.toLowerCase(c[i])); yield sb.toString(); }
            case "swapcase" -> { requireArgs(name,args,0); StringBuilder sb = new StringBuilder();
                for (int cp : str.codePoints().toArray()) sb.appendCodePoint(Character.isUpperCase(cp) ? Character.toLowerCase(cp) : Character.isLowerCase(cp) ? Character.toUpperCase(cp) : cp); yield sb.toString(); }
            case "title" -> { requireArgs(name,args,0); yield titleCase(str); }
            case "strip", "lstrip", "rstrip" -> stripCall(str,name,args);
            case "startswith" -> affix(str, name, args, true);
            case "endswith" -> affix(str, name, args, false);
            case "removeprefix" -> { requireArgs(name,args,1); String p = strArg(name,args,0); yield str.startsWith(p) ? str.substring(p.length()) : str; }
            case "removesuffix" -> { requireArgs(name,args,1); String p = strArg(name,args,0); yield !p.isEmpty() && str.endsWith(p) ? str.substring(0, str.length() - p.length()) : str; }
            case "replace" -> {
                if (args.length < 2 || args.length > 3) throw new PyException("TypeError", "replace expected 2 to 3 arguments");
                String old = strArg(name,args,0), repl = strArg(name,args,1);
                long count = args.length == 3 ? (long) PyRuntime.asIndex(args[2]) : -1;
                if (count < 0) yield str.replace(old, repl);
                StringBuilder sb = new StringBuilder(); int from = 0; long done = 0;
                if (old.isEmpty()) {
                    int[] c = str.codePoints().toArray();
                    for (int i = 0; i <= c.length; i++) { if (done < count) { sb.append(repl); done++; } if (i < c.length) sb.appendCodePoint(c[i]); }
                    yield sb.toString();
                }
                while (done < count) { int idx = str.indexOf(old, from); if (idx < 0) break; sb.append(str, from, idx).append(repl); from = idx + old.length(); done++; }
                yield sb.append(str.substring(from)).toString();
            }
            case "split" -> split(str, args, false);
            case "rsplit" -> split(str, args, true);
            case "splitlines" -> {
                boolean keep = args.length > 0 && PyRuntime.truth(args[0]);
                ArrayList<Object> out = new ArrayList<>(); int start = 0, n = str.length();
                for (int i = 0; i < n; ) {
                    char c = str.charAt(i); int len = 0;
                    if (c == '\r' && i + 1 < n && str.charAt(i + 1) == '\n') len = 2;
                    else if (c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || c == 0x1C || c == 0x1D || c == 0x1E || c == 0x85 || c == 0x2028 || c == 0x2029) len = 1;
                    if (len == 0) { i++; continue; }
                    out.add(str.substring(start, keep ? i + len : i)); i += len; start = i;
                }
                if (start < n) out.add(str.substring(start));
                yield out;
            }
            case "join" -> {
                requireArgs(name,args,1);
                StringBuilder sb = new StringBuilder(); int k = 0;
                for (Object x : iterable(args[0])) {
                    if (!(x instanceof String part)) throw new PyException("TypeError", "sequence item " + k + ": expected str instance, " + PyRuntime.typeName(x) + " found");
                    if (k++ > 0) sb.append(str);
                    sb.append(part);
                }
                yield sb.toString();
            }
            case "find" -> search(str, name, args, false, false);
            case "rfind" -> search(str, name, args, true, false);
            case "index" -> search(str, name, args, false, true);
            case "rindex" -> search(str, name, args, true, true);
            case "count" -> {
                if (args.length < 1 || args.length > 3) throw new PyException("TypeError", "count() takes 1 to 3 arguments");
                String sub = strArg(name,args,0); int[] b = bounds(str, args, 1);
                if (b[0] > cpLen(str)) yield 0L;
                String w = str.substring(off(str, b[0]), off(str, Math.max(b[0], b[1])));
                if (sub.isEmpty()) yield (long) (cpLen(w) + 1);
                long n = 0; for (int i = 0; (i = w.indexOf(sub, i)) >= 0; i += sub.length()) n++;
                yield n;
            }
            case "partition" -> partition(str, args, false, name);
            case "rpartition" -> partition(str, args, true, name);
            case "center" -> pad(str, args, name, 2);
            case "ljust" -> pad(str, args, name, 0);
            case "rjust" -> pad(str, args, name, 1);
            case "zfill" -> {
                requireArgs(name,args,1);
                long width = (long) PyRuntime.asIndex(args[0]), n = cpLen(str);
                if (width <= n) yield str;
                String sign = !str.isEmpty() && (str.charAt(0) == '+' || str.charAt(0) == '-') ? str.substring(0, 1) : "";
                yield sign + "0".repeat((int) (width - n)) + str.substring(sign.length());
            }
            case "expandtabs" -> {
                long tab = args.length > 0 ? (long) PyRuntime.asIndex(args[0]) : 8; StringBuilder sb = new StringBuilder(); long col = 0;
                for (int cp : str.codePoints().toArray()) {
                    if (cp == '\t') { if (tab > 0) { long fill = tab - col % tab; sb.append(" ".repeat((int) fill)); col += fill; } }
                    else { sb.appendCodePoint(cp); col = (cp == '\n' || cp == '\r') ? 0 : col + 1; }
                }
                yield sb.toString();
            }
            case "translate" -> {
                requireArgs(name,args,1); StringBuilder sb = new StringBuilder();
                for (int cp : str.codePoints().toArray()) {
                    Object mapped;
                    try { mapped = PyRuntime.getitem(args[0], (long) cp); }
                    catch (PyException e) { if (e.typeName.equals("LookupError") || e.typeName.equals("KeyError") || e.typeName.equals("IndexError")) { sb.appendCodePoint(cp); continue; } throw e; }
                    catch (RuntimeException e) { sb.appendCodePoint(cp); continue; }
                    if (mapped == null) continue;
                    if (mapped instanceof Long code) sb.appendCodePoint(code.intValue());
                    else if (mapped instanceof String t) sb.append(t);
                    else throw new PyException("TypeError", "character mapping must return integer, None or str");
                }
                yield sb.toString();
            }
            case "format" -> StrFormat.format(str, new ArrayList<Object>(Arrays.asList(args)), new LinkedHashMap<Object,Object>());
            case "format_map" -> { requireArgs(name,args,1); @SuppressWarnings("unchecked") Map<Object,Object> m=(Map<Object,Object>)args[0]; yield StrFormat.format(str, new ArrayList<Object>(), m); }
            case "isalpha" -> allChars(str, Character::isLetter);
            case "isdecimal" -> allChars(str, c -> Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER);
            case "isdigit" -> allChars(str, StringMethods::isDigitCp);
            case "isnumeric" -> allChars(str, StringMethods::isNumericCp);
            case "isalnum" -> allChars(str, c -> Character.isLetter(c) || isNumericCp(c) || isDigitCp(c));
            case "isspace" -> allChars(str, StringMethods::isPySpace);
            case "isascii" -> str.chars().allMatch(c -> c < 128);
            case "isupper" -> { boolean cased = false, ok = true; for (int cp : str.codePoints().toArray()) { if (Character.isLowerCase(cp) || Character.isTitleCase(cp)) ok = false; else if (Character.isUpperCase(cp)) cased = true; } yield cased && ok; }
            case "islower" -> { boolean cased = false, ok = true; for (int cp : str.codePoints().toArray()) { if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) ok = false; else if (Character.isLowerCase(cp)) cased = true; } yield cased && ok; }
            case "istitle" -> { boolean cased = false, prev = false, ok = true;
                for (int cp : str.codePoints().toArray()) { if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) { if (prev) ok = false; prev = true; cased = true; } else if (Character.isLowerCase(cp)) { if (!prev) ok = false; prev = true; cased = true; } else prev = false; }
                yield cased && ok; }
            case "isidentifier" -> { int[] c = str.codePoints().toArray(); boolean ok = c.length > 0 && (c[0] == '_' || Character.isUnicodeIdentifierStart(c[0]));
                for (int i = 1; ok && i < c.length; i++) ok = c[i] == '_' || Character.isUnicodeIdentifierPart(c[i]) && !Character.isIdentifierIgnorable(c[i]); yield ok; }
            case "isprintable" -> { boolean ok = true; for (int cp : str.codePoints().toArray()) { int t = Character.getType(cp); if (cp != ' ' && (t == Character.CONTROL || t == Character.FORMAT || t == Character.SURROGATE || t == Character.PRIVATE_USE || t == Character.UNASSIGNED || t == Character.SPACE_SEPARATOR || t == Character.LINE_SEPARATOR || t == Character.PARAGRAPH_SEPARATOR)) ok = false; } yield ok; }
            case "encode" -> {
                if(args.length>2) throw new PyException("TypeError","encode() takes at most 2 arguments");
                String enc=args.length==0||args[0]==null?"utf-8":(String)args[0];
                String errors=args.length<2||args[1]==null?"strict":(String)args[1];
                yield encode(str, enc, errors);
            }
            default -> throw new PyException("AttributeError","'str' object has no attribute '"+name+"'");
        };
    }
}
