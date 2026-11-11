// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native {@code _io} module: the stream class hierarchy (IoBase, IoFile, IoBytes, IoStringIO, IoBuffered,
 * IoText, IoCodec), {@code open}, {@code open_code} and {@code text_encoding}. CPython's {@code Lib/io.py}
 * imports this module unmodified.
 */
public final class IoModule {
    private IoModule() {}

    private static PyModule module;
    static PyClass unsupportedClass;
    private static NativeFunction openFn;

    static synchronized PyModule create() {
        if (module != null) return module;
        PyModule m = new PyModule("_io", "pyjvm315.runtime.IoModule");
        unsupportedClass = (PyClass) PyRuntime.classCreate("UnsupportedOperation", "io");
        PyRuntime.classAddBase(unsupportedClass, PyRuntime.builtinType("OSError"));
        PyRuntime.classAddBase(unsupportedClass, PyRuntime.builtinType("ValueError"));
        finish(unsupportedClass);
        m.attrs.put("UnsupportedOperation", unsupportedClass);
        m.attrs.put("BlockingIOError", PyRuntime.builtinType("BlockingIOError"));
        m.attrs.put("DEFAULT_BUFFER_SIZE", (long) IoUtil.DEFAULT_BUFFER_SIZE);
        IoBase.define(m);
        IoFile.define(m);
        IoBytes.define(m);
        IoStringIO.define(m);
        IoBuffered.define(m);
        IoCodec.define(m);
        IoText.define(m);
        m.attrs.put("open", openFunction());
        fn(m, "open_code", (a, k) -> {
            Object p = a.size() == 1 ? a.get(0) : k.get("path");
            if (a.size() + k.size() != 1) throw new PyException("TypeError", "open_code() takes exactly 1 argument (" + (a.size() + k.size()) + " given)");
            if (!(NativeSubclass.unwrap(p) instanceof String)) throw new PyException("TypeError", "open_code() argument 'path' must be str, not " + PyRuntime.typeName(p));
            return open(new ArrayList<Object>(List.of(p, "rb")), new LinkedHashMap<Object,Object>());
        });
        fn(m, "text_encoding", (a, k) -> {
            Object enc = a.isEmpty() ? k.get("encoding") : a.get(0);
            if (enc != null) return enc;
            return LangVersion.atLeast(3, 15) ? "utf-8" : "locale";
        });
        module = m;
        return m;
    }

    /** The one {@code open} function: {@code builtins.open is io.open}. */
    public static synchronized Object openFunction() {
        if (openFn == null) openFn = new NativeFunction("open", IoModule::open);
        return openFn;
    }

    private static final String[] OPEN_PARAMS = {"file", "mode", "buffering", "encoding", "errors", "newline", "closefd", "opener"};

    static Object open(List<Object> a, Map<Object,Object> k) {
        if (a.size() > OPEN_PARAMS.length) throw new PyException("TypeError", "open() takes at most 8 arguments (" + a.size() + " given)");
        Object[] v = new Object[OPEN_PARAMS.length];
        boolean[] given = new boolean[OPEN_PARAMS.length];
        for (int i = 0; i < a.size(); i++) { v[i] = a.get(i); given[i] = true; }
        for (Map.Entry<Object,Object> e : k.entrySet()) {
            int idx = java.util.Arrays.asList(OPEN_PARAMS).indexOf(e.getKey());
            if (idx < 0) throw new PyException("TypeError", "open() got an unexpected keyword argument '" + e.getKey() + "'");
            if (given[idx]) throw new PyException("TypeError", "argument for open() given by name ('" + e.getKey() + "') and position (" + (idx + 1) + ")");
            v[idx] = e.getValue(); given[idx] = true;
        }
        if (!given[0]) throw new PyException("TypeError", "open() missing required argument 'file' (pos 1)");
        Object file = v[0];
        Object mode = given[1] ? v[1] : "r";
        Object buffering = given[2] ? v[2] : (Object) (-1L);
        Object closefd = given[6] ? v[6] : (Object) true;
        return openFile(file, mode, buffering, v[3], v[4], v[5], closefd, v[7]);
    }

    static Object openFile(Object file, Object modeObj, Object bufObj, Object encoding, Object errors, Object newline, Object closefd, Object opener) {
        Object mo = NativeSubclass.unwrap(modeObj);
        if (!(mo instanceof String mode)) throw new PyException("TypeError", "open() argument 'mode' must be str, not " + PyRuntime.typeName(mo));
        Object bo = NativeSubclass.unwrap(bufObj);
        if (bo instanceof Double || !(bo instanceof Long || bo instanceof Boolean || bo instanceof java.math.BigInteger || bo instanceof PyInstance))
            throw new PyException("TypeError", "'" + PyRuntime.typeName(bo) + "' object cannot be interpreted as an integer");
        long buffering = IoUtil.asLong(bo);
        boolean creating = false, reading = false, writing = false, appending = false, updating = false, text = false, binary = false;
        java.util.HashSet<Character> seen = new java.util.HashSet<>();
        for (int i = 0; i < mode.length(); i++) {
            char c = mode.charAt(i);
            if ("axrwb+t".indexOf(c) < 0 || !seen.add(c)) throw new PyException("ValueError", "invalid mode: " + PyRuntime.pyRepr(mode));
            switch (c) {
                case 'x' -> creating = true;
                case 'r' -> reading = true;
                case 'w' -> writing = true;
                case 'a' -> appending = true;
                case '+' -> updating = true;
                case 't' -> text = true;
                default -> binary = true;
            }
        }
        if (text && binary) throw new PyException("ValueError", "can't have text and binary mode at once");
        int kinds = (creating ? 1 : 0) + (reading ? 1 : 0) + (writing ? 1 : 0) + (appending ? 1 : 0);
        if (kinds != 1) throw new PyException("ValueError", kinds > 1 ? "must have exactly one of create/read/write/append mode" : "Must have exactly one of create/read/write/append mode and at most one plus");
        if (binary && encoding != null) throw new PyException("ValueError", "binary mode doesn't take an encoding argument");
        if (binary && errors != null) throw new PyException("ValueError", "binary mode doesn't take an errors argument");
        if (binary && newline != null) throw new PyException("ValueError", "binary mode doesn't take a newline argument");
        if (binary && buffering == 1) {
            Object warnings = ImportSupport.importBuiltin(new ArrayList<Object>(List.of("warnings")), new LinkedHashMap<Object,Object>());
            PyRuntime.callMethod(warnings, "warn", new Object[]{"line buffering (buffering=1) isn't supported in binary mode, the default buffer size will be used",
                PyRuntime.builtinType("RuntimeWarning"), 2L});
        }
        String rawMode = (creating ? "x" : "") + (reading ? "r" : "") + (writing ? "w" : "") + (appending ? "a" : "") + (updating ? "+" : "");
        Object raw = PyRuntime.instantiate(IoFile.cls, new Object[]{file, rawMode, closefd, opener});
        try {
            boolean lineBuffering = false;
            if (buffering == 1 || (buffering < 0 && PyRuntime.truth(PyRuntime.callMethod(raw, "isatty", new Object[0])))) { buffering = -1; lineBuffering = true; }
            if (buffering < 0) buffering = IoUtil.DEFAULT_BUFFER_SIZE;
            if (buffering == 0) {
                if (binary) return raw;
                throw new PyException("ValueError", "can't have unbuffered text I/O");
            }
            PyClass bc = updating ? IoBuffered.random : (creating || writing || appending) ? IoBuffered.writer : IoBuffered.reader;
            Object buffer = PyRuntime.instantiate(bc, new Object[]{raw, buffering});
            if (binary) return buffer;
            PyInstance wrapper = (PyInstance) PyRuntime.instantiate(IoText.cls, new Object[]{buffer, encoding, errors, newline, lineBuffering, false});
            wrapper.fields.put("mode", mode);
            return wrapper;
        } catch (RuntimeException e) {
            try { PyRuntime.callMethod(raw, "close", new Object[0]); } catch (RuntimeException ignored) { }
            throw e;
        }
    }
}
