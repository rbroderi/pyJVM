// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/** {@code _random}: the Mersenne Twister (MT19937) core of {@code random.Random}, bit-exact with CPython. */
final class RandomModule {
    private RandomModule() {}

    private static final int N = 624, M = 397;
    private static PyClass randomClass;
    private static final SecureRandom ENTROPY = new SecureRandom();

    static final class Mt {
        final int[] mt = new int[N];
        int index = N + 1;

        void initGenrand(int s) {
            mt[0] = s;
            for (int i = 1; i < N; i++) mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            index = N;
        }

        void initByArray(int[] key) {
            initGenrand(19650218);
            int i = 1, j = 0;
            for (int k = Math.max(N, key.length); k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
                i++; j++;
                if (i >= N) { mt[0] = mt[N - 1]; i = 1; }
                if (j >= key.length) j = 0;
            }
            for (int k = N - 1; k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
                i++;
                if (i >= N) { mt[0] = mt[N - 1]; i = 1; }
            }
            mt[0] = 0x80000000;
            index = N;
        }

        int next() {
            if (index >= N) {
                int kk;
                int y;
                for (kk = 0; kk < N - M; kk++) {
                    y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                    mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                for (; kk < N - 1; kk++) {
                    y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                    mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                y = (mt[N - 1] & 0x80000000) | (mt[0] & 0x7fffffff);
                mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                index = 0;
            }
            int y = mt[index++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            return y;
        }
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_random", "pyjvm315.runtime.RandomModule");
        Class<?> c = RandomModule.class;
        randomClass = define("_random", "Random", null);
        PyRuntime.classAddMethodEx(randomClass, "__new__", c, "rNew", "static", "", "cls", "", "args", "kwargs", new PyDict(), "<native>", 0L);
        PyRuntime.classAddAttr(randomClass, "__new__", randomClass.methods.get("__new__").function);
        method(randomClass, c, "seed", "seed", "self,n", defaults("n", null));
        method(randomClass, c, "random", "random", "self");
        method(randomClass, c, "getrandbits", "getrandbits", "self,k");
        method(randomClass, c, "getstate", "getstate", "self");
        method(randomClass, c, "setstate", "setstate", "self,state");
        PyRuntime.setattr(randomClass, "__doc__", "Random() -> create a random number generator with its own internal state.");
        finish(randomClass);
        m.attrs.put("Random", randomClass);
        m.attrs.put("__doc__", "Module implements the Mersenne Twister random number generator.");
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Object rNew(Object cls, Object args, Object kwargs) {
        List<Object> a = ((PyTuple) args).items;
        Map<Object, Object> k = (Map<Object, Object>) kwargs;
        PyClass pc = (PyClass) cls;
        if (pc == randomClass && !k.isEmpty()) throw new PyException("TypeError", "Random() takes no keyword arguments");
        if (a.size() > 1) throw new PyException("TypeError", (LangVersion.atLeast(3, 14) ? "Random expected at most 1 argument, got " + a.size() : "Random() requires 0 or 1 argument"));
        PyInstance inst = newInstance(pc, new Mt());
        seedWith(inst, a.isEmpty() ? null : a.get(0));
        return inst;
    }

    private static Mt st(Object self) {
        if (self instanceof PyInstance i && i.slotValues.get(STATE) instanceof Mt s) return s;
        throw new PyException("TypeError", "descriptor requires a '_random.Random' object but received '" + PyRuntime.typeName(self) + "'");
    }

    private static void seedWith(Object self, Object arg) {
        Mt s = st(self);
        BigInteger n;
        if (arg == null) {
            byte[] b = new byte[64];
            ENTROPY.nextBytes(b);
            n = new BigInteger(1, b);
        } else if (PyRuntime.isIntLike(arg)) {
            n = PyRuntime.bigInt(arg).abs();
        } else {
            Object h = PyRuntime.hash(arg);
            n = PyRuntime.bigInt(h).abs();
        }
        int bits = n.bitLength();
        int len = bits == 0 ? 1 : (bits - 1) / 32 + 1;
        int[] key = new int[len];
        for (int i = 0; i < len; i++) key[i] = n.shiftRight(32 * i).intValue();
        s.initByArray(key);
    }

    public static Object seed(Object self, Object n) { st(self); seedWith(self, n); return null; }

    public static Object random(Object self) {
        Mt s = st(self);
        long a = (s.next() & 0xffffffffL) >>> 5, b = (s.next() & 0xffffffffL) >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    public static Object getrandbits(Object self, Object kObj) {
        Mt s = st(self);
        BigInteger kb = BuiltinsExtra.rangeIndex(kObj);
        if (kb.signum() < 0) throw new PyException("ValueError", LangVersion.atLeast(3, 14) ? "Cannot convert negative int" : "number of bits must be non-negative");
        if (kb.bitLength() > 31) throw new PyException("OverflowError", LangVersion.atLeast(3, 14) ? "Python int too large for C uint64_t" : "int too big to convert");
        int k = kb.intValue();
        if (k == 0) return 0L;
        if (k <= 32) return (long) ((s.next() & 0xffffffffL) >>> (32 - k));
        int words = (k - 1) / 32 + 1;
        byte[] be = new byte[words * 4];
        int rem = k;
        for (int i = 0; i < words; i++, rem -= 32) {
            long w = s.next() & 0xffffffffL;
            if (rem < 32) w >>>= 32 - rem;
            int off = be.length - 4 * (i + 1);
            be[off] = (byte) (w >>> 24); be[off + 1] = (byte) (w >>> 16); be[off + 2] = (byte) (w >>> 8); be[off + 3] = (byte) w;
        }
        return PyRuntime.compact(new BigInteger(1, be));
    }

    public static Object getstate(Object self) {
        Mt s = st(self);
        PyTuple t = new PyTuple();
        for (int v : s.mt) t.items.add(v & 0xffffffffL);
        t.items.add((long) s.index);
        return t;
    }

    public static Object setstate(Object self, Object state) {
        Mt s = st(self);
        if (!(state instanceof PyTuple t)) throw new PyException("TypeError", "state vector must be a tuple");
        if (t.items.size() != N + 1) throw new PyException("ValueError", "state vector is the wrong size");
        int[] tmp = new int[N];
        for (int i = 0; i < N; i++) {
            Object o = t.items.get(i);
            if (!PyRuntime.isIntLike(o)) throw new PyException("TypeError", "an integer is required");
            BigInteger v = PyRuntime.bigInt(o);
            if (v.signum() < 0) throw new PyException("OverflowError", "can't convert negative value to unsigned int");
            if (v.bitLength() > 64) throw new PyException("OverflowError", "Python int too large to convert to C unsigned long");
            tmp[i] = v.intValue();
        }
        Object io = t.items.get(N);
        if (!PyRuntime.isIntLike(io)) throw new PyException("TypeError", "an integer is required");
        BigInteger idx = PyRuntime.bigInt(io);
        if (idx.signum() < 0 || idx.compareTo(BigInteger.valueOf(N)) > 0) throw new PyException("ValueError", "invalid state");
        System.arraycopy(tmp, 0, s.mt, 0, N);
        s.index = idx.intValue();
        return null;
    }
}
