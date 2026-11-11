// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The streaming engines behind {@code zlib.Compress}, {@code zlib.Decompress} and {@code zlib._ZlibDecompressor}.
 * They wrap {@link Deflater}/{@link Inflater} (always raw deflate for the gzip wrapper, which is framed here because
 * the JDK only understands zlib and raw streams). The JDK cannot clone a native zlib stream, so {@code copy()}
 * replays the recorded operations into a fresh stream; recording stops (and {@code copy()} fails) beyond
 * {@link #LOG_CAP} bytes of history.
 */
final class ZlibStreams {
    private ZlibStreams() {}

    static final byte[] EMPTY = new byte[0];
    static final long LOG_CAP = 8L << 20;
    private static final int CHUNK = 16384;

    static PyException zerr(int code, String msg, String op) {
        return ZlibModule.error("Error " + code + " while " + op + (msg != null ? ": " + msg : ""));
    }

    static byte[] cat(byte[] a, byte[] b) {
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static byte[] tail(byte[] a, int from) { return from == 0 ? a : Arrays.copyOfRange(a, from, a.length); }

    /** Records operations so that a stream can be copied by replay. */
    private static final class Log {
        ArrayList<Object[]> ops = new ArrayList<>();
        long bytes;
        void add(byte[] data, int kind) {
            if (ops == null) return;
            bytes += data.length;
            if (bytes > LOG_CAP) { ops = null; return; }
            ops.add(new Object[]{data, kind});
        }
    }

    // ---------------------------------------------------------------- compression

    static final class Comp {
        final int mode; // 0 zlib, 1 raw, 2 gzip
        final int level, strategy;
        final byte[] zdict;
        private Deflater d;
        private boolean headerDone, finished;
        private int crc;
        private long size;
        private final Log log = new Log();

        Comp(int mode, int level, int strategy, byte[] zdict) {
            this.mode = mode; this.level = level; this.strategy = strategy; this.zdict = zdict;
            d = new Deflater(level, mode != 0);
            d.setStrategy(strategy);
            if (zdict != null) {
                if (mode == 2) throw zerr(-2, "inconsistent stream state", "setting zdict");
                d.setDictionary(zdict);
            }
        }

        private void header(ByteArrayOutputStream out) {
            if (headerDone || mode != 2) return;
            headerDone = true;
            int xfl = level == 9 ? 2 : (strategy >= 2 || (level >= 0 && level < 2)) ? 4 : 0;
            out.write(new byte[]{0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, (byte) xfl, 3}, 0, 10);
        }

        private void pump(ByteArrayOutputStream out, int flush) {
            byte[] buf = new byte[CHUNK];
            int n;
            do { n = d.deflate(buf, 0, buf.length, flush); out.write(buf, 0, n); } while (n == buf.length);
        }

        synchronized byte[] compress(byte[] data) {
            if (finished) throw zerr(-2, "inconsistent stream state", "compressing data");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            header(out);
            if (mode == 2) { crc = ZlibModule.crc32(crc, data, 0, data.length); size += data.length; }
            d.setInput(data);
            pump(out, Deflater.NO_FLUSH);
            log.add(data, -1);
            return out.toByteArray();
        }

        synchronized byte[] flush(int zmode) {
            if (zmode == 0) return EMPTY;
            if (zmode == 5 || zmode == 6) throw ZlibModule.error("Z_BLOCK and Z_TREES flush modes are not supported by the JVM zlib");
            if (finished || zmode < 0 || zmode > 4) throw zerr(-2, "inconsistent stream state", "flushing");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            header(out);
            if (zmode == 4) {
                d.finish();
                pump(out, Deflater.NO_FLUSH);
                if (mode == 2) writeTrailer(out);
                finished = true;
                d.end();
            } else {
                pump(out, zmode == 3 ? Deflater.FULL_FLUSH : Deflater.SYNC_FLUSH);
            }
            log.add(EMPTY, zmode);
            return out.toByteArray();
        }

        private void writeTrailer(ByteArrayOutputStream out) {
            long[] v = {crc & 0xffffffffL, size & 0xffffffffL};
            for (long x : v) for (int i = 0; i < 4; i++) out.write((int) (x >>> (8 * i)) & 0xff);
        }

        synchronized Comp copy() {
            if (log.ops == null) throw zerr(-2, "stream has more than 8 MiB of history, which cannot be copied on the JVM", "copying compression object");
            Comp c = new Comp(mode, level, strategy, zdict);
            for (Object[] op : log.ops) {
                int kind = (Integer) op[1];
                if (kind < 0) c.compress((byte[]) op[0]); else c.flush(kind);
            }
            return c;
        }
    }

    // ---------------------------------------------------------------- decompression

    private static final int HEADER = 0, BODY = 1, TRAILER = 2, DONE = 3;

    static final class Decomp {
        final int wbits;
        final byte[] zdict;
        private int mode; // 0 zlib, 1 raw, 2 gzip, 3 auto-detect
        private int phase;
        private Inflater inf;
        private byte[] hold = EMPTY;
        private int crc;
        private long total;
        boolean eof, limitHit, keepTail;
        byte[] unused = EMPTY, tail = EMPTY;
        private final Log log = new Log();

        Decomp(int wbits, byte[] zdict) {
            this.wbits = wbits; this.zdict = zdict;
            int wrap = wbits < 0 ? 0 : (wbits >> 4) + 5;
            mode = wrap == 0 ? 1 : wrap == 5 ? 0 : wrap == 6 ? 2 : 3;
            if (mode <= 1) { open(); phase = BODY; } else phase = HEADER;
        }

        private void open() {
            inf = new Inflater(mode == 1 || mode == 2);
            if (mode == 1 && zdict != null) {
                try { inf.setDictionary(zdict); }
                catch (IllegalArgumentException e) { throw zerr(-3, "invalid input data", "setting zdict"); }
            }
        }

        synchronized byte[] run(byte[] data, int limit, String op) {
            // CPython 3.15 leaves the leftover input in unconsumed_tail when the stream ends in a call that follows a limited one.
            keepTail = tail.length > 0 && LangVersion.atLeast(3, 15);
            limitHit = false; tail = EMPTY;
            if (phase == DONE) { unused = cat(unused, data); log.add(data, limit); return EMPTY; }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] rest = data;
            if (phase == HEADER) rest = header(data, op);
            if (rest != null && phase == BODY) rest = body(rest, limit, out, op);
            if (rest != null && phase == TRAILER) trailer(rest, op);
            log.add(data, limit);
            return out.toByteArray();
        }

        private void done(byte[] rest) {
            phase = DONE; eof = true; unused = cat(unused, rest);
            if (keepTail) tail = rest;
        }

        private byte[] header(byte[] data, String op) {
            byte[] b = cat(hold, data);
            if (mode == 3) {
                if (b.length < 2) { hold = b; return null; }
                mode = (b[0] == 0x1f && (b[1] & 0xff) == 0x8b) ? 2 : 0;
            }
            if (mode == 2) {
                int hl = gzipHeader(b, op);
                if (hl < 0) { hold = b; return null; }
                b = tail(b, hl);
            }
            hold = EMPTY;
            open();
            phase = BODY;
            return b;
        }

        private static PyException bad(String msg, String op) { return zerr(-3, msg, op); }

        private int gzipHeader(byte[] b, String op) {
            if (b.length >= 1 && b[0] != 0x1f) throw bad("incorrect header check", op);
            if (b.length >= 2 && (b[1] & 0xff) != 0x8b) throw bad("incorrect header check", op);
            if (b.length >= 3 && b[2] != 8) throw bad("unknown compression method", op);
            if (b.length >= 4 && (b[3] & 0xe0) != 0) throw bad("unknown header flags set", op);
            if (b.length < 10) return -1;
            int flg = b[3], pos = 10;
            if ((flg & 4) != 0) {
                if (b.length < pos + 2) return -1;
                pos += 2 + ((b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8));
                if (b.length < pos) return -1;
            }
            for (int bit : new int[]{8, 16}) {
                if ((flg & bit) == 0) continue;
                while (true) { if (pos >= b.length) return -1; if (b[pos++] == 0) break; }
            }
            if ((flg & 2) != 0) {
                if (b.length < pos + 2) return -1;
                int want = (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8);
                if ((ZlibModule.crc32(0, b, 0, pos) & 0xffff) != want) throw bad("header crc mismatch", op);
                pos += 2;
            }
            return pos;
        }

        private void setDict(String op) {
            if (zdict == null) throw zerr(2, null, op);
            try { inf.setDictionary(zdict); }
            catch (IllegalArgumentException e) { throw zerr(-3, "invalid input data", "setting zdict"); }
        }

        /** Inflates {@code in}; returns the bytes after the end of the deflate stream, or null when it has not ended. */
        private byte[] body(byte[] in, int limit, ByteArrayOutputStream out, String op) {
            inf.setInput(in);
            byte[] buf = new byte[CHUNK];
            try {
                while (!inf.finished()) {
                    int room = limit < 0 ? buf.length : Math.min(buf.length, limit - out.size());
                    if (room <= 0) { limitHit = true; break; }
                    int n = inf.inflate(buf, 0, room);
                    if (n > 0) {
                        out.write(buf, 0, n);
                        if (mode == 2) { crc = ZlibModule.crc32(crc, buf, 0, n); total += n; }
                    } else if (inf.needsDictionary()) setDict(op);
                    else break;
                }
            } catch (DataFormatException e) {
                throw zerr(-3, e.getMessage(), op);
            }
            byte[] rest = tail(in, in.length - inf.getRemaining());
            if (inf.finished()) {
                inf.end();
                if (mode == 2) phase = TRAILER; else done(rest);
                return mode == 2 ? rest : null;
            }
            if (limitHit) tail = rest;
            return null;
        }

        private void trailer(byte[] data, String op) {
            byte[] b = cat(hold, data);
            if (b.length < 8) { hold = b; return; }
            hold = EMPTY;
            long wantCrc = le32(b, 0), wantLen = le32(b, 4);
            if (wantCrc != (crc & 0xffffffffL)) throw bad("incorrect data check", op);
            if (wantLen != (total & 0xffffffffL)) throw bad("incorrect length check", op);
            done(tail(b, 8));
        }

        private static long le32(byte[] b, int o) {
            return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
        }

        /** {@code Decompress.flush()}: feeds the unconsumed tail to the stream without an output limit. */
        synchronized byte[] flush() {
            byte[] t = tail;
            byte[] r = run(t, -1, "flushing");
            tail = EMPTY;
            return r;
        }

        synchronized Decomp copy() {
            if (log.ops == null) throw zerr(-2, "stream has more than 8 MiB of history, which cannot be copied on the JVM", "copying decompression object");
            Decomp c = new Decomp(wbits, zdict);
            for (Object[] op : log.ops) c.run((byte[]) op[0], (Integer) op[1], "decompressing data");
            return c;
        }
    }
}
