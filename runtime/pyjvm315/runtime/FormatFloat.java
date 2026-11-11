// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Float to text conversion with CPython's PyOS_double_to_string semantics: shortest repr, and
 * 'e'/'f'/'g' rounding done half-even on the exact binary expansion (BigDecimal).
 */
final class FormatFloat {
    private FormatFloat() {}

    /** Shortest round-trip digits of a finite positive double: {@code digits} (no trailing zeros) and the decimal point position. */
    private static final class Shortest {
        String digits; int decpt;
    }

    private static Shortest shortestDigits(double abs) {
        String text = Double.toString(abs);
        int e = text.indexOf('E');
        int exp10 = e < 0 ? 0 : Integer.parseInt(text.substring(e + 1));
        String mantissa = e < 0 ? text : text.substring(0, e);
        int dot = mantissa.indexOf('.');
        String whole = mantissa.substring(0, dot), frac = mantissa.substring(dot + 1);
        String all = whole + frac;
        int decpt = whole.length() + exp10;
        int lead = 0;
        while (lead < all.length() - 1 && all.charAt(lead) == '0') lead++;
        all = all.substring(lead); decpt -= lead;
        int end = all.length();
        while (end > 1 && all.charAt(end - 1) == '0') end--;
        Shortest s = new Shortest();
        s.digits = all.substring(0, end); s.decpt = decpt;
        if (end == 2) oneDigit(abs, s);
        return s;
    }

    /** Double.toString always yields at least two digits; a single digit may already round-trip (5e-324). */
    private static void oneDigit(double abs, Shortest s) {
        BigDecimal one = new BigDecimal(abs).round(new MathContext(1, RoundingMode.HALF_EVEN));
        if (one.doubleValue() != abs) return;
        s.digits = one.unscaledValue().toString();
        s.decpt = one.precision() - one.scale();
    }

    private static String expSuffix(int exponent, boolean upper) {
        int a = Math.abs(exponent);
        return (upper ? "E" : "e") + (exponent < 0 ? "-" : "+") + (a < 10 ? "0" : "") + a;
    }

    /** repr()/str() of a finite non-negative double, with ".0" appended to integral values when requested. */
    static String reprAbs(double abs, boolean addDot0, boolean alternate) {
        if (abs == 0) return alternate ? "0." : addDot0 ? "0.0" : "0";
        Shortest s = shortestDigits(abs);
        String d = s.digits; int decpt = s.decpt, exponent = decpt - 1;
        StringBuilder out = new StringBuilder();
        if (exponent < -4 || exponent >= 16) {
            out.append(d.charAt(0));
            if (d.length() > 1) out.append('.').append(d, 1, d.length());
            else if (alternate) out.append('.');
            out.append(expSuffix(exponent, false));
            return out.toString();
        }
        if (decpt <= 0) out.append("0.").append("0".repeat(-decpt)).append(d);
        else if (decpt >= d.length()) {
            out.append(d).append("0".repeat(decpt - d.length()));
            if (addDot0 || alternate) out.append(addDot0 ? ".0" : ".");
        } else out.append(d, 0, decpt).append('.').append(d, decpt, d.length());
        return out.toString();
    }

    /** repr(float). */
    static String repr(double value) {
        if (Double.isNaN(value)) return "nan";
        if (Double.isInfinite(value)) return value < 0 ? "-inf" : "inf";
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        return (negative ? "-" : "") + reprAbs(Math.abs(value), true, false);
    }

    /**
     * Digits of a non-negative double in conversion {@code conversion} ('e','E','f','F','g','G', or 'r' for repr);
     * {@code precision} &lt; 0 means the default of 6 (ignored for 'r').
     */
    static String digits(double number, char conversion, int precision, boolean alternate, boolean addDot0) {
        if (precision > Integer.MAX_VALUE - 1024) throw new PyException("ValueError", "precision too big");
        boolean upper = Character.isUpperCase(conversion);
        if (Double.isNaN(number)) return upper ? "NAN" : "nan";
        if (Double.isInfinite(number)) return upper ? "INF" : "inf";
        char kind = Character.toLowerCase(conversion);
        if (kind == 'r') return reprAbs(number, addDot0, alternate);
        int p = precision < 0 ? 6 : precision;
        BigDecimal exact = new BigDecimal(number);
        if (kind == 'f') {
            String body = exact.setScale(p, RoundingMode.HALF_EVEN).toPlainString();
            return p == 0 && alternate ? body + "." : body;
        }
        int significant = kind == 'e' ? Math.addExact(p, 1) : Math.max(1, p);
        BigDecimal rounded = exact.round(new MathContext(significant, RoundingMode.HALF_EVEN));
        int exponent = rounded.signum() == 0 ? 0 : rounded.precision() - rounded.scale() - 1;
        boolean sci = kind == 'e' || exponent < -4 || exponent >= (addDot0 ? significant - 1 : significant);
        if (sci) return scientific(rounded, exponent, significant, kind == 'g', alternate, upper);
        String body = rounded.setScale(Math.max(0, significant - exponent - 1), RoundingMode.HALF_EVEN).toPlainString();
        if (kind == 'g' && !alternate) body = trimFraction(body);
        else if (alternate && body.indexOf('.') < 0) body += ".";
        if (addDot0 && body.indexOf('.') < 0) body += ".0";
        return body;
    }

    private static String scientific(BigDecimal rounded, int exponent, int significant, boolean general, boolean alternate, boolean upper) {
        String body = rounded.movePointLeft(exponent).setScale(significant - 1, RoundingMode.HALF_EVEN).toPlainString();
        if (general && !alternate) body = trimFraction(body);
        if (alternate && body.indexOf('.') < 0) body += ".";
        return body + expSuffix(exponent, upper);
    }

    static String trimFraction(String text) {
        if (text.indexOf('.') < 0) return text;
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') end--;
        if (end > 0 && text.charAt(end - 1) == '.') end--;
        return text.substring(0, end);
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
