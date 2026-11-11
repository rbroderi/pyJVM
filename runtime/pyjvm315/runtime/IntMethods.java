package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Methods of int/bool receivers and the static int.from_bytes. */
final class IntMethods {
    private IntMethods() {}

    static final Set<String> NAMES = Set.of("bit_length", "bit_count", "to_bytes", "conjugate", "is_integer", "as_integer_ratio");
    static final Map<String, List<String>> PARAMS = Map.of("to_bytes", List.of("length", "byteorder", "signed"));
    static final Map<String, List<String>> STATIC_PARAMS = Map.of("from_bytes", List.of("bytes", "byteorder", "signed"));

    static Object call(Object obj, String name, Object[] args) {
        BigInteger v = bigInt(obj);
        return switch (name) {
            case "bit_length" -> { requireArgs(name, args, 0); yield (long) v.abs().bitLength(); }
            case "bit_count" -> { requireArgs(name, args, 0); yield (long) v.abs().bitCount(); }
            case "conjugate" -> { requireArgs(name, args, 0); yield obj instanceof Boolean ? (Object) (v.signum() != 0 ? 1L : 0L) : obj; }
            case "is_integer" -> { requireArgs(name, args, 0); yield true; }
            case "as_integer_ratio" -> { requireArgs(name, args, 0); PyTuple t = new PyTuple(); t.items.add(compact(v)); t.items.add(1L); yield t; }
            case "to_bytes" -> toBytes(v, args);
            default -> throw new PyException("AttributeError", "'int' object has no attribute '" + name + "'");
        };
    }

    private static boolean little(Object order) {
        if (!(order instanceof String s) || !(s.equals("little") || s.equals("big"))) throw new PyException("ValueError", "byteorder must be either 'little' or 'big'");
        return s.equals("little");
    }

    private static Object toBytes(BigInteger v, Object[] args) {
        long length = args.length > 0 && args[0] != null ? asIndex(args[0]) : 1;
        boolean little = args.length > 1 && args[1] != null ? little(args[1]) : false;
        if (args.length > 1 && args[1] == null) little(null);
        boolean signed = args.length > 2 && truth(args[2]);
        if (length < 0) throw new PyException("ValueError", "length argument must be non-negative");
        if (v.signum() < 0 && !signed) throw new PyException("OverflowError", "can't convert negative int to unsigned");
        byte[] min = v.toByteArray();                  // minimal two's complement, big-endian
        int significant = signed ? min.length : (min.length > 1 && min[0] == 0 ? min.length - 1 : min.length);
        if (v.signum() == 0) significant = 0;
        if (significant > length) throw new PyException("OverflowError", "int too big to convert");
        byte[] out = new byte[(int) length];
        byte fill = (byte) (v.signum() < 0 ? 0xFF : 0);
        java.util.Arrays.fill(out, fill);
        int copy = Math.min(min.length, out.length);
        System.arraycopy(min, min.length - copy, out, out.length - copy, copy);
        if (little) for (int i = 0, j = out.length - 1; i < j; i++, j--) { byte t = out[i]; out[i] = out[j]; out[j] = t; }
        return new PyBytes(out);
    }

    /** int.from_bytes(bytes, byteorder, *, signed=False). */
    static Object fromBytes(Object[] args) {
        if (args.length < 2) throw new PyException("TypeError", "from_bytes() missing required argument 'byteorder' (pos 2)");
        boolean signed = args.length > 2 && truth(args[2]);
        byte[] data;
        if (args[0] instanceof PyBytes b) data = b.data;
        else if (args[0] instanceof PyByteArray ba) data = ba.toByteArray();
        else {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            for (Object item : iterable(args[0])) {
                long x = asIndex(item);
                if (x < 0 || x > 255) throw new PyException("ValueError", "bytes must be in range(0, 256)");
                bos.write((int) x);
            }
            data = bos.toByteArray();
        }
        data = data.clone();
        if (little(args[1])) for (int i = 0, j = data.length - 1; i < j; i++, j--) { byte t = data[i]; data[i] = data[j]; data[j] = t; }
        if (data.length == 0) return 0L;
        return compact(signed ? new BigInteger(data) : new BigInteger(1, data));
    }
}
