// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The native {@code _codecs} module: the registry functions and the C-level encoders/decoders CPython's {@code codecs.py} imports. */
final class CodecsModule {
    private CodecsModule() {}

    private interface Fn { Object call(List<Object> a, Map<Object,Object> k); }

    private static Object arg(List<Object> a, Map<Object,Object> k, int i, String name, Object dflt) {
        if (i < a.size()) return a.get(i);
        return k.containsKey(name) ? k.get(name) : dflt;
    }

    private static String str(Object o, String who, int pos) {
        o = NativeSubclass.unwrap(o);
        if (o instanceof String s) return s;
        throw new PyException("TypeError", who + "() argument " + pos + " must be str, not " + PyRuntime.typeName(o));
    }

    private static String errors(Object o, String who) {
        if (o == null) return "strict";
        o = NativeSubclass.unwrap(o);
        if (o instanceof String s) return s;
        throw new PyException("TypeError", who + "() argument 'errors' must be str or None, not " + PyRuntime.typeName(o));
    }

    private static long cpLen(String s) { return s.codePointCount(0, s.length()); }

    private static boolean flag(Object o) { return o != null && PyRuntime.truth(o); }

    static PyModule create() {
        PyModule m = new PyModule("_codecs", "pyjvm315.runtime.CodecsModule");
        registry(m);
        for (String n : new String[]{"utf_8", "ascii", "latin_1", "utf_16", "utf_16_le", "utf_16_be", "utf_32", "utf_32_le", "utf_32_be"}) textCodec(m, n);
        charmap(m);
        escapes(m);
        for (String n : new String[]{"strict", "ignore", "replace", "xmlcharrefreplace", "backslashreplace", "namereplace"}) {
            final String nm = n;
            fn(m, n + "_errors", (a, k) -> CodecErrors.apply(nm, a.get(0)));
        }
        return m;
    }

    private static void def(PyModule m, String name, Fn f) { fn(m, name, (a, k) -> f.call(a, k)); }

    private static void registry(PyModule m) {
        def(m, "register", (a, k) -> {
            if (a.size() != 1) throw new PyException("TypeError", "register() takes exactly one argument (" + a.size() + " given)");
            if (!PyRuntime.truth(PyRuntime.callable_(a.get(0)))) throw new PyException("TypeError", "argument must be callable");
            synchronized (Codecs.SEARCH) { Codecs.SEARCH.add(a.get(0)); }
            return null;
        });
        def(m, "unregister", (a, k) -> {
            synchronized (Codecs.SEARCH) { Codecs.SEARCH.removeIf(f -> f == a.get(0) || f.equals(a.get(0))); }
            Codecs.CACHE.clear();
            return null;
        });
        def(m, "lookup", (a, k) -> Codecs.lookup(str(arg(a, k, 0, "encoding", null), "lookup", 1)));
        def(m, "_forget_codec", (a, k) -> { Codecs.CACHE.remove(str(a.get(0), "_forget_codec", 1).toLowerCase().replace(' ', '-')); return null; });
        def(m, "_normalize_encoding", (a, k) -> Codecs.normalizeEncoding(str(a.get(0), "_normalize_encoding", 1)));
        def(m, "lookup_error", (a, k) -> CodecErrors.lookup(str(a.get(0), "lookup_error", 1)));
        def(m, "register_error", (a, k) -> { CodecErrors.register(str(a.get(0), "register_error", 1), a.get(1)); return null; });
        def(m, "_unregister_error", (a, k) -> false);
        def(m, "encode", (a, k) -> {
            Object obj = arg(a, k, 0, "obj", null);
            String enc = str(arg(a, k, 1, "encoding", "utf-8"), "encode", 2), err = errors(arg(a, k, 2, "errors", "strict"), "encode");
            return convert(Codecs.lookup(enc), "encode", obj, err);
        });
        def(m, "decode", (a, k) -> {
            Object obj = arg(a, k, 0, "obj", null);
            String enc = str(arg(a, k, 1, "encoding", "utf-8"), "decode", 2), err = errors(arg(a, k, 2, "errors", "strict"), "decode");
            return convert(Codecs.lookup(enc), "decode", obj, err);
        });
    }

    private static Object convert(Object info, String which, Object obj, String err) {
        Object fn = PyRuntime.getattr(info, which);
        Object r = PyRuntime.callFunction(fn, new ArrayList<Object>(List.of(obj, err)), new LinkedHashMap<Object,Object>());
        if (!(r instanceof PyTuple t) || t.items.size() != 2)
            throw new PyException("TypeError", "codec must return a tuple of (object, length)".replace("codec", which.equals("encode") ? "encoder" : "decoder"));
        return t.items.get(0);
    }

    private static void textCodec(PyModule m, String base) {
        String n = base.replace('_', '-');
        boolean sixteen = base.startsWith("utf_16"), thirtytwo = base.startsWith("utf_32");
        int fixed = base.endsWith("_le") ? -1 : base.endsWith("_be") ? 1 : 0;
        def(m, base + "_encode", (a, k) -> {
            String who = base + "_encode";
            String s = str(arg(a, k, 0, "str", null), who, 1);
            String err = errors(arg(a, k, 1, "errors", null), who);
            int order = fixed != 0 ? fixed : (sixteen || thirtytwo) ? ((Number) arg(a, k, 2, "byteorder", 0L)).intValue() : 0;
            byte[] out = sixteen ? CodecsCore.encodeUtf16(s, err, order) : thirtytwo ? CodecsCore.encodeUtf32(s, err, order) : Codecs.encodeNative(s, n, err);
            return tuple(new PyBytes(out), cpLen(s));
        });
        def(m, base + "_decode", (a, k) -> {
            String who = base + "_decode";
            byte[] d = Codecs.bytesOf(arg(a, k, 0, "data", null), who + "()");
            String err = errors(arg(a, k, 1, "errors", null), who);
            boolean fin = flag(arg(a, k, 2, "final", false)) || !(base.equals("utf_8") || sixteen || thirtytwo);
            int[] order = {0}, used = {0};
            String text = Codecs.decodeNative(d, n, err, fin, order, used);
            return tuple(text, (long) used[0]);
        });
        if (sixteen || thirtytwo) {
            if (fixed != 0) return;
            def(m, base + "_ex_decode", (a, k) -> {
                String who = base + "_ex_decode";
                byte[] d = Codecs.bytesOf(arg(a, k, 0, "data", null), who + "()");
                String err = errors(arg(a, k, 1, "errors", null), who);
                int[] order = {((Number) arg(a, k, 2, "byteorder", 0L)).intValue()}, used = {0};
                boolean fin = flag(arg(a, k, 3, "final", false));
                String text = sixteen ? CodecsCore.decodeUtf16(d, err, fin, order, used, n) : CodecsCore.decodeUtf32(d, err, fin, order, used, n);
                return tuple(text, (long) used[0], (long) order[0]);
            });
        }
    }

    private static void charmap(PyModule m) {
        def(m, "charmap_encode", (a, k) -> {
            String s = str(arg(a, k, 0, "str", null), "charmap_encode", 1);
            String err = errors(arg(a, k, 1, "errors", null), "charmap_encode");
            Object map = arg(a, k, 2, "mapping", null);
            return tuple(new PyBytes(CodecsMaps.encodeCharmap(s, err, map)), cpLen(s));
        });
        def(m, "charmap_decode", (a, k) -> {
            byte[] d = Codecs.bytesOf(arg(a, k, 0, "data", null), "charmap_decode()");
            String err = errors(arg(a, k, 1, "errors", null), "charmap_decode");
            Object map = arg(a, k, 2, "mapping", null);
            return tuple(CodecsMaps.decodeCharmap(d, err, NativeSubclass.unwrap(map)), (long) d.length);
        });
        def(m, "charmap_build", (a, k) -> {
            String table = str(a.get(0), "charmap_build", 1);
            PyDict d = new PyDict();
            int[] cps = table.codePoints().toArray();
            for (int i = 0; i < cps.length; i++) d.put((long) cps[i], (long) i);
            return d;
        });
    }

    private static void escapes(PyModule m) {
        for (boolean raw : new boolean[]{false, true}) {
            String base = raw ? "raw_unicode_escape" : "unicode_escape";
            def(m, base + "_encode", (a, k) -> {
                String s = str(arg(a, k, 0, "str", null), base + "_encode", 1);
                errors(arg(a, k, 1, "errors", null), base + "_encode");
                return tuple(new PyBytes(CodecsMaps.encodeUnicodeEscape(s, raw)), cpLen(s));
            });
            def(m, base + "_decode", (a, k) -> {
                byte[] d = Codecs.bytesOf(arg(a, k, 0, "data", null), base + "_decode()");
                String err = errors(arg(a, k, 1, "errors", null), base + "_decode");
                return tuple(CodecsMaps.decodeUnicodeEscape(d, err, raw, raw ? "rawunicodeescape" : "unicodeescape"), (long) d.length);
            });
        }
        def(m, "escape_encode", (a, k) -> {
            Object o = NativeSubclass.unwrap(a.get(0));
            if (!(o instanceof PyBytes b)) throw new PyException("TypeError", "argument 1 must be bytes, not " + PyRuntime.typeName(o));
            return tuple(new PyBytes(CodecsMaps.escapeEncode(b.data)), (long) b.data.length);
        });
        def(m, "escape_decode", (a, k) -> {
            Object o = NativeSubclass.unwrap(a.get(0));
            byte[] d = o instanceof String s ? s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1) : Codecs.bytesOf(o, "escape_decode()");
            return tuple(new PyBytes(CodecsMaps.escapeDecode(d, errors(arg(a, k, 1, "errors", null), "escape_decode"))), (long) d.length);
        });
        def(m, "readbuffer_encode", (a, k) -> {
            Object o = NativeSubclass.unwrap(a.get(0));
            byte[] d = o instanceof String s ? Codecs.encodeNative(s, "utf-8", "strict") : Codecs.bytesOf(o, "readbuffer_encode()");
            return tuple(new PyBytes(d), (long) d.length);
        });
    }
}
