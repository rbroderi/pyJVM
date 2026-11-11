// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * printf-style {@code %} formatting for str, bytes and bytearray (CPython's PyUnicode_Format and
 * _PyBytes_FormatEx). Bytes operate on a Latin-1 view of the format so one engine serves both.
 */
final class PercentFormat {
    private final String format; private final Object operand; private final List<Object> args;
    private final boolean tuple, mapping, bytes;
    private int index = 0, position = 0;
    private boolean keyed = false;

    // current conversion specification
    private boolean left, plus, blank, alternate, zero, hasKey;
    private int width, precision, start;
    private Object argumentKey;
    private String prefix;
    private boolean numeric;

    private PercentFormat(String format, Object operand, boolean bytes) {
        this.format = format; this.operand = operand; this.bytes = bytes;
        tuple = operand instanceof PyTuple;
        args = tuple ? ((PyTuple) operand).items : Collections.singletonList(operand);
        mapping = !(operand instanceof PyTuple) && !(operand instanceof String) && !(bytes && operand instanceof PyByteSequence)
            && (operand instanceof Map || operand instanceof List || operand instanceof PyByteSequence || operand instanceof PyRange
                || operand instanceof PyInstance instance && instance.cls.lookupMethod("__getitem__") != null);
    }

    static String formatString(String format, Object operand) { return new PercentFormat(format, operand, false).run(); }

    static Object formatBytes(Object formatObject, Object operand) {
        byte[] data = ((PyByteSequence) formatObject).toByteArray();
        String text = new String(data, StandardCharsets.ISO_8859_1);
        byte[] out = new PercentFormat(text, operand, true).run().getBytes(StandardCharsets.ISO_8859_1);
        return formatObject instanceof PyByteArray ? new PyByteArray(out) : new PyBytes(out);
    }

    private Object next(boolean star) {
        if (index >= args.size() || star && !tuple)
            throw new PyException("TypeError", "not enough arguments for format string (got " + args.size() + ")");
        return args.get(index++);
    }

    private int star(boolean forPrecision) {
        if (keyed) throw new PyException("ValueError", "* cannot be used with a parenthesised mapping key at position " + start);
        Object value = next(true);
        if (!isIntLike(value)) throw new PyException("TypeError", "format argument " + index + ": * requires int, not " + typeName(value));
        BigInteger integer = bigInt(value);
        try {
            return forPrecision ? Math.max(0, integer.intValueExact()) : integer.intValueExact();
        } catch (ArithmeticException error) {
            throw new PyException("OverflowError", "format argument " + index + ": too big for " + (forPrecision ? "precision" : "width"));
        }
    }

    private int digits(boolean forPrecision) {
        long value = 0;
        while (position < format.length() && format.charAt(position) >= '0' && format.charAt(position) <= '9') {
            int digit = format.charAt(position++) - '0';
            if (value > (Long.MAX_VALUE - digit) / 10 || forPrecision && value > (Integer.MAX_VALUE - digit) / 10)
                throw new PyException("ValueError", (forPrecision ? "precision" : "width") + " too big at position " + start);
            value = value * 10 + digit;
        }
        if (value > Integer.MAX_VALUE) throw new PyException("MemoryError", null);
        return (int) value;
    }

    private String conversionName() { return bytes ? "bytes" : "string"; }

    String run() {
        StringBuilder out = new StringBuilder();
        while (position < format.length()) {
            char literal = format.charAt(position++);
            if (literal != '%') { out.append(literal); continue; }
            start = position - 1;
            if (position < format.length() && format.charAt(position) == '%') { out.append('%'); position++; continue; }
            Object value = parseSpec();
            int conversionPosition = position;
            int code = format.codePointAt(position);
            position += Character.charCount(code);
            char conversion = code < 128 ? (char) code : 0;
            if (!hasKey) value = next(false);
            prefix = ""; numeric = false;
            String body = convertChecked(value, conversion, code, conversionPosition);
            int padding = Math.max(0, width - prefix.length() - body.codePointCount(0, body.length()));
            if (left) out.append(prefix).append(body).append(" ".repeat(padding));
            else if (zero && numeric) out.append(prefix).append("0".repeat(padding)).append(body);
            else out.append(" ".repeat(padding)).append(prefix).append(body);
        }
        if (!mapping && index < args.size())
            throw new PyException("TypeError", "not all arguments converted during " + conversionName() + " formatting (required " + index + ", got " + args.size() + ")");
        return out.toString();
    }

    /** Parse "(key)flags width .precision length" after a '%'; returns the mapping value when keyed. */
    private Object parseSpec() {
        Object value = null;
        hasKey = false; argumentKey = null;
        if (position < format.length() && format.charAt(position) == '(') {
            if (!mapping) throw new PyException("TypeError", "format requires a mapping, not " + typeName(operand));
            int keyStart = ++position, depth = 1;
            while (position < format.length() && depth > 0) { char c = format.charAt(position++); if (c == '(') depth++; else if (c == ')') depth--; }
            if (depth != 0) throw new PyException("ValueError", "stray % or incomplete format key at position " + start);
            String text = format.substring(keyStart, position - 1);
            argumentKey = bytes ? new PyBytes(text.getBytes(StandardCharsets.ISO_8859_1)) : text;
            value = getitem(operand, argumentKey); keyed = true; hasKey = true;
        } else if (keyed) throw new PyException("ValueError", "format requires a parenthesised mapping key at position " + start);
        left = plus = blank = alternate = zero = false;
        while (position < format.length()) {
            char flag = format.charAt(position);
            if (flag == '-') left = true; else if (flag == '+') plus = true; else if (flag == ' ') blank = true;
            else if (flag == '#') alternate = true; else if (flag == '0') zero = true; else break;
            position++;
        }
        width = 0; precision = -1;
        if (position < format.length() && format.charAt(position) == '*') {
            position++; width = star(false);
            if (width < 0) {
                left = true;
                if (width == Integer.MIN_VALUE) throw new PyException("OverflowError", "format argument " + index + ": too big for width");
                width = -width;
            }
        } else width = digits(false);
        if (position < format.length() && format.charAt(position) == '.') {
            position++;
            if (position < format.length() && format.charAt(position) == '*') { position++; precision = star(true); }
            else precision = digits(true);
        }
        if (position < format.length() && "hlL".indexOf(format.charAt(position)) >= 0) position++;
        if (position >= format.length()) throw new PyException("ValueError", "stray % at position " + format.codePointCount(0, start));
        return value;
    }

    private String convertChecked(Object value, char conversion, int code, int conversionPosition) {
        try {
            return convert(value, conversion, code, conversionPosition);
        } catch (PyException error) {
            if (error.value instanceof String message && message.startsWith("format argument:")) {
                String context = hasKey ? " " + pyRepr(argumentKey) : tuple ? " " + index : "";
                throw new PyException(error.typeName, "format argument" + context + message.substring("format argument".length()));
            }
            throw error;
        }
    }

    private String convert(Object value, char conversion, int code, int conversionPosition) {
        switch (conversion) {
            case 's', 'r', 'a', 'b':
                if (conversion == 'b' && !bytes) break;
                return textConversion(value, conversion);
            case 'c': return charConversion(value);
            case 'd', 'i', 'u', 'o', 'x', 'X': return integerConversion(value, conversion);
            case 'e', 'E', 'f', 'F', 'g', 'G': return floatConversion(value, conversion);
            default: break;
        }
        throw badConversion(code, conversionPosition);
    }

    private PyException badConversion(int code, int conversionPosition) {
        int conversionOffset = format.codePointCount(0, conversionPosition);
        int startOffset = format.codePointCount(0, start);
        if (code < 128 && Character.isLetter(code)) return new PyException("ValueError", "unsupported format %" + (char) code + " at position " + startOffset);
        String display;
        if (bytes) {
            display = code == '\'' ? "\"'\"" : code >= 32 && code < 127 ? "'" + (char) code + "'" : String.format(Locale.ROOT, "with code 0x%02x", code);
        } else if (code == '\'') display = "\"'\"";
        else if (code >= 32 && code < 127) display = "'" + (char) code + "'";
        else if (Character.isISOControl(code)) display = String.format(Locale.ROOT, "U+%04X", code);
        else display = "'" + new String(Character.toChars(code)) + "' (" + String.format(Locale.ROOT, "U+%04X", code) + ")";
        return new PyException("ValueError", "stray % at position " + startOffset + " or unexpected format character " + display + " at position " + conversionOffset);
    }

    private String textConversion(Object value, char conversion) {
        String body;
        if (conversion == 'r' || conversion == 'a') body = conversion == 'r' && !bytes ? pyRepr(value) : asciiRepr(value);
        else if (bytes) body = new String(bytesArgument(value), StandardCharsets.ISO_8859_1);
        else body = pyStr(value);
        if (precision >= 0 && body.codePointCount(0, body.length()) > precision) body = body.substring(0, body.offsetByCodePoints(0, precision));
        return body;
    }

    private static byte[] bytesArgument(Object value) {
        Object plain = NativeSubclass.unwrap(value);
        if (plain instanceof PyBytes || plain instanceof PyByteArray) return ((PyByteSequence) plain).toByteArray();
        if (value instanceof PyInstance instance) {
            PyMethod method = instance.cls.lookupMethod("__bytes__");
            if (method != null) {
                Object result = invoke(instance, method, new Object[0]);
                if (result instanceof PyBytes b) return b.toByteArray();
                throw new PyException("TypeError", "__bytes__ returned non-bytes (type " + typeName(result) + ")");
            }
        }
        if (plain instanceof PyByteSequence seq) return seq.toByteArray();
        throw new PyException("TypeError", "format argument: %b requires a bytes-like object, or an object that implements __bytes__, not " + typeName(value));
    }

    private String charConversion(Object value) {
        if (bytes) return byteCharConversion(value);
        if (value instanceof String text) {
            int length = text.codePointCount(0, text.length());
            if (length != 1) throw new PyException("TypeError", "format argument: %c requires an integer or a unicode character, not a string of length " + length);
            return text;
        }
        BigInteger charCode = percentInteger(value, 'c', false);
        if (charCode.signum() < 0 || charCode.compareTo(BigInteger.valueOf(0x10ffff)) > 0)
            throw new PyException("OverflowError", "format argument: %c argument not in range(0x110000)");
        return new String(Character.toChars(charCode.intValue()));
    }

    private String byteCharConversion(Object value) {
        Object plain = NativeSubclass.unwrap(value);
        if (plain instanceof PyBytes || plain instanceof PyByteArray) {
            byte[] data = ((PyByteSequence) plain).toByteArray();
            if (data.length == 1) return String.valueOf((char) (data[0] & 0xff));
            throw new PyException("TypeError", "format argument: %c requires an integer in range(256) or a single byte, not a " + typeName(plain) + " object of length " + data.length);
        }
        BigInteger code;
        try {
            code = percentInteger(value, 'c', false);
        } catch (PyException error) {
            if (!error.typeName.equals("TypeError")) throw error;
            throw new PyException("TypeError", "format argument: %c requires an integer in range(256) or a single byte, not " + typeName(value));
        }
        if (code.signum() < 0 || code.compareTo(BigInteger.valueOf(255)) > 0)
            throw new PyException("OverflowError", "format argument: %c argument not in range(256)");
        return String.valueOf((char) code.intValue());
    }

    private String integerConversion(Object value, char conversion) {
        BigInteger integer = percentInteger(value, conversion, "diu".indexOf(conversion) >= 0);
        numeric = true;
        prefix = integer.signum() < 0 ? "-" : plus ? "+" : blank ? " " : "";
        int radix = conversion == 'o' ? 8 : conversion == 'x' || conversion == 'X' ? 16 : 10;
        String body = integer.abs().toString(radix);
        if (conversion == 'X') body = body.toUpperCase(Locale.ROOT);
        if (precision > body.length()) body = "0".repeat(precision - body.length()) + body;
        if (alternate && radix != 10) prefix += radix == 8 ? "0o" : conversion == 'X' ? "0X" : "0x";
        return body;
    }

    private String floatConversion(Object value, char conversion) {
        double number = percentFloat(value, conversion);
        numeric = true;
        boolean negative = !Double.isNaN(number) && Double.doubleToRawLongBits(number) < 0;
        prefix = negative ? "-" : plus ? "+" : blank ? " " : "";
        return FormatFloat.digits(Math.abs(number), conversion, precision, alternate, false);
    }
}
