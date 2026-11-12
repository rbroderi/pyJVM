// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import static pyjvm315.runtime.ExceptionGroupSupport.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPython's traceback report (traceback.TracebackException.format): "Traceback (most recent call last)" with source
 * lines and caret markers, the cause / context chain, exception notes and the exception-group tree.
 *
 * Carets: CPython keeps a column range per bytecode instruction; pyJVM keeps one current line per frame.  The compiler
 * therefore records the single-line call / binary-operator / subscript expressions of every line (see carets.py) and
 * {@link #pickCaret} chooses among them; when the line is ambiguous, or the failing instruction is of another kind
 * (attribute load, name lookup, comparison, unary operator, iteration, unpacking, ...), no carets are printed.
 */
final class TracebackFormat {
    private TracebackFormat() {}

    private static final int RECURSION_CUTOFF = 3, MAX_GROUP_WIDTH = 15, MAX_GROUP_DEPTH = 10;

    // ---------- caret tables registered by compiled modules ----------
    private static final Map<String,StringBuilder> RAW = new HashMap<>();
    private static final Map<String,Map<Integer,List<String[]>>> PARSED = new HashMap<>();
    private static final Map<String,List<String>> SOURCE = new HashMap<>();

    static synchronized void registerCarets(String filename, String chunk) {
        RAW.computeIfAbsent(filename, k -> new StringBuilder()).append(chunk);
        PARSED.remove(filename);
    }

    private static synchronized List<String[]> candidates(String filename, int line) {
        Map<Integer,List<String[]>> table = PARSED.get(filename);
        if (table == null) {
            table = new HashMap<>();
            StringBuilder raw = RAW.get(filename);
            if (raw != null && raw.length() > 0) {
                for (String record : raw.toString().split(";")) {
                    String[] f = record.split(",");
                    table.computeIfAbsent(Integer.parseInt(f[0]), k -> new ArrayList<>()).add(f);
                }
            }
            PARSED.put(filename, table);
        }
        return table.getOrDefault(line, List.of());
    }

    private static synchronized String sourceLine(String filename, int line) {
        List<String> lines = SOURCE.get(filename);
        if (lines == null) {
            try {
                lines = filename.startsWith("<") ? List.of() : Files.readAllLines(Path.of(filename), StandardCharsets.UTF_8);
            } catch (Exception e) {
                lines = List.of();
            }
            SOURCE.put(filename, lines);
        }
        return line >= 1 && line <= lines.size() ? lines.get(line - 1) : null;
    }

    private static final java.util.Set<String> NO_CARET_TYPES = java.util.Set.of(
        "AttributeError", "NameError", "UnboundLocalError", "ImportError", "ModuleNotFoundError", "AssertionError",
        "StopIteration", "StopAsyncIteration", "SyntaxError", "KeyboardInterrupt", "SystemExit", "GeneratorExit", "RuntimeError");

    /** The caret record for a traceback entry, or null: {start,end,left,right,suppress} as ints. */
    private static int[] pickCaret(String filename, int line, boolean leaf, String excType) {
        List<String[]> all = candidates(filename, line);
        if (all.isEmpty()) return null;
        List<String[]> calls = new ArrayList<>(), binops = new ArrayList<>(), subs = new ArrayList<>();
        boolean statementKind = false;
        for (String[] r : all) {
            switch (r[1]) {
                case "x": statementKind = true; break;
                case "c": calls.add(r); break;
                case "b": binops.add(r); break;
                default: subs.add(r);
            }
        }
        String[] pick = null;
        if (!leaf) {
            if (calls.size() == 1) pick = calls.get(0);
        } else if (!statementKind && !NO_CARET_TYPES.contains(excType)) {
            int total = calls.size() + binops.size() + subs.size();
            if (total == 1) pick = !calls.isEmpty() ? calls.get(0) : !binops.isEmpty() ? binops.get(0) : subs.get(0);
            else if (excType.equals("ZeroDivisionError") && binops.size() == 1) pick = binops.get(0);
            else if ((excType.equals("IndexError") || excType.equals("KeyError")) && subs.size() == 1) pick = subs.get(0);
        }
        if (pick == null) return null;
        return new int[]{Integer.parseInt(pick[2]), Integer.parseInt(pick[3]), Integer.parseInt(pick[4]), Integer.parseInt(pick[5]), Integer.parseInt(pick[6])};
    }

    // ---------- positions for traceback.py ----------
    /**
     * What {@code code.co_positions()} holds for the instruction a traceback entry stopped at: (line, end line, start
     * column, end column), columns in UTF-8 bytes; null when {@link #pickCaret} has no single candidate. traceback.py
     * reads it as {@code co_positions()[tb_lasti // 2]} and derives the carets from that range itself, so the Python
     * formatter underlines what the native one does.
     */
    private static PyTuple positions(PyTraceback tb) {
        String filename = tb.frame.code.coFilename;
        int line = (int) tb.lineno;
        int[] caret = pickCaret(filename, line, tb.next == null, tb.leafType == null ? "" : tb.leafType);
        String text = caret == null ? null : sourceLine(filename, line);
        if (text == null || caret[1] <= caret[0]) return null;
        PyTuple t = new PyTuple();
        t.items.add((long) line); t.items.add((long) line); t.items.add(utf8Column(text, caret[0])); t.items.add(utf8Column(text, caret[1]));
        return t;
    }

    private static long utf8Column(String text, int chars) {
        int end = chars >= text.codePointCount(0, text.length()) ? text.length() : text.offsetByCodePoints(0, Math.max(0, chars));
        return text.substring(0, end).getBytes(StandardCharsets.UTF_8).length;
    }

    /** {@code tb.tb_lasti}: there are no instruction offsets; 0 selects the entry's one position record, -1 says there is none. */
    static long lasti(PyTraceback tb) { return tb.frame.code.shown == tb && positions(tb) != null ? 0L : -1L; }

    /** {@code tb.tb_frame.f_code.co_positions()} for the code object of a traceback entry. */
    static Object codePositions(PyTraceback tb) {
        ArrayList<Object> out = new ArrayList<>();
        PyTuple p = positions(tb);
        if (p == null) { p = new PyTuple(); for (int k = 0; k < 4; k++) p.items.add(null); }
        out.add(p);
        return PyRuntime.iter(out);
    }

    // ---------- stack ----------
    private static void formatFrame(StringBuilder out, PyTraceback tb, boolean leaf, String excType) {
        String filename = tb.frame.code.coFilename;
        int lineno = (int) tb.lineno;
        out.append("  File \"").append(filename).append("\", line ").append(lineno).append(", in ").append(tb.frame.code.coName).append('\n');
        String text = sourceLine(filename, lineno);
        if (text == null) return;
        if (leaf) {
            for (String[] r : candidates(filename, lineno)) {
                if (r[1].equals("x") && Integer.parseInt(r[2]) > lineno) { formatStatementLines(out, filename, lineno, Integer.parseInt(r[2])); return; }
            }
        }
        int lead = 0;
        while (lead < text.length() && (text.charAt(lead) == ' ' || text.charAt(lead) == '\t' || text.charAt(lead) == '\f')) lead++;
        String stripped = text.substring(lead).stripTrailing();
        if (stripped.isEmpty()) return;
        out.append("    ").append(stripped).append('\n');
        int[] caret = pickCaret(filename, lineno, leaf, excType);
        if (caret == null) return;
        int start = Math.max(0, caret[0] - lead), end = Math.max(0, caret[1] - lead);
        int left = Math.max(0, caret[2] - lead), right = Math.max(0, caret[3] - lead);
        end = Math.min(end, stripped.length());
        if (caret[4] == 1) return;                       // "return f(...)" / "x = f(...)" spanning the call: no carets
        if (end <= start) return;
        StringBuilder marks = new StringBuilder();
        for (int col = 0; col < end; col++) {
            if (col < start) marks.append(' ');
            else if (col >= left && col < right) marks.append('^');
            else marks.append('~');
        }
        out.append("    ").append(marks).append('\n');
    }

    /** A multi-line raise / assert / import statement is shown in full (dedented), without carets. */
    private static void formatStatementLines(StringBuilder out, String filename, int first, int last) {
        List<String> lines = new ArrayList<>();
        int indent = Integer.MAX_VALUE;
        for (int n = first; n <= last; n++) {
            String line = sourceLine(filename, n);
            if (line == null) return;
            lines.add(line);
            if (!line.isBlank()) {
                int lead = 0;
                while (lead < line.length() && (line.charAt(lead) == ' ' || line.charAt(lead) == '\t')) lead++;
                indent = Math.min(indent, lead);
            }
        }
        for (String line : lines) out.append("    ").append(line.isBlank() ? "" : line.substring(indent).stripTrailing()).append('\n');
    }

    private static void formatStack(StringBuilder out, PyTraceback tb, String excType) {
        String lastFile = null, lastName = null; int lastLine = -1, count = 0;
        for (PyTraceback cur = tb; cur != null; cur = cur.next) {
            String file = cur.frame.code.coFilename, name = cur.frame.code.coName; int line = (int) cur.lineno;
            if (lastFile == null || !lastFile.equals(file) || lastLine != line || !lastName.equals(name)) {
                if (count > RECURSION_CUTOFF) {
                    int more = count - RECURSION_CUTOFF;
                    out.append("  [Previous line repeated ").append(more).append(" more time").append(more > 1 ? "s" : "").append("]\n");
                }
                lastFile = file; lastLine = line; lastName = name; count = 0;
            }
            count++;
            if (count > RECURSION_CUTOFF) continue;
            formatFrame(out, cur, cur.next == null, excType);
        }
        if (count > RECURSION_CUTOFF) {
            int more = count - RECURSION_CUTOFF;
            out.append("  [Previous line repeated ").append(more).append(" more time").append(more > 1 ? "s" : "").append("]\n");
        }
    }

    // ---------- exception line ----------
    private static String safeStr(Object value, String what, boolean repr) {
        try {
            return repr ? pyRepr(value) : pyStr(value);
        } catch (Throwable t) {
            return "<" + what + " " + (repr ? "repr" : "str") + "() failed>";
        }
    }

    private static String typeString(Object exc) {
        String name; String module = "builtins";
        if (exc instanceof PyInstance i) {
            name = i.cls.qualname != null ? i.cls.qualname : i.cls.name;
            module = i.cls.moduleName == null ? "builtins" : i.cls.moduleName;
            if (i.cls.name.equals(name) && i.cls.exceptionBase != null && i.cls.moduleName == null) module = "builtins";
        } else {
            name = excTypeName(exc);
        }
        return module.equals("__main__") || module.equals("builtins") ? name : module + "." + name;
    }

    private static void formatExceptionOnly(StringBuilder out, Object exc) {
        String type = typeString(exc), str = safeStr(exc, "exception", false);
        out.append(str.isEmpty() ? type : type + ": " + str).append('\n');
        Object notes = notes(exc);
        if (notes == MISSING || notes == null) return;
        if (notes instanceof List<?> list) {
            for (Object note : new ArrayList<Object>(list)) {
                for (String part : safeStr(note, "note", false).split("\n", -1)) out.append(part).append('\n');
            }
        } else if (notes instanceof PyTuple tuple) {
            for (Object note : tuple.items) {
                for (String part : safeStr(note, "note", false).split("\n", -1)) out.append(part).append('\n');
            }
        } else if (!(notes instanceof String) && !(notes instanceof PyBytes)) {
            out.append(safeStr(notes, "__notes__", true)).append('\n');
        } else {
            out.append(safeStr(notes, "__notes__", true)).append('\n');
        }
    }

    // ---------- chain and groups ----------
    private static final class Node {
        Object exc; Node cause, context; List<Node> children;
    }

    private static Node build(Object exc, IdentityHashMap<Object,Boolean> seen) {
        Node node = new Node();
        node.exc = exc;
        seen.put(exc, Boolean.TRUE);
        Object cause = cause(exc), context = context(exc);
        if (cause != null && !seen.containsKey(cause)) node.cause = build(cause, seen);
        if (context != null && !seen.containsKey(context)) node.context = build(context, seen);
        if (isGroup(exc)) {
            node.children = new ArrayList<>();
            for (Object child : new ArrayList<>(groupExceptions(exc).items)) {
                if (isExc(child)) node.children.add(build(child, seen));
            }
        }
        return node;
    }

    private static final class Context {
        final StringBuilder out = new StringBuilder();
        int depth = 0; boolean needClose = false;

        String indent() { return "  ".repeat(depth); }

        void emit(String text, char margin) {
            String prefix = indent() + (depth > 0 ? margin + " " : "");
            int i = 0;
            while (i < text.length()) {
                int nl = text.indexOf('\n', i);
                int stop = nl < 0 ? text.length() : nl + 1;
                out.append(prefix).append(text, i, stop);
                i = stop;
            }
        }
        void emit(String text) { emit(text, '|'); }
    }

    private static void formatNode(Node node, Context ctx) {
        List<Object[]> chain = new ArrayList<>();
        Node cur = node;
        while (cur != null) {
            String msg = null; Node next = null;
            if (cur.cause != null) {
                msg = "\nThe above exception was the direct cause of the following exception:\n\n"; next = cur.cause;
            } else if (cur.context != null && !suppress(cur.exc)) {
                msg = "\nDuring handling of the above exception, another exception occurred:\n\n"; next = cur.context;
            }
            chain.add(new Object[]{msg, cur});
            cur = next;
        }
        for (int k = chain.size() - 1; k >= 0; k--) {
            String msg = (String) chain.get(k)[0];
            Node n = (Node) chain.get(k)[1];
            if (msg != null) ctx.emit(msg);
            formatOne(n, ctx);
        }
    }

    private static void formatOne(Node node, Context ctx) {
        Object exc = node.exc;
        PyTraceback tb = traceback(exc);
        StringBuilder stack = new StringBuilder();
        if (tb != null) formatStack(stack, tb, excTypeName(exc));
        StringBuilder only = new StringBuilder();
        formatExceptionOnly(only, exc);
        if (node.children == null) {
            if (tb != null) {
                ctx.emit("Traceback (most recent call last):\n");
                ctx.emit(stack.toString());
            }
            ctx.emit(only.toString());
            return;
        }
        if (ctx.depth > MAX_GROUP_DEPTH) {
            ctx.emit("... (max_group_depth is " + MAX_GROUP_DEPTH + ")\n");
            return;
        }
        boolean top = ctx.depth == 0;
        if (top) ctx.depth++;
        if (tb != null) {
            ctx.emit("Exception Group Traceback (most recent call last):\n", top ? '+' : '|');
            ctx.emit(stack.toString());
        }
        ctx.emit(only.toString());
        int total = node.children.size();
        int shown = total <= MAX_GROUP_WIDTH ? total : MAX_GROUP_WIDTH + 1;
        ctx.needClose = false;
        for (int i = 0; i < shown; i++) {
            boolean last = i == shown - 1;
            if (last) ctx.needClose = true;
            boolean truncated = i >= MAX_GROUP_WIDTH;
            String title = truncated ? "..." : Integer.toString(i + 1);
            ctx.out.append(ctx.indent()).append(i == 0 ? "+-" : "  ").append("+---------------- ").append(title).append(" ----------------\n");
            ctx.depth++;
            if (!truncated) {
                formatNode(node.children.get(i), ctx);
            } else {
                int remaining = total - MAX_GROUP_WIDTH;
                ctx.emit("and " + remaining + " more exception" + (remaining > 1 ? "s" : "") + "\n");
            }
            if (last && ctx.needClose) {
                ctx.out.append(ctx.indent()).append("+------------------------------------\n");
                ctx.needClose = false;
            }
            ctx.depth--;
        }
        if (top) ctx.depth = 0;
    }

    /** The complete report CPython prints for an uncaught (or traceback.print_exception'd) exception. */
    static String format(Object exc) {
        Context ctx = new Context();
        formatNode(build(exc, new IdentityHashMap<>()), ctx);
        return ctx.out.toString();
    }
}
