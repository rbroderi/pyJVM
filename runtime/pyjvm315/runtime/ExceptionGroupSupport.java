// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exception objects as Python values: the chaining attributes ({@code __cause__}, {@code __context__},
 * {@code __traceback__}, {@code __suppress_context__}), {@code __notes__} and {@code add_note}, the link between a
 * thrown {@link PyException} and its Python-visible instance, the {@code BaseExceptionGroup} methods
 * ({@code subgroup}, {@code split}, {@code derive}) and the run-time helpers behind {@code try/except*}.
 *
 * <p>An exception value is either a {@link PyExceptionValue} (builtin class) or a {@link PyInstance} whose class
 * derives from a builtin exception. The thrown form is always a {@link PyException}; {@link PyException#instance}
 * (or its {@code value} for Python-defined classes) keeps the two sides the same object so identity, notes and
 * user attributes survive a catch and a re-raise.
 */
final class ExceptionGroupSupport {
    private ExceptionGroupSupport() {}

    // ---------- classification and metadata ----------
    static boolean isExc(Object o) {
        return o instanceof PyExceptionValue || o instanceof PyInstance i && i.cls.exceptionBase != null;
    }

    static boolean isGroup(Object o) {
        if (o instanceof PyExceptionValue v) return v.value instanceof PyExcArgs a && a.group != null;
        return o instanceof PyInstance i && i.cls.exceptionBase != null && BuiltinsExtra.isGroupType(i.cls.exceptionBase)
            && i.fields.get("exceptions") instanceof PyTuple;
    }

    static PyTuple groupExceptions(Object g) {
        if (g instanceof PyExceptionValue v) return ((PyExcArgs) v.value).group;
        return (PyTuple) ((PyInstance) g).fields.get("exceptions");
    }

    static Object groupMessage(Object g) {
        if (g instanceof PyExceptionValue v) return ((PyExcArgs) v.value).args.items.get(0);
        return ((PyInstance) g).fields.get("message");
    }

    static String excTypeName(Object e) {
        return e instanceof PyExceptionValue v ? v.typeName : e instanceof PyInstance i ? i.cls.name : typeName(e);
    }

    static ExcState state(PyInstance i) { if (i.exc == null) i.exc = new ExcState(); return i.exc; }

    static Object cause(Object e) { return e instanceof PyExceptionValue v ? v.cause : e instanceof PyInstance i && i.exc != null ? i.exc.cause : null; }
    static Object context(Object e) { return e instanceof PyExceptionValue v ? v.context : e instanceof PyInstance i && i.exc != null ? i.exc.context : null; }
    static boolean suppress(Object e) { return e instanceof PyExceptionValue v ? v.suppressContext : e instanceof PyInstance i && i.exc != null && i.exc.suppressContext; }
    static PyTraceback traceback(Object e) { return e instanceof PyExceptionValue v ? v.traceback : e instanceof PyInstance i && i.exc != null ? i.exc.traceback : null; }

    static void setCause(Object e, Object c) {
        if (e instanceof PyExceptionValue v) v.cause = c; else state((PyInstance) e).cause = c;
    }
    static void setContext(Object e, Object c) {
        if (e instanceof PyExceptionValue v) v.context = c; else state((PyInstance) e).context = c;
    }
    static void setSuppress(Object e, boolean s) {
        if (e instanceof PyExceptionValue v) v.suppressContext = s; else state((PyInstance) e).suppressContext = s;
    }
    static void setTraceback(Object e, PyTraceback t) {
        if (e instanceof PyExceptionValue v) v.traceback = t; else state((PyInstance) e).traceback = t;
    }

    /** The attribute dictionary of an exception value; {@code create} makes it for a PyExceptionValue. */
    @SuppressWarnings("unchecked")
    static Map<Object,Object> dict(Object e, boolean create) {
        if (e instanceof PyInstance i) return (Map<Object,Object>) (Map<?,?>) i.fields;
        PyExceptionValue v = (PyExceptionValue) e;
        if (v.dict == null && create) v.dict = new LinkedHashMap<>();
        return v.dict;
    }

    static Object notes(Object e) {
        Map<Object,Object> d = dict(e, false);
        return d != null && d.containsKey("__notes__") ? d.get("__notes__") : MISSING;
    }

    // ---------- thrown exception <-> Python-visible instance ----------
    /** The PyException to throw for the exception value {@code e} (raise e), carrying its chaining metadata. */
    static PyException throwableFor(Object e) {
        if (e instanceof PyExceptionValue v) {
            PyException p = new PyException(v.typeName, v.value, v.cause, v.context, v.suppressContext, v.traceback);
            p.syntax = v.syntax; p.instance = v;
            return p;
        }
        PyInstance i = (PyInstance) e;
        ExcState s = i.exc;
        PyException p = new PyException(i.cls.name, i);
        if (s != null) { p.cause = s.cause; p.context = s.context; p.suppressContext = s.suppressContext; p.traceback = s.traceback; }
        p.instance = i;
        return p;
    }

    /** The Python-visible instance for a caught PyException, created on first use and kept for later catches. */
    static Object instanceFor(PyException p) {
        Object inst = p.value instanceof PyInstance user && user.cls.exceptionBase != null ? user : p.instance;
        if (inst == null) {
            PyExceptionValue v = new PyExceptionValue(p.typeName, p.value, p.cause, p.context, p.suppressContext, p.traceback);
            v.syntax = p.syntax;
            p.instance = v;
            return v;
        }
        if (inst instanceof PyInstance i) {
            ExcState s = state(i);
            if (s.cause == null && p.cause != null) s.cause = p.cause;
            if (s.context == null && p.context != null) s.context = p.context;
            if (p.suppressContext) s.suppressContext = true;
            s.traceback = p.traceback;
        } else {
            PyExceptionValue v = (PyExceptionValue) inst;
            if (v.cause == null && p.cause != null) v.cause = p.cause;
            if (v.context == null && p.context != null) v.context = p.context;
            if (p.suppressContext) v.suppressContext = true;
            v.traceback = p.traceback;
        }
        return inst;
    }

    private static Throwable lastForeign; private static PyExceptionValue lastForeignValue;

    /** Instance for a Throwable that is not a PyException (an internal Java error mapped to a Python class). */
    static synchronized Object instanceForForeign(Throwable t) {
        if (t == lastForeign) return lastForeignValue;
        PyExceptionValue v = new PyExceptionValue(pythonExceptionType(t), exceptionValue(t));
        lastForeign = t; lastForeignValue = v;
        return v;
    }

    /** {@code BaseException.__new__(cls, *args)}: allocates an exception of a Python-defined (or builtin) class. */
    static Object exceptionNew(PyBuiltinType builtin, Object[] args) {
        if (args.length < 1) throw new PyException("TypeError", builtin.name + ".__new__(): not enough arguments");
        if (args[0] instanceof PyBuiltinType t) {
            List<Object> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            return BuiltinsExtra.newException(t.name, rest);
        }
        if (!(args[0] instanceof PyClass cls) || cls.exceptionBase == null)
            throw new PyException("TypeError", builtin.name + ".__new__(X): X is not a subtype of " + builtin.name);
        PyInstance made = new PyInstance(cls);
        setConstructorArgs(made, Arrays.copyOfRange(args, 1, args.length));
        return made;
    }

    static void setArgsField(PyInstance exc, Object[] args) {
        PyTuple t = new PyTuple(); t.items.addAll(Arrays.asList(args));
        exc.fields.put("args", t);
    }

    /** Stores {@code args} (and, for exception groups, the validated message and exceptions). */
    static void setConstructorArgs(PyInstance exc, Object[] args) {
        PyTuple t = new PyTuple(); t.items.addAll(Arrays.asList(args));
        exc.fields.put("args", t);
        if (BuiltinsExtra.isGroupType(exc.cls.exceptionBase)) BuiltinsExtra.initGroupInstance(exc, args);
    }

    // ---------- attribute protocol ----------
    private static PyException attributeError(Object e, String name) {
        return new PyException("AttributeError", "'" + excTypeName(e) + "' object has no attribute '" + name + "'");
    }

    /** Attribute read on an exception value for names this layer owns; MISSING for everything else. */
    static Object getattr(Object e, String name) {
        switch (name) {
            case "__cause__": return cause(e);
            case "__context__": return context(e);
            case "__suppress_context__": return suppress(e);
            case "__traceback__": return traceback(e);
            case "add_note": return new NativeFunction("add_note", (a, k) -> addNote(e, a));
            case "with_traceback": return new NativeFunction("with_traceback", (a, k) -> withTraceback(e, a));
            case "subgroup": if (isGroup(e)) return new NativeFunction("subgroup", (a, k) -> subgroup(e, a)); break;
            case "split": if (isGroup(e)) return new NativeFunction("split", (a, k) -> split(e, a)); break;
            case "derive": if (isGroup(e)) return new NativeFunction("derive", (a, k) -> derive(e, a)); break;
            case "__dict__": if (e instanceof PyExceptionValue) return dict(e, true); break;
            default: break;
        }
        if (e instanceof PyExceptionValue) {
            Map<Object,Object> d = dict(e, false);
            if (d != null && d.containsKey(name)) return d.get(name);
        }
        return MISSING;
    }

    /** Attribute write; true when the name belongs to this layer (or the write was rejected). */
    static boolean setattr(Object e, String name, Object value) {
        switch (name) {
            case "__cause__":
                if (value != null && !isExc(value)) throw new PyException("TypeError", "exception cause must be None or derive from BaseException");
                setCause(e, value); setSuppress(e, true); return true;
            case "__context__":
                if (value != null && !isExc(value)) throw new PyException("TypeError", "exception context must be None or derive from BaseException");
                setContext(e, value); return true;
            case "__suppress_context__": setSuppress(e, truth(value)); return true;
            case "__traceback__":
                if (value != null && !(value instanceof PyTraceback)) throw new PyException("TypeError", "__traceback__ must be a traceback or None");
                setTraceback(e, (PyTraceback) value); return true;
            case "message": case "exceptions":
                if (isGroup(e)) throw new PyException("AttributeError", "readonly attribute");
                return false;
            default: break;
        }
        if (e instanceof PyExceptionValue) {
            if (name.equals("args")) { setArgs((PyExceptionValue) e, value); return true; }
            dict(e, true).put(name, value);
            return true;
        }
        return false;
    }

    static boolean delattr(Object e, String name) {
        switch (name) {
            case "__cause__": setCause(e, null); return true;
            case "__context__": setContext(e, null); return true;
            case "__traceback__": setTraceback(e, null); return true;
            case "__suppress_context__": throw new PyException("TypeError", "cannot delete __suppress_context__");
            default: break;
        }
        if (e instanceof PyExceptionValue v) {
            Map<Object,Object> d = dict(e, false);
            if (d == null || !d.containsKey(name)) throw attributeError(e, name);
            d.remove(name);
            return true;
        }
        return false;
    }

    private static void setArgs(PyExceptionValue v, Object value) {
        if (isGroup(v)) throw new PyException("AttributeError", "readonly attribute");
        PyTuple items = new PyTuple();
        for (Object item : iterable(value)) items.items.add(item);
        v.value = items.items.size() == 1 && items.items.get(0) != null ? items.items.get(0)
            : items.items.isEmpty() ? null : new PyExcArgs(items, null);
    }

    static Object invokeMethod(Object self, String name, Object[] args) {
        return callFunction(getattr(self, name) == MISSING ? attributeErrorValue(self, name) : getattr(self, name),
            new ArrayList<Object>(Arrays.asList(args)), new LinkedHashMap<Object,Object>());
    }

    private static Object attributeErrorValue(Object self, String name) { throw attributeError(self, name); }

    // ---------- notes ----------
    @SuppressWarnings("unchecked")
    static Object addNote(Object e, List<Object> args) {
        if (args.size() != 1) throw new PyException("TypeError", "BaseException.add_note() takes exactly one argument (" + args.size() + " given)");
        Object note = args.get(0);
        if (!(note instanceof String)) throw new PyException("TypeError", "note must be a str, not '" + typeName(note) + "'");
        Object notes = notes(e);
        if (notes == MISSING) { notes = new ArrayList<Object>(); dict(e, true).put("__notes__", notes); }
        if (!(notes instanceof List<?>)) throw new PyException("TypeError", "Cannot add note: __notes__ is not a list");
        ((List<Object>) notes).add(note);
        return null;
    }

    private static Object withTraceback(Object e, List<Object> args) {
        if (args.size() != 1) throw new PyException("TypeError", "BaseException.with_traceback() takes exactly one argument (" + args.size() + " given)");
        setattr(e, "__traceback__", args.get(0));
        return e;
    }

    // ---------- BaseExceptionGroup methods ----------
    private static final int BY_TYPE = 0, BY_PREDICATE = 1, BY_ID = 2;

    private static final class Matcher {
        final int kind; final Object value; final IdentityHashMap<Object,Boolean> ids;
        Matcher(int kind, Object value, IdentityHashMap<Object,Boolean> ids) { this.kind = kind; this.value = value; this.ids = ids; }
    }

    static boolean isExceptionClass(Object t) {
        return t instanceof PyBuiltinType bt && exceptionIsSubclass(bt.name, "BaseException") || t instanceof PyClass c && c.exceptionBase != null;
    }

    private static Matcher matcherFor(Object cond) {
        if (truth(callable_(cond)) && !isType(cond)) return new Matcher(BY_PREDICATE, cond, null);
        boolean ok = isExceptionClass(cond);
        if (cond instanceof PyTuple t) {
            ok = true;
            for (Object item : t.items) if (!isExceptionClass(item)) { ok = false; break; }
        }
        if (!ok) throw new PyException("TypeError", "expected an exception type, a tuple of exception types, or a callable (other than a class)");
        return new Matcher(BY_TYPE, cond, null);
    }

    private static boolean matches(Object exc, Matcher m) {
        switch (m.kind) {
            case BY_TYPE: return truth(isInstance(exc, m.value));
            case BY_PREDICATE: return truth(callFunction(m.value, new ArrayList<Object>(List.of(exc)), new LinkedHashMap<Object,Object>()));
            default: return m.ids.containsKey(exc);
        }
    }

    private static Object subset(Object orig, List<Object> excs) {
        Object eg = PyRuntime.callMethod(orig, "derive", new Object[]{new ArrayList<Object>(excs)});
        if (!isGroup(eg)) throw new PyException("TypeError", "derive must return an instance of BaseExceptionGroup");
        setTraceback(eg, traceback(orig));
        setContext(eg, context(orig));
        setCause(eg, cause(orig));
        setSuppress(eg, true);   // PyException_SetCause always marks the context as suppressed
        Object notes = notes(orig);
        if (notes instanceof List<?> list) dict(eg, true).put("__notes__", new ArrayList<Object>(list));
        return eg;
    }

    /** {match, rest}: the leaves of {@code exc} accepted by {@code m} and (when requested) the others. */
    private static Object[] splitRecursive(Object exc, Matcher m, boolean constructRest) {
        if (matches(exc, m)) return new Object[]{exc, null};
        if (!isGroup(exc)) return new Object[]{null, constructRest ? exc : null};
        List<Object> matchList = new ArrayList<>(), restList = new ArrayList<>();
        for (Object e : new ArrayList<>(groupExceptions(exc).items)) {
            Object[] r = splitRecursive(e, m, constructRest);
            if (r[0] != null) matchList.add(r[0]);
            if (r[1] != null) restList.add(r[1]);
        }
        Object match = matchList.isEmpty() ? null : subset(exc, matchList);
        Object rest = constructRest && !restList.isEmpty() ? subset(exc, restList) : null;
        return new Object[]{match, rest};
    }

    private static void oneArg(String method, List<Object> args) {
        if (args.size() != 1) throw new PyException("TypeError", "BaseExceptionGroup." + method + "() takes exactly one argument (" + args.size() + " given)");
    }

    static Object subgroup(Object g, List<Object> args) {
        oneArg("subgroup", args);
        return splitRecursive(g, matcherFor(args.get(0)), false)[0];
    }

    static Object split(Object g, List<Object> args) {
        oneArg("split", args);
        Object[] r = splitRecursive(g, matcherFor(args.get(0)), true);
        PyTuple out = new PyTuple();
        out.items.add(r[0]); out.items.add(r[1]);
        return out;
    }

    static Object derive(Object g, List<Object> args) {
        oneArg("derive", args);
        List<Object> ctor = new ArrayList<>();
        ctor.add(groupMessage(g)); ctor.add(args.get(0));
        return BuiltinsExtra.newGroup("BaseExceptionGroup", ctor);
    }

    // ---------- try/except* ----------
    private static void checkStarTypes(Object type) {
        List<Object> items = type instanceof PyTuple t ? t.items : List.of(type);
        for (Object item : items) {
            if (!isExceptionClass(item)) throw new PyException("TypeError", "catching classes that do not inherit from BaseException is not allowed");
        }
        for (Object item : items) {
            boolean group = item instanceof PyBuiltinType bt ? BuiltinsExtra.isGroupType(bt.name) || exceptionIsSubclass(bt.name, "ExceptionGroup")
                || exceptionIsSubclass(bt.name, "BaseExceptionGroup")
                : item instanceof PyClass c && c.exceptionBase != null && BuiltinsExtra.isGroupType(c.exceptionBase);
            if (group) throw new PyException("TypeError", "catching ExceptionGroup with except* is not allowed. Use except instead.");
        }
    }

    private static Object wrapNaked(Object exc, long line) {
        List<Object> ctor = new ArrayList<>();
        ctor.add("");
        ctor.add(new ArrayList<Object>(List.of(exc)));
        Object wrapped = BuiltinsExtra.newGroup("BaseExceptionGroup", ctor);
        ActiveFrame frame = currentLogicalFrame();
        if (frame != null) setTraceback(wrapped, prependTraceback(null, frame.name, frame.filename, frame.firstlineno, line));
        return wrapped;
    }

    /** CHECK_EG_MATCH: {match, rest} of {@code rest} against the except* type; both None when nothing is left. */
    public static Object egMatch(Object rest, Object type, Object lineObj) {
        PyTuple out = new PyTuple();
        if (rest == null) { out.items.add(null); out.items.add(null); return out; }
        checkStarTypes(type);
        if (truth(isInstance(rest, type))) {
            out.items.add(isGroup(rest) ? rest : wrapNaked(rest, bigInt(lineObj).longValue()));
            out.items.add(null);
            return out;
        }
        if (isGroup(rest)) return split(rest, new ArrayList<Object>(List.of(type)));
        out.items.add(null); out.items.add(rest);
        return out;
    }

    private static boolean sameMetadata(Object a, Object b) {
        return notes(a) == notes(b) && traceback(a) == traceback(b) && cause(a) == cause(b) && context(a) == context(b);
    }

    private static void leafIds(Object e, IdentityHashMap<Object,Boolean> out) {
        if (isGroup(e)) { for (Object item : groupExceptions(e).items) leafIds(item, out); }
        else out.put(e, Boolean.TRUE);
    }

    private static Object projection(Object orig, List<Object> keep) {
        if (keep.isEmpty()) return null;
        IdentityHashMap<Object,Boolean> ids = new IdentityHashMap<>();
        for (Object e : keep) leafIds(e, ids);
        return splitRecursive(orig, new Matcher(BY_ID, null, ids), false)[0];
    }

    /** PREP_RERAISE_STAR: what an except* statement raises after its clauses ran, or null when everything was handled. */
    @SuppressWarnings("unchecked")
    public static Object egPrepReraise(Object orig, Object raisedList, Object rest) {
        List<Object> excs = new ArrayList<>();
        for (Object e : (List<Object>) raisedList) if (e != null) excs.add(e);
        if (rest != null) excs.add(rest);
        if (excs.isEmpty()) return null;
        if (!isGroup(orig)) return excs.get(0);
        List<Object> raised = new ArrayList<>(), reraised = new ArrayList<>();
        for (Object e : excs) (sameMetadata(e, orig) ? reraised : raised).add(e);
        Object reraisedEg = projection(orig, reraised);
        if (raised.isEmpty()) return reraisedEg;
        if (reraisedEg != null) raised.add(reraisedEg);
        List<Object> ctor = new ArrayList<>();
        ctor.add(""); ctor.add(raised);
        return BuiltinsExtra.newGroup("BaseExceptionGroup", ctor);
    }

    /** Throws an exception value as it is: no context, no cause (re-raise of a matched or combined group). */
    public static void egThrow(Object value) { raiseObject(value); }

    /** Entry point of the {@code __py_eg_internal(op, ...)} intrinsics emitted by the except* lowering. */
    public static Object egOp(Object op, Object a, Object b, Object c) {
        switch ((String) op) {
            case "match": return egMatch(a, b, c);
            case "prep": return egPrepReraise(a, b, c);
            case "throw": egThrow(a); return null;
            default: throw new PyException("SystemError", "unknown except* helper " + op);
        }
    }
}
