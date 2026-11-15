// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The bytes/bytearray methods shared by both types that are not in their own method slots: rsplit, splitlines,
 * removeprefix/removesuffix, the padding methods and the ASCII predicates (CPython's Objects/bytes_methods.c).
 */
final class BinaryMethods {
    static final Set<String> NAMES = Set.of("rsplit", "splitlines", "removeprefix", "removesuffix", "center", "ljust", "rjust",
        "zfill", "expandtabs", "isalnum", "isalpha", "isascii", "isdigit", "islower", "isspace", "istitle", "isupper");
    /** Space-separated, for NativeSubclassMethods. */
    static final String NAMES_TEXT = String.join(" ", new java.util.TreeSet<>(NAMES));
    /** Parameter names of the bytes/bytearray methods that accept keyword arguments. */
    static final java.util.Map<String, List<String>> PARAMS = java.util.Map.of(
        "decode", List.of("encoding", "errors"), "split", List.of("sep", "maxsplit"), "rsplit", List.of("sep", "maxsplit"),
        "splitlines", List.of("keepends"), "expandtabs", List.of("tabsize"));

    private BinaryMethods() {}

    static Object call(PyByteSequence seq, String name, Object[] args) {
        return switch (name) {
            case "rsplit" -> rsplit(seq, args);
            case "splitlines" -> splitlines(seq, args);
            case "removeprefix" -> remove(seq, name, args, true);
            case "removesuffix" -> remove(seq, name, args, false);
            case "center" -> pad(seq, name, args, 0);
            case "ljust" -> pad(seq, name, args, -1);
            case "rjust" -> pad(seq, name, args, 1);
            case "zfill" -> zfill(seq, args);
            case "expandtabs" -> expandtabs(seq, args);
            case "isalnum" -> predicate(seq, name, args, v -> asciiAlpha(v) || digit(v));
            case "isalpha" -> predicate(seq, name, args, PyRuntime::asciiAlpha);
            case "isdigit" -> predicate(seq, name, args, BinaryMethods::digit);
            case "isspace" -> predicate(seq, name, args, PyRuntime::asciiWhitespace);
            case "isascii" -> { noArgs(seq, name, args); for (byte b : seq.toByteArray()) if (b < 0) yield false; yield true; }
            case "islower" -> cased(seq, name, args, false);
            case "isupper" -> cased(seq, name, args, true);
            case "istitle" -> istitle(seq, args);
            default -> throw new PyException("AttributeError", "'" + typeName(seq) + "' object has no attribute '" + name + "'");
        };
    }

    private static boolean digit(int v) { return v >= '0' && v <= '9'; }
    private static boolean upper(int v) { return v >= 'A' && v <= 'Z'; }
    private static boolean lower(int v) { return v >= 'a' && v <= 'z'; }

    private static void noArgs(PyByteSequence seq, String name, Object[] args) {
        if (args.length != 0) throw new PyException("TypeError", typeName(seq) + "." + name + "() takes no arguments (" + args.length + " given)");
    }

    private static Object predicate(PyByteSequence seq, String name, Object[] args, java.util.function.IntPredicate p) {
        if (args.length != 0) throw new PyException("TypeError", typeName(seq) + "." + name + "() takes no arguments (" + args.length + " given)");
        byte[] src = seq.toByteArray();
        if (src.length == 0) return false;
        for (byte b : src) if (!p.test(b & 0xff)) return false;
        return true;
    }

    /** islower/isupper: at least one cased byte and none of the other case. */
    private static Object cased(PyByteSequence seq, String name, Object[] args, boolean wantUpper) {
        if (args.length != 0) throw new PyException("TypeError", typeName(seq) + "." + name + "() takes no arguments (" + args.length + " given)");
        boolean any = false;
        for (byte b : seq.toByteArray()) {
            int v = b & 0xff;
            if (wantUpper ? lower(v) : upper(v)) return false;
            if (wantUpper ? upper(v) : lower(v)) any = true;
        }
        return any;
    }

    private static Object istitle(PyByteSequence seq, Object[] args) {
        if (args.length != 0) throw new PyException("TypeError", typeName(seq) + ".istitle() takes no arguments (" + args.length + " given)");
        boolean cased = false, previousCased = false;
        for (byte b : seq.toByteArray()) {
            int v = b & 0xff;
            if (upper(v)) { if (previousCased) return false; previousCased = true; cased = true; }
            else if (lower(v)) { if (!previousCased) return false; previousCased = true; cased = true; }
            else previousCased = false;
        }
        return cased;
    }

    private static byte[] slice(byte[] src, int from, int to) { return Arrays.copyOfRange(src, from, to); }

    /** rsplit(sep=None, maxsplit=-1): splits from the right; the result is in left-to-right order. */
    private static Object rsplit(PyByteSequence seq, Object[] args) {
        if (args.length > 2) throw new PyException("TypeError", "rsplit() takes at most 2 arguments (" + args.length + " given)");
        byte[] src = seq.toByteArray();
        Object sepObj = args.length >= 1 ? args[0] : null;
        int maxsplit = args.length == 2 ? asIndex(args[1]) : -1;
        ArrayList<Object> out = new ArrayList<>();
        if (sepObj == null) {
            int i = src.length, splits = 0;
            while (i > 0) {
                while (i > 0 && asciiWhitespace(src[i - 1] & 0xff)) i--;
                if (i == 0) break;
                int end = i;
                if (maxsplit >= 0 && splits >= maxsplit) i = 0;
                else { while (i > 0 && !asciiWhitespace(src[i - 1] & 0xff)) i--; splits++; }
                // once maxsplit is reached the rest, leading whitespace included, is one piece (like CPython)
                out.add(binaryResult(seq, slice(src, i, end)));
            }
            Collections.reverse(out);
            return out;
        }
        byte[] sep = requireBytesLike(sepObj);
        if (sep.length == 0) throw new PyException("ValueError", "empty separator");
        int end = src.length, splits = 0;
        while (maxsplit < 0 || splits < maxsplit) {
            int found = binaryLastIndexOf(src, sep, 0, end);
            if (found < 0) break;
            out.add(binaryResult(seq, slice(src, found + sep.length, end)));
            end = found;
            splits++;
        }
        out.add(binaryResult(seq, slice(src, 0, end)));
        Collections.reverse(out);
        return out;
    }

    /** splitlines(keepends=False): the line boundaries of bytes are \n, \r and \r\n only. */
    private static Object splitlines(PyByteSequence seq, Object[] args) {
        if (args.length > 1) throw new PyException("TypeError", "splitlines() takes at most 1 argument (" + args.length + " given)");
        boolean keepends = args.length == 1 && args[0] != null && truth(args[0]);
        byte[] src = seq.toByteArray();
        ArrayList<Object> out = new ArrayList<>();
        int i = 0;
        while (i < src.length) {
            int start = i;
            while (i < src.length && src[i] != '\n' && src[i] != '\r') i++;
            int eol = i;
            if (i < src.length) {
                if (src[i] == '\r' && i + 1 < src.length && src[i + 1] == '\n') i += 2; else i++;
                if (keepends) eol = i;
            }
            out.add(binaryResult(seq, slice(src, start, eol)));
        }
        return out;
    }

    private static Object remove(PyByteSequence seq, String name, Object[] args, boolean prefix) {
        if (args.length != 1) throw new PyException("TypeError", name + "() takes exactly one argument (" + args.length + " given)");
        byte[] src = seq.toByteArray(), affix = requireBytesLike(args[0]);
        if (affix.length <= src.length) {
            int at = prefix ? 0 : src.length - affix.length;
            boolean match = true;
            for (int i = 0; i < affix.length && match; i++) match = src[at + i] == affix[i];
            if (match && affix.length > 0) return binaryResult(seq, prefix ? slice(src, affix.length, src.length) : slice(src, 0, at));
        }
        return seq instanceof PyBytes ? seq : binaryResult(seq, src);
    }

    /** center (mode 0), ljust (-1), rjust (1) with an optional single-byte fill. */
    private static Object pad(PyByteSequence seq, String name, Object[] args, int mode) {
        if (args.length < 1 || args.length > 2)
            throw new PyException("TypeError", name + " expected " + (args.length < 1 ? "at least 1 argument" : "at most 2 arguments") + ", got " + args.length);
        int width = asIndex(args[0]);
        byte fill = ' ';
        if (args.length == 2) {
            Object f = args[1];
            if (!(f instanceof PyBytes || f instanceof PyByteArray))
                throw new PyException("TypeError", name + "() argument 2 must be a byte string of length 1, not " + typeName(f));
            // checked against CPython 3.13 (old text) and 3.15 (new text); 3.14 is not a supported lane
            int n = ((PyByteSequence) f).toByteArray().length;
            if (n != 1) throw new PyException("TypeError", LangVersion.MINOR >= 14
                ? name + "(): argument 2 must be a byte string of length 1, not a " + typeName(f) + " object of length " + n
                : name + "() argument 2 must be a byte string of length 1, not " + typeName(f));
            fill = ((PyByteSequence) f).toByteArray()[0];
        }
        byte[] src = seq.toByteArray();
        if (width <= src.length) return seq instanceof PyBytes ? seq : binaryResult(seq, src);
        int marg = width - src.length, left = switch (mode) {
            case 0 -> marg / 2 + (marg & width & 1);
            case -1 -> 0;
            default -> marg;
        };
        byte[] out = new byte[width];
        Arrays.fill(out, fill);
        System.arraycopy(src, 0, out, left, src.length);
        return binaryResult(seq, out);
    }

    private static Object zfill(PyByteSequence seq, Object[] args) {
        if (args.length != 1) throw new PyException("TypeError", "zfill() takes exactly one argument (" + args.length + " given)");
        int width = asIndex(args[0]);
        byte[] src = seq.toByteArray();
        if (width <= src.length) return seq instanceof PyBytes ? seq : binaryResult(seq, src);
        int fill = width - src.length;
        byte[] out = new byte[width];
        Arrays.fill(out, 0, fill, (byte) '0');
        System.arraycopy(src, 0, out, fill, src.length);
        if (src.length > 0 && (src[0] == '+' || src[0] == '-')) { out[0] = src[0]; out[fill] = '0'; }
        return binaryResult(seq, out);
    }

    private static Object expandtabs(PyByteSequence seq, Object[] args) {
        if (args.length > 1) throw new PyException("TypeError", "expandtabs() takes at most 1 argument (" + args.length + " given)");
        int tabsize = args.length == 1 ? asIndex(args[0]) : 8;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long column = 0;
        for (byte b : seq.toByteArray()) {
            if (b == '\t') {
                if (tabsize > 0) { long n = tabsize - column % tabsize; column += n; for (long i = 0; i < n; i++) out.write(' '); }
            } else {
                out.write(b);
                column = b == '\n' || b == '\r' ? 0 : column + 1;
            }
        }
        return binaryResult(seq, out.toByteArray());
    }
}
