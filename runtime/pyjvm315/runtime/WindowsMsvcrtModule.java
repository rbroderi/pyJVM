// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;

/**
 * {@code msvcrt}: the part of the C runtime module that bridges descriptors and Win32 handles ({@code get_osfhandle},
 * {@code open_osfhandle}), {@code setmode} (which _pyio imports) and the error-mode calls. {@link IoFd} descriptors are
 * not CRT descriptors, so the bridge is its own: see {@link IoFd#osHandle} and {@link IoFd#openHandle}. The console and
 * locking functions ({@code getch}, {@code kbhit}, {@code putwch}, {@code locking}, ...) and {@code heapmin} are absent.
 */
final class WindowsMsvcrtModule {
    private WindowsMsvcrtModule() {}

    private static final long O_TEXT = 0x4000, O_BINARY = 0x8000;

    static synchronized PyModule create() {
        PyModule m = new PyModule("msvcrt", WindowsMsvcrtModule.class.getName());
        for (Object[] c : new Object[][]{{"LK_UNLCK", 0L}, {"LK_LOCK", 1L}, {"LK_NBLCK", 2L}, {"LK_RLCK", 3L}, {"LK_NBRLCK", 4L},
                {"SEM_FAILCRITICALERRORS", 1L}, {"SEM_NOGPFAULTERRORBOX", 2L}, {"SEM_NOALIGNMENTFAULTEXCEPT", 4L}, {"SEM_NOOPENFILEERRORBOX", 32768L}})
            m.attrs.put((String) c[0], c[1]);
        fn(m, "get_osfhandle", (a, k) -> unsigned(IoFd.osHandle(IoUtil.asLong(a.get(0)))));
        fn(m, "open_osfhandle", (a, k) -> openOsfhandle(a.get(0), IoUtil.asLong(a.get(1))));
        fn(m, "setmode", (a, k) -> setmode(IoUtil.asLong(a.get(0)), IoUtil.asLong(a.get(1))));
        fn(m, "GetErrorMode", (a, k) -> Integer.toUnsignedLong(WindowsHandles.errorMode()));
        fn(m, "SetErrorMode", (a, k) -> Integer.toUnsignedLong(WindowsHandles.setErrorMode((int) IoUtil.asLong(a.get(0)))));
        return m;
    }

    private static Object unsigned(long v) { return v >= 0 ? (Object) v : new BigInteger(Long.toUnsignedString(v)); }

    /** The handle becomes owned by the new descriptor, which closes it. An invalid handle is EBADF, as in _open_osfhandle. */
    private static Object openOsfhandle(Object handle, long flags) {
        long h = handle instanceof BigInteger b ? b.longValue() : IoUtil.asLong(handle);
        if (h == WindowsNative.INVALID_HANDLE || h == 0) throw IoFd.badFd();
        try { WindowsHandles.fileType(h); } catch (PyException e) { throw IoFd.badFd(); }
        return IoFd.openHandle(h);
    }

    /** Records the translation mode and returns the previous one; the descriptors do binary I/O whatever it is. */
    private static Object setmode(long fd, long mode) {
        IoFd.Fd f = IoFd.get(fd);
        if (mode != O_TEXT && mode != O_BINARY) throw PosixModule.osError("OSError", 22, "Invalid argument", null);
        long old = f.crtMode;
        f.crtMode = (int) mode;
        return old;
    }
}
