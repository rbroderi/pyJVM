// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.util.List;
import java.util.Map;

/**
 * Audit events (PEP 578) the runtime raises itself, with the argument shapes CPython 3.13 and 3.15 use:
 * the {@code os.*} file-system events of the posix / nt functions pyJVM implements, plus {@code open} for
 * {@code os.open}. Events raised from other places ({@code open}, {@code compile}, {@code exec}, {@code import},
 * {@code sys.*}, {@code marshal.*}, {@code time.sleep}) call {@link SysExtras#audit} at the operation itself.
 */
final class AuditEvents {
    private AuditEvents() {}

    /** One audited os function: the event name, how many leading arguments it needs, and how to build the event arguments. */
    private interface Args { Object[] of(List<Object> a, Map<Object,Object> k); }

    private static Object arg(List<Object> a, Map<Object,Object> k, int index, String name) {
        return index < a.size() ? a.get(index) : k.get(name);
    }

    /** A dir_fd argument as CPython reports it: -1 when absent or None. */
    private static Object dirFd(List<Object> a, Map<Object,Object> k, int index, String name) {
        Object v = arg(a, k, index, name);
        return v == null ? (Object) (-1L) : v;
    }

    private static void wrap(PyModule m, String function, String event, int required, Args args) {
        if (!(m.attrs.get(function) instanceof NativeFunction f)) return;
        m.attrs.put(function, new NativeFunction(function, (a, k) -> {
            if (SysExtras.auditing() && a.size() >= required) SysExtras.audit(event, args.of(a, k));
            return f.body.call(a, k);
        }));
    }

    /** Wrap the already registered functions of posix / nt so that calling them raises their audit events. */
    static void install(PyModule m) {
        Args path = (a, k) -> new Object[]{arg(a, k, 0, "path")};
        Args optionalPath = (a, k) -> new Object[]{arg(a, k, 0, "path")};
        wrap(m, "listdir", "os.listdir", 0, optionalPath);
        wrap(m, "scandir", "os.scandir", 0, optionalPath);
        wrap(m, "chdir", "os.chdir", 1, path);
        wrap(m, "mkdir", "os.mkdir", 1, (a, k) -> new Object[]{arg(a, k, 0, "path"),
            arg(a, k, 1, "mode") == null ? (Object) 511L : arg(a, k, 1, "mode"), dirFd(a, k, 2, "dir_fd")});
        wrap(m, "rmdir", "os.rmdir", 1, (a, k) -> new Object[]{arg(a, k, 0, "path"), dirFd(a, k, 1, "dir_fd")});
        wrap(m, "remove", "os.remove", 1, (a, k) -> new Object[]{arg(a, k, 0, "path"), dirFd(a, k, 1, "dir_fd")});
        wrap(m, "unlink", "os.remove", 1, (a, k) -> new Object[]{arg(a, k, 0, "path"), dirFd(a, k, 1, "dir_fd")});
        Args rename = (a, k) -> new Object[]{arg(a, k, 0, "src"), arg(a, k, 1, "dst"), dirFd(a, k, 2, "src_dir_fd"), dirFd(a, k, 3, "dst_dir_fd")};
        wrap(m, "rename", "os.rename", 2, rename);
        wrap(m, "replace", "os.rename", 2, rename);
        wrap(m, "chmod", "os.chmod", 2, (a, k) -> new Object[]{arg(a, k, 0, "path"), arg(a, k, 1, "mode"), dirFd(a, k, 2, "dir_fd")});
        wrap(m, "utime", "os.utime", 1, (a, k) -> new Object[]{arg(a, k, 0, "path"), arg(a, k, 1, "times"), k.get("ns"), dirFd(a, k, 99, "dir_fd")});
        wrap(m, "link", "os.link", 2, (a, k) -> new Object[]{arg(a, k, 0, "src"), arg(a, k, 1, "dst"), dirFd(a, k, 2, "src_dir_fd"), dirFd(a, k, 3, "dst_dir_fd")});
        wrap(m, "symlink", "os.symlink", 2, (a, k) -> new Object[]{arg(a, k, 0, "src"), arg(a, k, 1, "dst"), dirFd(a, k, 99, "dir_fd")});
        wrap(m, "kill", "os.kill", 2, (a, k) -> new Object[]{a.get(0), a.get(1)});
        // os.open adds O_NOINHERIT (Windows) or O_CLOEXEC before it raises the event
        wrap(m, "open", "open", 2, (a, k) -> {
            Object flags = arg(a, k, 1, "flags");
            long extra = isWindows() ? 0x80L : 02000000L;
            return new Object[]{arg(a, k, 0, "path"), null, flags instanceof Number n ? (Object) (n.longValue() | extra) : flags};
        });
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    /** The O_* flags of the platform for a FileIO open, as CPython passes them in the "open" event. */
    static long platformOpenFlags(long linuxFlags) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return linuxFlags | 02000000L;   // O_CLOEXEC
        long flags = linuxFlags & 3;
        if ((linuxFlags & 02000) != 0) flags |= 8;        // O_APPEND
        if ((linuxFlags & 0100) != 0) flags |= 256;       // O_CREAT
        if ((linuxFlags & 01000) != 0) flags |= 512;      // O_TRUNC
        if ((linuxFlags & 0200) != 0) flags |= 1024;      // O_EXCL
        return flags | 0x8000 | 0x80;                     // O_BINARY | O_NOINHERIT
    }
}
