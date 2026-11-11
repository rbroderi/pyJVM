// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builtins and exception/class-creation helpers that sit outside the main runtime file: divmod, ascii, input,
 * breakpoint, __import__, print() keywords, range() index conversion, matrix multiplication, the
 * ExceptionGroup constructors, exception matching against run-time values, the handled-exception stack behind
 * bare ``raise`` and class-statement ``*bases`` / ``**keywords``.
 */
public final class BuiltinsExtra {
    private BuiltinsExtra() {}

    // ---------- Named builtin functions dispatched from callBuiltin ----------
    static final Set<String> NAMES = Set.of("divmod", "ascii", "breakpoint", "input", "__import__");

    static boolean handles(String name) { return NAMES.contains(name); }

    static Object call(String name, List<Object> args, Map<Object,Object> kwargs) {
        switch (name) {
            case "breakpoint": return breakpoint(args, kwargs);
            case "__import__": return importBuiltin(args, kwargs);
            default: break;
        }
        if (!kwargs.isEmpty()) throw new PyException("TypeError", name + "() takes no keyword arguments");
        switch (name) {
            case "divmod":
                if (args.size() != 2) throw new PyException("TypeError", "divmod expected 2 arguments, got " + args.size());
                return divmod(args.get(0), args.get(1));
            case "ascii":
                if (args.size() != 1) throw new PyException("TypeError", "ascii() takes exactly one argument (" + args.size() + " given)");
                return ascii_(args.get(0));
            default:
                return input(args);
        }
    }

    // ---------- divmod ----------
    static Object divmod(Object a, Object b) {
        if (a instanceof PyInstance || b instanceof PyInstance) {
            Object result = binarySpecial(a, b, "__divmod__", "__rdivmod__", false);
            if (result != NOT_IMPLEMENTED) return result;
        } else if ((isIntLike(a) || a instanceof Double) && (isIntLike(b) || b instanceof Double)) {
            PyTuple out = new PyTuple();
            if (isIntLike(a) && isIntLike(b)) {
                BigInteger x = bigInt(a), y = bigInt(b);
                if (y.signum() == 0) throw new PyException("ZeroDivisionError", "division by zero");
                BigInteger[] qr = x.divideAndRemainder(y);
                if (qr[1].signum() != 0 && qr[1].signum() != y.signum()) {
                    qr[1] = qr[1].add(y);
                    qr[0] = qr[0].subtract(BigInteger.ONE);
                }
                out.items.add(compact(qr[0])); out.items.add(compact(qr[1]));
                return out;
            }
            double vx = number(a), wx = number(b);
            if (wx == 0.0) throw new PyException("ZeroDivisionError", "division by zero");
            double mod = vx % wx;
            double div = (vx - mod) / wx;
            if (mod != 0.0) {
                if ((wx < 0) != (mod < 0)) { mod += wx; div -= 1.0; }
            } else {
                mod = Math.copySign(0.0, wx);
            }
            double floordiv;
            if (div != 0.0) {
                floordiv = Math.floor(div);
                if (div - floordiv > 0.5) floordiv += 1.0;
            } else {
                floordiv = Math.copySign(0.0, vx / wx);
            }
            out.items.add(floordiv); out.items.add(mod);
            return out;
        }
        throw new PyException("TypeError", "unsupported operand type(s) for divmod(): '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    // ---------- matrix multiplication ----------
    public static Object matmul(Object a, Object b) {
        if (a instanceof PyInstance || b instanceof PyInstance) {
            Object result = binarySpecial(a, b, "__matmul__", "__rmatmul__", false);
            if (result != NOT_IMPLEMENTED) return result;
        }
        throw new PyException("TypeError", "unsupported operand type(s) for @: '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    public static Object imatmul(Object a, Object b) {
        if (a instanceof PyInstance instance) {
            PyMethod method = instance.cls.lookupMethod("__imatmul__");
            if (method != null) {
                Object value = invoke(instance, method, new Object[]{b});
                if (value != NOT_IMPLEMENTED) return value;
            }
        }
        if (a instanceof PyInstance || b instanceof PyInstance) {
            Object result = binarySpecial(a, b, "__matmul__", "__rmatmul__", false);
            if (result != NOT_IMPLEMENTED) return result;
        }
        throw new PyException("TypeError", "unsupported operand type(s) for @=: '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    // ---------- range() ----------
    static BigInteger rangeIndex(Object value) {
        value = NativeSubclass.indexValue(value);
        if (isIntLike(value)) return bigInt(value);
        if (value instanceof PyInstance instance) {
            PyMethod method = instance.cls.lookupMethod("__index__");
            if (method != null) {
                Object result = invoke(instance, method, new Object[0]);
                if (!isIntLike(result)) throw new PyException("TypeError", "__index__ returned non-int (type " + typeName(result) + ")");
                return bigInt(result);
            }
        }
        throw new PyException("TypeError", "'" + typeName(value) + "' object cannot be interpreted as an integer");
    }

    static Object rangeCall(List<Object> args, Map<Object,Object> kwargs) {
        if (!kwargs.isEmpty()) throw new PyException("TypeError", "range() takes no keyword arguments");
        if (args.isEmpty()) throw new PyException("TypeError", "range expected at least 1 argument, got 0");
        if (args.size() > 3) throw new PyException("TypeError", "range expected at most 3 arguments, got " + args.size());
        return args.size() == 1 ? range1(args.get(0)) : args.size() == 2 ? range2(args.get(0), args.get(1)) : range3(args.get(0), args.get(1), args.get(2));
    }

    // ---------- print() ----------
    static Object printKw(List<Object> args, Map<Object,Object> kwargs) {
        for (Object key : kwargs.keySet())
            if (!key.equals("sep") && !key.equals("end") && !key.equals("file") && !key.equals("flush"))
                throw new PyException("TypeError", "print() got an unexpected keyword argument '" + key + "'");
        return printCall(new ArrayList<Object>(args), kwargs.get("sep"), kwargs.get("end"), kwargs.get("file"), kwargs.get("flush"));
    }

    static String printSeparator(Object value, String name, String fallback) {
        if (value == null) return fallback;
        if (value instanceof String text) return text;
        throw new PyException("TypeError", name + " must be None or a string, not " + typeName(value));
    }

    @SuppressWarnings("unchecked")
    public static Object printCall(Object argsObj, Object sepObj, Object endObj, Object fileObj, Object flushObj) {
        List<Object> args = (List<Object>) argsObj;
        String sep = printSeparator(sepObj, "sep", " "), end = printSeparator(endObj, "end", "\n");
        Object file = fileObj != null ? fileObj : currentStream("stdout");
        if (file == STDOUT || file == STDERR) {
            java.io.PrintStream stream = file == STDOUT ? System.out : System.err;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i != 0) out.append(sep);
                out.append(pyStr(args.get(i)));
            }
            out.append(end);
            stream.print(out.toString());
            if (truth(flushObj)) stream.flush();
            return null;
        }
        if (file == null) return null; // sys.stdout is None: print() is a no-op
        for (int i = 0; i < args.size(); i++) {
            if (i != 0) callMethod(file, "write", new Object[]{sep});
            callMethod(file, "write", new Object[]{pyStr(args.get(i))});
        }
        callMethod(file, "write", new Object[]{end});
        if (truth(flushObj)) callMethod(file, "flush", new Object[0]);
        return null;
    }

    // ---------- sys streams, input(), breakpoint(), __import__ ----------
    static final PyModule STDOUT = stream("stdout"), STDERR = stream("stderr"), STDIN = stream("stdin");
    static BufferedReader stdinReader;

    private static PyModule stream(String name) {
        PyModule m = new PyModule("<" + name + ">", "pyjvm315.runtime.NativeModules");
        m.attrs.put("name", "<" + name + ">");
        m.attrs.put("encoding", "utf-8");
        m.attrs.put("errors", name.equals("stderr") ? "backslashreplace" : "strict");
        m.attrs.put("closed", false);
        if (name.equals("stdin")) {
            m.attrs.put("readline", new NativeFunction("readline", (a, k) -> {
                String line = readStdinLine(true);
                return line == null ? "" : line;
            }));
        } else {
            java.io.PrintStream target = name.equals("stdout") ? System.out : System.err;
            m.attrs.put("write", new NativeFunction("write", (a, k) -> {
                if (a.size() != 1) throw new PyException("TypeError", "write() takes exactly one argument (" + a.size() + " given)");
                if (!(a.get(0) instanceof String text)) throw new PyException("TypeError", "write() argument must be str, not " + typeName(a.get(0)));
                target.print(text);
                return (long) text.codePointCount(0, text.length());
            }));
            m.attrs.put("flush", new NativeFunction("flush", (a, k) -> { target.flush(); return null; }));
        }
        return m;
    }

    static synchronized String readStdinLine(boolean keepNewline) {
        try {
            if (stdinReader == null) stdinReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = stdinReader.read()) >= 0) {
                if (c == '\n') { if (keepNewline) line.append('\n'); return line.toString(); }
                line.append((char) c);
            }
            return line.length() == 0 ? null : line.toString();
        } catch (IOException e) {
            throw new PyException("OSError", "cannot read standard input: " + e.getMessage());
        }
    }

    static void installSys(PyModule m) {
        m.attrs.put("stdout", STDOUT); m.attrs.put("stderr", STDERR); m.attrs.put("stdin", STDIN);
        m.attrs.put("__stdout__", STDOUT); m.attrs.put("__stderr__", STDERR); m.attrs.put("__stdin__", STDIN);
        NativeFunction hook = new NativeFunction("breakpointhook", (a, k) -> defaultBreakpointHook());
        m.attrs.put("breakpointhook", hook); m.attrs.put("__breakpointhook__", hook);
    }

    /** The live ``sys.<name>`` when ``sys`` has been loaded, otherwise the process stream. */
    static Object currentStream(String name) {
        PyModule sys = MODULE_CACHE.get("sys");
        if (sys == null) return name.equals("stdout") ? STDOUT : name.equals("stderr") ? STDERR : STDIN;
        if (!sys.attrs.containsKey(name)) throw new PyException("RuntimeError", "lost sys." + name);
        return sys.attrs.get(name);
    }

    static Object input(List<Object> args) {
        if (args.size() > 1) throw new PyException("TypeError", "input expected at most 1 argument, got " + args.size());
        Object stdin = currentStream("stdin"), stdout = currentStream("stdout");
        String prompt = args.isEmpty() ? "" : pyStr(args.get(0));
        if (stdout == STDOUT) { System.out.print(prompt); System.out.flush(); }
        else if (stdout != null) { callMethod(stdout, "write", new Object[]{prompt}); callMethod(stdout, "flush", new Object[0]); }
        String line;
        if (stdin == STDIN) {
            line = readStdinLine(false);
            if (line == null) throw new PyException("EOFError", "EOF when reading a line");
        } else {
            Object raw = callMethod(stdin, "readline", new Object[0]);
            if (!(raw instanceof String text)) throw new PyException("TypeError", "object.readline() returned non-string");
            if (text.isEmpty()) throw new PyException("EOFError", "EOF when reading a line");
            line = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        }
        return line;
    }

    @SuppressWarnings("unchecked")
    static Object breakpoint(List<Object> args, Map<Object,Object> kwargs) {
        PyModule sys = (PyModule) importNative("sys");
        if (!sys.attrs.containsKey("breakpointhook")) throw new PyException("RuntimeError", "lost sys.breakpointhook");
        return callFunction(sys.attrs.get("breakpointhook"), new ArrayList<Object>(args), new LinkedHashMap<Object,Object>(kwargs));
    }

    static Object defaultBreakpointHook() {
        String target = System.getenv("PYTHONBREAKPOINT");
        if (target != null && target.equals("0")) return null;
        String module = target == null || target.isEmpty() ? "pdb" : target.contains(".") ? target.substring(0, target.lastIndexOf('.')) : "builtins";
        throw new PyException("ImportError", "breakpoint() cannot import '" + module + "': no debugger or import system is available in this runtime");
    }

    static Object importBuiltin(List<Object> args, Map<Object,Object> kwargs) { return ImportSupport.importBuiltin(args, kwargs); }

    // ---------- Exceptions ----------
    static void registerExceptionTypes(Map<String,String> parents) {
        String[][] extra = {
            {"EOFError", "Exception"}, {"SystemError", "Exception"}, {"ReferenceError", "Exception"},
            {"EncodingWarning", "Warning"}, {"BaseExceptionGroup", "BaseException"}, {"ExceptionGroup", "Exception"},
            {"FileExistsError", "OSError"}, {"BrokenPipeError", "ConnectionError"}, {"ConnectionAbortedError", "ConnectionError"},
            {"ConnectionRefusedError", "ConnectionError"}, {"ConnectionResetError", "ConnectionError"},
        };
        for (String[] pair : extra) parents.putIfAbsent(pair[0], pair[1]);
    }

    static boolean isGroupType(String type) { return type.equals("ExceptionGroup") || type.equals("BaseExceptionGroup"); }

    public static Object exception1(Object type, Object arg) {
        List<Object> args = new ArrayList<>(); args.add(arg);
        return newException((String) type, args);
    }

    @SuppressWarnings("unchecked")
    public static Object exceptionN(Object type, Object args) { return newException((String) type, (List<Object>) args); }

    static Object newExceptionCall(String type, List<Object> args, Map<Object,Object> kwargs) {
        if (!kwargs.isEmpty()) {
            if (ImportSupport.isImportErrorType(type)) return ImportSupport.newImportError(type, args, kwargs);
            if (Set.of("SyntaxError", "IndentationError", "TabError", "OSError", "StopIteration").contains(type))
                throw new PyException("TypeError", type + "() keyword arguments are not supported by this runtime");
            throw new PyException("TypeError", type + "() takes no keyword arguments");
        }
        return newException(type, args);
    }

    static Object newException(String type, List<Object> args) {
        if (isGroupType(type)) return newGroup(type, args);
        if (args.isEmpty()) return new PyExceptionValue(type, null);
        if (args.size() == 1 && args.get(0) != null) return new PyExceptionValue(type, args.get(0));
        PyTuple all = new PyTuple(); all.items.addAll(args);
        return new PyExceptionValue(type, new PyExcArgs(all, null));
    }

    static boolean isExceptionObject(Object value) {
        return value instanceof PyExceptionValue || value instanceof PyException || value instanceof PyInstance i && i.cls.exceptionBase != null;
    }

    static boolean isPlainException(Object value) {
        return truth(isInstance(value, builtinType("Exception")));
    }

    static PyTuple validatedGroupItems(Object excs) {
        boolean sequence = excs instanceof List<?> || excs instanceof PyTuple || excs instanceof String || excs instanceof PyRange
            || excs instanceof PyBytes || excs instanceof PyByteArray || excs instanceof PyInstance i && i.cls.lookupMethod("__getitem__") != null;
        if (!sequence) throw new PyException("TypeError", "second argument (exceptions) must be a sequence");
        PyTuple items = new PyTuple();
        for (Object item : iterable(excs)) items.items.add(item);
        if (items.items.isEmpty()) throw new PyException("ValueError", "second argument (exceptions) must be a non-empty sequence");
        for (int i = 0; i < items.items.size(); i++)
            if (!isExceptionObject(items.items.get(i)))
                throw new PyException("ValueError", "Item " + i + " of second argument (exceptions) is not an exception");
        return items;
    }

    static Object newGroup(String type, List<Object> args) {
        if (args.size() != 2) throw new PyException("TypeError", "BaseExceptionGroup.__new__() takes exactly 2 arguments (" + args.size() + " given)");
        if (!(args.get(0) instanceof String)) throw new PyException("TypeError", "BaseExceptionGroup.__new__() argument 1 must be str, not " + typeName(args.get(0)));
        PyTuple items = validatedGroupItems(args.get(1));
        boolean allPlain = true;
        for (Object item : items.items) if (!isPlainException(item)) { allPlain = false; break; }
        if (type.equals("ExceptionGroup") && !allPlain) throw new PyException("TypeError", "Cannot nest BaseExceptions in an ExceptionGroup");
        if (type.equals("BaseExceptionGroup") && allPlain) type = "ExceptionGroup";
        PyTuple all = new PyTuple(); all.items.addAll(args);
        return new PyExceptionValue(type, new PyExcArgs(all, items));
    }

    /** Group bookkeeping for instances of user subclasses of (Base)ExceptionGroup. */
    static void initGroupInstance(PyInstance instance, Object[] args) {
        if (args.length != 2) throw new PyException("TypeError", "BaseExceptionGroup.__new__() takes exactly 2 arguments (" + args.length + " given)");
        if (!(args[0] instanceof String)) throw new PyException("TypeError", "BaseExceptionGroup.__new__() argument 1 must be str, not " + typeName(args[0]));
        PyTuple items = validatedGroupItems(args[1]);
        if (instance.cls.exceptionBase.equals("ExceptionGroup"))
            for (Object item : items.items) if (!isPlainException(item)) throw new PyException("TypeError", "Cannot nest BaseExceptions in an ExceptionGroup");
        instance.fields.put("message", args[0]);
        instance.fields.put("exceptions", items);
    }

    static Object exceptionArgs(Object value) {
        if (value instanceof PyExcArgs a) return a.copyArgs();
        PyTuple out = new PyTuple();
        if (value != null) out.items.add(value);
        return out;
    }

    /** ``message`` / ``exceptions`` of an exception group value; MISSING for anything else. */
    static Object groupAttribute(PyExceptionValue exc, String name) {
        if (exc.value instanceof PyExcArgs a && a.group != null) {
            if (name.equals("message")) return a.args.items.get(0);
            if (name.equals("exceptions")) return a.group;
        }
        return MISSING;
    }

    static String exceptionRepr(PyExceptionValue exc) {
        Object value = exc.value;
        List<Object> shown = new ArrayList<>();
        if (value instanceof PyExcArgs a) {
            shown.addAll(a.args.items);
            if (a.group != null && !(shown.get(1) instanceof List<?>)) shown.set(1, new ArrayList<Object>(a.group.items));
        } else if (value != null) {
            shown.add(value);
        }
        StringBuilder out = new StringBuilder(exc.typeName).append('(');
        for (int i = 0; i < shown.size(); i++) {
            if (i != 0) out.append(", ");
            out.append(pyRepr(shown.get(i)));
        }
        return out.append(')').toString();
    }

    public static boolean exceptionMatchesValue(Object throwable, Object type) {
        checkExceptionType(type);
        if (type instanceof PyTuple tuple) {
            for (Object entry : tuple.items) if (exceptionMatchesValue(throwable, entry)) return true;
            return false;
        }
        return exceptionMatches(throwable, type instanceof PyClass cls ? cls.name : type);
    }

    private static void checkExceptionType(Object type) {
        if (type instanceof PyTuple tuple) {
            for (Object entry : tuple.items) {
                if (entry instanceof PyTuple) throw new PyException("TypeError", "catching classes that do not inherit from BaseException is not allowed");
                checkExceptionType(entry);
            }
            return;
        }
        boolean ok = type instanceof PyBuiltinType bt && exceptionIsSubclass(bt.name, "BaseException")
            || type instanceof PyClass cls && cls.exceptionBase != null;
        if (!ok) throw new PyException("TypeError", "catching classes that do not inherit from BaseException is not allowed");
    }

    // ---------- Handled exception stack (bare raise outside the handler's own function) ----------
    static final ThreadLocal<ArrayList<Object>> HANDLED = ThreadLocal.withInitial(ArrayList::new);

    public static Object excEnter(Object throwable) {
        ArrayList<Object> stack = HANDLED.get();
        long depth = stack.size();
        stack.add(throwable);
        return depth;
    }

    public static void excLeave(Object depth) {
        ArrayList<Object> stack = HANDLED.get();
        int keep = (int) (long) (Long) depth;
        while (stack.size() > keep) stack.remove(stack.size() - 1);
    }

    public static void reraiseCurrent() {
        ArrayList<Object> stack = HANDLED.get();
        if (stack.isEmpty()) throw new PyException("RuntimeError", "No active exception to reraise");
        rethrowThrowable(stack.get(stack.size() - 1));
    }

    // ---------- class statements ----------
    public static void classAddBases(Object cls, Object bases) {
        for (Object base : iterable(bases)) classAddBase(cls, base);
    }

    @SuppressWarnings("unchecked")
    public static void classKwUpdate(Object target, Object other) {
        Map<Object,Object> kwargs = (Map<Object,Object>) target;
        Map<Object,Object> source;
        if (other instanceof Map<?,?> map) source = (Map<Object,Object>) map;
        else if (other instanceof PyInstance && hasattr(other, "keys") == Boolean.TRUE) { source = new LinkedHashMap<>(); dictUpdate(source, other); }
        else throw new PyException("TypeError", "type() argument after ** must be a mapping, not " + typeName(other));
        for (Map.Entry<Object,Object> entry : new ArrayList<>(source.entrySet())) {
            if (!(entry.getKey() instanceof String key)) throw new PyException("TypeError", "keywords must be strings");
            if (kwargs.containsKey(key)) throw new PyException("TypeError", "type() got multiple values for keyword argument '" + key + "'");
            kwargs.put(key, entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    public static Object classTakeMetaclass(Object kwargs) {
        return ((Map<Object,Object>) kwargs).remove("metaclass");
    }
}
