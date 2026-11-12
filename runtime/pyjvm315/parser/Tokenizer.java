// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Python tokenizer producing the same token stream as CPython's {@code tokenize.generate_tokens}
 * (including COMMENT/NL and PEP 701 f-string tokens), so the parser and tooling share one lexer.
 */
public final class Tokenizer {
    private final String src;
    private final int n;
    private final int[] lineStarts;
    private final List<Token> out = new ArrayList<>();
    private int i = 0;
    private int bracketDepth = 0;
    private boolean atLineStart = true;
    private boolean templates = true;
    private final ArrayDeque<Integer> indents = new ArrayDeque<>();
    private final ArrayDeque<FString> fstrings = new ArrayDeque<>();

    private static final class Field { int nest; boolean inSpec; boolean afterNested; }
    private static final class FString {
        final String quote; final boolean triple, raw, template; final ArrayDeque<Field> fields = new ArrayDeque<>();
        int savedBracketDepth;
        FString(String quote, boolean raw, boolean template) { this.quote = quote; this.triple = quote.length() == 3; this.raw = raw; this.template = template; }
    }

    private Tokenizer(String src) {
        this.src = src; this.n = src.length();
        int[] starts = new int[16]; int count = 0; starts[count++] = 0;
        for (int k = 0; k < n; k++) {
            char c = src.charAt(k);
            if (c == '\n' || (c == '\r' && !(k + 1 < n && src.charAt(k + 1) == '\n'))) {
                if (count == starts.length) starts = Arrays.copyOf(starts, count * 2);
                starts[count++] = k + 1;
            }
        }
        this.lineStarts = Arrays.copyOf(starts, count);
        indents.push(0);
    }

    public static List<Token> tokenize(String source) {
        Tokenizer t = new Tokenizer(source);
        t.run();
        return t.out;
    }

    /** The tokens up to the first lexical error, and that error (null when the whole source tokenizes). */
    public static final class Partial {
        public final List<Token> tokens;
        public final PySyntaxError error;
        Partial(List<Token> tokens, PySyntaxError error) { this.tokens = tokens; this.error = error; }
    }

    /**
     * Like {@link #tokenize} but keeps the tokens scanned before an error (the {@code _tokenize} module yields them
     * before raising). {@code templates} false lexes {@code t"..."} as before Python 3.14: a name followed by a string.
     */
    public static Partial tokenizePartial(String source, boolean templates) {
        Tokenizer t = new Tokenizer(source);
        t.templates = templates;
        try { t.run(); }
        catch (PySyntaxError e) { return new Partial(t.out, e); }
        return new Partial(t.out, null);
    }

    // ---- positions ----------------------------------------------------------------------------
    private int lineOf(int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1; }
        return lo + 1;
    }
    private int colOf(int offset) { int ls = lineStarts[lineOf(offset) - 1]; return src.codePointCount(ls, Math.max(ls, offset)); }

    private void emit(int type, int start, int end) { emit(type, src.substring(start, end), start, end); }
    private void emit(int type, String text, int start, int end) {
        if (type == Token.NEWLINE || type == Token.NL) { // the line terminator belongs to the line it ends
            int line = lineOf(start), col = colOf(start);
            out.add(new Token(type, text, line, col, line, col + (end - start), start, end));
            return;
        }
        out.add(new Token(type, text, lineOf(start), colOf(start), lineOf(end), colOf(end), start, end));
    }
    private void emitAt(int type, String text, int line, int col, int offset) {
        out.add(new Token(type, text, line, col, line, col, offset, offset));
    }

    private PySyntaxError error(String message, int offset) { return error("SyntaxError", message, offset); }
    private PySyntaxError error(String kind, String message, int offset) {
        int clamped = Math.min(offset, n);
        return new PySyntaxError(kind, message, lineOf(clamped), colOf(clamped) + 1);
    }

    private boolean isNewline(int k) { return k < n && (src.charAt(k) == '\n' || src.charAt(k) == '\r'); }
    private int newlineEnd(int k) { return src.charAt(k) == '\r' && k + 1 < n && src.charAt(k + 1) == '\n' ? k + 2 : k + 1; }

    // ---- main loop ----------------------------------------------------------------------------
    private void run() {
        while (true) {
            if (!fstrings.isEmpty() && inLiteral(fstrings.peek())) { scanFStringLiteral(); continue; }
            if (atLineStart && bracketDepth == 0 && fstrings.isEmpty()) {
                if (!lineStart()) break;
                continue;
            }
            // skip blanks
            while (i < n && (src.charAt(i) == ' ' || src.charAt(i) == '\t' || src.charAt(i) == '\f')) i++;
            if (i >= n) break;
            char c = src.charAt(i);
            if (c == '#') { int s = i; while (i < n && !isNewline(i)) i++; emit(Token.COMMENT, s, i); continue; }
            if (c == '\n' || c == '\r') {
                int e = newlineEnd(i);
                if (bracketDepth > 0 || !fstrings.isEmpty()) emit(Token.NL, i, e);
                else { emit(Token.NEWLINE, i, e); atLineStart = true; }
                i = e;
                continue;
            }
            if (c == '\\' && isNewline(i + 1)) { i = newlineEnd(i + 1); continue; }
            if (c == '\\' && i + 1 >= n) { i++; continue; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) { scanNumber(); continue; }
            if (c == '"' || c == '\'') { scanString(i, ""); continue; }
            if (isIdentStart(src.codePointAt(i))) { scanNameOrString(); continue; }
            scanOperator();
        }
        finish();
    }

    private void finish() {
        if (!fstrings.isEmpty()) throw error("unterminated f-string literal", n);
        if (bracketDepth > 0) throw error("'" + "(" + "' was never closed", n);
        int lastLine = lineOf(Math.max(n - 1, 0));
        boolean endsWithNewline = n > 0 && isNewline(n - 1);
        if (n > 0 && !endsWithNewline) {
            int lineStart = lineStarts[lastLine - 1];
            boolean onlyCommentOrBlank = lastLogicalLineEmpty();
            int col = n - lineStart;
            out.add(new Token(onlyCommentOrBlank ? Token.NL : Token.NEWLINE, "", lastLine, col, lastLine, col + 1, n, n));
        }
        int endLine = (n > 0 && endsWithNewline) ? lineOf(n - 1) + 1 : lastLine + 1;
        if (n == 0) endLine = 1;
        while (indents.peek() > 0) { indents.pop(); emitAt(Token.DEDENT, "", endLine, 0, n); }
        emitAt(Token.ENDMARKER, "", endLine, 0, n);
    }

    private boolean lastLogicalLineEmpty() {
        for (int k = out.size() - 1; k >= 0; k--) {
            int t = out.get(k).type;
            if (t == Token.NEWLINE || t == Token.NL || t == Token.INDENT || t == Token.DEDENT) return t != Token.NEWLINE ? (k == out.size() - 1 ? true : true) : true;
            if (t == Token.COMMENT) continue;
            return false;
        }
        return true;
    }

    /** Handle indentation/blank lines at the start of a logical line. @return false at EOF. */
    private boolean lineStart() {
        int j = i, col = 0, contCol = -1;
        while (j < n) {
            char c = src.charAt(j);
            if (c == ' ') col++;
            else if (c == '\t') col = (col / 8 + 1) * 8;
            else if (c == '\f') col = 0;
            else if (c == '\\' && isNewline(j + 1)) {
                // Indentation cannot be split over physical lines: the first backslash fixes the level (as CPython's tokenizer does).
                if (contCol < 0) contCol = col;
                j = newlineEnd(j + 1); col = 0; continue;
            }
            else break;
            j++;
        }
        if (j >= n) { i = n; return false; }
        if (contCol >= 0) col = contCol;
        char c = src.charAt(j);
        if (c == '#') {
            int s = j; int e = s; while (e < n && !isNewline(e)) e++;
            emit(Token.COMMENT, s, e);
            i = e;
            if (isNewline(i)) { int ne = newlineEnd(i); emit(Token.NL, i, ne); i = ne; }
            return true;
        }
        if (isNewline(j)) { int ne = newlineEnd(j); emit(Token.NL, j, ne); i = ne; return true; }
        int line = lineOf(j);
        if (col > indents.peek()) {
            indents.push(col);
            emit(Token.INDENT, i, j);
        } else {
            while (col < indents.peek()) {
                indents.pop();
                if (col > indents.peek()) throw error("IndentationError", "unindent does not match any outer indentation level", j);
                emitAt(Token.DEDENT, "", line, j - lineStarts[line - 1], j);
            }
        }
        i = j;
        atLineStart = false;
        return true;
    }

    // ---- names, numbers, operators ------------------------------------------------------------
    static boolean isIdentStart(int c) { return c == '_' || Character.isLetter(c) || (c > 127 && Character.isUnicodeIdentifierStart(c)); }
    static boolean isIdentPart(int c) { return c == '_' || Character.isLetterOrDigit(c) || (c > 127 && Character.isUnicodeIdentifierPart(c) && !Character.isIdentifierIgnorable(c)); }

    private void scanNameOrString() {
        int s = i;
        while (i < n) {
            int cp = src.codePointAt(i);
            if (!isIdentPart(cp)) break;
            i += Character.charCount(cp);
        }
        if (i < n && (src.charAt(i) == '"' || src.charAt(i) == '\'') && i - s <= 3 && isStringPrefix(src.substring(s, i))
                && (templates || src.substring(s, i).toLowerCase().indexOf('t') < 0)) {
            String prefix = src.substring(s, i);
            i = s;
            scanString(s, prefix);
            return;
        }
        emit(Token.NAME, s, i);
    }

    private static boolean isStringPrefix(String p) {
        String l = p.toLowerCase();
        return switch (l) { case "r", "u", "b", "f", "t", "br", "rb", "fr", "rf", "tr", "rt" -> true; default -> false; };
    }

    private void scanNumber() {
        int s = i;
        char c = src.charAt(i);
        if (c == '0' && i + 1 < n && "xXoObB".indexOf(src.charAt(i + 1)) >= 0) {
            char kind = Character.toLowerCase(src.charAt(i + 1));
            i += 2;
            while (i < n && (src.charAt(i) == '_' || digitOf(src.charAt(i), kind))) i++;
            emit(Token.NUMBER, s, i);
            return;
        }
        digits(); 
        if (i < n && src.charAt(i) == '.') { i++; digits(); }
        if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
            int save = i; i++;
            if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
            if (i < n && Character.isDigit(src.charAt(i))) digits(); else i = save;
        }
        if (i < n && (src.charAt(i) == 'j' || src.charAt(i) == 'J')) i++;
        emit(Token.NUMBER, s, i);
    }
    private void digits() { while (i < n && (Character.isDigit(src.charAt(i)) || (src.charAt(i) == '_' && i + 1 < n && Character.isDigit(src.charAt(i + 1))))) i++; }
    private static boolean digitOf(char c, char kind) {
        return switch (kind) { case 'x' -> Character.digit(c, 16) >= 0; case 'o' -> c >= '0' && c <= '7'; default -> c == '0' || c == '1'; };
    }

    private static final String[] OPS3 = {"**=", "//=", ">>=", "<<=", "..."};
    private static final String[] OPS2 = {"**", "//", ">>", "<<", "<=", ">=", "==", "!=", "->", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@=", ":="};

    private void scanOperator() {
        int s = i;
        char c = src.charAt(i);
        String op = null;
        if (i + 2 < n + 0 && i + 3 <= n) { String t = src.substring(i, i + 3); for (String o : OPS3) if (o.equals(t)) op = o; }
        if (op == null && i + 2 <= n) { String t = src.substring(i, i + 2); for (String o : OPS2) if (o.equals(t)) op = o; }
        if (op == null) {
            if ("+-*/%&|^~<>()[]{},:.;@=!".indexOf(c) >= 0) op = String.valueOf(c);
            else { i++; emit(Token.ERRORTOKEN, s, i); return; }
        }
        i += op.length();
        // f-string field bookkeeping
        FString fs = fstrings.peek();
        Field field = fs == null ? null : fs.fields.peek();
        boolean inFieldExpr = field != null && !field.inSpec;
        if (inFieldExpr) {
            if (op.equals("(") || op.equals("[") || op.equals("{")) { field.nest++; bracketDepth++; emit(Token.OP, s, i); return; }
            if (op.equals(")") || op.equals("]")) { field.nest--; bracketDepth--; emit(Token.OP, s, i); return; }
            if (op.equals("}")) {
                if (field.nest > 0) { field.nest--; bracketDepth--; emit(Token.OP, s, i); return; }
                emit(Token.OP, s, i); fs.fields.pop(); closedField(fs); return;
            }
            if (field.nest == 0) {
                if (op.equals(":")) { emit(Token.OP, s, i); field.inSpec = true; return; }
                if (op.equals("!") ) { emit(Token.OP, s, i); return; }
                if (op.equals(":=") ) { // top-level ':=' in a field is ':' (format spec starting with '=')
                    i = s + 1; emit(Token.OP, s, i); field.inSpec = true; return;
                }
            }
        } else {
            if (op.equals("(") || op.equals("[") || op.equals("{")) bracketDepth++;
            else if (op.equals(")") || op.equals("]") || op.equals("}")) bracketDepth--;
        }
        emit(Token.OP, s, i);
    }

    private void closedField(FString fs) {
        Field parent = fs.fields.peek();
        if (parent != null) parent.afterNested = true; // inside a format spec: next literal run may be empty
        bracketDepth = fs.savedBracketDepth + fs.fields.size() * 0;
    }

    // ---- strings ------------------------------------------------------------------------------
    private void scanString(int start, String prefix) {
        int p = start + prefix.length();
        char q = src.charAt(p);
        boolean triple = p + 2 < n && src.charAt(p + 1) == q && src.charAt(p + 2) == q;
        boolean raw = prefix.toLowerCase().indexOf('r') >= 0;
        String lower = prefix.toLowerCase();
        boolean formatted = lower.indexOf('f') >= 0, template = lower.indexOf('t') >= 0;
        String quote = triple ? "" + q + q + q : "" + q;
        if (formatted || template) {
            i = p + quote.length();
            emit(template ? Token.TSTRING_START : Token.FSTRING_START, src.substring(start, i), start, i);
            FString fs = new FString(quote, raw, template);
            fs.savedBracketDepth = bracketDepth;
            fstrings.push(fs);
            return;
        }
        int k = p + quote.length();
        while (true) {
            if (k >= n) throw error(triple ? "unterminated triple-quoted string literal" : "unterminated string literal", start);
            char c = src.charAt(k);
            if (c == '\\') { k += 2; continue; }
            if (!triple && (c == '\n' || c == '\r')) throw error("unterminated string literal", start);
            if (c == q && (!triple || (k + 2 < n && src.charAt(k + 1) == q && src.charAt(k + 2) == q))) { k += quote.length(); break; }
            k++;
        }
        i = k;
        emit(Token.STRING, start, i);
    }

    private boolean inLiteral(FString fs) { Field f = fs.fields.peek(); return f == null || f.inSpec; }

    private void scanFStringLiteral() {
        FString fs = fstrings.peek();
        Field field = fs.fields.peek();
        boolean inSpec = field != null;
        int litStart = i;
        StringBuilder text = new StringBuilder();
        while (true) {
            if (i >= n) throw error("unterminated f-string literal", litStart);
            char c = src.charAt(i);
            if (src.startsWith(fs.quote, i) && !inSpecQuoteEscape(fs)) {
                if (inSpec) {
                    // a closing quote inside a format spec ends the whole f-string (error in CPython); treat as end
                }
                if (text.length() > 0) emit(fs.template ? Token.TSTRING_MIDDLE : Token.FSTRING_MIDDLE, text.toString(), litStart, i);
                int s = i; i += fs.quote.length();
                emit(fs.template ? Token.TSTRING_END : Token.FSTRING_END, s, i);
                fstrings.pop();
                bracketDepth = fs.savedBracketDepth;
                return;
            }
            if (!fs.triple && (c == '\n' || c == '\r')) throw error("unterminated f-string literal", litStart);
            if (c == '\\') {
                if (i + 1 < n) {
                    if (!fs.raw && src.charAt(i + 1) == 'N' && i + 2 < n && src.charAt(i + 2) == '{') {
                        int close = src.indexOf('}', i + 3);
                        if (close < 0) throw error("unterminated f-string literal", litStart);
                        text.append(src, i, close + 1); i = close + 1; continue;
                    }
                    char next = src.charAt(i + 1);
                    if (next == '{' || next == '}') { text.append(c); i++; continue; } // a brace after a backslash still opens/closes a field
                    text.append(c).append(next); i += 2; continue;
                }
            }
            if (c == '{') {
                if (!inSpec && i + 1 < n && src.charAt(i + 1) == '{') {
                    text.append('{'); emit(fs.template ? Token.TSTRING_MIDDLE : Token.FSTRING_MIDDLE, text.toString(), litStart, i + 1);
                    i += 2; return;
                }
                if (text.length() > 0) emit(fs.template ? Token.TSTRING_MIDDLE : Token.FSTRING_MIDDLE, text.toString(), litStart, i);
                int s = i; i++; emit(Token.OP, s, i);
                Field nested = new Field();
                fs.fields.push(nested);
                bracketDepth++;
                return;
            }
            if (c == '}') {
                if (!inSpec && i + 1 < n && src.charAt(i + 1) == '}') {
                    text.append('}'); emit(fs.template ? Token.TSTRING_MIDDLE : Token.FSTRING_MIDDLE, text.toString(), litStart, i + 1);
                    i += 2; return;
                }
                if (!inSpec) throw error("f-string: single '}' is not allowed", i);
                emit(fs.template ? Token.TSTRING_MIDDLE : Token.FSTRING_MIDDLE, text.toString(), litStart, i); // a closing spec always yields a (possibly empty) middle
                int s = i; i++; emit(Token.OP, s, i);
                fs.fields.pop();
                bracketDepth--;
                closedField(fs);
                bracketDepth = fs.fields.isEmpty() ? fs.savedBracketDepth : bracketDepth;
                return;
            }
            text.append(c); i++;
        }
    }
    private boolean inSpecQuoteEscape(FString fs) { return false; }
}
