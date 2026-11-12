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
    private final ArrayDeque<Integer> altIndents = new ArrayDeque<>();   // columns with a tab counting 1: mixed tabs and spaces are detected by comparing both
    private final ArrayDeque<FString> fstrings = new ArrayDeque<>();

    private static final class Field { int nest; boolean inSpec; boolean afterNested; }
    private static final class FString {
        final String quote; final boolean triple, raw, template; final ArrayDeque<Field> fields = new ArrayDeque<>();
        int savedBracketDepth, start;
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
        altIndents.push(0);
    }

    // ---- incremental use by _tokenize ---------------------------------------------------------
    // Streaming and strict mode (only the _tokenize module uses them; the parser keeps the lenient lexing and its own
    // diagnostics): lines are tokenized one logical line at a time and the C tokenizer's lexical errors are diagnosed.
    private int baseLine = 0;          // physical lines before this chunk
    private boolean strict, extra, last = true, needMore, eofHit, contPending, commitBrackets;
    private int logicalStart = 0;      // offset of the physical line where the current logical line began

    /** One step of {@link Stream#feed}: the tokens scanned so far, the error that ended the scan, whether more input is needed. */
    public static final class Chunk {
        public final List<Token> tokens;
        public final PySyntaxError error;
        public final boolean needMore;
        Chunk(List<Token> tokens, PySyntaxError error, boolean needMore) { this.tokens = tokens; this.error = error; this.needMore = needMore; }
    }

    /**
     * Tokenizer state carried between chunks: the indentation stacks and, inside brackets, the nesting. {@link #feed}
     * is given the text of the current logical line so far (re-scanned as lines arrive); tokens before the unfinished
     * construct are stable, so a caller yields only those it has not yet seen.
     */
    public static final class Stream {
        private final boolean templates, extra;
        private final ArrayDeque<Integer> indents = new ArrayDeque<>(), altIndents = new ArrayDeque<>();
        private int baseLine, bracketDepth;
        public Stream(boolean templates, boolean extra) { this.templates = templates; this.extra = extra; indents.push(0); altIndents.push(0); }

        /** Tokenize {@code text}; {@code last} says the source ends with it (then the end-of-file tokens or errors follow). */
        public Chunk feed(String text, boolean last) {
            Tokenizer t = new Tokenizer(text);
            t.templates = templates; t.strict = true; t.extra = extra; t.last = last; t.baseLine = baseLine;
            t.indents.clear(); t.indents.addAll(indents);
            t.altIndents.clear(); t.altIndents.addAll(altIndents);
            if (bracketDepth > 0) { t.bracketDepth = bracketDepth; t.atLineStart = false; }
            PySyntaxError err = null;
            try { t.run(); }
            catch (PySyntaxError e) { err = e; if (!last && t.eofHit) { err = null; t.needMore = true; } }
            if (err == null && (!t.needMore || last || t.commitBrackets)) {
                indents.clear(); indents.addAll(t.indents);
                altIndents.clear(); altIndents.addAll(t.altIndents);
            }
            if (err == null && !t.needMore || last) {
                baseLine += t.lineStarts.length - (t.n > 0 && t.isNewline(t.n - 1) ? 1 : 0);
                bracketDepth = 0;
            } else if (err == null && t.commitBrackets) {   // inside brackets only the nesting matters: do not re-scan these lines
                baseLine += t.lineStarts.length - 1;
                bracketDepth = t.bracketDepth;
                return new Chunk(t.out, null, false);
            }
            return new Chunk(t.out, err, err == null && t.needMore);
        }
    }

    public static List<Token> tokenize(String source) {
        Tokenizer t = new Tokenizer(source);
        t.run();
        return t.out;
    }

    // ---- positions ----------------------------------------------------------------------------
    private int lineOf(int offset) { return lineOf0(offset) + baseLine; }
    private int lineOf0(int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1; }
        return lo + 1;
    }
    private int colOf(int offset) { int ls = lineStarts[lineOf0(offset) - 1]; return src.codePointCount(ls, Math.max(ls, offset)); }

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
            contPending = false;
            if (c == '#') { int s = i; while (i < n && !isNewline(i)) i++; emit(Token.COMMENT, s, i); continue; }
            if (c == '\n' || c == '\r') {
                int e = newlineEnd(i);
                if (bracketDepth > 0 || !fstrings.isEmpty()) emit(Token.NL, i, e);
                else { emit(Token.NEWLINE, i, e); atLineStart = true; }
                i = e;
                continue;
            }
            if (c == '\\' && isNewline(i + 1)) { i = newlineEnd(i + 1); contPending = strict && i >= n; continue; }
            if (c == '\\' && i + 1 >= n) { i++; contPending = strict; continue; }
            if (strict && c == '\\') throw lineEndError("SyntaxError", "unexpected character after line continuation character", i, true);
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                if (strict) scanNumberStrict(); else scanNumber();
                continue;
            }
            if (c == '"' || c == '\'') { scanString(i, ""); continue; }
            if (isIdentStart(src.codePointAt(i)) || (strict && src.codePointAt(i) > 127)) { scanNameOrString(); continue; }
            scanOperator();
        }
        if (last) {
            if (contPending) throw lineEndError("SyntaxError", "unexpected EOF in multi-line statement", n - 1, true);
            finish();
        } else {
            boolean terminated = n > 0 && isNewline(n - 1);
            needMore = !fstrings.isEmpty() || bracketDepth > 0 || contPending || !terminated;
            commitBrackets = needMore && terminated && fstrings.isEmpty() && !contPending && bracketDepth > 0;
        }
    }

    /**
     * A SyntaxError located like the C tokenizer's errors that carry no column of their own: at the end of the physical
     * line containing {@code at} (terminator included), counted from the start of the physical line, or from the start of
     * the logical line when {@code group} (readline's buffer holds the lines of a continued statement together).
     */
    private PySyntaxError lineEndError(String kind, String message, int at, boolean group) {
        at = Math.max(0, Math.min(at, n));
        int e = at;
        while (e < n && !isNewline(e)) e++;
        boolean terminated = e < n;
        if (terminated) e = newlineEnd(e);
        int from = group ? Math.min(logicalStart, at) : lineStarts[lineOf0(at) - 1];
        return new PySyntaxError(kind, message, lineOf(at), src.codePointCount(from, e) + (terminated ? 0 : 1));
    }

    private void finish() {
        if (!fstrings.isEmpty()) {
            FString fs = fstrings.peek();
            if (strict && !fs.fields.isEmpty() && !inLiteral(fs)) throw lineEndError("SyntaxError", "unexpected EOF in multi-line statement", n - 1, true);
            throw fsError(fs, n, n - 1);
        }
        if (bracketDepth > 0) throw error("'" + "(" + "' was never closed", n);
        int lastLine = lineOf(Math.max(n - 1, 0));
        if (n == 0) lastLine = baseLine + 1;
        boolean endsWithNewline = n > 0 && isNewline(n - 1);
        if (n > 0 && !endsWithNewline) {
            int lineStart = lineStarts[lastLine - baseLine - 1];
            boolean onlyCommentOrBlank = lastLogicalLineEmpty();
            int col = n - lineStart;
            out.add(new Token(onlyCommentOrBlank ? Token.NL : Token.NEWLINE, "", lastLine, col, lastLine, col + 1, n, n));
        }
        int endLine = (n > 0 && endsWithNewline) ? lineOf(n - 1) + 1 : lastLine + 1;
        if (n == 0) endLine = baseLine + 1;
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
        int j = i, col = 0, alt = 0, contCol = -1, contAlt = 0;
        while (j < n) {
            char c = src.charAt(j);
            if (c == ' ') { col++; alt++; }
            else if (c == '\t') { col = (col / 8 + 1) * 8; alt++; }
            else if (c == '\f') col = alt = 0;
            else if (c == '\\' && isNewline(j + 1)) {
                // Indentation cannot be split over physical lines: the first backslash fixes the level (as CPython's tokenizer does).
                if (contCol < 0) { contCol = col; contAlt = alt; }
                j = newlineEnd(j + 1); col = alt = 0; continue;
            }
            else break;
            j++;
        }
        if (j >= n) {
            i = n;
            if (strict && contCol >= 0) {   // a continuation line with nothing after it
                if (last) throw new PySyntaxError("SyntaxError", "unexpected EOF in multi-line statement", lineOf(n - 1), 0);
                contPending = true;
            }
            return false;
        }
        if (contCol >= 0) { col = contCol; alt = contAlt; }
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
        if (col == indents.peek()) {
            if (strict && alt != altIndents.peek()) throw lineEndError("TabError", TAB_ERROR, j, false);
        } else if (col > indents.peek()) {
            if (strict && alt <= altIndents.peek()) throw lineEndError("TabError", TAB_ERROR, j, false);
            indents.push(col); altIndents.push(alt);
            emit(Token.INDENT, i, j);
        } else {
            int mark = out.size();
            while (col < indents.peek()) {
                indents.pop(); altIndents.pop();
                if (col > indents.peek()) {
                    while (out.size() > mark) out.remove(out.size() - 1);   // the C tokenizer fails before it yields any DEDENT
                    if (strict) throw lineEndError("IndentationError", DEDENT_ERROR, j, false);
                    throw error("IndentationError", DEDENT_ERROR, j);
                }
                emitAt(Token.DEDENT, "", line, j - lineStarts[line - baseLine - 1], j);
            }
            if (strict && alt != altIndents.peek()) {
                while (out.size() > mark) out.remove(out.size() - 1);
                throw lineEndError("TabError", TAB_ERROR, j, false);
            }
        }
        logicalStart = lineStarts[lineOf0(j) - 1];
        i = j;
        atLineStart = false;
        return true;
    }

    private static final String TAB_ERROR = "inconsistent use of tabs and spaces in indentation";
    private static final String DEDENT_ERROR = "unindent does not match any outer indentation level";

    // ---- names, numbers, operators ------------------------------------------------------------
    static boolean isIdentStart(int c) { return c == '_' || Character.isLetter(c) || (c > 127 && Character.isUnicodeIdentifierStart(c)); }
    static boolean isIdentPart(int c) { return c == '_' || Character.isLetterOrDigit(c) || (c > 127 && Character.isUnicodeIdentifierPart(c) && !Character.isIdentifierIgnorable(c)); }

    private void scanNameOrString() {
        int s = i;
        while (i < n) {
            int cp = src.codePointAt(i);
            if (!isIdentPart(cp) && !(strict && cp > 127)) break;
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

    // The C tokenizer's number scanning (tok_get): an index-based port so malformed literals fail where and as it does.
    private int ch(int k) { return k >= 0 && k < n ? src.charAt(k) : -1; }
    private int nc() { int c = ch(i); if (c != -1) i++; return c; }
    private void back(int c) { if (c != -1) i--; }
    private static boolean dig(int c) { return c >= '0' && c <= '9'; }
    private static boolean hex(int c) { return dig(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
    /** The offset CPython reports for an error with the cursor before index {@code at}: the characters preceding it. */
    private PySyntaxError numberError(String message, int at) {
        int a = Math.min(at, n), ls = lineStarts[lineOf0(Math.max(a - 1, 0)) - 1];
        return new PySyntaxError("SyntaxError", message, lineOf(Math.max(a - 1, 0)), src.codePointCount(ls, Math.max(ls, a)));
    }

    private int decimalTail() {
        int c;
        while (true) {
            do { c = nc(); } while (dig(c));
            if (c != '_') break;
            c = nc();
            if (!dig(c)) { back(c); throw numberError("invalid decimal literal", i); }
        }
        return c;
    }

    /** verify_end_of_number: a literal may not run into a name (keywords such as {@code 1if} only warn); relaxed for tokenize's extra tokens. */
    private void verifyEnd(int c, String kind) {
        if (extra) return;
        boolean keyword = false;
        switch (c) {
            case 'a': keyword = src.startsWith("nd", i); break;
            case 'e': keyword = src.startsWith("lse", i); break;
            case 'f': keyword = src.startsWith("or", i); break;
            case 'i': { int c2 = ch(i); keyword = c2 == 'f' || c2 == 'n' || c2 == 's'; break; }
            case 'o': keyword = src.startsWith("r", i); break;
            case 'n': keyword = src.startsWith("ot", i); break;
            default: break;
        }
        if (!keyword && c >= 0 && c < 128 && (c == '_' || Character.isLetterOrDigit(c))) { back(c); throw numberError("invalid " + kind + " literal", i); }
    }

    private void exponentAndRest(int c) {
        if (c == 'e' || c == 'E') {
            int e = c;
            c = nc();
            if (c == '+' || c == '-') {
                c = nc();
                if (!dig(c)) { back(c); throw numberError("invalid decimal literal", i); }
            } else if (!dig(c)) {
                back(c);
                verifyEnd(e, "decimal");
                back(e);
                return;
            }
            c = decimalTail();
        }
        if (c == 'j' || c == 'J') { c = nc(); verifyEnd(c, "imaginary"); }
        else verifyEnd(c, "decimal");
        back(c);
    }

    private void radixDigits(int c, String kind, int radix) {
        // c is the character after the prefix
        do {
            if (c == '_') c = nc();
            boolean ok = radix == 16 ? hex(c) : c >= '0' && c < '0' + radix;
            if (!ok) {
                if (radix != 16 && dig(c)) throw numberError("invalid digit '" + (char) c + "' in " + kind + " literal", i);
                back(c);
                throw numberError("invalid " + kind + " literal", i);
            }
            do { c = nc(); } while (radix == 16 ? hex(c) : c >= '0' && c < '0' + radix);
        } while (c == '_');
        if (radix != 16 && dig(c)) throw numberError("invalid digit '" + (char) c + "' in " + kind + " literal", i);
        verifyEnd(c, kind);
        back(c);
    }

    private void scanNumberStrict() {
        int s = i;
        int c = nc();
        if (c == '.') {
            c = nc();
            c = decimalTail1(c);
            exponentAndRest(c);
        } else if (c == '0') {
            c = nc();
            if (c == 'x' || c == 'X') radixDigits(nc(), "hexadecimal", 16);
            else if (c == 'o' || c == 'O') radixDigits(nc(), "octal", 8);
            else if (c == 'b' || c == 'B') radixDigits(nc(), "binary", 2);
            else {
                boolean nonzero = false;
                while (true) {
                    if (c == '_') {
                        c = nc();
                        if (!dig(c)) { back(c); throw numberError("invalid decimal literal", i); }
                    }
                    if (c != '0') break;
                    c = nc();
                }
                int zerosEnd = i;
                if (dig(c)) { nonzero = true; c = decimalTail(); }
                if (c == '.') { c = nc(); c = decimalTail1(c); exponentAndRest(c); }
                else if (c == 'e' || c == 'E') exponentAndRest(c);
                else if (c == 'j' || c == 'J') { c = nc(); verifyEnd(c, "imaginary"); back(c); }
                else if (nonzero && !extra) {
                    back(c);
                    int ls = lineStarts[lineOf0(s) - 1];
                    PySyntaxError leading = new PySyntaxError("SyntaxError", "leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers",
                        lineOf(s), src.codePointCount(ls, s) + 1);
                    leading.endColumn = src.codePointCount(ls, zerosEnd);
                    throw leading;
                } else { verifyEnd(c, "decimal"); back(c); }
            }
        } else {
            c = decimalTail();
            if (c == '.') { c = nc(); c = decimalTail1(c); }
            exponentAndRest(c);
        }
        emit(Token.NUMBER, s, i);
    }

    /** The fraction digits after a '.', with {@code c} the character after it. */
    private int decimalTail1(int c) { return dig(c) ? decimalTail() : c; }

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
            fs.savedBracketDepth = bracketDepth; fs.start = start;
            fstrings.push(fs);
            return;
        }
        int k = p + quote.length();
        while (true) {
            if (k >= n) { eofHit = true; throw stringError(triple ? "unterminated triple-quoted string literal" : "unterminated string literal", start, n - 1); }
            char c = src.charAt(k);
            if (c == '\\') { k += 2; continue; }
            if (!triple && (c == '\n' || c == '\r')) throw stringError("unterminated string literal", start, k);
            if (c == q && (!triple || (k + 2 < n && src.charAt(k + 1) == q && src.charAt(k + 2) == q))) { k += quote.length(); break; }
            k++;
        }
        i = k;
        emit(Token.STRING, start, i);
    }

    /** The C tokenizer adds the line where an unterminated string was noticed to the message. */
    private PySyntaxError stringError(String message, int start, int detect) {
        return error(strict ? message + " (detected at line " + lineOf(Math.max(0, Math.min(detect, n - 1))) + ")" : message, start);
    }

    /** An unterminated f-string: the C tokenizer reports it at the start of the string. */
    private PySyntaxError fsError(FString fs, int at, int detect) {
        if (!strict) return error("unterminated f-string literal", at);
        return stringError("unterminated " + (fs.triple ? "triple-quoted " : "") + (fs.template ? "t" : "f") + "-string literal", fs.start, detect);
    }

    private boolean inLiteral(FString fs) { Field f = fs.fields.peek(); return f == null || f.inSpec; }

    private void scanFStringLiteral() {
        FString fs = fstrings.peek();
        Field field = fs.fields.peek();
        boolean inSpec = field != null;
        int litStart = i;
        StringBuilder text = new StringBuilder();
        while (true) {
            if (i >= n) { eofHit = true; throw fsError(fs, litStart, n - 1); }
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
            if (!fs.triple && (c == '\n' || c == '\r')) throw fsError(fs, litStart, i);
            if (c == '\\') {
                if (i + 1 < n) {
                    if (!fs.raw && src.charAt(i + 1) == 'N' && i + 2 < n && src.charAt(i + 2) == '{') {
                        int close = src.indexOf('}', i + 3);
                        if (close < 0) throw fsError(fs, litStart, i);
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
