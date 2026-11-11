package pyjvm315.parser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An AST node as produced by {@link Parser}: a node type from {@link AstSchema} with positional field values and
 * (for statements/expressions/...) CPython-compatible positions (lines 1-based, columns in UTF-8 bytes).
 * Field values are: AstNode, List&lt;Object&gt;, String, byte[], Long/BigInteger, Double, {@link Complex},
 * Boolean, {@link #ELLIPSIS}, or null.
 */
public final class AstNode {
    public record Complex(double real, double imag) {}
    public static final Object ELLIPSIS = new Object() { @Override public String toString() { return "Ellipsis"; } };

    /** Static description of one node type. */
    public static final class Spec {
        public final String name, base; public final boolean hasPositions;
        public final String[] fields; public final boolean[] isList, isOptional;
        Spec(String[] row) {
            this.name = row[0]; this.base = row[1]; this.hasPositions = row[2].equals("1");
            int count = row.length - 3;
            fields = new String[count]; isList = new boolean[count]; isOptional = new boolean[count];
            for (int k = 0; k < count; k++) {
                String f = row[3 + k];
                char last = f.charAt(f.length() - 1);
                isList[k] = last == '*'; isOptional[k] = last == '?';
                fields[k] = (last == '*' || last == '?') ? f.substring(0, f.length() - 1) : f;
            }
        }
        public int indexOf(String field) { for (int k = 0; k < fields.length; k++) if (fields[k].equals(field)) return k; return -1; }
    }

    private static final Map<String, Spec> SPECS = new HashMap<>();
    static { for (String[] row : AstSchema.NODES) SPECS.put(row[0], new Spec(row)); }
    public static Spec spec(String name) {
        Spec s = SPECS.get(name);
        if (s == null) throw new IllegalArgumentException("unknown AST node type " + name);
        return s;
    }
    public static boolean exists(String name) { return SPECS.containsKey(name); }
    public static java.util.Collection<Spec> allSpecs() { return SPECS.values(); }

    public final Spec spec;
    public final Object[] values;
    public int lineno, colOffset, endLineno, endColOffset;

    public AstNode(String type, Object... values) {
        this.spec = spec(type);
        if (values.length != spec.fields.length)
            throw new IllegalArgumentException(type + " takes " + spec.fields.length + " fields, got " + values.length);
        this.values = values;
    }

    public String type() { return spec.name; }
    public Object get(String field) { return values[spec.indexOf(field)]; }
    public void set(String field, Object value) { values[spec.indexOf(field)] = value; }
    public boolean is(String type) { return spec.name.equals(type); }
    @SuppressWarnings("unchecked") public List<Object> list(String field) { return (List<Object>) get(field); }
    public AstNode node(String field) { return (AstNode) get(field); }

    public static List<Object> listOf(Object... items) { List<Object> l = new ArrayList<>(items.length); for (Object o : items) l.add(o); return l; }

    // ---- canonical JSON ----------------------------------------------------------------------
    /** Deterministic JSON used to compare trees with CPython's ast (see tools/compare_ast.py). */
    public static String toJson(Object value) { StringBuilder sb = new StringBuilder(); json(sb, value); return sb.toString(); }

    private static void json(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof AstNode n) {
            sb.append("{\"_\":"); string(sb, n.spec.name);
            for (int k = 0; k < n.values.length; k++) { sb.append(','); string(sb, n.spec.fields[k]); sb.append(':'); json(sb, n.values[k]); }
            if (n.spec.hasPositions) {
                sb.append(",\"lineno\":").append(n.lineno).append(",\"col_offset\":").append(n.colOffset)
                  .append(",\"end_lineno\":").append(n.endLineno).append(",\"end_col_offset\":").append(n.endColOffset);
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int k = 0; k < list.size(); k++) { if (k > 0) sb.append(','); json(sb, list.get(k)); }
            sb.append(']');
        } else if (v instanceof String s) string(sb, s);
        else if (v instanceof Boolean b) sb.append(b ? "true" : "false");
        else if (v instanceof Long || v instanceof Integer || v instanceof BigInteger) sb.append(v);
        else if (v instanceof Double d) sb.append("{\"float\":").append(Double.doubleToLongBits(d)).append('}');
        else if (v instanceof Complex c) sb.append("{\"complex\":[").append(Double.doubleToLongBits(c.real())).append(',').append(Double.doubleToLongBits(c.imag())).append("]}");
        else if (v instanceof byte[] bytes) { sb.append("{\"bytes\":\""); for (byte b : bytes) sb.append(String.format("%02x", b & 0xff)); sb.append("\"}"); }
        else if (v == ELLIPSIS) sb.append("{\"Ellipsis\":1}");
        else throw new IllegalArgumentException("cannot serialise " + v.getClass());
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\""); case '\\' -> sb.append("\\\\"); case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r"); case '\t' -> sb.append("\\t"); case '\b' -> sb.append("\\b"); case '\f' -> sb.append("\\f");
                default -> { if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }
}
