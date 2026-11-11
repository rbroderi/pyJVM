// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * The file-descriptor table behind {@code posix.open/read/write/...} and {@code _io.FileIO}. Descriptors 0-2 are the
 * process streams; every other descriptor is a FileChannel (or a directory). {@code dup} shares the entry, so the
 * offset is shared as on a real descriptor.
 */
final class IoFd {
    private IoFd() {}

    static final class Fd {
        FileChannel ch; boolean append; int refs = 1; String path; boolean dir; int std = -1; boolean readable, writable;
    }

    private static final TreeMap<Integer,Fd> TABLE = new TreeMap<>();
    private static final FileInputStream STDIN = new FileInputStream(FileDescriptor.in);
    private static final FileOutputStream STDOUT = new FileOutputStream(FileDescriptor.out), STDERR = new FileOutputStream(FileDescriptor.err);

    static {
        for (int i = 0; i < 3; i++) { Fd f = new Fd(); f.std = i; f.readable = i == 0; f.writable = i != 0; TABLE.put(i, f); }
    }

    static PyException badFd() { return PosixModule.osError("OSError", 9, "Bad file descriptor", null); }

    static synchronized Fd get(long fd) {
        Fd f = fd < 0 || fd > Integer.MAX_VALUE ? null : TABLE.get((int) fd);
        if (f == null) throw badFd();
        return f;
    }

    static boolean valid(long fd) { synchronized (IoFd.class) { return fd >= 0 && fd <= Integer.MAX_VALUE && TABLE.containsKey((int) fd); } }

    private static synchronized long register(Fd f) {
        int n = 3;
        while (TABLE.containsKey(n)) n++;
        TABLE.put(n, f);
        return n;
    }

    static long open(String path, long flags, long mode) {
        IoUtil.syncAll();
        Set<StandardOpenOption> opts = EnumSet.noneOf(StandardOpenOption.class);
        long access = flags & 3;
        Fd f = new Fd();
        f.readable = access == 0 || access == 2;
        f.writable = access == 1 || access == 2;
        if (f.readable) opts.add(StandardOpenOption.READ);
        if (f.writable) opts.add(StandardOpenOption.WRITE);
        if ((flags & 0100) != 0) opts.add((flags & 0200) != 0 ? StandardOpenOption.CREATE_NEW : StandardOpenOption.CREATE);
        if ((flags & 01000) != 0 && f.writable) opts.add(StandardOpenOption.TRUNCATE_EXISTING);
        f.append = (flags & 02000) != 0;
        f.path = path;
        Path p = PosixModule.resolve(path);
        try {
            if (Files.isDirectory(p)) {
                if (f.writable) throw PosixModule.osError("IsADirectoryError", 21, "Is a directory", path);
                f.dir = true;
            } else {
                if ((flags & 0200000) != 0) throw PosixModule.osError("NotADirectoryError", 20, "Not a directory", path);
                if ((flags & 0100) != 0 && !Files.exists(p)) {
                    Set<PosixFilePermission> perms = permissions(mode & ~umask());
                    f.ch = FileChannel.open(p, opts, PosixFilePermissions.asFileAttribute(perms));
                } else {
                    if (!f.writable && f.readable) opts.remove(StandardOpenOption.CREATE);
                    f.ch = FileChannel.open(p, opts);
                }
            }
        } catch (IOException e) {
            throw PosixModule.osError(e, path);
        } catch (UnsupportedOperationException e) {
            try { f.ch = FileChannel.open(p, opts); } catch (IOException e2) { throw PosixModule.osError(e2, path); }
        }
        return register(f);
    }

    private static long umask = -1;
    static long umask() {
        if (umask < 0) {
            long m = 022;
            try {
                for (String l : Files.readAllLines(Path.of("/proc/self/status"))) if (l.startsWith("Umask:")) m = Long.parseLong(l.substring(6).trim(), 8);
            } catch (IOException | RuntimeException ignored) { }
            umask = m;
        }
        return umask;
    }

    static Set<PosixFilePermission> permissions(long mode) {
        Set<PosixFilePermission> s = new HashSet<>();
        PosixFilePermission[] all = {PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};
        for (int i = 0; i < 9; i++) if ((mode & (1L << i)) != 0) s.add(all[i]);
        return s;
    }

    static void close(long fd) {
        Fd f;
        synchronized (IoFd.class) {
            f = TABLE.get((int) fd);
            if (f == null) throw badFd();
            if (fd <= 2 && f.std >= 0 && f.refs <= 1) return;       // the process streams stay open
            if (--f.refs > 0) { TABLE.remove((int) fd); return; }
            TABLE.remove((int) fd);
        }
        if (f.ch != null) try { f.ch.close(); } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    static long dup(long fd) {
        Fd f = get(fd);
        synchronized (IoFd.class) { f.refs++; }
        return register(f);
    }

    /** Up to {@code n} bytes; an empty array at end of file. */
    static byte[] read(long fd, int n) {
        Fd f = get(fd);
        if (n <= 0) return new byte[0];
        try {
            if (f.std == 0) {
                byte[] b = new byte[n];
                int r = STDIN.read(b, 0, n);
                return r <= 0 ? new byte[0] : r == n ? b : java.util.Arrays.copyOf(b, r);
            }
            if (f.dir) throw PosixModule.osError("IsADirectoryError", 21, "Is a directory", null);
            if (f.ch == null || !f.readable) throw badFd();
            ByteBuffer buf = ByteBuffer.allocate(n);
            int got = 0;
            while (got < n) { int r = f.ch.read(buf); if (r <= 0) break; got += r; }
            return got == n ? buf.array() : java.util.Arrays.copyOf(buf.array(), got);
        } catch (NonReadableChannelException e) {
            throw badFd();
        } catch (IOException e) {
            throw PosixModule.osError(e, null);
        }
    }

    static int write(long fd, byte[] b, int off, int len) {
        Fd f = get(fd);
        try {
            if (f.std == 1 || f.std == 2) {
                FileOutputStream o = f.std == 1 ? STDOUT : STDERR;
                o.write(b, off, len);
                return len;
            }
            if (f.ch == null || !f.writable) throw badFd();
            if (f.append) f.ch.position(f.ch.size());
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            while (buf.hasRemaining()) f.ch.write(buf);
            return len;
        } catch (NonWritableChannelException e) {
            throw badFd();
        } catch (IOException e) {
            throw PosixModule.osError(e, null);
        }
    }

    static long seek(long fd, long pos, int how) {
        Fd f = get(fd);
        if (f.ch == null) {
            if (f.dir) return 0;
            throw PosixModule.osError("OSError", 29, "Illegal seek", null);
        }
        try {
            long target = how == 0 ? pos : how == 1 ? f.ch.position() + pos : how == 2 ? f.ch.size() + pos : -1;
            if (how < 0 || how > 2 || target < 0) throw PosixModule.osError("OSError", 22, "Invalid argument", null);
            f.ch.position(target);
            return target;
        } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    static long tell(long fd) { return seek(fd, 0, 1); }

    static boolean seekable(long fd) { return get(fd).ch != null; }

    static long size(long fd) {
        Fd f = get(fd);
        try { return f.ch == null ? 0 : f.ch.size(); } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    static void truncate(long fd, long size) {
        Fd f = get(fd);
        if (size < 0) throw PosixModule.osError("OSError", 22, "Invalid argument", null);
        if (f.ch == null || !f.writable) throw badFd();
        try {
            long cur = f.ch.size();
            if (size < cur) { long pos = f.ch.position(); f.ch.truncate(size); f.ch.position(pos); }
            else if (size > cur) { long pos = f.ch.position(); f.ch.write(ByteBuffer.wrap(new byte[]{0}), size - 1); f.ch.position(pos); }
        } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    static void sync(long fd) {
        Fd f = get(fd);
        try { if (f.ch != null && f.writable) f.ch.force(true); } catch (IOException e) { throw PosixModule.osError(e, null); }
    }

    static boolean isatty(long fd) {
        Fd f = get(fd);
        String target = null;
        if (f.std >= 0) {
            try { target = Files.readSymbolicLink(Path.of("/proc/self/fd/" + f.std)).toString(); } catch (IOException | RuntimeException e) { return false; }
        } else target = f.path;
        return target != null && (target.startsWith("/dev/pts/") || target.startsWith("/dev/tty") || target.equals("/dev/console"));
    }

    static void flushProcessStreams() { try { STDOUT.flush(); STDERR.flush(); } catch (IOException ignored) { } }
}
