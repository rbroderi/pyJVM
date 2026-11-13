// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import pyjvm315.parser.PySyntaxError;
import pyjvm315.parser.Token;
import pyjvm315.parser.Tokenizer;

/**
 * {@code _tokenize}: the lexer behind {@code tokenize.py}. {@code TokenizerIter(readline, *, extra_tokens, encoding=None)}
 * iterates over {@code (type, string, (start row, start col), (end row, end col), line)} tuples produced by the
 * parser package's {@link Tokenizer}, with the token numbers of the emulated version's {@code token.py}.
 *
 * <p>{@code readline} is called lazily, one line each time the tokens already read run out, and the lexical errors of the
 * C tokenizer (TabError, malformed numbers, a stray backslash, unterminated strings, EOF in a continued statement) are
 * raised after the tokens before them. {@code extra_tokens=False} (the parser's own view, used only by
 * {@code python -m tokenize -e} and tests) is derived from the same stream: exact operator types, no COMMENT/NL, empty
 * NEWLINE/INDENT/DEDENT strings. See JVM_BACKEND.md "Tokenizing" for what still differs.
 */
public final class TokenizeModule {
    private TokenizeModule() {}

    private static PyClass iterClass;

    private static final String[] EXACT = {"(", ")", "[", "]", ":", ",", ";", "+", "-", "*", "/", "|", "&", "<", ">", "=", ".", "%", "{", "}",
        "==", "!=", "<=", ">=", "~", "^", "<<", ">>", "**", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", "**=", "//", "//=",
        "@", "@=", "->", "...", ":=", "!"};
    private static final int LPAR = 7, OP = 55, FSTRING_START = 59;

    static final class State {
        Object readline; boolean extra; String encoding;
        Tokenizer.Stream stream;
        final StringBuilder all = new StringBuilder();        // every line read so far
        final StringBuilder pending = new StringBuilder();    // the logical line being tokenized
        final ArrayList<Integer> starts = new ArrayList<>(List.of(0));   // offsets in `all` where physical lines start
        final ArrayDeque<Token> ready = new ArrayDeque<>();
        int emitted, implicit;                                          // tokens of the pending chunk already queued
        PySyntaxError error; boolean eof, done;
        int newlineLine;                                      // line of the last NEWLINE token
        Token pendingComment;
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_tokenize", "pyjvm315.runtime.TokenizeModule");
        Class<?> c = TokenizeModule.class;
        iterClass = define("_tokenize", "TokenizerIter", null);
        method(iterClass, c, "__init__", "init", "self,readline,extra_tokens,encoding", defaults("extra_tokens", false, "encoding", null));
        method(iterClass, c, "__iter__", "iter", "self");
        method(iterClass, c, "__next__", "next", "self");
        finish(iterClass);
        m.attrs.put("TokenizerIter", iterClass);
        return m;
    }

    public static Object init(Object self, Object readline, Object extra, Object encoding) {
        if (encoding != null && !(encoding instanceof String)) throw new PyException("TypeError", "TokenizerIter() argument 'encoding' must be str, not " + PyRuntime.typeName(encoding));
        State s = new State();
        s.readline = readline; s.extra = PyRuntime.truth(extra); s.encoding = (String) encoding;
        setState(self, s);
        return null;
    }

    public static Object iter(Object self) { state(self, State.class, "TokenizerIter"); return self; }

    /** One more call of {@code readline}; null at the end of the input (an empty string or StopIteration). */
    private static String readLine(State s) {
        Object line;
        try { line = PyRuntime.callFunction(s.readline, new ArrayList<Object>(), new LinkedHashMap<Object,Object>()); }
        catch (PyException e) { if (PyRuntime.exceptionIsSubclass(e.typeName, "StopIteration")) return null; throw e; }
        if (line instanceof PyByteSequence b) {
            line = PyRuntime.callMethod(new PyBytes(b.toByteArray()), "decode", new Object[]{s.encoding == null ? "utf-8" : s.encoding});
        }
        if (!(line instanceof String text)) throw new PyException("TypeError", "readline() returned a non-string object");
        return text.isEmpty() ? null : text;
    }

    /** Pull one line from readline and queue the tokens it completes (CPython reads a line only when it needs one). */
    private static void fill(State s) {
        String text = readLine(s);
        boolean last = text == null;
        if (!last && text.indexOf('\0') >= 0) {
            s.error = new PySyntaxError("SyntaxError", "source code cannot contain null bytes", s.starts.size(), 0);
            s.eof = true;
            return;
        }
        if (!last) {
            int base = s.all.length();
            s.pending.append(text);
            s.all.append(text);
            for (int k = 0; k < text.length(); k++) {
                char ch = text.charAt(k);
                if (ch == '\n' || (ch == '\r' && !(k + 1 < text.length() && text.charAt(k + 1) == '\n'))) s.starts.add(base + k + 1);
            }
            char end = text.charAt(text.length() - 1);
            if (end != '\n' && end != '\r') {   // like the C tokenizer, supply the missing line terminator (its token has an empty string)
                s.pending.append('\n');
                s.starts.add(s.all.length());
                s.implicit = s.pending.length();
            }
        }
        if (s.stream == null) s.stream = new Tokenizer.Stream(LangVersion.atLeast(3, 14), s.extra);
        Tokenizer.Chunk c = s.stream.feed(s.pending.toString(), last);
        for (int k = s.emitted; k < c.tokens.size(); k++) {
            Token t = c.tokens.get(k);
            if (s.implicit > 0 && t.endOffset == s.implicit && (t.type == Token.NEWLINE || t.type == Token.NL) && t.string.length() == 1) {
                t = new Token(t.type, "", t.startLine, t.startCol, t.endLine, t.endCol, t.startOffset, t.endOffset);
            }
            s.ready.add(t);
        }
        if (c.error != null) { s.error = c.error; s.eof = true; }
        else if (c.needMore) s.emitted = c.tokens.size();
        else { s.pending.setLength(0); s.emitted = 0; s.implicit = 0; }
        if (last) s.eof = true;
    }

    /** Physical lines {@code first..last} (1-based, with their terminators); empty past the end of the source. */
    private static String lines(State s, int first, int last) {
        int n = s.starts.size();
        if (first < 1 || first > n) return "";
        int from = s.starts.get(first - 1), to = last >= n ? s.all.length() : s.starts.get(last);
        return s.all.substring(from, Math.max(from, to));
    }

    /** The number of this token type in the emulated version's token.py (3.14 inserted the TSTRING tokens before COMMENT). */
    private static long number(int type) {
        boolean t = LangVersion.atLeast(3, 14);
        return switch (type) {
            case Token.OP -> OP;
            case Token.FSTRING_START, Token.FSTRING_MIDDLE, Token.FSTRING_END -> FSTRING_START + (type - Token.FSTRING_START);
            case Token.TSTRING_START, Token.TSTRING_MIDDLE, Token.TSTRING_END -> 62 + (type - Token.TSTRING_START);
            case Token.COMMENT -> t ? 65 : 62;
            case Token.NL -> t ? 66 : 63;
            case Token.ERRORTOKEN -> t ? 67 : 64;
            default -> type;   // ENDMARKER .. DEDENT
        };
    }

    private static long exact(String op) {
        for (int k = 0; k < EXACT.length; k++) if (EXACT[k].equals(op)) return LPAR + k;
        return OP;
    }

    private static PyTuple info(long type, String string, long sl, long sc, long el, long ec, String line) {
        return tuple(type, string, tuple(sl, sc), tuple(el, ec), line);
    }

    public static Object next(Object self) {
        State s = state(self, State.class, "TokenizerIter");
        while (true) {
            Token t = s.ready.poll();
            if (t != null) {
                Object r = convert(s, t);
                if (r != null) return r;
                continue;
            }
            if (s.eof) {
                if (!s.done && s.error != null) { s.done = true; throw error(s); }
                s.done = true;
                throw new PyException("StopIteration", null);
            }
            fill(s);
        }
    }

    /** The tuple for a token, or null when this view of the stream skips it. */
    private static Object convert(State s, Token t) {
        if (t.type == Token.NEWLINE) s.newlineLine = t.startLine;
        if (t.type == Token.ERRORTOKEN) {
            // a character no token starts with: CPython's tokenize reports it as an OP and leaves the complaint to the parser
            return info(OP, t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
        }
        if (s.extra) return info(number(t.type), t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
        // the parser's view
        switch (t.type) {
            case Token.COMMENT: s.pendingComment = t; return null;
            case Token.NL: s.pendingComment = null; return null;
            case Token.NEWLINE: {
                Token c = s.pendingComment; s.pendingComment = null;
                if (c != null) return info(Token.NEWLINE, c.string, c.startLine, c.startCol, c.endLine, c.endCol, lines(s, c.startLine, c.endLine));
                return info(Token.NEWLINE, "", t.startLine, t.startCol, t.startLine, t.startCol, lines(s, t.startLine, t.startLine));
            }
            case Token.INDENT: case Token.DEDENT: case Token.ENDMARKER: {
                int line = t.startLine;
                if (t.type == Token.ENDMARKER && s.all.length() == 0) return null;   // the parser's view of an empty source has no tokens
                if (t.type == Token.ENDMARKER || lines(s, line, line).isEmpty()) {   // at the end of the source
                    line = lastLine(s);
                    // the line is empty when trailing comment or blank lines were skipped after the last statement
                    return info(t.type, "", line, -1, line, -1, s.newlineLine == line ? lines(s, line, line) : "");
                }
                return info(t.type, "", line, -1, line, -1, lines(s, line, line));
            }
            case Token.OP:
                return info(exact(t.string), t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
            default:
                return info(number(t.type), t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
        }
    }

    /** The last physical line that has any text. */
    private static int lastLine(State s) {
        int n = s.starts.size();
        return n > 1 && s.starts.get(n - 1) >= s.all.length() ? n - 1 : n;
    }

    private static PyException error(State s) {
        PySyntaxError e = s.error;
        String message = e.getMessage();
        int line = e.line, column = e.column;
        if (message.endsWith("was never closed")) {
            message = "unexpected EOF in multi-line statement"; line = lastLine(s); column = 0;
        }
        PyException ex = SyntaxInfo.error(e.kind, message, "<string>", s.all.toString(), line, column);
        SyntaxInfo si = ex.syntax;
        // the C tokenizer's errors carry the offending line without its terminator (none at all for EOF in a statement)
        boolean eof = message.equals("unexpected EOF in multi-line statement");
        String text = eof || si.text == null ? null : si.text.endsWith("\n") ? si.text.substring(0, si.text.length() - 1) : si.text;
        ex.syntax = new SyntaxInfo(si.filename, si.lineno, si.offset, text, e.endColumn >= 0 ? e.endColumn : si.offset);
        if (!e.kind.equals("SyntaxError")) ex.syntax.noEnd = true;
        ex.syntax.noEndOffset = eof;
        return ex;
    }
}
