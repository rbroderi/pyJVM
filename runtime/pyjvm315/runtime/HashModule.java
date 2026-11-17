// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The builtin digest modules {@code _md5}, {@code _sha1} and {@code _sha2} (hashlib's fallbacks when OpenSSL's
 * {@code _hashlib} is absent), backed by {@link MessageDigest}. {@code _sha3} and {@code _blake2} are in {@link Hash3Module}.
 */
final class HashModule {
    private HashModule() {}

    private static final class Algo {
        final String javaName, pyName; final int digestSize, blockSize; PyClass cls;
        Algo(String pyName, String javaName, int digestSize, int blockSize) { this.pyName = pyName; this.javaName = javaName; this.digestSize = digestSize; this.blockSize = blockSize; }
    }

    private static final Map<String, Algo> ALGOS = new HashMap<>();
    private static final String ME = HashModule.class.getName();

    static {
        add(new Algo("md5", "MD5", 16, 64));
        add(new Algo("sha1", "SHA-1", 20, 64));
        add(new Algo("sha224", "SHA-224", 28, 64));
        add(new Algo("sha256", "SHA-256", 32, 64));
        add(new Algo("sha384", "SHA-384", 48, 128));
        add(new Algo("sha512", "SHA-512", 64, 128));
    }

    private static void add(Algo a) { ALGOS.put(a.pyName, a); }

    private static final class St { final Algo algo; MessageDigest md; St(Algo a, MessageDigest md) { this.algo = a; this.md = md; } }

    static PyModule create(String module, String... names) {
        PyModule m = new PyModule(module, ME);
        for (String n : names) {
            Algo a = ALGOS.get(n);
            String typeName = module.equals("_md5") ? "md5" : module.equals("_sha1") ? "sha1" : "SHA" + n.substring(3) + "Type";
            PyClass cls = define(module, typeName, null);
            a.cls = cls;
            Class<?> c = HashModule.class;
            method(cls, c, "update", "update", "self,obj");
            method(cls, c, "digest", "digest", "self");
            method(cls, c, "hexdigest", "hexdigest", "self");
            method(cls, c, "copy", "copy", "self");
            PyRuntime.classAddProperty(cls, "name", ME, "nameOf", null);
            PyRuntime.classAddProperty(cls, "digest_size", ME, "digestSizeOf", null);
            PyRuntime.classAddProperty(cls, "block_size", ME, "blockSizeOf", null);
            noPickle(cls, false);
            finish(cls);
            seal(cls);
            m.attrs.put(typeName, cls);
            fn(m, n, (args, kw) -> construct(a, n, args, kw));
        }
        HashArgs.gilMinsize(m);
        return m;
    }

    private static Object construct(Algo a, String fname, List<Object> args, Map<Object, Object> kw) {
        Map<Object, Object> rest = new java.util.LinkedHashMap<>(kw);
        Object data = HashArgs.dataArg(fname, args, rest);
        HashArgs.noUnknown(fname, rest);
        PyInstance inst = newInstance(a.cls, new St(a, digest(a)));
        if (data != NativeArgs.MISSING) update(inst, data);
        return inst;
    }

    private static MessageDigest digest(Algo a) {
        try { return MessageDigest.getInstance(a.javaName); }
        catch (java.security.NoSuchAlgorithmException e) { throw new PyException("ValueError", "unsupported hash type " + a.pyName); }
    }

    private static St st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof St s) return s;
        throw new PyException("TypeError", "descriptor requires a hash object but received '" + PyRuntime.typeName(self) + "'");
    }

    public static Object update(Object self, Object obj) {
        St s = st(self);
        s.md.update(HashArgs.bytes(obj));
        return null;
    }

    private static byte[] snapshot(St s) {
        try { return ((MessageDigest) s.md.clone()).digest(); }
        catch (CloneNotSupportedException e) { throw new PyException("RuntimeError", "digest state cannot be copied"); }
    }

    public static Object digest(Object self) { return new PyBytes(snapshot(st(self))); }

    public static Object hexdigest(Object self) {
        byte[] d = snapshot(st(self));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) { sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16)); }
        return sb.toString();
    }

    public static Object copy(Object self) {
        St s = st(self);
        try { return newInstance(s.algo.cls, new St(s.algo, (MessageDigest) s.md.clone())); }
        catch (CloneNotSupportedException e) { throw new PyException("RuntimeError", "digest state cannot be copied"); }
    }

    public static Object nameOf(Object self) { return st(self).algo.pyName; }
    public static Object digestSizeOf(Object self) { return (long) st(self).algo.digestSize; }
    public static Object blockSizeOf(Object self) { return (long) st(self).algo.blockSize; }
}
