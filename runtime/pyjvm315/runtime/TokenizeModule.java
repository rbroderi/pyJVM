// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

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
 * <p>Differences from CPython's C tokenizer: {@code readline} is drained completely on the first {@code __next__}
 * (CPython reads a line at a time, which only matters for interactive sources); tokens scanned before a lexical error
 * are yielded, then SyntaxError / IndentationError is raised with the parser's message and position, which do not always
 * match the C tokenizer's (TabError and malformed number literals are not diagnosed here: the latter lex as
 * NUMBER then NAME); {@code extra_tokens=False} (the parser's own view, used only by {@code python -m tokenize -e} and
 * tests) is derived from the same stream: exact operator types, no COMMENT/NL, empty NEWLINE/INDENT/DEDENT strings.
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
        List<Token> tokens; PySyntaxError error; String source; int[] lineStarts; int pos; boolean done;
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

    private static void load(State s) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            Object line;
            try { line = PyRuntime.callFunction(s.readline, new ArrayList<Object>(), new LinkedHashMap<Object,Object>()); }
            catch (PyException e) { if (PyRuntime.exceptionIsSubclass(e.typeName, "StopIteration")) break; throw e; }
            if (line instanceof PyByteSequence b) {
                line = PyRuntime.callMethod(new PyBytes(b.toByteArray()), "decode", new Object[]{s.encoding == null ? "utf-8" : s.encoding});
            }
            if (!(line instanceof String text)) throw new PyException("TypeError", "readline() returned a non-string object");
            if (text.isEmpty()) break;
            sb.append(text);
        }
        s.source = sb.toString();
        Tokenizer.Partial p = Tokenizer.tokenizePartial(s.source, LangVersion.atLeast(3, 14));
        s.tokens = p.tokens; s.error = p.error;
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(0);
        String src = s.source;
        for (int k = 0; k < src.length(); k++) {
            char ch = src.charAt(k);
            if (ch == '\n' || (ch == '\r' && !(k + 1 < src.length() && src.charAt(k + 1) == '\n'))) starts.add(k + 1);
        }
        s.lineStarts = new int[starts.size()];
        for (int k = 0; k < s.lineStarts.length; k++) s.lineStarts[k] = starts.get(k);
    }

    /** Physical lines {@code first..last} (1-based, with their terminators); empty past the end of the source. */
    private static String lines(State s, int first, int last) {
        int n = s.lineStarts.length;
        if (first < 1 || first > n) return "";
        int from = s.lineStarts[first - 1], to = last >= n ? s.source.length() : s.lineStarts[last];
        return s.source.substring(from, Math.max(from, to));
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
        if (s.tokens == null) load(s);
        while (!s.done && s.pos < s.tokens.size()) {
            Token t = s.tokens.get(s.pos++);
            if (t.type == Token.ERRORTOKEN) {
                // a character no token starts with: CPython's tokenize reports it as an OP and leaves the complaint to the parser
                return info(OP, t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
            }
            if (s.extra) return info(number(t.type), t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
            // the parser's view
            switch (t.type) {
                case Token.COMMENT: s.pendingComment = t; continue;
                case Token.NL: s.pendingComment = null; continue;
                case Token.NEWLINE: {
                    Token c = s.pendingComment; s.pendingComment = null;
                    if (c != null) return info(Token.NEWLINE, c.string, c.startLine, c.startCol, c.endLine, c.endCol, lines(s, c.startLine, c.endLine));
                    return info(Token.NEWLINE, "", t.startLine, t.startCol, t.startLine, t.startCol, lines(s, t.startLine, t.startLine));
                }
                case Token.INDENT: case Token.DEDENT: case Token.ENDMARKER: {
                    int line = t.startLine;
                    if (t.type == Token.ENDMARKER || lines(s, line, line).isEmpty()) line = lastLine(s);   // at the end of the source
                    return info(t.type, "", line, -1, line, -1, lines(s, line, line));
                }
                case Token.OP:
                    return info(exact(t.string), t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
                default:
                    return info(number(t.type), t.string, t.startLine, t.startCol, t.endLine, t.endCol, lines(s, t.startLine, t.endLine));
            }
        }
        if (!s.done && s.error != null) {
            s.done = true;
            throw error(s);
        }
        s.done = true;
        throw new PyException("StopIteration", null);
    }

    /** The last physical line that has any text. */
    private static int lastLine(State s) {
        int n = s.lineStarts.length;
        return n > 1 && s.lineStarts[n - 1] >= s.source.length() ? n - 1 : n;
    }

    private static PyException error(State s) {
        PySyntaxError e = s.error;
        String message = e.getMessage();
        int line = e.line, column = e.column;
        if (message.endsWith("was never closed")) {
            message = "unexpected EOF in multi-line statement"; line = lastLine(s); column = 0;
        } else if (message.startsWith("unterminated") && !message.contains("(detected at line")) {
            message += " (detected at line " + (message.contains("triple-quoted") || message.contains("f-string") ? lastLine(s) : line) + ")";
        }
        return SyntaxInfo.error(e.kind, message, "<string>", s.source, line, column);
    }
}
