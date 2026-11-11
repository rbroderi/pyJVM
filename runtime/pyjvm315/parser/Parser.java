// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.parser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser for Python (3.13 grammar plus 3.14/3.15 additions) producing {@link AstNode} trees
 * that match CPython's {@code ast.parse} (verified by tools/compare_ast.py over the standard library).
 */
public final class Parser {
    private static final Set<String> KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "async", "await", "break",
        "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
        "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield");

    private final String src;
    private final Token[] toks;
    private int p = 0;
    private Token prevReal;
    private final String[] lines;
    private final boolean[] ascii;
    private final IdentityHashMap<AstNode, Boolean> parenthesized = new IdentityHashMap<>();
    private static final AstNode LOAD = new AstNode("Load"), STORE = new AstNode("Store"), DEL = new AstNode("Del");

    private int featureMinor = Integer.MAX_VALUE;
    /** PEP 810 context for {@code lazy import}: 0 module, 1 function, 2 class; and the depth of enclosing try statements. */
    private int lazyScope, lazyTry;

    /** CPython's CHECK_VERSION: syntax introduced in 3.{@code since} fails under an older feature_version. */
    private void gate(int since, String what) {
        if (featureMinor >= since) return;
        Token at = prevReal != null ? prevReal : peek();
        throw new PySyntaxError("SyntaxError", what + " in Python 3." + since + " and greater", at.startLine, at.startCol + 1);
    }

    private Parser(String src) {
        this.src = src;
        List<Token> all = Tokenizer.tokenize(src), kept = new ArrayList<>();
        for (Token t : all) {
            if (t.type == Token.COMMENT || t.type == Token.NL) continue;
            if (t.type == Token.NAME && !isAscii(t.string)) { // identifiers are NFKC-normalised, positions stay as written
                String norm = java.text.Normalizer.normalize(t.string, java.text.Normalizer.Form.NFKC);
                t = new Token(Token.NAME, norm, t.startLine, t.startCol, t.endLine, t.endCol, t.startOffset, t.endOffset);
            }
            if (t.type == Token.ERRORTOKEN) throw new PySyntaxError("SyntaxError", "invalid syntax", t.startLine, t.startCol + 1);
            kept.add(t);
        }
        toks = kept.toArray(new Token[0]);
        List<String> ls = new ArrayList<>();
        int start = 0;
        for (int k = 0; k <= src.length(); k++) {
            if (k == src.length() || src.charAt(k) == '\n' || (src.charAt(k) == '\r' && !(k + 1 < src.length() && src.charAt(k + 1) == '\n'))) {
                ls.add(src.substring(start, k)); start = k + 1;
            }
        }
        lines = ls.toArray(new String[0]);
        ascii = new boolean[lines.length];
        for (int k = 0; k < lines.length; k++) { boolean a = true; for (int j = 0; j < lines[k].length(); j++) if (lines[k].charAt(j) > 127) { a = false; break; } ascii[k] = a; }
    }

    private static boolean isAscii(String s) { for (int k = 0; k < s.length(); k++) if (s.charAt(k) > 127) return false; return true; }

    /** @param mode "exec" or "eval" ("single" parses like exec but yields Interactive). */
    public static AstNode parse(String source, String mode) { return parse(source, mode, Integer.MAX_VALUE); }

    /**
     * @param featureMinor the 3.x grammar level ast.parse(feature_version=(3, x)) asks for: syntax newer than that
     *                     is rejected with CPython's wording. Integer.MAX_VALUE accepts everything.
     */
    public static AstNode parse(String source, String mode, int featureMinor) {
        Parser parser = new Parser(source);
        parser.featureMinor = featureMinor;
        return switch (mode) {
            case "eval" -> parser.parseEval();
            case "single" -> new AstNode("Interactive", parser.parseModuleBody());
            default -> new AstNode("Module", parser.parseModuleBody(), new ArrayList<>());
        };
    }

    // ---- token helpers ------------------------------------------------------------------------
    private Token peek() { return toks[p]; }
    private Token peek(int k) { return toks[Math.min(p + k, toks.length - 1)]; }
    private Token next() {
        Token t = toks[p++];
        if (t.type != Token.NEWLINE && t.type != Token.INDENT && t.type != Token.DEDENT && t.type != Token.ENDMARKER) prevReal = t;
        return t;
    }
    private boolean atOp(String s) { Token t = peek(); return t.type == Token.OP && t.string.equals(s); }
    private boolean atKw(String s) { Token t = peek(); return t.type == Token.NAME && t.string.equals(s); }
    private boolean acceptOp(String s) { if (atOp(s)) { next(); return true; } return false; }
    private boolean acceptKw(String s) { if (atKw(s)) { next(); return true; } return false; }
    private Token expectOp(String s) { if (!atOp(s)) throw unexpected(peek(), "expected '" + s + "'"); return next(); }
    private Token expectKw(String s) { if (!atKw(s)) throw unexpected(peek(), "expected '" + s + "'"); return next(); }
    private PySyntaxError unexpected(Token t, String msg) {
        if (t.type == Token.INDENT) return new PySyntaxError("IndentationError", "unexpected indent", t.startLine, t.startCol + 1);
        return new PySyntaxError("SyntaxError", msg == null ? "invalid syntax" : msg, t.startLine, t.startCol + 1);
    }
    private PySyntaxError invalid() { return unexpected(peek(), "invalid syntax"); }
    private boolean isIdentifier(Token t) { return t.type == Token.NAME && !KEYWORDS.contains(t.string); }
    private Token expectName() {
        if (!isIdentifier(peek())) throw invalid();
        return next();
    }

    // ---- positions ----------------------------------------------------------------------------
    private int bcol(int line, int cpCol) {
        if (line - 1 >= lines.length || ascii[line - 1]) return cpCol;
        String s = lines[line - 1]; int bytes = 0, idx = 0;
        for (int k = 0; k < cpCol && idx < s.length(); k++) {
            int cp = s.codePointAt(idx); idx += Character.charCount(cp);
            bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        }
        return bytes;
    }
    private AstNode at(AstNode n, int sl, int sc, int el, int ec) {
        n.lineno = sl; n.colOffset = bcol(sl, sc); n.endLineno = el; n.endColOffset = bcol(el, ec); return n;
    }
    private AstNode mk(String type, Token s, Token e, Object... vals) { return at(new AstNode(type, vals), s.startLine, s.startCol, e.endLine, e.endCol); }
    private AstNode mk(String type, Token s, AstNode e, Object... vals) { return span(new AstNode(type, vals), s, e); }
    private AstNode span(AstNode n, Token s, AstNode e) {
        n.lineno = s.startLine; n.colOffset = bcol(s.startLine, s.startCol); n.endLineno = e.endLineno; n.endColOffset = e.endColOffset; return n;
    }
    private AstNode mk(String type, AstNode s, AstNode e, Object... vals) {
        AstNode n = new AstNode(type, vals);
        n.lineno = s.lineno; n.colOffset = s.colOffset; n.endLineno = e.endLineno; n.endColOffset = e.endColOffset; return n;
    }
    private AstNode mk(String type, AstNode s, Token e, Object... vals) {
        AstNode n = new AstNode(type, vals);
        n.lineno = s.lineno; n.colOffset = s.colOffset; n.endLineno = e.endLine; n.endColOffset = bcol(e.endLine, e.endCol); return n;
    }
    /** Statements of a compound statement end where their last body statement ends. */
    private AstNode lastOf(List<Object> body) { return (AstNode) body.get(body.size() - 1); }
    private static List<Object> list() { return new ArrayList<>(); }

    // ---- module -------------------------------------------------------------------------------
    private List<Object> parseModuleBody() {
        List<Object> body = list();
        while (peek().type != Token.ENDMARKER) {
            if (peek().type == Token.NEWLINE) { next(); continue; }
            body.addAll(parseStatement());
        }
        return body;
    }

    private AstNode parseEval() {
        while (peek().type == Token.NEWLINE) next();
        AstNode body = parseStarExpressions();
        while (peek().type == Token.NEWLINE) next();
        if (peek().type != Token.ENDMARKER) throw invalid();
        return new AstNode("Expression", body);
    }

    // ---- statements ---------------------------------------------------------------------------
    private List<Object> parseStatement() {
        Token t = peek();
        if (t.type == Token.INDENT) throw new PySyntaxError("IndentationError", "unexpected indent", t.startLine, t.startCol + 1);
        if (t.type == Token.NAME) {
            switch (t.string) {
                case "if": return one(parseIf());
                case "while": return one(parseWhile());
                case "for": return one(parseFor(null));
                case "try": return one(parseTry());
                case "with": return one(parseWith(null));
                case "def": return one(parseFuncDef(list(), null));
                case "class": return one(parseClassDef(list()));
                case "async": {
                    Token a = next();
                    if (atKw("def")) return one(parseFuncDef(list(), a));
                    if (atKw("for")) return one(parseFor(a));
                    if (atKw("with")) return one(parseWith(a));
                    throw invalid();
                }
                case "match": { List<Object> m = tryMatch(); if (m != null) return m; break; }
                default: break;
            }
        } else if (t.type == Token.OP && t.string.equals("@")) return one(parseDecorated());
        return parseSimpleStatements();
    }
    private static List<Object> one(AstNode n) { List<Object> l = list(); l.add(n); return l; }

    private List<Object> parseSimpleStatements() {
        List<Object> out = list();
        out.add(parseSimpleStatement());
        while (acceptOp(";")) {
            if (peek().type == Token.NEWLINE) break;
            out.add(parseSimpleStatement());
        }
        if (peek().type != Token.NEWLINE && peek().type != Token.ENDMARKER) throw invalid();
        if (peek().type == Token.NEWLINE) next();
        return out;
    }

    private List<Object> parseBlock() {
        expectOp(":");
        if (peek().type == Token.NEWLINE) {
            next();
            if (peek().type != Token.INDENT) throw new PySyntaxError("IndentationError", "expected an indented block", peek().startLine, peek().startCol + 1);
            next();
            List<Object> body = list();
            while (peek().type != Token.DEDENT && peek().type != Token.ENDMARKER) body.addAll(parseStatement());
            if (peek().type == Token.DEDENT) next();
            return body;
        }
        return parseSimpleStatements();
    }

    private AstNode parseSimpleStatement() {
        Token t = peek();
        if (t.type == Token.NAME) {
            switch (t.string) {
                case "pass": next(); return mk("Pass", t, t);
                case "break": next(); return mk("Break", t, t);
                case "continue": next(); return mk("Continue", t, t);
                case "return": {
                    next(); AstNode v = canStartExpression(peek()) ? parseStarExpressions() : null;
                    return mk("Return", t, prevReal, v);
                }
                case "raise": {
                    next(); AstNode exc = null, cause = null;
                    if (canStartExpression(peek())) { exc = parseExpression(); if (acceptKw("from")) cause = parseExpression(); }
                    return mk("Raise", t, prevReal, exc, cause);
                }
                case "global": case "nonlocal": {
                    next(); List<Object> names = list();
                    do { names.add(expectName().string); } while (acceptOp(","));
                    return mk(t.string.equals("global") ? "Global" : "Nonlocal", t, prevReal, names);
                }
                case "del": {
                    next(); List<Object> targets = list();
                    do {
                        if (!canStartExpression(peek())) break;
                        AstNode target = parseBitwiseOr(); setCtx(target, DEL, "delete"); targets.add(target);
                    } while (acceptOp(","));
                    return mk("Delete", t, prevReal, targets);
                }
                case "assert": {
                    next(); AstNode test = parseExpression(), msg = null;
                    if (acceptOp(",")) msg = parseExpression();
                    return mk("Assert", t, prevReal, test, msg);
                }
                case "import": return parseImport(t, false);
                case "from": return parseFrom(t, false);
                case "lazy":
                    if (featureMinor >= 15 && pyjvm315.runtime.LangVersion.atLeast(3, 15) && peek(1).type == Token.NAME && (peek(1).string.equals("import") || peek(1).string.equals("from"))) {
                        next();
                        return atKw("import") ? parseImport(t, true) : parseFrom(t, true);
                    }
                    break;
                case "type":
                    if (isIdentifier(peek(1)) && (peek(2).type == Token.OP && (peek(2).string.equals("=") || peek(2).string.equals("[")))) return parseTypeAlias();
                    break;
                default: break;
            }
        }
        return parseExpressionStatement();
    }

    /** PEP 810: a lazy import is only legal at module level and outside try statements. */
    private void checkLazyPlacement(Token start, String what) {
        String where = lazyScope == 1 ? "functions" : lazyScope == 2 ? "classes" : lazyTry > 0 ? "try/except blocks" : null;
        if (where != null) throw new PySyntaxError("SyntaxError", what + " not allowed inside " + where, start.startLine, start.startCol + 1);
    }

    private AstNode parseImport(Token start, boolean lazy) {
        if (lazy) checkLazyPlacement(start, "lazy import");
        expectKw("import");
        List<Object> names = list();
        do {
            Token s = peek(); String name = dottedName();
            String as = null;
            if (acceptKw("as")) as = expectName().string;
            names.add(mk("alias", s, prevReal, name, as));
        } while (acceptOp(","));
        return mk("Import", start, prevReal, names, lazy ? 1L : 0L);
    }
    private String dottedName() {
        StringBuilder sb = new StringBuilder(expectName().string);
        while (atOp(".")) { next(); sb.append('.').append(expectName().string); }
        return sb.toString();
    }
    private AstNode parseFrom(Token start, boolean lazy) {
        if (lazy) checkLazyPlacement(start, "lazy from ... import");
        expectKw("from");
        long level = 0;
        while (atOp(".") || atOp("...")) level += next().string.length();
        String module = null;
        if (!atKw("import")) module = dottedName();
        if (lazy && "__future__".equals(module)) throw unexpected(start, "lazy from __future__ import is not allowed");
        expectKw("import");
        List<Object> names = list();
        if (atOp("*")) {
            if (lazy) throw new PySyntaxError("SyntaxError", "lazy from ... import * is not allowed", start.startLine, start.startCol + 1);
            Token s = next(); names.add(mk("alias", s, s, "*", null));
        }
        else {
            boolean paren = acceptOp("(");
            do {
                if (paren && atOp(")")) break;
                Token s = expectName(); String as = null;
                if (acceptKw("as")) as = expectName().string;
                names.add(mk("alias", s, prevReal, s.string, as));
            } while (acceptOp(","));
            if (paren) expectOp(")");
            else if (prevReal != null && prevReal.string.equals(",")) throw unexpected(prevReal, "trailing comma not allowed without surrounding parentheses");
        }
        return mk("ImportFrom", start, prevReal, module, names, level, lazy ? 1L : 0L);
    }

    private AstNode parseTypeAlias() {
        Token t = next();
        gate(12, "Type statement is only supported");
        Token nameTok = expectName();
        AstNode name = mk("Name", nameTok, nameTok, nameTok.string, STORE);
        List<Object> tp = atOp("[") ? parseTypeParams() : list();
        expectOp("=");
        AstNode value = parseExpression();
        return mk("TypeAlias", t, prevReal, name, tp, value);
    }

    private List<Object> parseTypeParams() {
        expectOp("[");
        List<Object> out = list();
        do {
            if (atOp("]")) break;
            Token s = peek();
            if (acceptOp("*")) {
                Token n = expectName(); AstNode def = null;
                if (acceptOp("=")) { gate(13, "Type parameter defaults are only supported"); def = parseStarExpression(); }
                out.add(mk("TypeVarTuple", s, prevReal, n.string, def));
            } else if (acceptOp("**")) {
                Token n = expectName(); AstNode def = null;
                if (acceptOp("=")) { gate(13, "Type parameter defaults are only supported"); def = parseExpression(); }
                out.add(mk("ParamSpec", s, prevReal, n.string, def));
            } else {
                Token n = expectName(); AstNode bound = null, def = null;
                if (acceptOp(":")) bound = parseExpression();
                if (acceptOp("=")) { gate(13, "Type parameter defaults are only supported"); def = parseExpression(); }
                out.add(mk("TypeVar", s, prevReal, n.string, bound, def));
            }
        } while (acceptOp(","));
        expectOp("]");
        gate(12, "Type parameter lists are only supported");
        return out;
    }

    // ---- expression statements / assignment ----------------------------------------------------
    private static final Set<String> AUG = Set.of("+=", "-=", "*=", "/=", "//=", "%=", "@=", "&=", "|=", "^=", ">>=", "<<=", "**=");

    private AstNode parseExpressionStatement() {
        Token s = peek();
        AstNode first = atKw("yield") ? parseYield() : parseStarExpressions();
        if (atOp("=")) {
            List<Object> targets = list();
            targets.add(first);
            AstNode value;
            while (true) {
                next();
                AstNode rhs = atKw("yield") ? parseYield() : parseStarExpressions();
                if (atOp("=")) targets.add(rhs); else { value = rhs; break; }
            }
            for (Object t : targets) setCtx((AstNode) t, STORE, "assign to");
            return mk("Assign", s, prevReal, targets, value, null);
        }
        if (atOp(":") ) {
            if (!(first.is("Name") || first.is("Attribute") || first.is("Subscript"))) {
                if (first.is("Tuple")) throw unexpected(s, "only single target (not tuple) can be annotated");
                if (first.is("List")) throw unexpected(s, "only single target (not list) can be annotated");
                throw unexpected(s, "illegal target for annotation");
            }
            next();
            AstNode annotation = parseExpression(), value = null;
            if (acceptOp("=")) value = atKw("yield") ? parseYield() : parseStarExpressions();
            setCtx(first, STORE, "assign to");
            long simple = first.is("Name") && !parenthesized.containsKey(first) ? 1L : 0L;
            return mk("AnnAssign", s, prevReal, first, annotation, value, simple);
        }
        if (peek().type == Token.OP && AUG.contains(peek().string)) {
            if (!(first.is("Name") || first.is("Attribute") || first.is("Subscript")))
                throw unexpected(s, "'" + describe(first) + "' is an illegal expression for augmented assignment");
            String op = next().string;
            AstNode value = atKw("yield") ? parseYield() : parseStarExpressions();
            setCtx(first, STORE, "assign to");
            return mk("AugAssign", s, prevReal, first, new AstNode(binOpName(op.substring(0, op.length() - 1))), value);
        }
        return mk("Expr", s, prevReal, first);
    }

    private static String binOpName(String op) {
        return switch (op) {
            case "+" -> "Add"; case "-" -> "Sub"; case "*" -> "Mult"; case "/" -> "Div"; case "//" -> "FloorDiv"; case "%" -> "Mod";
            case "@" -> "MatMult"; case "&" -> "BitAnd"; case "|" -> "BitOr"; case "^" -> "BitXor"; case ">>" -> "RShift";
            case "<<" -> "LShift"; case "**" -> "Pow"; default -> throw new IllegalArgumentException(op);
        };
    }

    private String describe(AstNode n) {
        return switch (n.type()) {
            case "Tuple" -> "tuple"; case "List" -> "list"; case "Call" -> "function call"; case "Constant" -> "literal";
            case "BinOp", "UnaryOp", "BoolOp", "Compare" -> "expression"; case "Lambda" -> "lambda"; case "IfExp" -> "conditional expression";
            case "Dict" -> "dict literal"; case "Set" -> "set display"; case "JoinedStr" -> "f-string expression";
            case "ListComp" -> "list comprehension"; case "GeneratorExp" -> "generator expression"; case "Await" -> "await expression";
            case "Yield", "YieldFrom" -> "yield expression"; case "NamedExpr" -> "named expression"; case "Starred" -> "starred";
            default -> n.type();
        };
    }

    /** Set the expression context of an assignment/deletion target, rejecting invalid targets. */
    private void setCtx(AstNode n, AstNode ctx, String what) {
        switch (n.type()) {
            case "Name", "Attribute", "Subscript" -> n.set("ctx", ctx);
            case "Starred" -> { n.set("ctx", ctx); setCtx(n.node("value"), ctx, what); }
            case "Tuple", "List" -> { n.set("ctx", ctx); for (Object e : n.list("elts")) setCtx((AstNode) e, ctx, what); }
            default -> {
                String verb = ctx == DEL ? "delete" : "assign to";
                throw new PySyntaxError("SyntaxError", "cannot " + verb + " " + describe(n), n.lineno, n.colOffset + 1);
            }
        }
    }

    // ---- compound statements -------------------------------------------------------------------
    private AstNode parseIf() {
        Token s = next();
        AstNode test = parseNamedExpression();
        List<Object> body = parseBlock(), orelse = list();
        if (atKw("elif")) orelse.add(parseIf());
        else if (atKw("else")) { next(); orelse = parseBlock(); }
        return mk("If", s, prevReal, test, body, orelse);
    }
    private AstNode parseWhile() {
        Token s = next();
        AstNode test = parseNamedExpression();
        List<Object> body = parseBlock(), orelse = list();
        if (acceptKw("else")) orelse = parseBlock();
        return mk("While", s, prevReal, test, body, orelse);
    }
    private AstNode parseFor(Token async) {
        Token s = async != null ? async : peek();
        expectKw("for");
        AstNode target = parseTargetList();
        expectKw("in");
        AstNode iter = parseStarExpressions();
        List<Object> body = parseBlock(), orelse = list();
        if (acceptKw("else")) orelse = parseBlock();
        return mk(async != null ? "AsyncFor" : "For", s, prevReal, target, iter, body, orelse, null);
    }

    private AstNode parseTry() {
        lazyTry++;
        try { return parseTry0(); } finally { lazyTry--; }
    }

    private AstNode parseTry0() {
        Token s = next();
        List<Object> body = parseBlock(), handlers = list(), orelse = list(), fin = list();
        boolean star = false;
        while (atKw("except")) {
            Token et = next();
            boolean isStar = acceptOp("*");
            if (isStar) gate(11, "Exception groups are only supported");
            if (handlers.isEmpty()) star = isStar; else if (star != isStar) throw unexpected(et, "cannot have both 'except' and 'except*' on the same 'try'");
            AstNode type = null; String name = null;
            if (!atOp(":")) {
                Token ts = peek();
                type = parseExpression();
                if (atOp(",")) { // PEP 758: except A, B:
                    gate(14, "except expressions without parentheses are only supported");
                    List<Object> elts = list(); elts.add(type);
                    while (acceptOp(",")) { if (atOp(":") || atKw("as")) break; elts.add(parseExpression()); }
                    type = mk("Tuple", ts, prevReal, elts, LOAD);
                }
                if (acceptKw("as")) name = expectName().string;
            } else if (isStar) throw unexpected(peek(), "expected one or more exception types");
            List<Object> hbody = parseBlock();
            handlers.add(mk("ExceptHandler", et, prevReal, type, name, hbody));
        }
        if (!handlers.isEmpty() && acceptKw("else")) orelse = parseBlock();
        if (acceptKw("finally")) fin = parseBlock();
        if (handlers.isEmpty() && fin.isEmpty()) throw unexpected(peek(), "expected 'except' or 'finally' block");
        return mk(star ? "TryStar" : "Try", s, prevReal, body, handlers, orelse, fin);
    }

    private AstNode parseWith(Token async) {
        Token s = async != null ? async : peek();
        expectKw("with");
        List<Object> items = null;
        if (atOp("(")) { // parenthesised with-items; fall back to an ordinary expression on failure
            int save = p; Token savePrev = prevReal;
            try {
                next();
                List<Object> tmp = list();
                do { if (atOp(")")) break; tmp.add(parseWithItem()); } while (acceptOp(","));
                expectOp(")");
                if (!atOp(":")) throw invalid();
                items = tmp;
            } catch (PySyntaxError e) { p = save; prevReal = savePrev; items = null; }
        }
        if (items == null) {
            items = list();
            do { items.add(parseWithItem()); } while (acceptOp(","));
        }
        List<Object> body = parseBlock();
        return mk(async != null ? "AsyncWith" : "With", s, prevReal, items, body, null);
    }
    private AstNode parseWithItem() {
        AstNode ctx = parseExpression(), vars = null;
        if (acceptKw("as")) { vars = parseStarTarget(); setCtx(vars, STORE, "assign to"); }
        return new AstNode("withitem", ctx, vars);
    }

    private AstNode parseDecorated() {
        List<Object> decorators = list();
        while (atOp("@")) {
            next(); decorators.add(parseNamedExpression());
            if (peek().type != Token.NEWLINE) throw invalid();
            next();
        }
        if (atKw("def")) return parseFuncDef(decorators, null);
        if (atKw("class")) return parseClassDef(decorators);
        if (atKw("async")) { Token a = next(); if (!atKw("def")) throw invalid(); return parseFuncDef(decorators, a); }
        throw invalid();
    }

    private AstNode parseFuncDef(List<Object> decorators, Token async) {
        Token s = async != null ? async : peek();
        expectKw("def");
        Token name = expectName();
        List<Object> tp = atOp("[") ? parseTypeParams() : list();
        expectOp("(");
        AstNode args = parseArguments(false);
        expectOp(")");
        AstNode returns = null;
        if (acceptOp("->")) returns = parseExpression();
        int savedScope = lazyScope, savedTry = lazyTry;
        lazyScope = 1; lazyTry = 0;
        List<Object> body;
        try { body = parseBlock(); } finally { lazyScope = savedScope; lazyTry = savedTry; }
        return mk(async != null ? "AsyncFunctionDef" : "FunctionDef", s, prevReal, name.string, args, body, decorators, returns, null, tp);
    }

    private AstNode parseClassDef(List<Object> decorators) {
        Token s = next();
        Token name = expectName();
        List<Object> tp = atOp("[") ? parseTypeParams() : list();
        List<Object> bases = list(), keywords = list();
        if (acceptOp("(")) { parseCallArguments(bases, keywords, null); expectOp(")"); }
        int savedScope = lazyScope, savedTry = lazyTry;
        lazyScope = 2; lazyTry = 0;
        List<Object> body;
        try { body = parseBlock(); } finally { lazyScope = savedScope; lazyTry = savedTry; }
        return mk("ClassDef", s, prevReal, name.string, bases, keywords, body, decorators, tp);
    }

    /** Parameters of a def (stop at ')') or lambda (stop at ':'). */
    private AstNode parseArguments(boolean lambda) {
        List<Object> posonly = list(), args = list(), kwonly = list(), kwDefaults = list(), defaults = list();
        AstNode vararg = null, kwarg = null;
        boolean star = false;
        String end = lambda ? ":" : ")";
        while (!atOp(end)) {
            if (atOp("/")) {
                Token t = next();
                gate(8, "Positional-only parameters are only supported");
                if (star || !posonly.isEmpty() || args.isEmpty()) throw unexpected(t, "at least one argument must precede /");
                posonly.addAll(args); args.clear();
            } else if (atOp("*")) {
                Token t = next();
                if (star) throw unexpected(t, "* argument may appear only once");
                star = true;
                if (!atOp(",") && !atOp(end)) vararg = parseParam(lambda, true);
                else if (atOp(end) || (atOp(",") && (peek(1).type == Token.OP && (peek(1).string.equals(end) || peek(1).string.equals("**")))))
                    throw unexpected(t, "named arguments must follow bare *");
            } else if (atOp("**")) {
                next(); kwarg = parseParam(lambda, false);
                acceptOp(",");
                if (!atOp(end)) throw unexpected(peek(), "arguments cannot follow var-keyword argument");
                break;
            } else {
                AstNode a = parseParam(lambda, false);
                AstNode def = null;
                if (acceptOp("=")) def = parseExpression();
                if (star) { kwonly.add(a); kwDefaults.add(def); }
                else {
                    args.add(a);
                    if (def != null) defaults.add(def);
                    else if (!defaults.isEmpty()) throw unexpected(peek(), "parameter without a default follows parameter with a default");
                }
            }
            if (!acceptOp(",")) break;
        }
        return new AstNode("arguments", posonly, args, vararg, kwonly, kwDefaults, kwarg, defaults);
    }
    private AstNode parseParam(boolean lambda, boolean starAnnotation) {
        Token n = expectName(); AstNode ann = null;
        if (!lambda && acceptOp(":")) ann = starAnnotation && atOp("*") ? parseStarExpression() : parseExpression();
        return mk("arg", n, prevReal, n.string, ann, null);
    }

    // ---- match statement -----------------------------------------------------------------------
    private List<Object> tryMatch() {
        int save = p; Token savePrev = prevReal;
        try {
            Token s = next();
            Token ss = peek();
            AstNode subject = parseStarNamedExpression();
            if (atOp(",")) {
                List<Object> elts = list(); elts.add(subject);
                while (acceptOp(",")) { if (atOp(":")) break; elts.add(parseStarNamedExpression()); }
                subject = mk("Tuple", ss, prevReal, elts, LOAD);
            }
            if (!atOp(":")) throw invalid();
            next();
            if (peek().type != Token.NEWLINE) throw invalid();
            next();
            if (peek().type != Token.INDENT) throw invalid();
            next();
            if (!atKw("case")) throw invalid();
            List<Object> cases = list();
            while (atKw("case")) {
                Token cs = next();
                AstNode pattern = parseTopPattern();
                AstNode guard = null;
                if (acceptKw("if")) guard = parseNamedExpression();
                List<Object> body = parseBlock();
                cases.add(new AstNode("match_case", pattern, guard, body));
            }
            if (peek().type == Token.DEDENT) next(); else if (peek().type != Token.ENDMARKER) throw invalid();
            gate(10, "Pattern matching is only supported");
            return one(mk("Match", s, prevReal, subject, cases));
        } catch (PySyntaxError e) {
            p = save; prevReal = savePrev;
            // genuine errors inside a case block must surface; only fall back when the header is not a match statement
            if (e.getMessage() != null && p + 1 < toks.length && looksLikeMatch(save)) throw e;
            return null;
        }
    }
    /** After "match <subject> : NEWLINE INDENT case", errors belong to the match statement. */
    private boolean looksLikeMatch(int start) {
        int depth = 0, k = start + 1;
        while (k < toks.length) {
            Token t = toks[k];
            if (t.type == Token.NEWLINE && depth == 0) break;
            if (t.type == Token.OP) { if (t.string.equals("(") || t.string.equals("[") || t.string.equals("{")) depth++; else if (t.string.equals(")") || t.string.equals("]") || t.string.equals("}")) depth--; }
            k++;
        }
        return k + 2 < toks.length && toks[k - 1].type == Token.OP && toks[k - 1].string.equals(":") && toks[k + 1].type == Token.INDENT
            && toks[k + 2].type == Token.NAME && toks[k + 2].string.equals("case");
    }

    private AstNode parseTopPattern() {
        Token s = peek();
        AstNode first = parseMaybeStarPattern();
        if (atOp(",")) {
            List<Object> pats = list(); pats.add(first);
            while (acceptOp(",")) { if (atOp(":") || atKw("if")) break; pats.add(parseMaybeStarPattern()); }
            return mk("MatchSequence", s, prevReal, pats);
        }
        if (first.is("MatchStar")) { List<Object> pats = list(); pats.add(first); return mk("MatchSequence", s, prevReal, pats); }
        return first;
    }
    private AstNode parseMaybeStarPattern() {
        if (atOp("*")) {
            Token s = next(); Token n = expectName();
            return mk("MatchStar", s, n, n.string.equals("_") ? null : n.string);
        }
        return parseAsPattern();
    }
    private AstNode parseAsPattern() {
        Token s = peek();
        AstNode pat = parseOrPattern();
        if (acceptKw("as")) {
            Token n = expectName();
            if (n.string.equals("_")) throw unexpected(n, "cannot use '_' as a target");
            return mk("MatchAs", s, n, pat, n.string);
        }
        return pat;
    }
    private AstNode parseOrPattern() {
        Token s = peek();
        AstNode first = parseClosedPattern();
        if (!atOp("|")) return first;
        List<Object> alts = list(); alts.add(first);
        while (acceptOp("|")) alts.add(parseClosedPattern());
        return mk("MatchOr", s, prevReal, alts);
    }
    private AstNode parseClosedPattern() {
        Token t = peek();
        switch (t.type) {
            case Token.NUMBER -> { AstNode v = parseSignedNumber(); return mk("MatchValue", t, prevReal, v); }
            case Token.STRING, Token.FSTRING_START -> {
                if (t.type == Token.FSTRING_START) throw unexpected(t, "patterns may only match literals and attribute lookups");
                AstNode v = parseStrings(); return mk("MatchValue", t, prevReal, v);
            }
            case Token.NAME -> {
                switch (t.string) {
                    case "None": next(); return mk("MatchSingleton", t, t, (Object) null);
                    case "True": next(); return mk("MatchSingleton", t, t, Boolean.TRUE);
                    case "False": next(); return mk("MatchSingleton", t, t, Boolean.FALSE);
                    default: break;
                }
                if (!isIdentifier(t)) throw invalid();
                next();
                if (!atOp(".") && !atOp("(")) {
                    if (t.string.equals("_")) return mk("MatchAs", t, t, null, null);
                    return mk("MatchAs", t, t, null, t.string);
                }
                AstNode name = mk("Name", t, t, t.string, LOAD);
                while (atOp(".")) { next(); Token a = expectName(); name = mk("Attribute", t, a, name, a.string, LOAD); }
                if (atOp("(")) return parseClassPattern(t, name);
                return mk("MatchValue", t, name, name);
            }
            case Token.OP -> {
                if (t.string.equals("-") || t.string.equals("+")) { AstNode v = parseSignedNumber(); return mk("MatchValue", t, prevReal, v); }
                if (t.string.equals("(") || t.string.equals("[")) return parseSequencePattern();
                if (t.string.equals("{")) return parseMappingPattern();
            }
            default -> {}
        }
        throw invalid();
    }
    private AstNode parseSignedNumber() {
        Token s = peek();
        AstNode left;
        if (atOp("-") || atOp("+")) {
            boolean minus = next().string.equals("-");
            Token ns = peek(); if (ns.type != Token.NUMBER) throw invalid();
            AstNode operand = parseNumber();
            if (minus) left = mk("UnaryOp", s, prevReal, new AstNode("USub"), operand);
            else left = operand; // a leading '+' is folded away
        } else left = parseNumber();
        if (atOp("+") || atOp("-")) {
            boolean plus = next().string.equals("+");
            Token rs = peek(); if (rs.type != Token.NUMBER) throw invalid();
            AstNode right = parseNumber();
            return mk("BinOp", s, prevReal, left, new AstNode(plus ? "Add" : "Sub"), right);
        }
        return left;
    }
    private AstNode parseSequencePattern() {
        Token open = next();
        String close = open.string.equals("(") ? ")" : "]";
        List<Object> pats = list();
        boolean comma = false;
        while (!atOp(close)) {
            pats.add(parseMaybeStarPattern());
            if (acceptOp(",")) comma = true; else break;
        }
        Token end = expectOp(close);
        if (close.equals(")") && pats.size() == 1 && !comma && !((AstNode) pats.get(0)).is("MatchStar")) return (AstNode) pats.get(0); // group pattern
        return mk("MatchSequence", open, end, pats);
    }
    private AstNode parseMappingPattern() {
        Token open = next();
        List<Object> keys = list(), pats = list(); String rest = null;
        while (!atOp("}")) {
            if (acceptOp("**")) { rest = expectName().string; acceptOp(","); break; }
            Token ks = peek(); AstNode key;
            if (ks.type == Token.NUMBER || atOp("-") || atOp("+")) key = parseSignedNumber();
            else if (ks.type == Token.STRING) key = parseStrings();
            else if (ks.type == Token.NAME && (ks.string.equals("None") || ks.string.equals("True") || ks.string.equals("False"))) key = parseAtom();
            else if (isIdentifier(ks)) {
                next(); key = mk("Name", ks, ks, ks.string, LOAD);
                if (!atOp(".")) throw invalid();
                while (atOp(".")) { next(); Token a = expectName(); key = mk("Attribute", ks, a, key, a.string, LOAD); }
            } else throw invalid();
            expectOp(":");
            keys.add(key); pats.add(parseAsPattern());
            if (!acceptOp(",")) break;
        }
        Token end = expectOp("}");
        return mk("MatchMapping", open, end, keys, pats, rest);
    }
    private AstNode parseClassPattern(Token s, AstNode cls) {
        next(); // '('
        List<Object> pats = list(), kwAttrs = list(), kwPats = list();
        while (!atOp(")")) {
            if (isIdentifier(peek()) && peek(1).type == Token.OP && peek(1).string.equals("=")) {
                Token n = next(); next(); kwAttrs.add(n.string); kwPats.add(parseAsPattern());
            } else {
                if (!kwAttrs.isEmpty()) throw unexpected(peek(), "positional patterns follow keyword patterns");
                pats.add(parseAsPattern());
            }
            if (!acceptOp(",")) break;
        }
        Token end = expectOp(")");
        return mk("MatchClass", s, end, cls, pats, kwAttrs, kwPats);
    }

    // ---- targets -------------------------------------------------------------------------------
    private AstNode parseTargetList() {
        Token s = peek();
        AstNode first = parseStarTarget();
        AstNode result = first;
        if (atOp(",")) {
            List<Object> elts = list(); elts.add(first);
            while (acceptOp(",")) { if (atKw("in") || atOp("=") || atOp(")")) break; elts.add(parseStarTarget()); }
            result = mk("Tuple", s, prevReal, elts, LOAD);
        }
        setCtx(result, STORE, "assign to");
        return result;
    }
    private AstNode parseStarTarget() {
        if (atOp("*")) { Token s = next(); AstNode v = parseBitwiseOr(); return mk("Starred", s, prevReal, v, LOAD); }
        return parseBitwiseOr();
    }

    // ---- expressions ---------------------------------------------------------------------------
    private boolean canStartExpression(Token t) {
        switch (t.type) {
            case Token.NUMBER, Token.STRING, Token.FSTRING_START, Token.TSTRING_START: return true;
            case Token.NAME:
                return !KEYWORDS.contains(t.string) || Set.of("None", "True", "False", "not", "lambda", "await", "yield").contains(t.string);
            case Token.OP:
                return Set.of("(", "[", "{", "-", "+", "~", "...", "*").contains(t.string);
            default: return false;
        }
    }

    private AstNode parseStarExpressions() {
        Token s = peek();
        AstNode first = parseStarExpression();
        if (!atOp(",")) return first;
        List<Object> elts = list(); elts.add(first);
        while (acceptOp(",")) { if (!canStartExpression(peek())) break; elts.add(parseStarExpression()); }
        return mk("Tuple", s, prevReal, elts, LOAD);
    }
    private AstNode parseStarExpression() {
        if (atOp("*")) { Token s = next(); AstNode v = parseBitwiseOr(); return mk("Starred", s, prevReal, v, LOAD); }
        return parseExpression();
    }
    private AstNode parseStarNamedExpression() {
        if (atOp("*")) { Token s = next(); AstNode v = parseBitwiseOr(); return mk("Starred", s, prevReal, v, LOAD); }
        return parseNamedExpression();
    }
    private AstNode parseNamedExpression() {
        Token t = peek();
        if (isIdentifier(t) && peek(1).type == Token.OP && peek(1).string.equals(":=")) {
            next(); next();
            gate(8, "Assignment expressions are only supported");
            AstNode target = mk("Name", t, t, t.string, STORE);
            AstNode value = parseExpression();
            return mk("NamedExpr", t, prevReal, target, value);
        }
        return parseExpression();
    }

    private AstNode parseYield() {
        Token s = expectKw("yield");
        if (acceptKw("from")) { AstNode v = parseExpression(); return mk("YieldFrom", s, prevReal, v); }
        AstNode v = canStartExpression(peek()) ? parseStarExpressions() : null;
        return mk("Yield", s, prevReal, v);
    }

    private AstNode parseExpression() {
        if (atKw("lambda")) return parseLambda();
        Token s = peek();
        AstNode body = parseDisjunction();
        if (atKw("if")) {
            next();
            AstNode test = parseDisjunction();
            expectKw("else");
            AstNode orelse = parseExpression();
            return mk("IfExp", s, prevReal, test, body, orelse);
        }
        return body;
    }
    private AstNode parseLambda() {
        Token s = next();
        AstNode args = parseArguments(true);
        expectOp(":");
        AstNode body = parseExpression();
        return mk("Lambda", s, prevReal, args, body);
    }
    private AstNode parseDisjunction() {
        Token s = peek();
        AstNode first = parseConjunction();
        if (!atKw("or")) return first;
        List<Object> values = list(); values.add(first);
        while (acceptKw("or")) values.add(parseConjunction());
        return mk("BoolOp", s, prevReal, new AstNode("Or"), values);
    }
    private AstNode parseConjunction() {
        Token s = peek();
        AstNode first = parseInversion();
        if (!atKw("and")) return first;
        List<Object> values = list(); values.add(first);
        while (acceptKw("and")) values.add(parseInversion());
        return mk("BoolOp", s, prevReal, new AstNode("And"), values);
    }
    private AstNode parseInversion() {
        if (atKw("not")) { Token s = next(); AstNode v = parseInversion(); return mk("UnaryOp", s, prevReal, new AstNode("Not"), v); }
        return parseComparison();
    }
    private AstNode parseComparison() {
        Token s = peek();
        AstNode left = parseBitwiseOr();
        List<Object> ops = list(), comps = list();
        while (true) {
            Token t = peek(); String op = null;
            if (t.type == Token.OP) {
                switch (t.string) {
                    case "==": op = "Eq"; break; case "!=": op = "NotEq"; break; case "<": op = "Lt"; break; case "<=": op = "LtE"; break;
                    case ">": op = "Gt"; break; case ">=": op = "GtE"; break; default: break;
                }
                if (op != null) next();
            } else if (t.type == Token.NAME) {
                if (t.string.equals("in")) { next(); op = "In"; }
                else if (t.string.equals("is")) { next(); op = acceptKw("not") ? "IsNot" : "Is"; }
                else if (t.string.equals("not") && peek(1).type == Token.NAME && peek(1).string.equals("in")) { next(); next(); op = "NotIn"; }
            }
            if (op == null) break;
            ops.add(new AstNode(op)); comps.add(parseBitwiseOr());
        }
        return ops.isEmpty() ? left : mk("Compare", s, prevReal, left, ops, comps);
    }

    private AstNode binary(String[] opsTokens, java.util.function.Supplier<AstNode> operand) {
        Token s = peek();
        AstNode left = operand.get();
        while (peek().type == Token.OP) {
            String op = peek().string; boolean match = false;
            for (String o : opsTokens) if (o.equals(op)) { match = true; break; }
            if (!match) break;
            next();
            AstNode right = operand.get();
            left = mk("BinOp", s, prevReal, left, new AstNode(binOpName(op)), right);
        }
        return left;
    }
    private static final String[] OR = {"|"}, XOR = {"^"}, AND = {"&"}, SHIFT = {"<<", ">>"}, SUM = {"+", "-"}, TERM = {"*", "/", "//", "%", "@"};
    private AstNode parseBitwiseOr() { return binary(OR, this::parseBitwiseXor); }
    private AstNode parseBitwiseXor() { return binary(XOR, this::parseBitwiseAnd); }
    private AstNode parseBitwiseAnd() { return binary(AND, this::parseShift); }
    private AstNode parseShift() { return binary(SHIFT, this::parseSum); }
    private AstNode parseSum() { return binary(SUM, this::parseTerm); }
    private AstNode parseTerm() { return binary(TERM, this::parseFactor); }
    private AstNode parseFactor() {
        if (peek().type == Token.OP) {
            String op = peek().string;
            if (op.equals("+") || op.equals("-") || op.equals("~")) {
                Token s = next(); AstNode v = parseFactor();
                return mk("UnaryOp", s, prevReal, new AstNode(op.equals("+") ? "UAdd" : op.equals("-") ? "USub" : "Invert"), v);
            }
        }
        return parsePower();
    }
    private AstNode parsePower() {
        Token s = peek();
        AstNode left = parseAwaitPrimary();
        if (atOp("**")) { next(); AstNode right = parseFactor(); return mk("BinOp", s, prevReal, left, new AstNode("Pow"), right); }
        return left;
    }
    private AstNode parseAwaitPrimary() {
        if (atKw("await")) { Token s = next(); AstNode v = parsePrimary(); return mk("Await", s, prevReal, v); }
        return parsePrimary();
    }

    private AstNode parsePrimary() {
        Token s = peek();
        AstNode node = parseAtom();
        while (true) {
            if (atOp(".")) {
                next(); Token name = expectName();
                node = mk("Attribute", s, name, node, name.string, LOAD);
            } else if (atOp("(")) {
                Token open = next();
                List<Object> args = list(), keywords = list();
                parseCallArguments(args, keywords, open);
                Token close = expectOp(")");
                // a sole generator-expression argument spans the call's parentheses; fix its end position
                node = mk("Call", s, close, node, args, keywords);
            } else if (atOp("[")) {
                next();
                AstNode slice = parseSlices();
                Token close = expectOp("]");
                node = mk("Subscript", s, close, node, slice, LOAD);
            } else break;
        }
        return node;
    }

    private void parseCallArguments(List<Object> args, List<Object> keywords, Token open) {
        boolean sawKeyword = false, sawDoubleStar = false;
        while (!atOp(")")) {
            Token s = peek();
            if (atOp("*")) {
                if (sawDoubleStar) throw unexpected(s, "iterable argument unpacking follows keyword argument unpacking");
                next(); AstNode v = parseExpression();
                args.add(mk("Starred", s, prevReal, v, LOAD));
            } else if (atOp("**")) {
                sawDoubleStar = true;
                next(); AstNode v = parseExpression();
                keywords.add(mk("keyword", s, prevReal, null, v));
            } else if (isIdentifier(s) && peek(1).type == Token.OP && peek(1).string.equals("=")) {
                sawKeyword = true;
                next(); next(); AstNode v = parseExpression();
                keywords.add(mk("keyword", s, prevReal, s.string, v));
            } else {
                AstNode e = parseNamedExpression();
                if (sawKeyword || sawDoubleStar)
                    throw unexpected(s, sawDoubleStar ? "positional argument follows keyword argument unpacking" : "positional argument follows keyword argument");
                if (atKw("for") || (atKw("async") && peek(1).type == Token.NAME && peek(1).string.equals("for"))) {
                    List<Object> gens = parseCompFor();
                    if (open == null || !atOp(")") || !args.isEmpty() || !keywords.isEmpty())
                        throw unexpected(s, "Generator expression must be parenthesized");
                    Token close = peek();
                    e = mk("GeneratorExp", open, close, e, gens);
                    args.add(e);
                    return;
                }
                args.add(e);
            }
            if (!acceptOp(",")) break;
        }
    }

    private AstNode parseSlices() {
        Token s = peek();
        AstNode first = parseSliceItem();
        if (!atOp(",")) {
            if (first.is("Starred")) { List<Object> only = list(); only.add(first); return mk("Tuple", s, prevReal, only, LOAD); }
            return first;
        }
        List<Object> elts = list(); elts.add(first);
        while (acceptOp(",")) { if (atOp("]")) break; elts.add(parseSliceItem()); }
        return mk("Tuple", s, prevReal, elts, LOAD);
    }
    private AstNode parseSliceItem() {
        if (atOp("*")) { Token s = next(); AstNode v = parseBitwiseOr(); return mk("Starred", s, prevReal, v, LOAD); }
        Token s = peek();
        AstNode lower = atOp(":") ? null : parseNamedExpression();
        if (!atOp(":")) return lower;
        next();
        AstNode upper = (atOp(":") || atOp(",") || atOp("]")) ? null : parseExpression();
        AstNode step = null;
        if (acceptOp(":")) step = (atOp(",") || atOp("]")) ? null : parseExpression();
        return mk("Slice", s, prevReal, lower, upper, step);
    }

    private List<Object> parseCompFor() {
        List<Object> gens = list();
        while (atKw("for") || (atKw("async") && peek(1).type == Token.NAME && peek(1).string.equals("for"))) {
            boolean async = acceptKw("async");
            expectKw("for");
            AstNode target = parseTargetList();
            expectKw("in");
            AstNode iter = parseDisjunction();
            List<Object> ifs = list();
            while (acceptKw("if")) ifs.add(parseDisjunction());
            gens.add(new AstNode("comprehension", target, iter, ifs, async ? 1L : 0L));
        }
        return gens;
    }

    // ---- atoms ---------------------------------------------------------------------------------
    private AstNode parseAtom() {
        Token t = peek();
        switch (t.type) {
            case Token.NAME -> {
                switch (t.string) {
                    case "True": next(); return mk("Constant", t, t, Boolean.TRUE, null);
                    case "False": next(); return mk("Constant", t, t, Boolean.FALSE, null);
                    case "None": next(); return mk("Constant", t, t, null, null);
                    default: break;
                }
                if (!isIdentifier(t)) throw invalid();
                next();
                return mk("Name", t, t, t.string, LOAD);
            }
            case Token.NUMBER -> { return parseNumber(); }
            case Token.STRING, Token.FSTRING_START, Token.TSTRING_START -> { return parseStrings(); }
            case Token.OP -> {
                switch (t.string) {
                    case "(": return parseParen();
                    case "[": return parseListDisplay();
                    case "{": return parseBraces();
                    case "...": next(); return mk("Constant", t, t, AstNode.ELLIPSIS, null);
                    default: break;
                }
            }
            default -> {}
        }
        throw invalid();
    }

    private AstNode parseParen() {
        Token open = next();
        if (atOp(")")) { Token close = next(); return mk("Tuple", open, close, list(), LOAD); }
        if (atKw("yield")) { AstNode y = parseYield(); expectOp(")"); parenthesized.put(y, true); return y; }
        AstNode first = parseStarNamedExpression();
        if (atKw("for") || (atKw("async") && peek(1).type == Token.NAME && peek(1).string.equals("for"))) {
            List<Object> gens = parseCompFor(); Token close = expectOp(")");
            return mk("GeneratorExp", open, close, first, gens);
        }
        if (atOp(",")) {
            List<Object> elts = list(); elts.add(first);
            while (acceptOp(",")) { if (atOp(")")) break; elts.add(parseStarNamedExpression()); }
            Token close = expectOp(")");
            return mk("Tuple", open, close, elts, LOAD);
        }
        expectOp(")");
        if (first.is("Starred")) throw unexpected(open, "cannot use starred expression here");
        parenthesized.put(first, true);
        return first;
    }

    private AstNode parseListDisplay() {
        Token open = next();
        if (atOp("]")) { Token close = next(); return mk("List", open, close, list(), LOAD); }
        AstNode first = parseStarNamedExpression();
        if (atKw("for") || (atKw("async") && peek(1).type == Token.NAME && peek(1).string.equals("for"))) {
            List<Object> gens = parseCompFor(); Token close = expectOp("]");
            return mk("ListComp", open, close, first, gens);
        }
        List<Object> elts = list(); elts.add(first);
        while (acceptOp(",")) { if (atOp("]")) break; elts.add(parseStarNamedExpression()); }
        Token close = expectOp("]");
        return mk("List", open, close, elts, LOAD);
    }

    private AstNode parseBraces() {
        Token open = next();
        if (atOp("}")) { Token close = next(); return mk("Dict", open, close, list(), list()); }
        List<Object> keys = list(), values = list();
        if (atOp("**")) { // dict with leading unpack
            next(); keys.add(null); values.add(parseBitwiseOr());
        } else {
            AstNode first = parseStarNamedExpression();
            if (atOp(":") && !first.is("Starred")) {
                next();
                AstNode value = parseExpression();
                if (atKw("for") || (atKw("async") && peek(1).type == Token.NAME && peek(1).string.equals("for"))) {
                    List<Object> gens = parseCompFor(); Token close = expectOp("}");
                    return mk("DictComp", open, close, first, value, gens);
                }
                keys.add(first); values.add(value);
            } else {
                if (atKw("for") || (atKw("async") && peek(1).type == Token.NAME && peek(1).string.equals("for"))) {
                    List<Object> gens = parseCompFor(); Token close = expectOp("}");
                    return mk("SetComp", open, close, first, gens);
                }
                List<Object> elts = list(); elts.add(first);
                while (acceptOp(",")) { if (atOp("}")) break; elts.add(parseStarNamedExpression()); }
                Token close = expectOp("}");
                return mk("Set", open, close, elts);
            }
        }
        while (acceptOp(",")) {
            if (atOp("}")) break;
            if (acceptOp("**")) { keys.add(null); values.add(parseBitwiseOr()); }
            else { keys.add(parseExpression()); expectOp(":"); values.add(parseExpression()); }
        }
        Token close = expectOp("}");
        return mk("Dict", open, close, keys, values);
    }

    // ---- numbers -------------------------------------------------------------------------------
    private AstNode parseNumber() {
        Token t = next();
        String text = t.string.replace("_", "");
        Object value;
        String lower = text.toLowerCase();
        try {
            if (lower.startsWith("0x")) value = integer(new BigInteger(text.substring(2), 16));
            else if (lower.startsWith("0o")) value = integer(new BigInteger(text.substring(2), 8));
            else if (lower.startsWith("0b")) value = integer(new BigInteger(text.substring(2), 2));
            else if (lower.endsWith("j")) value = new AstNode.Complex(0.0, Double.parseDouble(text.substring(0, text.length() - 1)));
            else if (lower.contains(".") || lower.contains("e")) value = Double.parseDouble(text);
            else {
                if (text.length() > 1 && text.charAt(0) == '0' && !text.chars().allMatch(c -> c == '0'))
                    throw unexpected(t, "leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers");
                value = integer(new BigInteger(text));
            }
        } catch (NumberFormatException e) { throw unexpected(t, "invalid decimal literal"); }
        return mk("Constant", t, t, value, null);
    }
    private static Object integer(BigInteger v) { return v.bitLength() < 64 ? (Object) v.longValue() : v; }

    // ---- strings -------------------------------------------------------------------------------
    /** One literal or formatted piece of a (possibly concatenated) string expression. */
    private static final class Piece {
        String text; byte[] bytes; AstNode node; Token first, last; int extra; // extra: columns beyond `last` (the second brace of {{ or }})
    }

    private AstNode parseStrings() {
        Token first = peek();
        List<Piece> pieces = new ArrayList<>();
        boolean anyF = false, anyT = false, anyBytes = false, anyStr = false;
        String kind = null;
        boolean firstTok = true;
        while (peek().type == Token.STRING || peek().type == Token.FSTRING_START || peek().type == Token.TSTRING_START) {
            Token t = peek();
            if (t.type == Token.STRING) {
                next();
                String s = t.string; int q = 0;
                while (s.charAt(q) != '"' && s.charAt(q) != '\'') q++;
                String prefix = s.substring(0, q).toLowerCase();
                int ql = (s.length() >= q + 6 && s.charAt(q + 1) == s.charAt(q) && s.charAt(q + 2) == s.charAt(q)) ? 3 : 1;
                String body = s.substring(q + ql, s.length() - ql);
                boolean raw = prefix.contains("r");
                Piece pc = new Piece(); pc.first = t; pc.last = t;
                if (prefix.contains("b")) { anyBytes = true; pc.bytes = StringLiterals.decodeBytes(body, raw, msg -> unexpected(t, msg)); }
                else { anyStr = true; pc.text = StringLiterals.decodeString(body, raw, msg -> unexpected(t, msg)); }
                if (firstTok && prefix.contains("u")) kind = "u";
                pieces.add(pc);
            } else {
                boolean template = t.type == Token.TSTRING_START;
                if (template) anyT = true; else anyF = true;
                anyStr = true;
                parseFString(pieces, template);
            }
            firstTok = false;
        }
        Token last = prevReal;
        if (anyBytes && anyStr) throw unexpected(first, "cannot mix bytes and nonbytes literals");
        if (anyT && (anyF || pieces.stream().anyMatch(pc -> pc.node == null && pc.text != null && pc.first.type == Token.STRING)))
            throw unexpected(first, "cannot mix t-strings with other string literals");
        if (anyBytes) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            for (Piece pc : pieces) bos.writeBytes(pc.bytes);
            return mk("Constant", first, last, bos.toByteArray(), kind);
        }
        if (!anyF && !anyT) {
            StringBuilder sb = new StringBuilder();
            for (Piece pc : pieces) sb.append(pc.text);
            return mk("Constant", first, last, sb.toString(), kind);
        }
        // merge adjacent literal pieces into single Constants; drop empty ones
        List<Object> values = list();
        Piece pending = null; StringBuilder acc = null;
        for (Piece pc : pieces) {
            if (pc.node != null) {
                if (acc != null) { values.add(literal(acc, pending)); acc = null; pending = null; }
                values.add(pc.node);
            } else {
                if (acc == null) { acc = new StringBuilder(); pending = pc; }
                acc.append(pc.text); pending.last = pc.last; pending.extra = pc.extra;
            }
        }
        if (acc != null) values.add(literal(acc, pending));
        values.removeIf(v -> v == SKIP);
        if (anyT) gate(14, "t-strings are only supported");
        return mk(anyT ? "TemplateStr" : "JoinedStr", first, last, values);
    }
    private Object literal(StringBuilder text, Piece p) {
        if (text.length() == 0) return skipMarker();
        AstNode n = mk("Constant", p.first, p.last, text.toString(), null);
        n.endColOffset += p.extra;
        return n;
    }
    private Object skipMarker() { return SKIP; }
    /** A middle token ending in a collapsed `{{`/`}}` pair covers one more source column than its text shows. */
    private int doubledBrace(Token t) {
        if (t.string.isEmpty()) return 0;
        char last = t.string.charAt(t.string.length() - 1);
        return (last == '{' || last == '}') && t.endOffset < src.length() && src.charAt(t.endOffset) == last ? 1 : 0;
    }
    private static final Object SKIP = new Object();

    /** Parse FSTRING_START .. FSTRING_END into pieces (literals and FormattedValue/Interpolation nodes). */
    private void parseFString(List<Piece> out, boolean template) {
        Token start = next();
        String prefix = start.string.toLowerCase();
        boolean raw = prefix.contains("r");
        int endType = template ? Token.TSTRING_END : Token.FSTRING_END;
        int midType = template ? Token.TSTRING_MIDDLE : Token.FSTRING_MIDDLE;
        while (peek().type != endType) {
            Token t = peek();
            if (t.type == midType) {
                next();
                Piece pc = new Piece(); pc.first = t; pc.last = t;
                pc.text = StringLiterals.decodeString(t.string, raw, msg -> unexpected(t, msg));
                pc.extra = doubledBrace(t);
                out.add(pc);
            } else if (t.type == Token.OP && t.string.equals("{")) {
                Piece pc = new Piece(); pc.node = parseReplacementField(template, raw, out); pc.first = t; pc.last = prevReal;
                if (pc.node != null) out.add(pc);
            } else throw invalid();
        }
        next(); // end
        out.removeIf(pc -> pc.node == null && pc.text != null && pc.text.isEmpty() && pc.first != null && pc.first.type != Token.STRING);
    }

    private AstNode parseReplacementField(boolean template, boolean raw, List<Piece> out) { return parseReplacementField(template, template, raw, out); }
    private AstNode parseReplacementField(boolean template, boolean interpolation, boolean raw, List<Piece> out) {
        Token open = next();
        if (atOp("}") || atOp("!") || atOp(":")) throw unexpected(peek(), "f-string: valid expression required before '" + peek().string + "'");
        AstNode value = atKw("yield") ? parseYield() : parseStarExpressions();
        Token exprEnd = prevReal;
        long conversion = -1;
        boolean debug = false;
        if (atOp("=")) {
            Token eq = next(); debug = true;
            // the debug text is the expression source, '=' and any whitespace after it
            int endOffset = peek().startOffset;
            Piece text = new Piece();
            text.first = new Token(Token.OP, "", open.endLine, open.endCol, open.endLine, open.endCol, open.endOffset, open.endOffset);
            text.last = new Token(Token.OP, "", peek().startLine, peek().startCol, peek().startLine, peek().startCol, endOffset, endOffset);
            text.text = src.substring(open.endOffset, endOffset);
            out.add(text);
        }
        if (atOp("!")) {
            next(); Token c = expectName();
            conversion = switch (c.string) { case "s" -> 115; case "r" -> 114; case "a" -> 97;
                default -> throw unexpected(c, "f-string: invalid conversion character: expected 's', 'r', or 'a'"); };
        }
        AstNode spec = null;
        if (atOp(":")) {
            Token colon = next();
            List<Piece> specPieces = new ArrayList<>();
            int midType = template ? Token.TSTRING_MIDDLE : Token.FSTRING_MIDDLE;
            while (!atOp("}")) {
                Token t = peek();
                if (t.type == midType) {
                    next();
                    Piece pc = new Piece(); pc.first = t; pc.last = t;
                    pc.text = StringLiterals.decodeString(t.string, raw, msg -> unexpected(t, msg));
                    pc.extra = doubledBrace(t);
                    specPieces.add(pc);
                } else if (t.type == Token.OP && t.string.equals("{")) {
                    Piece pc = new Piece(); pc.node = parseReplacementField(template, false, raw, specPieces); pc.first = t; pc.last = prevReal; specPieces.add(pc);
                } else throw invalid();
            }
            specPieces.removeIf(pc -> pc.node == null && pc.text.isEmpty());
            List<Object> values = list();
            Piece pending = null; StringBuilder acc = null;
            for (Piece pc : specPieces) {
                if (pc.node != null) { if (acc != null) { values.add(literal(acc, pending)); acc = null; } values.add(pc.node); }
                else { if (acc == null) { acc = new StringBuilder(); pending = pc; } acc.append(pc.text); pending.last = pc.last; }
            }
            if (acc != null) values.add(literal(acc, pending));
            values.removeIf(v -> v == SKIP);
            Token specEnd = prevReal;
            Token specStart = specPieces.isEmpty() ? peek() : specPieces.get(0).first;
            spec = mk("JoinedStr", colon, specEnd, values);
            // the spec starts at the ':' and ends just before '}'
            spec.endLineno = peek().startLine; spec.endColOffset = bcol(peek().startLine, peek().startCol);
        }
        Token close = expectOp("}");
        if (debug && conversion == -1 && spec == null) conversion = 114;
        if (interpolation) {
            String text = src.substring(open.endOffset, exprEnd.endOffset);
            return mk("Interpolation", open, close, value, text, conversion, spec);
        }
        return mk("FormattedValue", open, close, value, conversion, spec);
    }
}
