package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pyjvm315.parser.AstNode;
import pyjvm315.parser.PySyntaxError;
import pyjvm315.parser.Parser;

/**
 * The native {@code ast} module: node classes created from the parser's schema (so Python code can
 * subclass, instantiate and pattern-match them), conversion to and from parser trees, and the
 * functions of CPython's ast module that do not need the rest of the standard library.
 */
public final class AstModule {
    private AstModule() {}

    private static PyModule module;
    private static final Map<String, PyClass> CLASSES = new HashMap<>();
    private static PyClass astClass, visitorClass, transformerClass;

    // ---- module construction -------------------------------------------------------------------
    static synchronized PyModule module() {
        if (module != null) return module;
        PyModule m = new PyModule("ast", "pyjvm315.runtime.AstModule");
        buildClasses();
        for (var e : CLASSES.entrySet()) m.attrs.put(e.getKey(), e.getValue());
        m.attrs.put("PyCF_ONLY_AST", 1024L);
        m.attrs.put("PyCF_TYPE_COMMENTS", 4096L);
        m.attrs.put("PyCF_ALLOW_TOP_LEVEL_AWAIT", 8192L);
        fn(m, "parse", (a, k) -> parse(a, k));
        fn(m, "dump", (a, k) -> dump(a, k));
        fn(m, "walk", (a, k) -> walk(a.get(0)));
        fn(m, "iter_fields", (a, k) -> iterFields(a.get(0)));
        fn(m, "iter_child_nodes", (a, k) -> iterChildNodes(a.get(0)));
        fn(m, "copy_location", (a, k) -> copyLocation(a.get(0), a.get(1)));
        fn(m, "fix_missing_locations", (a, k) -> fixMissingLocations(a.get(0)));
        fn(m, "increment_lineno", (a, k) -> incrementLineno(a.get(0), a.size() > 1 ? a.get(1) : (Object) 1L));
        fn(m, "get_docstring", (a, k) -> getDocstring(a.get(0)));
        fn(m, "literal_eval", (a, k) -> literalEval(a.get(0)));
        m.attrs.put("NodeVisitor", visitorClass);
        m.attrs.put("NodeTransformer", transformerClass);
        m.attrs.put("AST", astClass);
        return module = m;
    }

    private static void fn(PyModule m, String name, NativeFunction.Body body) { m.attrs.put(name, new NativeFunction(name, body)); }

    /** The classes behind `import _ast`, the same objects as in `ast`. */
    static PyModule privateModule() {
        PyModule ast = module();
        PyModule m = new PyModule("_ast", "pyjvm315.runtime.AstModule");
        for (var e : CLASSES.entrySet()) m.attrs.put(e.getKey(), e.getValue());
        m.attrs.put("AST", astClass);
        m.attrs.put("PyCF_ONLY_AST", 1024L);
        return m;
    }

    // ---- node classes ---------------------------------------------------------------------------
    private static PyClass newClass(String name, PyClass base) {
        PyClass cls = (PyClass) classCreate(name, "ast");
        if (base != null) classAddBase(cls, base);
        else classAddBase(cls, builtinType("object"));
        return cls;
    }

    private static PyTuple tuple(List<String> items) { PyTuple t = new PyTuple(); t.items.addAll(items); return t; }

    private static void buildClasses() {
        astClass = newClass("AST", null);
        classAddAttr(astClass, "_fields", tuple(List.of()));
        classAddAttr(astClass, "_attributes", tuple(List.of()));
        classAddAttr(astClass, "__match_args__", tuple(List.of()));
        classAddMethodEx(astClass, "__init__", AstModule.class, "nodeInit", "instance", "", "self", "", "args", "kwargs",
            new PyDict(), "<ast>", 0L);
        PyRuntime.classFinalizeWithKeywords(astClass, new PyDict());
        CLASSES.put("AST", astClass);
        List<AstNode.Spec> specs = new ArrayList<>(AstNode.allSpecs());
        java.util.Set<String> abstractBases = new java.util.LinkedHashSet<>();
        for (AstNode.Spec s : specs) abstractBases.add(s.base);
        for (String base : abstractBases) {
            if (CLASSES.containsKey(base)) continue;
            PyClass cls = newClass(base, astClass);
            classAddAttr(cls, "_fields", tuple(List.of()));
            PyRuntime.classFinalizeWithKeywords(cls, new PyDict());
            CLASSES.put(base, cls);
        }
        for (AstNode.Spec s : specs) {
            PyClass cls = newClass(s.name, CLASSES.get(s.base));
            classAddAttr(cls, "_fields", tuple(List.of(s.fields)));
            classAddAttr(cls, "__match_args__", tuple(List.of(s.fields)));
            classAddAttr(cls, "_attributes", tuple(s.hasPositions ? List.of("lineno", "col_offset", "end_lineno", "end_col_offset") : List.of()));
            for (int k = 0; k < s.fields.length; k++) if (s.isOptional[k]) classAddAttr(cls, s.fields[k], null); // optional fields default to None on the class
            PyRuntime.classFinalizeWithKeywords(cls, new PyDict());
            CLASSES.put(s.name, cls);
        }
        visitorClass = newClass("NodeVisitor", null);
        method(visitorClass, "visit", "node", "visit");
        method(visitorClass, "generic_visit", "node", "genericVisit");
        PyRuntime.classFinalizeWithKeywords(visitorClass, new PyDict());
        transformerClass = newClass("NodeTransformer", visitorClass);
        method(transformerClass, "generic_visit", "node", "transformerGenericVisit");
        PyRuntime.classFinalizeWithKeywords(transformerClass, new PyDict());
    }

    private static void method(PyClass cls, String pyName, String params, String javaName) {
        classAddMethodEx(cls, pyName, AstModule.class, javaName, "instance", "", "self," + params, "", null, null, new PyDict(), "<ast>", 0L);
    }

    private static PyClass specClass(PyClass cls) {
        for (PyClass c : cls.mro) if (AstNode.exists(c.name) && CLASSES.get(c.name) == c) return c;
        return null;
    }

    private static boolean isAst(Object o) { return o instanceof PyInstance i && i.cls.mro.contains(astClass); }

    private static List<String> fieldsOf(PyInstance node) {
        Object f = node.cls.lookupAttr("_fields");
        List<String> out = new ArrayList<>();
        if (f instanceof PyTuple t) for (Object x : t.items) out.add((String) x);
        return out;
    }

    /** AST.__init__(self, *args, **kwargs): positional arguments fill _fields in order. */
    public static Object nodeInit(Object selfObj, Object argsObj, Object kwargsObj) {
        PyInstance self = (PyInstance) selfObj;
        List<?> args = argsObj instanceof PyTuple t ? t.items : (List<?>) argsObj;
        Map<?, ?> kwargs = (Map<?, ?>) kwargsObj;
        List<String> fields = fieldsOf(self);
        if (args.size() > fields.size())
            throw new PyException("TypeError", self.cls.name + " constructor takes at most " + fields.size() + " positional argument" + (fields.size() == 1 ? "" : "s"));
        for (int k = 0; k < args.size(); k++) self.fields.put(fields.get(k), args.get(k));
        for (var e : kwargs.entrySet()) {
            String key = (String) e.getKey();
            if (self.fields.containsKey(key) && fields.indexOf(key) >= 0 && fields.indexOf(key) < args.size())
                throw new PyException("TypeError", self.cls.name + " got multiple values for argument '" + key + "'");
            self.fields.put(key, e.getValue());
        }
        AstNode.Spec spec = AstNode.exists(self.cls.name) ? AstNode.spec(self.cls.name) : null;
        PyClass sc = specClass(self.cls);
        if (spec == null && sc != null) spec = AstNode.spec(sc.name);
        if (spec != null) {
            for (int k = 0; k < spec.fields.length; k++) {
                String f = spec.fields[k];
                if (self.fields.containsKey(f)) continue;
                if (spec.isList[k]) self.fields.put(f, new ArrayList<Object>());
                else if (spec.isOptional[k]) self.fields.put(f, null);
                else if (f.equals("ctx")) self.fields.put(f, instance("Load"));
            }
        }
        return null;
    }

    static PyInstance instance(String type) { return new PyInstance(CLASSES.get(type)); }

    // ---- conversion --------------------------------------------------------------------------------
    public static Object toPython(Object value) {
        if (value == null) return null;
        if (value instanceof AstNode n) {
            PyInstance inst = new PyInstance(CLASSES.get(n.type()));
            for (int k = 0; k < n.values.length; k++) inst.fields.put(n.spec.fields[k], toPython(n.values[k]));
            if (n.spec.hasPositions) {
                inst.fields.put("lineno", (long) n.lineno); inst.fields.put("col_offset", (long) n.colOffset);
                inst.fields.put("end_lineno", (long) n.endLineno); inst.fields.put("end_col_offset", (long) n.endColOffset);
            }
            return inst;
        }
        if (value instanceof List<?> list) { ArrayList<Object> out = new ArrayList<>(list.size()); for (Object o : list) out.add(toPython(o)); return out; }
        if (value instanceof byte[] b) return new PyBytes(b);
        if (value instanceof AstNode.Complex c) return new PyComplex(c.real(), c.imag());
        if (value == AstNode.ELLIPSIS) return ellipsis();
        return value;
    }

    static Object fromPython(Object value) {
        if (value instanceof PyInstance inst && isAst(inst)) {
            PyClass sc = specClass(inst.cls);
            if (sc == null) throw new PyException("TypeError", "expected some sort of AST node, but got " + inst.cls.name);
            AstNode.Spec spec = AstNode.spec(sc.name);
            Object[] vals = new Object[spec.fields.length];
            for (int k = 0; k < vals.length; k++) {
                String f = spec.fields[k];
                if (!inst.fields.containsKey(f)) {
                    if (spec.isList[k]) { vals[k] = new ArrayList<Object>(); continue; }
                    if (spec.isOptional[k]) { vals[k] = null; continue; }
                    throw new PyException("TypeError", "required field \"" + f + "\" missing from " + sc.name);
                }
                vals[k] = fromPython(inst.fields.get(f));
            }
            AstNode n = new AstNode(sc.name, vals);
            if (spec.hasPositions) {
                n.lineno = posOf(inst, "lineno", 1); n.colOffset = posOf(inst, "col_offset", 0);
                n.endLineno = posOf(inst, "end_lineno", n.lineno); n.endColOffset = posOf(inst, "end_col_offset", n.colOffset);
            }
            return n;
        }
        if (value instanceof List<?> list) { ArrayList<Object> out = new ArrayList<>(list.size()); for (Object o : list) out.add(fromPython(o)); return out; }
        if (value instanceof PyBytes b) return b.data;
        if (value instanceof PyComplex c) return new AstNode.Complex(c.real, c.imag);
        if (value != null && value == ellipsis()) return AstNode.ELLIPSIS;
        return value;
    }
    private static int posOf(PyInstance inst, String name, int dflt) { Object v = inst.fields.get(name); return v instanceof Number n ? n.intValue() : dflt; }

    // ---- functions ---------------------------------------------------------------------------------
    private static Object arg(List<Object> a, Map<Object, Object> k, int pos, String name, Object dflt) {
        if (a.size() > pos) return a.get(pos);
        return k.containsKey(name) ? k.get(name) : dflt;
    }

    private static Object parse(List<Object> a, Map<Object, Object> k) {
        Object source = arg(a, k, 0, "source", null);
        String mode = (String) arg(a, k, 2, "mode", "exec");
        String text = source instanceof String s ? s : source instanceof PyBytes b ? new String(b.data, java.nio.charset.StandardCharsets.UTF_8) : null;
        if (text == null) throw new PyException("TypeError", "parse() argument 'source' must be str or bytes, not " + typeName(source));
        try { return toPython(Parser.parse(text, mode)); }
        catch (PySyntaxError e) { throw new PyException(e.kind, e.getMessage()); }
    }

    /** compile(source, filename, mode, flags=PyCF_ONLY_AST): the AST of the source (also used by exec/eval). */
    public static Object compileToAst(Object source, String mode) {
        return parse(new ArrayList<>(List.of(source, "<unknown>", mode)), new LinkedHashMap<>());
    }

    private static Object iterFields(Object node) {
        if (!isAst(node)) throw new PyException("TypeError", "expected an AST node");
        PyInstance n = (PyInstance) node;
        List<Object> out = new ArrayList<>();
        for (String f : fieldsOf(n)) {
            if (!n.fields.containsKey(f)) continue;
            PyTuple pair = new PyTuple(); pair.items.add(f); pair.items.add(n.fields.get(f)); out.add(pair);
        }
        return out;
    }

    private static Object iterChildNodes(Object node) {
        List<Object> out = new ArrayList<>();
        for (Object pair : (List<?>) iterFields(node)) {
            Object value = ((PyTuple) pair).items.get(1);
            if (isAst(value)) out.add(value);
            else if (value instanceof List<?> list) for (Object x : list) if (isAst(x)) out.add(x);
        }
        return out;
    }

    private static Object walk(Object root) {
        List<Object> out = new ArrayList<>();
        java.util.ArrayDeque<Object> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            Object node = todo.poll();
            out.add(node);
            todo.addAll((List<?>) iterChildNodes(node));
        }
        return out;
    }

    private static Object copyLocation(Object newNode, Object oldNode) {
        PyInstance n = (PyInstance) newNode, o = (PyInstance) oldNode;
        for (String attr : new String[]{"lineno", "col_offset", "end_lineno", "end_col_offset"}) {
            if (o.fields.containsKey(attr) && ((List<?>) pyAttrs(n)).contains(attr)) {
                Object v = o.fields.get(attr);
                if (v != null || !attr.startsWith("end_")) n.fields.put(attr, v);
            }
        }
        return newNode;
    }
    private static Object pyAttrs(PyInstance n) {
        Object a = n.cls.lookupAttr("_attributes");
        return a instanceof PyTuple t ? t.items : List.of();
    }

    private static Object fixMissingLocations(Object root) {
        fix((PyInstance) root, 1L, 0L, 1L, 0L);
        return root;
    }
    private static void fix(PyInstance node, long lineno, long col, long endLineno, long endCol) {
        if (((List<?>) pyAttrs(node)).contains("lineno")) {
            Object v = node.fields.get("lineno");
            if (v == null) node.fields.put("lineno", lineno); else lineno = ((Number) v).longValue();
            v = node.fields.get("end_lineno");
            if (v == null) node.fields.put("end_lineno", endLineno); else endLineno = ((Number) v).longValue();
            v = node.fields.get("col_offset");
            if (v == null) node.fields.put("col_offset", col); else col = ((Number) v).longValue();
            v = node.fields.get("end_col_offset");
            if (v == null) node.fields.put("end_col_offset", endCol); else endCol = ((Number) v).longValue();
        }
        for (Object child : (List<?>) iterChildNodes(node)) fix((PyInstance) child, lineno, col, endLineno, endCol);
    }

    private static Object incrementLineno(Object root, Object nObj) {
        long n = ((Number) nObj).longValue();
        for (Object o : (List<?>) walk(root)) {
            PyInstance node = (PyInstance) o;
            for (String a : new String[]{"lineno", "end_lineno"}) if (node.fields.get(a) instanceof Number v) node.fields.put(a, v.longValue() + n);
        }
        return null;
    }

    private static Object getDocstring(Object node) {
        PyInstance n = (PyInstance) node;
        String t = n.cls.name;
        if (!List.of("AsyncFunctionDef", "FunctionDef", "ClassDef", "Module").contains(t)) throw new PyException("TypeError", "'" + t + "' can't have docstrings");
        List<?> body = (List<?>) n.fields.get("body");
        if (body == null || body.isEmpty() || !(body.get(0) instanceof PyInstance first) || !first.cls.name.equals("Expr")) return null;
        Object v = first.fields.get("value");
        if (v instanceof PyInstance c && c.cls.name.equals("Constant") && c.fields.get("value") instanceof String s) return s;
        return null;
    }

    private static Object literalEval(Object node) {
        if (node instanceof String s) node = compileToAst(s.strip(), "eval");
        PyInstance n = (PyInstance) node;
        if (n.cls.name.equals("Expression")) n = (PyInstance) n.fields.get("body");
        return evalLiteral(n);
    }
    private static Object evalLiteral(PyInstance n) {
        switch (n.cls.name) {
            case "Constant": return n.fields.get("value");
            case "Tuple": { PyTuple t = new PyTuple(); for (Object e : (List<?>) n.fields.get("elts")) t.items.add(evalLiteral((PyInstance) e)); return t; }
            case "List": { ArrayList<Object> l = new ArrayList<>(); for (Object e : (List<?>) n.fields.get("elts")) l.add(evalLiteral((PyInstance) e)); return l; }
            case "Set": { List<Object> items = new ArrayList<>(); for (Object e : (List<?>) n.fields.get("elts")) items.add(evalLiteral((PyInstance) e)); return setFrom(items); }
            case "Dict": {
                PyDict d = new PyDict(); List<?> ks = (List<?>) n.fields.get("keys"), vs = (List<?>) n.fields.get("values");
                for (int i = 0; i < ks.size(); i++) {
                    if (ks.get(i) == null) throw new PyException("ValueError", "malformed node or string: " + pyRepr(n));
                    d.put(evalLiteral((PyInstance) ks.get(i)), evalLiteral((PyInstance) vs.get(i)));
                }
                return d;
            }
            case "UnaryOp": {
                Object operand = evalLiteral((PyInstance) n.fields.get("operand"));
                String op = ((PyInstance) n.fields.get("op")).cls.name;
                if (op.equals("USub") && (operand instanceof Number || operand instanceof PyComplex)) return neg(operand);
                if (op.equals("UAdd") && (operand instanceof Number || operand instanceof PyComplex)) return operand;
                break;
            }
            case "BinOp": {
                Object l = evalLiteral((PyInstance) n.fields.get("left")), r = evalLiteral((PyInstance) n.fields.get("right"));
                String op = ((PyInstance) n.fields.get("op")).cls.name;
                if ((l instanceof Number || l instanceof PyComplex) && (r instanceof PyComplex || r instanceof Number)) {
                    if (op.equals("Add")) return add(l, r);
                    if (op.equals("Sub")) return sub(l, r);
                }
                break;
            }
            default: break;
        }
        throw new PyException("ValueError", "malformed node or string on line " + n.fields.get("lineno") + ": " + pyRepr(n));
    }

    // ---- dump (CPython 3.13's algorithm) ------------------------------------------------------------
    private static Object dump(List<Object> a, Map<Object, Object> k) {
        Object node = a.get(0);
        boolean annotate = truth(arg(a, k, 1, "annotate_fields", true));
        boolean includeAttributes = truth(arg(a, k, 2, "include_attributes", false));
        Object indentArg = k.get("indent");
        boolean showEmpty = truth(k.getOrDefault("show_empty", false));
        String indent = indentArg == null ? null : indentArg instanceof String s ? s : " ".repeat(((Number) indentArg).intValue());
        if (!isAst(node)) throw new PyException("TypeError", "expected AST, got '" + typeName(node) + "'");
        return new Dumper(annotate, includeAttributes, indent, showEmpty).format(node, 0).text;
    }

    private static final class Dumper {
        final boolean annotate, includeAttributes, showEmpty; final String indent;
        Dumper(boolean annotate, boolean includeAttributes, String indent, boolean showEmpty) {
            this.annotate = annotate; this.includeAttributes = includeAttributes; this.indent = indent; this.showEmpty = showEmpty;
        }
        record Result(String text, boolean simple) {}

        Result format(Object node, int level) {
            String prefix, sep;
            if (indent != null) { level++; prefix = "\n" + indent.repeat(level); sep = ",\n" + indent.repeat(level); }
            else { prefix = ""; sep = ", "; }
            if (isAst(node)) {
                PyInstance n = (PyInstance) node;
                List<String> args = new ArrayList<>(), buffer = new ArrayList<>();
                boolean allSimple = true, keywords = annotate;
                for (String name : fieldsOf(n)) {
                    if (!n.fields.containsKey(name)) {
                        Object cv = n.cls.lookupAttr(name);
                        keywords = true; continue;
                    }
                    Object value = n.fields.get(name);
                    if (value == null && n.cls.lookupAttr(name) == null) { keywords = true; continue; }
                    boolean special = n.cls.name.equals("Constant") || n.cls.name.equals("MatchSingleton");
                    // 3.14+: a default ctx=Load() is not shown
                    if (!showEmpty && LangVersion.atLeast(3, 14) && name.equals("ctx") && value instanceof PyInstance cv && cv.cls.name.equals("Load")) continue;
                    if (!showEmpty && (value == null || (value instanceof List<?> l && l.isEmpty())) && !special) { buffer.add(pyRepr(value)); continue; }
                    else if (!keywords) { args.addAll(buffer); buffer.clear(); }
                    Result r = format(value, level);
                    allSimple = allSimple && r.simple;
                    args.add(keywords ? name + "=" + r.text : r.text);
                }
                if (includeAttributes) {
                    for (Object an : (List<?>) pyAttrs(n)) {
                        String name = (String) an;
                        if (!n.fields.containsKey(name)) continue;
                        Object value = n.fields.get(name);
                        if (value == null && n.cls.lookupAttr(name) == null) continue;
                        Result r = format(value, level);
                        allSimple = allSimple && r.simple;
                        args.add(name + "=" + r.text);
                    }
                }
                if (allSimple && args.size() <= 3) return new Result(n.cls.name + "(" + String.join(", ", args) + ")", args.isEmpty());
                return new Result(n.cls.name + "(" + prefix + String.join(sep, args) + ")", false);
            }
            if (node instanceof List<?> list) {
                if (list.isEmpty()) return new Result("[]", true);
                List<String> parts = new ArrayList<>();
                for (Object x : list) parts.add(format(x, level).text);
                return new Result("[" + prefix + String.join(sep, parts) + "]", false);
            }
            return new Result(pyRepr(node), true);
        }
    }

    // ---- NodeVisitor / NodeTransformer ------------------------------------------------------------------
    public static Object visit(Object self, Object node) {
        String name = "visit_" + ((PyInstance) node).cls.name;
        Object visitor = getattrDefault(self, name, getattr(self, "generic_visit"));
        return callFunction(visitor, new ArrayList<Object>(List.of(node)), new LinkedHashMap<Object, Object>());
    }

    public static Object genericVisit(Object self, Object node) {
        for (Object pair : (List<?>) iterFields(node)) {
            Object value = ((PyTuple) pair).items.get(1);
            if (value instanceof List<?> list) { for (Object item : new ArrayList<>(list)) if (isAst(item)) callMethod(self, "visit", new Object[]{item}); }
            else if (isAst(value)) callMethod(self, "visit", new Object[]{value});
        }
        return null;
    }

    public static Object transformerGenericVisit(Object self, Object node) {
        PyInstance n = (PyInstance) node;
        for (Object pair : (List<?>) iterFields(node)) {
            String field = (String) ((PyTuple) pair).items.get(0);
            Object old = ((PyTuple) pair).items.get(1);
            if (old instanceof List<?> list) {
                ArrayList<Object> fresh = new ArrayList<>();
                for (Object value : list) {
                    if (isAst(value)) {
                        Object replaced = callMethod(self, "visit", new Object[]{value});
                        if (replaced == null) continue;
                        if (!isAst(replaced)) { if (replaced instanceof List<?> many) { fresh.addAll(many); continue; } }
                    fresh.add(replaced);
                    } else fresh.add(value);
                }
                ((ArrayList<Object>) old).clear();
                ((ArrayList<Object>) old).addAll(fresh);
            } else if (isAst(old)) {
                Object replaced = callMethod(self, "visit", new Object[]{old});
                if (replaced == null) n.fields.remove(field); else n.fields.put(field, replaced);
            }
        }
        return node;
    }
}
