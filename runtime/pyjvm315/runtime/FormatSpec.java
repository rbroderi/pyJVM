// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;

import java.math.BigInteger;
import java.util.Locale;

/**
 * The format-spec mini-language: one parser (a port of CPython's parse_internal_render_format_spec) and the
 * str / int / float / complex renderers built on it. Used by format(), __format__, f-strings and str.format.
 */
final class FormatSpec {
    int fill = ' ';
    int align;
    int sign;
    int width = -1;
    int precision = -1;
    int type;
    char group;          // 0, ',', '_', or 'F' for underscores every four digits (b/o/x/X)
    boolean alternate, noNeg0, fillSpecified, alignSpecified;

    private FormatSpec() {}

    private static boolean isAlign(int c) { return c == '<' || c == '>' || c == '=' || c == '^'; }

    private static int integer(int[] cp, int[] pos, String what) {
        int start = pos[0];
        long n = 0;
        while (pos[0] < cp.length && cp[pos[0]] >= '0' && cp[pos[0]] <= '9') {
            n = n * 10 + (cp[pos[0]++] - '0');
            if (n > Integer.MAX_VALUE) throw new PyException("ValueError", "Too many decimal digits in format string");
        }
        return pos[0] == start ? -1 : (int) n;
    }

    /** Parse {@code spec} for an object of type {@code typeName}; {@code defaultType} 0 means "no type given". */
    static FormatSpec parse(String spec, String typeName, int defaultType, int defaultAlign) {
        FormatSpec f = new FormatSpec();
        int[] cp = spec.codePoints().toArray();
        int[] pos = {0};
        f.align = defaultAlign; f.type = defaultType;
        if (cp.length - pos[0] >= 2 && isAlign(cp[1])) {
            f.align = cp[1]; f.fill = cp[0]; f.fillSpecified = f.alignSpecified = true; pos[0] = 2;
        } else if (cp.length >= 1 && isAlign(cp[0])) { f.align = cp[0]; f.alignSpecified = true; pos[0] = 1; }
        parseFlags(f, cp, pos, defaultAlign);
        int w = integer(cp, pos, "width");
        f.width = w;
        parseGrouping(f, cp, pos);
        if (pos[0] < cp.length && cp[pos[0]] == '.') {
            pos[0]++;
            int p = integer(cp, pos, "precision");
            if (p < 0) throw new PyException("ValueError", "Format specifier missing precision");
            f.precision = p;
        }
        if (cp.length - pos[0] > 1)
            throw new PyException("ValueError", "Invalid format specifier '" + spec + "' for object of type '" + typeName + "'");
        if (cp.length - pos[0] == 1) f.type = cp[pos[0]];
        f.checkGrouping();
        return f;
    }

    private static void parseFlags(FormatSpec f, int[] cp, int[] pos, int defaultAlign) {
        int n = cp.length;
        if (pos[0] < n && (cp[pos[0]] == ' ' || cp[pos[0]] == '+' || cp[pos[0]] == '-')) f.sign = cp[pos[0]++];
        if (pos[0] < n && cp[pos[0]] == 'z') { f.noNeg0 = true; pos[0]++; }
        if (pos[0] < n && cp[pos[0]] == '#') { f.alternate = true; pos[0]++; }
        if (!f.fillSpecified && pos[0] < n && cp[pos[0]] == '0') {
            f.fill = '0';
            if (!f.alignSpecified && defaultAlign == '>') f.align = '=';
            pos[0]++;
        }
    }

    private static void parseGrouping(FormatSpec f, int[] cp, int[] pos) {
        int n = cp.length;
        if (pos[0] < n && cp[pos[0]] == ',') { f.group = ','; pos[0]++; }
        if (pos[0] < n && cp[pos[0]] == '_') {
            if (f.group != 0) throw new PyException("ValueError", "Cannot specify both ',' and '_'.");
            f.group = '_'; pos[0]++;
        }
        if (pos[0] < n && cp[pos[0]] == ',' && f.group == '_') throw new PyException("ValueError", "Cannot specify both ',' and '_'.");
    }

    private void checkGrouping() {
        if (group == 0) return;
        switch (type) {
            case 'd', 'e', 'f', 'g', 'E', 'G', '%', 'F', 0 -> { }
            case 'b', 'o', 'x', 'X' -> {
                if (group == '_') { group = 'F'; return; }
                throw new PyException("ValueError", "Cannot specify ',' with '" + typeText(type) + "'.");
            }
            default -> throw new PyException("ValueError", "Cannot specify '" + group + "' with '" + typeText(type) + "'.");
        }
    }

    private static String typeText(int t) {
        return t > 32 && t < 128 ? String.valueOf((char) t) : "\\x" + Integer.toHexString(t);
    }

    static PyException unknownCode(int t, String typeName) {
        return new PyException("ValueError", "Unknown format code '" + typeText(t) + "' for object of type '" + typeName + "'");
    }

    // ---------------------------------------------------------------- padding / assembly

    private static int cpLen(String s) { return s.codePointCount(0, s.length()); }

    private String fillRun(int n) { return n <= 0 ? "" : new String(Character.toChars(fill)).repeat(n); }

    /** Pad {@code body} (already complete, sign included) to the width per align; '=' puts the padding after {@code signPrefix}. */
    String pad(String signPrefix, String body, int defaultAlign) {
        int a = align == 0 ? defaultAlign : align;
        int padding = Math.max(0, width - cpLen(signPrefix) - cpLen(body));
        return switch (a) {
            case '<' -> signPrefix + body + fillRun(padding);
            case '^' -> fillRun(padding / 2) + signPrefix + body + fillRun(padding - padding / 2);
            case '=' -> signPrefix + fillRun(padding) + body;
            default -> fillRun(padding) + signPrefix + body;
        };
    }

    /** Insert separators every {@code size} digits, zero-extending the digits until the whole is at least {@code minWidth} wide. */
    static String groupDigits(String digits, int size, char sep, int minWidth) {
        int k = digits.length();
        StringBuilder z = new StringBuilder();
        if (minWidth > 0) {
            int want = k;
            while (want + (want - 1) / size < minWidth) want++;
            z.append("0".repeat(want - k));
        }
        String d = z + digits;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            if (i > 0 && (d.length() - i) % size == 0) out.append(sep);
            out.append(d.charAt(i));
        }
        return out.toString();
    }

    private String signText(boolean negative) {
        return negative ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
    }

    /** sign + prefix + grouped digits + remainder, aligned. */
    private String number(boolean negative, String prefix, String digits, String remainder, int groupSize) {
        String head = signText(negative) + prefix;
        boolean zeroPad = fill == '0' && align == '=';
        if (group != 0) {
            char sep = group == 'F' ? '_' : group;
            digits = groupDigits(digits, groupSize, sep, zeroPad ? width - cpLen(head) - cpLen(remainder) : 0);
        }
        return pad(head, digits + remainder, '>');
    }

    // ---------------------------------------------------------------- dispatch

    /** format() of a builtin value (or the native payload of a builtin subclass instance) with the default __format__. */
    static String formatNative(Object original, String spec) {
        Object v = NativeSubclass.unwrap(original);
        if (v instanceof String s) return spec.isEmpty() ? pyStr(original) : formatString(s, spec, typeName(original));
        if (isIntLike(v)) return spec.isEmpty() ? pyStr(original) : formatInt(bigInt(v), spec, typeName(original));
        if (v instanceof Double d) return spec.isEmpty() ? pyStr(original) : formatFloat(d, spec);
        if (v instanceof PyComplex c) return spec.isEmpty() ? pyStr(original) : formatComplex(c.real, c.imag, spec);
        return null;
    }

    // ---------------------------------------------------------------- str

    static String formatString(String text, String spec, String typeName) {
        FormatSpec f = parse(spec, typeName, 's', '<');
        if (f.sign != 0) throw new PyException("ValueError", (f.sign == ' ' ? "Space" : "Sign") + " not allowed in string format specifier");
        if (f.noNeg0) throw new PyException("ValueError", "Negative zero coercion (z) not allowed in string format specifier");
        if (f.alternate) throw new PyException("ValueError", "Alternate form (#) not allowed in string format specifier");
        if (f.align == '=') throw new PyException("ValueError", "'=' alignment not allowed in string format specifier");
        if (f.type != 's') throw unknownCode(f.type, typeName);
        if (f.precision >= 0 && cpLen(text) > f.precision) text = text.substring(0, text.offsetByCodePoints(0, f.precision));
        return f.pad("", text, '<');
    }

    // ---------------------------------------------------------------- int

    static String formatInt(BigInteger value, String spec, String typeName) {
        FormatSpec f = parse(spec, typeName, 'd', '>');
        switch (f.type) {
            case 'e', 'E', 'f', 'F', 'g', 'G', '%' -> {
                double d = value.doubleValue();
                if (Double.isInfinite(d)) throw new PyException("OverflowError", "int too large to convert to float");
                return f.floatBody(d, typeName);
            }
            case 'b', 'o', 'x', 'X', 'd', 'n', 'c' -> { }
            default -> throw unknownCode(f.type, typeName);
        }
        if (f.precision != -1) throw new PyException("ValueError", "Precision not allowed in integer format specifier");
        if (f.noNeg0) throw new PyException("ValueError", "Negative zero coercion (z) not allowed in integer format specifier");
        if (f.type == 'c') return f.charBody(value);
        int radix = f.type == 'b' ? 2 : f.type == 'o' ? 8 : f.type == 'x' || f.type == 'X' ? 16 : 10;
        String digits = value.abs().toString(radix);
        String prefix = "";
        if (f.alternate) prefix = f.type == 'b' ? "0b" : f.type == 'o' ? "0o" : f.type == 'x' ? "0x" : f.type == 'X' ? "0X" : "";
        if (f.type == 'X') digits = digits.toUpperCase(Locale.ROOT);
        return f.number(value.signum() < 0, prefix, digits, "", radix == 10 ? 3 : 4);
    }

    private String charBody(BigInteger value) {
        if (sign != 0) throw new PyException("ValueError", "Sign not allowed with integer format specifier 'c'");
        if (alternate) throw new PyException("ValueError", "Alternate form (#) not allowed with integer format specifier 'c'");
        if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(0x10ffff)) > 0)
            throw new PyException("OverflowError", "%c arg not in range(0x110000)");
        return pad("", new String(Character.toChars(value.intValue())), '>');
    }

    // ---------------------------------------------------------------- float

    static String formatFloat(double value, String spec) {
        FormatSpec f = parse(spec, "float", 0, '>');
        return f.floatBody(value, "float");
    }

    private String floatBody(double value, String typeName) {
        int t = type;
        if (t != 0 && "eEfFgGn%".indexOf(t) < 0) throw unknownCode(t, typeName);
        boolean negative = Double.doubleToRawLongBits(value) < 0 && !Double.isNaN(value);
        String[] parts = floatParts(Math.abs(value), t, true);
        if (noNeg0 && negative && zeroDigits(parts)) negative = false;
        if (group != 0 && Double.isFinite(value)) return number(negative, "", parts[0], parts[1], 3);
        return pad(signText(negative), parts[0] + parts[1], '>');
    }

    /** The digit text of a non-negative float split into {integer part, remainder (fraction, exponent, percent sign)}. */
    String[] floatParts(double abs, int t, boolean splitIntegerPart) {
        char conv = (char) t;
        boolean addDot0 = false, percent = false;
        int prec = precision;
        if (t == 0) { conv = 'r'; addDot0 = true; if (prec >= 0) conv = 'g'; }
        else if (t == 'n') conv = 'g';
        else if (t == '%') { conv = 'f'; abs *= 100; percent = true; }
        String body = FormatFloat.digits(abs, conv, conv == 'r' ? -1 : prec, alternate, addDot0);
        if (percent) body += "%";
        if (!Double.isFinite(abs)) return new String[]{body, ""};
        int end = 0;
        while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
        return new String[]{body.substring(0, end), body.substring(end)};
    }

    // ---------------------------------------------------------------- complex

    static String formatComplex(double re, double im, String spec) {
        FormatSpec f = parse(spec, "complex", 0, '>');
        return f.complexBody(re, im);
    }

    private String complexBody(double re, double im) {
        if (fill == '0') throw new PyException("ValueError", "Zero padding is not allowed in complex format specifier");
        if (align == '=') throw new PyException("ValueError", "'=' alignment flag is not allowed in complex format specifier");
        int t = type;
        if (t != 0 && "eEfFgGn".indexOf(t) < 0) throw unknownCode(t, "complex");
        boolean parens = false, skipRe = false;
        if (t == 0) {
            if (re == 0.0 && Double.doubleToRawLongBits(re) >= 0) skipRe = true; else parens = true;
        }
        String reText = skipRe ? "" : complexPart(re, t, sign, true);
        String imText = complexPart(im, t, skipRe ? sign : '+', skipRe);
        String body = (parens ? "(" : "") + reText + imText + "j" + (parens ? ")" : "");
        return pad("", body, '>');
    }

    /** One component of a complex: sign first (mandatory for the imaginary part), digits grouped, no padding. */
    private String complexPart(double v, int t, int signFlag, boolean first) {
        boolean negative = Double.doubleToRawLongBits(v) < 0 && !Double.isNaN(v);
        String[] parts = floatPartsComplex(Math.abs(v), t);
        if (noNeg0 && negative && zeroDigits(parts)) negative = false;
        String s = negative ? "-" : signFlag == '+' ? "+" : signFlag == ' ' ? " " : first ? "" : "+";
        String integerPart = parts[0];
        if (group != 0 && Double.isFinite(v)) integerPart = groupDigits(integerPart, 3, group == 'F' ? '_' : group, 0);
        return s + integerPart + parts[1];
    }

    private boolean zeroDigits(String[] parts) {
        String all = parts[0] + parts[1];
        int e = all.indexOf('e');
        if (e < 0) e = all.indexOf('E');
        if (e >= 0) all = all.substring(0, e);
        for (int i = 0; i < all.length(); i++) { char c = all.charAt(i); if (c >= '1' && c <= '9') return false; }
        return !all.isEmpty() && Character.isDigit(all.charAt(0));
    }

    private String[] floatPartsComplex(double abs, int t) {
        char conv = (char) t;
        int prec = precision;
        if (t == 0) { conv = 'r'; if (prec >= 0) conv = 'g'; }
        else if (t == 'n') conv = 'g';
        String body = FormatFloat.digits(abs, conv, conv == 'r' ? -1 : prec, alternate, false);
        if (!Double.isFinite(abs)) return new String[]{body, ""};
        int end = 0;
        while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
        return new String[]{body.substring(0, end), body.substring(end)};
    }
}
