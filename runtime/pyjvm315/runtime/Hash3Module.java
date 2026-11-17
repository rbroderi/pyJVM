// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The builtin digest modules {@code _sha3} (SHA3-224/256/384/512, SHAKE128/256 over {@link Keccak}) and {@code _blake2}
 * (BLAKE2b/BLAKE2s over {@link Blake2}). Like CPython, the module attributes are the types themselves, with the per-lane
 * argument and error texts measured on 3.13 / 3.14 / 3.15.
 *
 * <p>The types are sealed ({@link NativeTypes#seal}: immutable, not subclassable, no instance __dict__) and unpicklable
 * ({@link NativeTypes#noPickle}) like CPython's. The {@code unsigned long} overflow texts (blake2 {@code leaf_size}, shake
 * {@code length} on 3.13/3.14) follow the platform's C long width; measured on Linux CPython 3.13.18 and 3.15.2
 * (no 3.14 interpreter in WSL, 3.14 follows the 3.13 code path).
 */
final class Hash3Module {
    private Hash3Module() {}

    private static final Class<?> C = Hash3Module.class;
    private static final String ME = C.getName();
    private static final boolean WINDOWS = ImportSupport.WINDOWS;
    private static final Map<Object, Object> SPECS = new IdentityHashMap<>();

    private static boolean l14() { return LangVersion.atLeast(3, 14); }
    private static boolean l15() { return LangVersion.atLeast(3, 15); }
    private static PyException err(String type, String msg) { return new PyException(type, msg); }

    // ------------------------------------------------------------------------------------------------ shared method plumbing

    private static void noKw(String q, PyDict kw) {
        if (!kw.isEmpty()) throw err("TypeError", q + "() takes no keyword arguments");
    }

    private static void noArgs(String q, PyTuple args, PyDict kw) {
        noKw(q, kw);
        if (!args.items.isEmpty()) throw err("TypeError", q + "() takes no arguments (" + args.items.size() + " given)");
    }

    private static void copyNoArgs(String q, PyTuple args, PyDict kw) {
        if (l15()) {
            if (!kw.isEmpty() || !args.items.isEmpty()) throw err("TypeError", "copy() takes no arguments");
        } else noArgs(q, args, kw);
    }

    private static byte[] updateArg(String q, PyTuple args, PyDict kw) {
        noKw(q, kw);
        if (args.items.size() != 1) throw err("TypeError", q + "() takes exactly one argument (" + args.items.size() + " given)");
        return HashArgs.bytes(args.items.get(0));
    }

    private static void methods(PyClass cls, String prefix) {
        for (String m : new String[]{"update", "digest", "hexdigest", "copy"}) {
            methodVarKw(cls, C, m, prefix + m, "self", "args", "kwargs", new PyDict());
        }
        PyRuntime.classAddProperty(cls, "name", ME, prefix + "nameOf", null);
        PyRuntime.classAddProperty(cls, "digest_size", ME, prefix + "digestSizeOf", null);
        PyRuntime.classAddProperty(cls, "block_size", ME, prefix + "blockSizeOf", null);
    }

    private static void ctor(PyClass cls, String javaName) {
        PyRuntime.classAddMethodEx(cls, "__new__", C, javaName, "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(cls, "__new__", cls.methods.get("__new__").function);
    }

    private static Object spec(Object cls) {
        Object s = SPECS.get(cls);
        if (s == null) throw err("TypeError", "cannot create instances of '" + PyRuntime.typeName(cls) + "' (unsupported subclass)");
        return s;
    }

    // ------------------------------------------------------------------------------------------------ _sha3

    private record Spec(String name, int digestSize, int rate, int suffix) {
        boolean shake() { return digestSize == 0; }
    }

    private static final class S3 {
        final Spec spec; final Keccak k;
        S3(Spec spec, Keccak k) { this.spec = spec; this.k = k; }
    }

    static PyModule createSha3() {
        PyModule m = new PyModule("_sha3", ME);
        Spec[] specs = {
            new Spec("sha3_224", 28, 144, 0x06), new Spec("sha3_256", 32, 136, 0x06), new Spec("sha3_384", 48, 104, 0x06),
            new Spec("sha3_512", 64, 72, 0x06), new Spec("shake_128", 0, 168, 0x1f), new Spec("shake_256", 0, 136, 0x1f)};
        for (Spec sp : specs) {
            PyClass cls = define("_sha3", sp.name(), null);
            SPECS.put(cls, sp);
            ctor(cls, "s3New");
            methods(cls, "s3");
            PyRuntime.classAddProperty(cls, "_capacity_bits", ME, "s3capacityBits", null);
            PyRuntime.classAddProperty(cls, "_rate_bits", ME, "s3rateBits", null);
            PyRuntime.classAddProperty(cls, "_suffix", ME, "s3suffix", null);
            PyRuntime.classAddAttr(cls, "__doc__", sp.shake()
                ? sp.name() + "([data], *, usedforsecurity=True) -> SHAKE object\n\nReturn a new SHAKE hash object."
                : sp.name() + "([data], *, usedforsecurity=True) -> SHA3 object\n\nReturn a new SHA3 hash object with a hashbit length of " + sp.digestSize() + " bytes.");
            noPickle(cls, true);
            finish(cls);
            seal(cls);
            m.attrs.put(sp.name(), cls);
        }
        m.attrs.put("implementation", "HACL");
        HashArgs.gilMinsize(m);
        return m;
    }

    private static S3 s3(Object self) { return state(self, S3.class, "_sha3 hash"); }

    public static Object s3New(Object cls, Object args, Object kwargs) {
        Spec sp = (Spec) spec(cls);
        Map<Object, Object> rest = new LinkedHashMap<>((PyDict) kwargs);
        // CPython quirk: the argument errors of all six constructors name sha3_224
        Object data = HashArgs.dataArg("sha3_224", ((PyTuple) args).items, rest, 2L);
        HashArgs.noUnknown("sha3_224", rest);
        Keccak k = new Keccak(sp.rate(), sp.suffix());
        if (data != NativeArgs.MISSING) k.absorb(HashArgs.bytes(data));
        return newInstance((PyClass) cls, new S3(sp, k));
    }

    public static Object s3update(Object self, Object args, Object kwargs) {
        S3 s = s3(self);
        s.k.absorb(updateArg(s.spec.name() + ".update", (PyTuple) args, (PyDict) kwargs));
        return null;
    }

    public static Object s3copy(Object self, Object args, Object kwargs) {
        S3 s = s3(self);
        copyNoArgs(s.spec.name() + ".copy", (PyTuple) args, (PyDict) kwargs);
        return newInstance(((PyInstance) self).cls, new S3(s.spec, s.k.copy()));
    }

    private static byte[] s3out(Object self, String fn, Object args, Object kwargs) {
        S3 s = s3(self);
        PyTuple a = (PyTuple) args; PyDict kw = (PyDict) kwargs;
        if (!s.spec.shake()) {
            noArgs(s.spec.name() + "." + fn, a, kw);
            return s.k.squeeze(s.spec.digestSize());
        }
        Object len = NativeArgs.MISSING;
        if (a.items.size() > 1) throw err("TypeError", fn + "() takes at most 1 argument (" + a.items.size() + " given)");
        if (!a.items.isEmpty()) len = a.items.get(0);
        for (Map.Entry<Object, Object> e : kw.entrySet()) {
            if (!"length".equals(e.getKey())) throw err("TypeError", fn + "() got an unexpected keyword argument '" + e.getKey() + "'");
            if (len != NativeArgs.MISSING) throw err("TypeError", "argument for " + fn + "() given by name ('length') and position (1)");
            len = e.getValue();
        }
        if (len == NativeArgs.MISSING) throw err("TypeError", fn + "() missing required argument 'length' (pos 1)");
        return s.k.squeeze(shakeLength(len));
    }

    private static int shakeLength(Object o) {
        BigInteger b = asInteger(o);
        if (l15()) {
            if (b.bitLength() > 63) throw err("OverflowError", "Python int too large to convert to C ssize_t");
            if (b.signum() < 0) throw err("ValueError", "length cannot be negative");
            if (b.bitLength() > 29) throw err("OverflowError", "digest length is too large");
            return b.intValue();
        }
        if (b.signum() < 0) throw err("ValueError", l14() ? "Cannot convert negative int" : "value must be positive");
        if (b.bitLength() > (WINDOWS ? 32 : 64)) throw err("OverflowError", l14() ? "Python int too large for C unsigned long" : "Python int too large to convert to C unsigned long");
        if (b.bitLength() > 29) throw err("ValueError", "length is too large");
        return b.intValue();
    }

    public static Object s3digest(Object self, Object args, Object kwargs) { return new PyBytes(s3out(self, "digest", args, kwargs)); }
    public static Object s3hexdigest(Object self, Object args, Object kwargs) { return HashArgs.hex(s3out(self, "hexdigest", args, kwargs)); }
    public static Object s3nameOf(Object self) { return s3(self).spec.name(); }
    public static Object s3digestSizeOf(Object self) { return (long) s3(self).spec.digestSize(); }
    public static Object s3blockSizeOf(Object self) { return (long) s3(self).spec.rate(); }
    public static Object s3capacityBits(Object self) { return (long) (1600 - 8 * s3(self).spec.rate()); }
    public static Object s3rateBits(Object self) { return (long) (8 * s3(self).spec.rate()); }
    public static Object s3suffix(Object self) { return new PyBytes(new byte[]{(byte) s3(self).spec.suffix()}); }

    // ------------------------------------------------------------------------------------------------ integer conversions

    /** {@code __index__} conversion; 3.13's unsigned converters only say "an integer is required". */
    private static BigInteger asInteger(Object o) {
        if (l14()) return BuiltinsExtra.rangeIndex(o);
        try { return BuiltinsExtra.rangeIndex(o); }
        catch (PyException e) {
            if ("TypeError".equals(e.typeName)) throw err("TypeError", "an integer is required");
            throw e;
        }
    }

    /** A C {@code int} parameter. */
    private static int cint(Object o) {
        BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (b.bitLength() > 31) throw err("OverflowError", "Python int too large to convert to C int");
        return b.intValue();
    }

    private static BigInteger unsigned(Object o, int bits, String what, String over13, String over14) {
        BigInteger b = asInteger(o);
        if (b.signum() < 0) throw err("ValueError", l14() ? "Cannot convert negative int" : "value must be positive");
        if (b.bitLength() > bits) throw err("OverflowError", l14() ? over14 : over13);
        return b;
    }

    // ------------------------------------------------------------------------------------------------ _blake2

    private static final class B2 {
        final String name; final Blake2 h;
        B2(String name, Blake2 h) { this.name = name; this.h = h; }
    }

    private static final Set<String> B2_KEYS = Set.of("digest_size", "key", "salt", "person", "fanout", "depth", "leaf_size",
        "node_offset", "node_depth", "inner_size", "last_node");

    static PyModule createBlake2() {
        PyModule m = new PyModule("_blake2", ME);
        for (String name : new String[]{"blake2b", "blake2s"}) {
            boolean big = name.equals("blake2b");
            PyClass cls = define("_blake2", name, null);
            SPECS.put(cls, name);
            ctor(cls, "b2New");
            methods(cls, "b2");
            long salt = big ? 16 : 8, key = big ? 64 : 32;
            PyRuntime.classAddAttr(cls, "SALT_SIZE", salt);
            PyRuntime.classAddAttr(cls, "PERSON_SIZE", salt);
            PyRuntime.classAddAttr(cls, "MAX_KEY_SIZE", key);
            PyRuntime.classAddAttr(cls, "MAX_DIGEST_SIZE", key);
            PyRuntime.classAddAttr(cls, "__doc__", "Return a new BLAKE2" + (big ? "b" : "s") + " hash object.");
            noPickle(cls, true);
            finish(cls);
            seal(cls);
            String up = name.substring(0, 7).toUpperCase();
            m.attrs.put(up + "_SALT_SIZE", salt);
            m.attrs.put(up + "_PERSON_SIZE", salt);
            m.attrs.put(up + "_MAX_KEY_SIZE", key);
            m.attrs.put(up + "_MAX_DIGEST_SIZE", key);
            m.attrs.put(name, cls);
        }
        HashArgs.gilMinsize(m);
        return m;
    }

    private static B2 b2(Object self) { return state(self, B2.class, "_blake2 hash"); }

    private static byte[] buf(Map<Object, Object> rest, String key) {
        return rest.containsKey(key) ? IoUtil.bytesLike(rest.get(key)) : new byte[0];
    }

    public static Object b2New(Object cls, Object args, Object kwargs) {
        String name = (String) spec(cls);
        boolean big = name.equals("blake2b");
        Map<Object, Object> rest = new LinkedHashMap<>((PyDict) kwargs);
        Object data = HashArgs.dataArg(name, ((PyTuple) args).items, rest, 2L);
        for (Object k : rest.keySet()) {
            if (!B2_KEYS.contains(k)) throw err("TypeError", name + "() got an unexpected keyword argument '" + k + "'");
        }
        int max = big ? 64 : 32, saltMax = big ? 16 : 8;
        // clinic converts in parameter order before any range check
        int ds = rest.containsKey("digest_size") ? cint(rest.get("digest_size")) : max;
        byte[] key = buf(rest, "key"), salt = buf(rest, "salt"), person = buf(rest, "person");
        int fanout = rest.containsKey("fanout") ? cint(rest.get("fanout")) : 1;
        int depth = rest.containsKey("depth") ? cint(rest.get("depth")) : 1;
        BigInteger leaf = rest.containsKey("leaf_size")
            ? unsigned(rest.get("leaf_size"), WINDOWS ? 32 : 64, "leaf_size", "Python int too large to convert to C unsigned long", "Python int too large for C unsigned long")
            : BigInteger.ZERO;
        BigInteger nodeOff = rest.containsKey("node_offset")
            ? unsigned(rest.get("node_offset"), 64, "node_offset", "int too big to convert", "Python int too large for C unsigned long long")
            : BigInteger.ZERO;
        int nodeDepth = rest.containsKey("node_depth") ? cint(rest.get("node_depth")) : 0;
        int inner = rest.containsKey("inner_size") ? cint(rest.get("inner_size")) : 0;
        boolean last = rest.containsKey("last_node") && PyRuntime.truth(rest.get("last_node"));

        boolean l15 = l15();
        int[] order = l15 ? new int[]{0, 1, 2, 3, 4, 5, 7, 8, 9}
            : big ? new int[]{0, 2, 3, 4, 5, 7, 8, 1} : new int[]{0, 2, 3, 4, 5, 9, 7, 8, 1};
        for (int step : order) {
            switch (step) {
                case 0 -> {
                    if (ds < 1 || ds > max) {
                        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                        throw err("ValueError", l15 ? "digest_size for " + name + " must be between 1 and " + max + " bytes, got " + ds
                            : l14() ? "digest_size for " + cap + " must be between 1 and " + max + " bytes, here it is " + ds
                            : "digest_size must be between 1 and " + max + " bytes");
                    }
                }
                case 1 -> lenCheck("key", key.length, max);
                case 2 -> lenCheck("salt", salt.length, saltMax);
                case 3 -> lenCheck("person", person.length, saltMax);
                case 4 -> range("fanout", fanout, 0, 255);
                case 5 -> range("depth", depth, 1, 255);
                case 7 -> range("node_depth", nodeDepth, 0, 255);
                case 8 -> {
                    if (inner < 0 || inner > max) {
                        throw err("ValueError", l15 ? "'inner_size' must be between 0 and " + max : "inner_size must be between 0 and is " + max);
                    }
                }
                default -> {
                    if (!big && nodeOff.bitLength() > 48) throw err("OverflowError", (l15 ? "'node_offset'" : "node_offset") + " is too large");
                }
            }
        }
        if (leaf.bitLength() > 32) {
            throw err("OverflowError", l15 ? "'leaf_size' is too large" : "leaf_size is too large");
        }
        Blake2 h = new Blake2(big, ds, key, salt, person, fanout, depth, leaf.longValue(), nodeOff.longValue(), nodeDepth, inner, last);
        if (data != NativeArgs.MISSING) h.update(HashArgs.bytes(data));
        return newInstance((PyClass) cls, new B2(name, h));
    }

    private static void lenCheck(String what, int len, int max) {
        if (len > max) throw err("ValueError", "maximum " + what + " length is " + max + " bytes" + (l15() ? ", got " + len : ""));
    }

    private static void range(String what, int v, int lo, int hi) {
        if (v < lo || v > hi) throw err("ValueError", (l15() ? "'" + what + "'" : what) + " must be between " + lo + " and " + hi);
    }

    public static Object b2update(Object self, Object args, Object kwargs) {
        B2 s = b2(self);
        s.h.update(updateArg(s.name + ".update", (PyTuple) args, (PyDict) kwargs));
        return null;
    }

    public static Object b2copy(Object self, Object args, Object kwargs) {
        B2 s = b2(self);
        copyNoArgs(s.name + ".copy", (PyTuple) args, (PyDict) kwargs);
        return newInstance(((PyInstance) self).cls, new B2(s.name, s.h.copy()));
    }

    public static Object b2digest(Object self, Object args, Object kwargs) {
        B2 s = b2(self);
        noArgs(s.name + ".digest", (PyTuple) args, (PyDict) kwargs);
        return new PyBytes(s.h.digest());
    }

    public static Object b2hexdigest(Object self, Object args, Object kwargs) {
        B2 s = b2(self);
        noArgs(s.name + ".hexdigest", (PyTuple) args, (PyDict) kwargs);
        return HashArgs.hex(s.h.digest());
    }

    public static Object b2nameOf(Object self) { return b2(self).name; }
    public static Object b2digestSizeOf(Object self) { return (long) b2(self).h.outLen; }
    public static Object b2blockSizeOf(Object self) { return (long) b2(self).h.block; }
}
