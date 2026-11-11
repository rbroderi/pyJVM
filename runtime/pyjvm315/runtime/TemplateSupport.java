// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.List;

/**
 * PEP 750 template strings: the {@code string.templatelib.Template} and {@code Interpolation} classes that the compiler's
 * lowering of {@code t"..."} literals constructs. {@code string/templatelib.py} obtains both classes with
 * {@code type(t"{0}")}, so they only need to exist as the types of these literals.
 */
public final class TemplateSupport {
    private TemplateSupport() {}

    static final class Tpl { final ArrayList<String> strings = new ArrayList<>(); final ArrayList<PyInstance> interpolations = new ArrayList<>(); }
    static final class Ip { Object value, expression, conversion, formatSpec; }

    private static PyClass templateClass, interpolationClass;

    private static synchronized void init() {
        if (templateClass != null) return;
        Class<?> c = TemplateSupport.class;
        PyClass ip = define("string.templatelib", "Interpolation", null);
        method(ip, c, "__init__", "ipInit", "self,value,expression,conversion,format_spec",
                defaults("expression", "", "conversion", null, "format_spec", ""));
        method(ip, c, "__repr__", "ipRepr", "self");
        PyRuntime.classAddProperty(ip, "value", c.getName(), "ipValue", null);
        PyRuntime.classAddProperty(ip, "expression", c.getName(), "ipExpression", null);
        PyRuntime.classAddProperty(ip, "conversion", c.getName(), "ipConversion", null);
        PyRuntime.classAddProperty(ip, "format_spec", c.getName(), "ipFormatSpec", null);
        finish(ip);
        PyRuntime.classAddAttr(ip, "__match_args__", tuple("value", "expression", "conversion", "format_spec"));

        PyClass tpl = define("string.templatelib", "Template", null);
        methodVar(tpl, c, "__init__", "tplInit", "self", "args");
        method(tpl, c, "__iter__", "tplIter", "self");
        method(tpl, c, "__repr__", "tplRepr", "self");
        method(tpl, c, "__add__", "tplAdd", "self,other");
        PyRuntime.classAddProperty(tpl, "strings", c.getName(), "tplStrings", null);
        PyRuntime.classAddProperty(tpl, "interpolations", c.getName(), "tplInterpolations", null);
        PyRuntime.classAddProperty(tpl, "values", c.getName(), "tplValues", null);
        finish(tpl);
        interpolationClass = ip;
        templateClass = tpl;
    }

    /** Runtime entry for an interpolation of a t-string literal. */
    public static Object interpolation(Object value, Object expression, Object conversion, Object formatSpec) {
        init();
        Ip state = new Ip();
        state.value = value; state.expression = expression; state.conversion = conversion; state.formatSpec = formatSpec;
        return newInstance(interpolationClass, state);
    }

    /** Runtime entry for a t-string literal: {@code parts} alternates str and Interpolation values. */
    public static Object template(Object parts) {
        init();
        Tpl state = new Tpl();
        build(state, (List<?>) parts);
        return newInstance(templateClass, state);
    }

    private static void build(Tpl state, List<?> parts) {
        StringBuilder current = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof String s) current.append(s);
            else if (part instanceof PyInstance i && i.slotValues.get(STATE) instanceof Ip) {
                state.strings.add(current.toString());
                current.setLength(0);
                state.interpolations.add(i);
            } else throw new PyException("TypeError", "Template.__new__ *args need to be of type 'str' or 'Interpolation', got " + PyRuntime.typeName(part));
        }
        state.strings.add(current.toString());
    }

    private static Ip ip(Object o) { return state(o, Ip.class, "Interpolation"); }
    private static Tpl tpl(Object o) { return state(o, Tpl.class, "Template"); }

    // ---- Interpolation -----------------------------------------------------------------------------

    public static Object ipInit(Object self, Object value, Object expression, Object conversion, Object formatSpec) {
        if (!(expression instanceof String)) throw new PyException("TypeError", "Interpolation() argument 'expression' must be str, not " + PyRuntime.typeName(expression));
        if (!(formatSpec instanceof String)) throw new PyException("TypeError", "Interpolation() argument 'format_spec' must be str, not " + PyRuntime.typeName(formatSpec));
        if (conversion != null && !("a".equals(conversion) || "r".equals(conversion) || "s".equals(conversion)))
            throw new PyException("ValueError", "Interpolation() argument 'conversion' must be one of 's', 'a' or 'r'");
        Ip state = new Ip();
        state.value = value; state.expression = expression; state.conversion = conversion; state.formatSpec = formatSpec;
        setState(self, state);
        return null;
    }

    public static Object ipValue(Object self) { return ip(self).value; }
    public static Object ipExpression(Object self) { return ip(self).expression; }
    public static Object ipConversion(Object self) { return ip(self).conversion; }
    public static Object ipFormatSpec(Object self) { return ip(self).formatSpec; }

    public static Object ipRepr(Object self) {
        Ip s = ip(self);
        return "Interpolation(" + PyRuntime.pyRepr(s.value) + ", " + PyRuntime.pyRepr(s.expression) + ", " + PyRuntime.pyRepr(s.conversion) + ", " + PyRuntime.pyRepr(s.formatSpec) + ")";
    }

    // ---- Template ----------------------------------------------------------------------------------

    public static Object tplInit(Object self, Object args) {
        Tpl state = new Tpl();
        build(state, ((PyTuple) args).items);
        setState(self, state);
        return null;
    }

    public static Object tplStrings(Object self) { return tupleOf(tpl(self).strings); }
    public static Object tplInterpolations(Object self) { return tupleOf(tpl(self).interpolations); }
    public static Object tplValues(Object self) {
        ArrayList<Object> out = new ArrayList<>();
        for (PyInstance i : tpl(self).interpolations) out.add(ip(i).value);
        return tupleOf(out);
    }

    public static Object tplIter(Object self) {
        Tpl t = tpl(self);
        ArrayList<Object> out = new ArrayList<>();
        for (int i = 0; i < t.strings.size(); i++) {
            if (!t.strings.get(i).isEmpty()) out.add(t.strings.get(i));
            if (i < t.interpolations.size()) out.add(t.interpolations.get(i));
        }
        return out.iterator();
    }

    public static Object tplRepr(Object self) {
        Tpl t = tpl(self);
        return "Template(strings=" + PyRuntime.pyRepr(tupleOf(t.strings)) + ", interpolations=" + PyRuntime.pyRepr(tupleOf(t.interpolations)) + ")";
    }

    public static Object tplAdd(Object self, Object other) {
        if (!(other instanceof PyInstance o) || !(o.slotValues.get(STATE) instanceof Tpl right))
            throw new PyException("TypeError", "unsupported operand type(s) for +: 'Template' and '" + PyRuntime.typeName(other) + "'");
        Tpl left = tpl(self);
        Tpl out = new Tpl();
        out.strings.addAll(left.strings);
        out.interpolations.addAll(left.interpolations);
        out.strings.set(out.strings.size() - 1, out.strings.get(out.strings.size() - 1) + right.strings.get(0));
        for (int i = 1; i < right.strings.size(); i++) out.strings.add(right.strings.get(i));
        out.interpolations.addAll(right.interpolations);
        return newInstance(templateClass, out);
    }
}
