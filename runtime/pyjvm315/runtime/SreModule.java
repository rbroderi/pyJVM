// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * {@code _sre}: the Python-facing layer over the sre engine ({@link SreEngine}), a port of Modules/_sre/sre.c.
 * Pattern, Match and Scanner objects are native classes whose state lives in {@link PyInstance#slotValues}; CPython's
 * unmodified Lib/re (_parser, _compiler) produces the code that {@code compile} validates and runs.
 */
public final class SreModule {
    private SreModule() {}

    static final long MAGIC = 20230612L;
    static final int CODESIZE = 4;
    static final long MAXREPEAT = 4294967295L;
    static final long MAXGROUPS = 1073741823L;

    private static final int FLAG_UNICODE = 32;

    private static PyClass patClass, matClass, scanClass, tmplClass;

    static final class Pat {
        Object obj;               // the Pattern instance
        Object pattern;           // the source (str, bytes or None)
        int flags;
        int[] code;
        int groups;
        PyDict groupindex;
        Object indexgroup;
        boolean isbytes;
        SreEngine spare;          // an idle engine to reuse (taken under the pattern's lock)
    }

    static final class Mat {
        Pat pat;
        SreSubject subj;
        int[] regs;               // 2 * (groups + 1) offsets, -1 for unmatched
        int lastindex;
        int pos, endpos;
    }

    static final class Scan {
        Pat pat;
        SreSubject subj;
        SreEngine e;
        boolean done;
    }

    /** A parsed replacement template: literal0 group0 literal1 group1 ... literalN. */
    static final class Tmpl {
        Object[] lits;
        int[] idx;
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_sre", "pyjvm315.runtime.SreModule");
        Class<?> c = SreModule.class;
        String cn = c.getName();

        patClass = define("re", "Pattern", null);
        staticMethodVar(patClass, c, "__new__", "noNewPattern", "cls", "args");
        method(patClass, c, "match", "patMatch", "self,string,pos,endpos", defaults("pos", 0L, "endpos", Long.MAX_VALUE));
        method(patClass, c, "fullmatch", "patFullmatch", "self,string,pos,endpos", defaults("pos", 0L, "endpos", Long.MAX_VALUE));
        method(patClass, c, "search", "patSearch", "self,string,pos,endpos", defaults("pos", 0L, "endpos", Long.MAX_VALUE));
        if (LangVersion.atLeast(3, 15)) method(patClass, c, "prefixmatch", "patMatch", "self,string,pos,endpos", defaults("pos", 0L, "endpos", Long.MAX_VALUE));
        method(patClass, c, "findall", "patFindall", "self,string,pos,endpos", defaults("pos", 0L, "endpos", Long.MAX_VALUE));
        method(patClass, c, "finditer", "patFinditer", "self,string,pos,endpos", defaults("pos", 0L, "endpos", Long.MAX_VALUE));
        method(patClass, c, "scanner", "patScanner", "self,string,pos,endpos", defaults("pos", 0L, "endpos", Long.MAX_VALUE));
        method(patClass, c, "sub", "patSub", "self,repl,string,count", defaults("count", 0L));
        method(patClass, c, "subn", "patSubn", "self,repl,string,count", defaults("count", 0L));
        method(patClass, c, "split", "patSplit", "self,string,maxsplit", defaults("maxsplit", 0L));
        method(patClass, c, "__repr__", "patRepr", "self");
        method(patClass, c, "__eq__", "patEq", "self,other");
        method(patClass, c, "__ne__", "patNe", "self,other");
        method(patClass, c, "__hash__", "patHash", "self");
        method(patClass, c, "__copy__", "selfReturn", "self");
        method(patClass, c, "__deepcopy__", "selfReturn1", "self,memo");
        PyRuntime.classAddMethodEx(patClass, "__class_getitem__", c, "classGetitem", "class", "", "cls,item", "", null, null, new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(patClass, "__class_getitem__", PyRuntime.methodDescriptor(patClass.methods.get("__class_getitem__").function, "class"));
        PyRuntime.classAddProperty(patClass, "pattern", cn, "patPattern", null);
        PyRuntime.classAddProperty(patClass, "flags", cn, "patFlags", null);
        PyRuntime.classAddProperty(patClass, "groups", cn, "patGroups", null);
        PyRuntime.classAddProperty(patClass, "groupindex", cn, "patGroupindex", null);
        finish(patClass);

        matClass = define("re", "Match", null);
        staticMethodVar(matClass, c, "__new__", "noNewMatch", "cls", "args");
        methodVar(matClass, c, "group", "matGroup", "self", "args");
        method(matClass, c, "groups", "matGroups", "self,default", defaults("default", null));
        method(matClass, c, "groupdict", "matGroupdict", "self,default", defaults("default", null));
        method(matClass, c, "start", "matStart", "self,group", defaults("group", 0L));
        method(matClass, c, "end", "matEnd", "self,group", defaults("group", 0L));
        method(matClass, c, "span", "matSpan", "self,group", defaults("group", 0L));
        method(matClass, c, "expand", "matExpand", "self,template");
        method(matClass, c, "__getitem__", "matGetitem", "self,key");
        method(matClass, c, "__repr__", "matRepr", "self");
        method(matClass, c, "__copy__", "selfReturn", "self");
        method(matClass, c, "__deepcopy__", "selfReturn1", "self,memo");
        PyRuntime.classAddMethodEx(matClass, "__class_getitem__", c, "classGetitem", "class", "", "cls,item", "", null, null, new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(matClass, "__class_getitem__", PyRuntime.methodDescriptor(matClass.methods.get("__class_getitem__").function, "class"));
        PyRuntime.classAddProperty(matClass, "regs", cn, "matRegs", null);
        PyRuntime.classAddProperty(matClass, "lastindex", cn, "matLastindex", null);
        PyRuntime.classAddProperty(matClass, "lastgroup", cn, "matLastgroup", null);
        PyRuntime.classAddProperty(matClass, "re", cn, "matRe", null);
        PyRuntime.classAddProperty(matClass, "string", cn, "matString", null);
        PyRuntime.classAddProperty(matClass, "pos", cn, "matPos", null);
        PyRuntime.classAddProperty(matClass, "endpos", cn, "matEndpos", null);
        finish(matClass);

        scanClass = define("re", "SRE_Scanner", null);
        method(scanClass, c, "match", "scanMatch", "self");
        method(scanClass, c, "search", "scanSearch", "self");
        if (LangVersion.atLeast(3, 15)) method(scanClass, c, "prefixmatch", "scanMatch", "self");
        PyRuntime.classAddProperty(scanClass, "pattern", cn, "scanPattern", null);
        finish(scanClass);

        tmplClass = define("re", "SRE_Template", null);
        finish(tmplClass);

        m.attrs.put("MAGIC", MAGIC);
        m.attrs.put("CODESIZE", (long) CODESIZE);
        m.attrs.put("MAXREPEAT", MAXREPEAT);
        m.attrs.put("MAXGROUPS", MAXGROUPS);
        m.attrs.put("copyright", " SRE 2.2.2 Copyright (c) 1997-2002 by Secret Labs AB ");
        fn(m, "getcodesize", (a, k) -> (long) CODESIZE);
        fn(m, "ascii_iscased", (a, k) -> SreUnicode.asciiIsCased(oneChar(a)));
        fn(m, "unicode_iscased", (a, k) -> SreUnicode.uniIsCased(oneChar(a)));
        fn(m, "ascii_tolower", (a, k) -> (long) SreUnicode.lowerAscii(oneChar(a)));
        fn(m, "unicode_tolower", (a, k) -> (long) SreUnicode.lowerUnicode(oneChar(a)));
        fn(m, "compile", (a, k) -> compile(a, k));
        fn(m, "template", (a, k) -> template(a, k));
        return m;
    }

    private static int oneChar(List<Object> a) {
        if (a.size() != 1) throw new PyException("TypeError", "function takes exactly 1 argument (" + a.size() + " given)");
        long v = IoUtil.asLong(a.get(0));
        if (v < 0 || v > 0xffffffffL) throw new PyException("OverflowError", "unsigned int is out of range");
        return (int) v;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Pat pat(Object o) { return state(o, Pat.class, "re.Pattern"); }
    private static Mat mat(Object o) { return state(o, Mat.class, "re.Match"); }

    private static long ssize(Object o) {
        BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (b.bitLength() > 63) return b.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        return b.longValue();
    }

    private static boolean isIndex(Object o) {
        if (o instanceof Long || o instanceof Integer || o instanceof Boolean || o instanceof BigInteger) return true;
        if (o instanceof PyInstance pi) {
            if (pi.payload instanceof Long || pi.payload instanceof BigInteger || pi.payload instanceof Boolean) return true;
            return pi.cls.lookupMethod("__index__") != null;
        }
        return false;
    }

    public static Object noNewPattern(Object cls, Object args) { throw new PyException("TypeError", "cannot create 're.Pattern' instances"); }
    public static Object noNewMatch(Object cls, Object args) { throw new PyException("TypeError", "cannot create 're.Match' instances"); }

    public static Object selfReturn(Object self) { return self; }
    public static Object selfReturn1(Object self, Object memo) { return self; }

    public static Object classGetitem(Object cls, Object item) {
        PyTuple args = new PyTuple();
        if (item instanceof PyTuple t) args.items.addAll(t.items); else args.items.add(item);
        return new TypeParamsGeneric(cls, args, true);
    }

    private static int clamp(long v, int lo, int hi) { return (int) Math.max(lo, Math.min(hi, v)); }

    /** state_init(): a fresh engine over the subject with pos/endpos clamped into it. */
    private static SreEngine engine(Pat p, SreSubject s, Object posO, Object endO) {
        long pos = ssize(posO), end = ssize(endO);
        if (s.isBytes != p.isbytes)
            throw new PyException("TypeError", p.isbytes ? "cannot use a bytes pattern on a string-like object" : "cannot use a string pattern on a bytes-like object");
        int n = s.length();
        SreEngine e;
        synchronized (p) { e = p.spare; p.spare = null; }
        if (e == null) e = new SreEngine(p.code, p.groups);
        e.init(s.cp, n, clamp(pos, 0, n), clamp(end, 0, n));
        return e;
    }

    /** Hands an engine that is no longer referenced by any result back for the next call. */
    private static void release(Pat p, SreEngine e) {
        if (e.st.length > 8192) return;
        e.str = null;
        synchronized (p) { p.spare = e; }
    }

    private static Object newMatch(Pat p, SreSubject s, SreEngine e) {
        Mat m = new Mat();
        m.pat = p;
        m.subj = s;
        int[] regs = new int[2 * (p.groups + 1)];
        regs[0] = e.start;
        regs[1] = e.sptr;
        for (int i = 0, j = 0; i < p.groups; i++, j += 2) {
            if (j + 1 <= e.lastmark && e.mark[j] >= 0 && e.mark[j + 1] >= 0) {
                regs[2 * i + 2] = e.mark[j];
                regs[2 * i + 3] = e.mark[j + 1];
            } else {
                regs[2 * i + 2] = -1;
                regs[2 * i + 3] = -1;
            }
        }
        m.regs = regs;
        m.lastindex = e.lastindex;
        m.pos = e.pos;
        m.endpos = e.endpos;
        return newInstance(matClass, m);
    }

    /** state_getslice() for group i (1-based); unmatched groups give {@code dflt}. */
    private static Object groupSlice(SreEngine e, SreSubject s, int i, Object dflt) {
        int j = 2 * (i - 1);
        if (j + 1 > e.lastmark || e.mark[j] < 0 || e.mark[j + 1] < 0) return dflt;
        int a = e.mark[j], b = e.mark[j + 1];
        if (a > b) throw new PyException("SystemError", "The span of capturing group is wrong, please report a bug for the re module.");
        return s.slice(a, b);
    }

    // ---- module functions --------------------------------------------------------------------------

    private static Object argAt(List<Object> a, Map<Object,Object> k, int i, String name) {
        if (i < a.size()) return a.get(i);
        if (k != null && k.containsKey(name)) return k.get(name);
        throw new PyException("TypeError", "compile() missing required argument '" + name + "' (pos " + (i + 1) + ")");
    }

    static Object compile(List<Object> a, Map<Object,Object> k) {
        Object pattern = argAt(a, k, 0, "pattern");
        long flags = IoUtil.asLong(argAt(a, k, 1, "flags"));
        Object codeO = argAt(a, k, 2, "code");
        long groups = IoUtil.asLong(argAt(a, k, 3, "groups"));
        Object gi = argAt(a, k, 4, "groupindex");
        Object ig = argAt(a, k, 5, "indexgroup");
        ArrayList<Object> items = new ArrayList<>();
        for (Object x : PyRuntime.iterable(codeO)) items.add(x);
        int[] code = new int[items.size()];
        for (int i = 0; i < code.length; i++) {
            long v = IoUtil.asLong(items.get(i));
            if (v < 0 || v > 0xffffffffL) throw new PyException("OverflowError", "regular expression code size limit exceeded");
            code[i] = (int) v;
        }
        Pat p = new Pat();
        p.pattern = pattern;
        p.flags = (int) flags;
        if (pattern == null) p.isbytes = (flags & FLAG_UNICODE) == 0;
        else p.isbytes = SreSubject.of(pattern).isBytes;
        if (groups < 0 || groups > MAXGROUPS || !SreValidate.validate(code, (int) groups))
            throw new PyException("RuntimeError", "invalid SRE code");
        p.code = code;
        p.groups = (int) groups;
        PyDict d = new PyDict();
        if (gi instanceof Map<?,?> mm) {
            for (Map.Entry<?,?> en : mm.entrySet()) d.put(en.getKey(), en.getValue());
        } else if (gi != null) {
            Object ks = PyRuntime.callMethod(gi, "items", new Object[0]);
            for (Object kv : PyRuntime.iterable(ks)) {
                PyTuple t = (PyTuple) kv;
                d.put(t.items.get(0), t.items.get(1));
            }
        }
        p.groupindex = d;
        p.indexgroup = ig;
        PyInstance obj = newInstance(patClass, p);
        p.obj = obj;
        return obj;
    }

    static Object template(List<Object> a, Map<Object,Object> k) {
        Object pattern = argAt(a, k, 0, "pattern");
        Object list = argAt(a, k, 1, "template");
        pat(pattern);
        ArrayList<Object> items = new ArrayList<>();
        if (!(list instanceof List)) throw new PyException("TypeError", "template() argument 2 must be list, not " + PyRuntime.typeName(list));
        items.addAll((List<?>) list);
        int n = items.size();
        if (n % 2 == 0) throw new PyException("TypeError", "invalid template");
        Tmpl t = new Tmpl();
        t.lits = new Object[n / 2 + 1];
        t.idx = new int[n / 2];
        for (int i = 0; i < n; i++) {
            Object o = items.get(i);
            if ((i & 1) == 0) {
                t.lits[i / 2] = o == null ? "" : o;
            } else {
                long g = IoUtil.asLong(o);
                if (g < 0 || g > pat(pattern).groups) throw new PyException("TypeError", "invalid template");
                t.idx[i / 2] = (int) g;
            }
        }
        return newInstance(tmplClass, t);
    }

    // ---- Pattern -------------------------------------------------------------------------------

    public static Object patPattern(Object self) { return pat(self).pattern; }
    public static Object patFlags(Object self) { return (long) pat(self).flags; }
    public static Object patGroups(Object self) { return (long) pat(self).groups; }
    public static Object patGroupindex(Object self) {
        // CPython: a read-only mappingproxy over the dict; here a frozen dict copy (pyJVM has no mappingproxy type)
        PyDict copy = new PyDict();
        for (Map.Entry<Object,Object> en : pat(self).groupindex.entrySet()) copy.put(en.getKey(), en.getValue());
        return copy.freeze();
    }

    private static Object doMatch(Object self, Object string, Object pos, Object endpos, int mode) {
        Pat p = pat(self);
        SreSubject s = SreSubject.of(string);
        SreEngine e = engine(p, s, pos, endpos);
        int r;
        if (mode == 2) {
            r = e.search();
        } else {
            e.matchAll = mode == 1;
            e.sptr = e.start;
            r = e.matchTop();
        }
        Object result = r > 0 ? newMatch(p, s, e) : null;
        release(p, e);
        return result;
    }

    public static Object patMatch(Object self, Object string, Object pos, Object endpos) { return doMatch(self, string, pos, endpos, 0); }
    public static Object patFullmatch(Object self, Object string, Object pos, Object endpos) { return doMatch(self, string, pos, endpos, 1); }
    public static Object patSearch(Object self, Object string, Object pos, Object endpos) { return doMatch(self, string, pos, endpos, 2); }

    public static Object patFindall(Object self, Object string, Object pos, Object endpos) {
        Pat p = pat(self);
        SreSubject s = SreSubject.of(string);
        SreEngine e = engine(p, s, pos, endpos);
        ArrayList<Object> out = new ArrayList<>();
        Object empty = s.empty();
        while (e.start <= e.end) {
            e.reset();
            e.sptr = e.start;
            if (e.search() <= 0) break;
            Object item;
            if (p.groups == 0) {
                item = s.slice(e.start, e.sptr);
            } else if (p.groups == 1) {
                item = groupSlice(e, s, 1, empty);
            } else {
                PyTuple t = new PyTuple();
                for (int i = 1; i <= p.groups; i++) t.items.add(groupSlice(e, s, i, empty));
                item = t;
            }
            out.add(item);
            e.mustAdvance = e.sptr == e.start;
            e.start = e.sptr;
        }
        release(p, e);
        return out;
    }

    public static Object patFinditer(Object self, Object string, Object pos, Object endpos) {
        Object scanner = patScanner(self, string, pos, endpos);
        return new PyLazyIterator() {
            @Override Object compute() {
                Object m = scanStep(scanner, true);
                if (m == null) throw new NoSuchElementException();
                return m;
            }
            @Override String pyTypeName() { return "callable_iterator"; }
        };
    }

    public static Object patScanner(Object self, Object string, Object pos, Object endpos) {
        Pat p = pat(self);
        SreSubject s = SreSubject.of(string);
        Scan sc = new Scan();
        sc.pat = p;
        sc.subj = s;
        sc.e = engine(p, s, pos, endpos);
        return newInstance(scanClass, sc);
    }

    /** Joins the pieces of sub(): slices of the subject and replacement objects. */
    private static final class Out {
        final boolean bytes;
        final StringBuilder sb;
        final ByteArrayOutputStream bo;
        int count;
        Out(boolean bytes) {
            this.bytes = bytes;
            sb = bytes ? null : new StringBuilder();
            bo = bytes ? new ByteArrayOutputStream() : null;
        }
        void slice(SreSubject s, int a, int b) {
            if (a >= b) return;
            count++;
            if (bytes) bo.write(s.raw, a, b - a);
            else if (s.bmp) sb.append(s.str, a, b);
            else sb.append(s.slice(a, b));
        }
        void obj(Object o) {
            Object v = NativeSubclass.unwrap(o);
            if (bytes) {
                if (v instanceof PyBytes b) bo.write(b.data, 0, b.data.length);
                else if (v instanceof PyByteSequence bs) { byte[] r = bs.toByteArray(); bo.write(r, 0, r.length); }
                else throw new PyException("TypeError", "sequence item " + count + ": expected a bytes-like object, " + PyRuntime.typeName(o) + " found");
            } else {
                if (v instanceof String str) sb.append(str);
                else throw new PyException("TypeError", "sequence item " + count + ": expected str instance, " + PyRuntime.typeName(o) + " found");
            }
            count++;
        }
        Object result() { return bytes ? new PyBytes(bo.toByteArray()) : sb.toString(); }
    }

    private static void expandInto(Out out, Tmpl t, SreSubject s, int[] regs) {
        for (int i = 0; i < t.lits.length; i++) {
            Object lit = t.lits[i];
            if (!isEmptyLit(lit)) out.obj(lit);
            if (i < t.idx.length) {
                int g = t.idx[i];
                if (2 * g + 1 < regs.length && regs[2 * g] >= 0 && regs[2 * g + 1] >= 0) out.slice(s, regs[2 * g], regs[2 * g + 1]);
            }
        }
    }

    private static boolean isEmptyLit(Object lit) {
        Object v = NativeSubclass.unwrap(lit);
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof PyByteSequence b) return b.byteSize() == 0;
        return false;
    }

    private static Tmpl compileTemplate(Pat p, Object repl) {
        PyModule re = ImportSupport.cachedModule("re");
        if (re == null) re = (PyModule) PyRuntime.importModule("re", null);
        Object f = PyRuntime.getattr(re, "_compile_template");
        ArrayList<Object> args = new ArrayList<>();
        args.add(p.obj);
        args.add(repl);
        Object r = PyRuntime.callFunction(f, args, noKwargs());
        return state(r, Tmpl.class, "SRE_Template");
    }

    private static Object subx(Object self, Object repl, Object string, Object countO, boolean subn) {
        Pat p = pat(self);
        long count = ssize(countO);
        boolean callable = Boolean.TRUE.equals(PyRuntime.callable_(repl));
        Object literal = null;
        Tmpl tmpl = null;
        if (!callable) {
            Object v = NativeSubclass.unwrap(repl);
            boolean hasBackslash;
            if (v instanceof String str) hasBackslash = str.indexOf('\\') >= 0;
            else if (v instanceof PyByteSequence bs) {
                hasBackslash = false;
                byte[] raw = bs.toByteArray();
                for (byte b : raw) if (b == '\\') { hasBackslash = true; break; }
                v = new PyBytes(raw);
            } else
                throw new PyException("TypeError", p.isbytes ? "expected a bytes-like object, " + PyRuntime.typeName(repl) + " found"
                    : "decoding to str: need a bytes-like object, " + PyRuntime.typeName(repl) + " found");
            if (hasBackslash) tmpl = compileTemplate(p, repl);
            else literal = v;
        }
        SreSubject s = SreSubject.of(string);
        SreEngine e = engine(p, s, 0L, Long.MAX_VALUE);
        Out out = new Out(s.isBytes);
        long n = 0;
        int i = 0;
        while (count == 0 || n < count) {
            e.reset();
            e.sptr = e.start;
            if (e.search() <= 0) break;
            int b = e.start, en = e.sptr;
            out.slice(s, i, b);
            if (callable) {
                ArrayList<Object> args = new ArrayList<>();
                args.add(newMatch(p, s, e));
                Object item = PyRuntime.callFunction(repl, args, noKwargs());
                if (item != null) out.obj(item);
            } else if (tmpl != null) {
                int[] regs = new int[2 * (p.groups + 1)];
                regs[0] = b;
                regs[1] = en;
                for (int g = 0; g < p.groups; g++) {
                    int j = 2 * g;
                    if (j + 1 <= e.lastmark && e.mark[j] >= 0 && e.mark[j + 1] >= 0) { regs[2 * g + 2] = e.mark[j]; regs[2 * g + 3] = e.mark[j + 1]; }
                    else { regs[2 * g + 2] = -1; regs[2 * g + 3] = -1; }
                }
                expandInto(out, tmpl, s, regs);
            } else {
                out.obj(literal);
            }
            i = en;
            n++;
            e.mustAdvance = e.sptr == e.start;
            e.start = e.sptr;
        }
        out.slice(s, i, e.endpos);
        release(p, e);
        Object joined = out.result();
        if (subn) return tuple(joined, n);
        return joined;
    }

    public static Object patSub(Object self, Object repl, Object string, Object count) { return subx(self, repl, string, count, false); }
    public static Object patSubn(Object self, Object repl, Object string, Object count) { return subx(self, repl, string, count, true); }

    public static Object patSplit(Object self, Object string, Object maxsplitO) {
        Pat p = pat(self);
        long maxsplit = ssize(maxsplitO);
        SreSubject s = SreSubject.of(string);
        SreEngine e = engine(p, s, 0L, Long.MAX_VALUE);
        ArrayList<Object> out = new ArrayList<>();
        long n = 0;
        int last = e.start;
        while (maxsplit == 0 || n < maxsplit) {
            e.reset();
            e.sptr = e.start;
            if (e.search() <= 0) break;
            out.add(s.slice(last, e.start));
            for (int g = 1; g <= p.groups; g++) out.add(groupSlice(e, s, g, null));
            n++;
            last = e.sptr;
            e.mustAdvance = e.sptr == e.start;
            e.start = e.sptr;
        }
        out.add(s.slice(last, e.endpos));
        release(p, e);
        return out;
    }

    private static final String[] FLAG_NAMES = {"re.IGNORECASE", "re.LOCALE", "re.MULTILINE", "re.DOTALL", "re.UNICODE", "re.VERBOSE", "re.DEBUG", "re.ASCII"};
    private static final int[] FLAG_VALUES = {2, 4, 8, 16, 32, 64, 128, 256};

    public static Object patRepr(Object self) {
        Pat p = pat(self);
        String r = PyRuntime.pyRepr(p.pattern);
        if (r.length() > 200) r = r.substring(0, 200);
        int flags = p.flags;
        if (!p.isbytes) flags &= ~FLAG_UNICODE;
        StringBuilder sb = new StringBuilder("re.compile(").append(r);
        if (flags != 0) {
            sb.append(", ");
            boolean first = true;
            for (int i = 0; i < FLAG_NAMES.length; i++) {
                if ((flags & FLAG_VALUES[i]) != 0) {
                    if (!first) sb.append('|');
                    sb.append(FLAG_NAMES[i]);
                    first = false;
                    flags &= ~FLAG_VALUES[i];
                }
            }
            if (flags != 0) {
                if (!first) sb.append('|');
                sb.append("0x").append(Integer.toHexString(flags));
            }
        }
        return sb.append(')').toString();
    }

    private static Boolean sameOrNull(Object self, Object other) {
        if (!(other instanceof PyInstance oi) || !(oi.slotValues.get(STATE) instanceof Pat b)) return null;
        Pat a = pat(self);
        if (a == b) return true;
        if (a.flags != b.flags || a.isbytes != b.isbytes || !Arrays.equals(a.code, b.code)) return false;
        return PyRuntime.truth(PyRuntime.eq(a.pattern, b.pattern));
    }

    public static Object patEq(Object self, Object other) {
        Boolean r = sameOrNull(self, other);
        return r == null ? PyRuntime.notImplemented() : (Object) r;
    }

    public static Object patNe(Object self, Object other) {
        Boolean r = sameOrNull(self, other);
        return r == null ? PyRuntime.notImplemented() : (Object) (!r);
    }

    public static Object patHash(Object self) {
        Pat p = pat(self);
        long h = ((Number) PyRuntime.hash(p.pattern)).longValue();
        h = h * 1000003L ^ p.flags;
        h = h * 1000003L ^ (p.isbytes ? 1 : 0);
        h = h * 1000003L ^ Arrays.hashCode(p.code);
        return h == -1 ? -2L : h;
    }

    // ---- Scanner -------------------------------------------------------------------------------

    private static Scan scan(Object o) { return state(o, Scan.class, "SRE_Scanner"); }

    static Object scanStep(Object self, boolean search) {
        Scan sc = scan(self);
        if (sc.done) return null;
        SreEngine e = sc.e;
        e.reset();
        e.sptr = e.start;
        int r;
        if (search) r = e.search();
        else { e.matchAll = false; r = e.matchTop(); }
        Object m = r > 0 ? newMatch(sc.pat, sc.subj, e) : null;
        if (r == 0) {
            sc.done = true;
        } else {
            e.mustAdvance = e.sptr == e.start;
            e.start = e.sptr;
        }
        return m;
    }

    public static Object scanMatch(Object self) { return scanStep(self, false); }
    public static Object scanSearch(Object self) { return scanStep(self, true); }
    public static Object scanPattern(Object self) { return scan(self).pat.obj; }

    // ---- Match ---------------------------------------------------------------------------------

    private static int groupIndex(Mat m, Object o) {
        long i;
        if (isIndex(o)) {
            i = ssize(o);
        } else {
            i = -1;
            Object key = NativeSubclass.unwrap(o);
            Object v = m.pat.groupindex.containsKey(key) ? m.pat.groupindex.get(key) : null;
            if (v != null && NativeSubclass.unwrap(v) instanceof Long l) i = l;
        }
        if (i < 0 || i > m.pat.groups) throw new PyException("IndexError", "no such group");
        return (int) i;
    }

    private static Object groupValue(Mat m, int i, Object dflt) {
        int a = m.regs[2 * i], b = m.regs[2 * i + 1];
        if (a < 0 || b < 0) return dflt;
        return m.subj.slice(a, b);
    }

    public static Object matGroup(Object self, Object args) {
        Mat m = mat(self);
        List<Object> a = ((PyTuple) args).items;
        if (a.isEmpty()) return groupValue(m, 0, null);
        if (a.size() == 1) return groupValue(m, groupIndex(m, a.get(0)), null);
        PyTuple t = new PyTuple();
        for (Object o : a) t.items.add(groupValue(m, groupIndex(m, o), null));
        return t;
    }

    public static Object matGetitem(Object self, Object key) {
        Mat m = mat(self);
        return groupValue(m, groupIndex(m, key), null);
    }

    public static Object matGroups(Object self, Object dflt) {
        Mat m = mat(self);
        PyTuple t = new PyTuple();
        for (int i = 1; i <= m.pat.groups; i++) t.items.add(groupValue(m, i, dflt));
        return t;
    }

    public static Object matGroupdict(Object self, Object dflt) {
        Mat m = mat(self);
        PyDict d = new PyDict();
        for (Map.Entry<Object,Object> en : m.pat.groupindex.entrySet()) {
            int i = groupIndex(m, en.getKey());
            d.put(en.getKey(), groupValue(m, i, dflt));
        }
        return d;
    }

    public static Object matStart(Object self, Object g) { Mat m = mat(self); return (long) m.regs[2 * groupIndex(m, g)]; }
    public static Object matEnd(Object self, Object g) { Mat m = mat(self); return (long) m.regs[2 * groupIndex(m, g) + 1]; }
    public static Object matSpan(Object self, Object g) {
        Mat m = mat(self);
        int i = groupIndex(m, g);
        return tuple((long) m.regs[2 * i], (long) m.regs[2 * i + 1]);
    }

    public static Object matRegs(Object self) {
        Mat m = mat(self);
        PyTuple t = new PyTuple();
        for (int i = 0; i <= m.pat.groups; i++) t.items.add(tuple((long) m.regs[2 * i], (long) m.regs[2 * i + 1]));
        return t;
    }

    public static Object matExpand(Object self, Object template) {
        Mat m = mat(self);
        Tmpl t = compileTemplate(m.pat, template);
        Out out = new Out(m.subj.isBytes);
        expandInto(out, t, m.subj, m.regs);
        return out.result();
    }

    public static Object matLastindex(Object self) { Mat m = mat(self); return m.lastindex >= 0 ? (Object) (long) m.lastindex : null; }

    public static Object matLastgroup(Object self) {
        Mat m = mat(self);
        if (m.lastindex >= 0 && m.pat.indexgroup instanceof PyTuple t && m.lastindex < t.items.size()) return t.items.get(m.lastindex);
        return null;
    }

    public static Object matRe(Object self) { return mat(self).pat.obj; }
    public static Object matString(Object self) { return mat(self).subj.orig; }
    public static Object matPos(Object self) { return (long) mat(self).pos; }
    public static Object matEndpos(Object self) { return (long) mat(self).endpos; }

    public static Object matRepr(Object self) {
        Mat m = mat(self);
        String g = PyRuntime.pyRepr(groupValue(m, 0, null));
        if (g.length() > 50) g = g.substring(0, 50);
        return "<re.Match object; span=(" + m.regs[0] + ", " + m.regs[1] + "), match=" + g + ">";
    }
}
