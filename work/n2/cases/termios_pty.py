# termios, tty and the os pty functions, on a pseudo-terminal this script opens.
# SAFETY (work/n2_plan.md 2.3): every terminal call is made on the pty pair from os.openpty() or on a pipe; never on
# descriptors 0/1/2, never on /dev/tty, no getpass. The pty is opened without becoming a controlling terminal.
import os

print(os.name)
if os.name == "posix":
    import fcntl
    import select
    import struct
    import termios
    import tty

    def t(label, f, *a):
        try:
            r = f(*a)
            print(label, "->", repr(r))
            return r
        except Exception as e:
            print(label, "!!", type(e).__name__, e.args, isinstance(e, OSError))

    print(sorted(n for n in dir(termios) if not n.startswith("_") and not n.isupper()))
    ints = sorted(n for n in dir(termios) if isinstance(getattr(termios, n), int))
    print(len(ints), sum(getattr(termios, n) for n in ints), termios.NCCS, termios.TCSANOW, termios.TCSADRAIN, termios.TCSAFLUSH,
          termios.VMIN, termios.VTIME, termios.ECHO, termios.ICANON, termios.ISIG, termios.B38400, termios.TIOCGWINSZ, termios.FIONREAD)
    print(termios.error.__module__, termios.error.__qualname__, termios.error.__mro__[1].__name__, issubclass(termios.error, OSError))
    # os.login_tty and os.forkpty exist in CPython and are absent under pyJVM (no fork): not printed.
    print([n for n in ("openpty", "ttyname", "posix_openpt", "grantpt", "unlockpt", "ptsname") if hasattr(os, n)])

    m, s = os.openpty()
    rp, wp = os.pipe()
    try:
        print("openpty", m > 2, s > 2, m != s, os.get_inheritable(m), os.get_inheritable(s), os.isatty(m), os.isatty(s), os.get_blocking(m), os.get_blocking(s))
        t("ttyname slave", lambda: os.ttyname(s).startswith("/dev/pts/"))
        t("ttyname master", os.ttyname, m)
        t("ttyname pipe", os.ttyname, rp)
        t("ttyname closed", os.ttyname, 9999)
        t("ttyname str", os.ttyname, "x")
        t("openpty arg", os.openpty, 1)
        print("same tty", os.fstat(s).st_rdev == os.stat(os.ttyname(s)).st_rdev)

        a = termios.tcgetattr(s)
        print(type(a).__name__, len(a), [type(x).__name__ for x in a[:6]], type(a[6]).__name__, len(a[6]), sorted(set(type(x).__name__ for x in a[6])), all(len(x) == 1 for x in a[6]))
        print(a[:6], a[6])
        print(bool(a[3] & termios.ECHO), bool(a[3] & termios.ICANON), a[4] == termios.B38400, a[5] == termios.B38400, a[6][termios.VINTR], a[6][termios.VEOF])
        t("tcgetattr master", lambda: termios.tcgetattr(m) == a)
        b = termios.tcgetattr(s)
        b[3] &= ~termios.ECHO
        t("echo off", termios.tcsetattr, s, termios.TCSANOW, b)
        print("echo", bool(termios.tcgetattr(s)[3] & termios.ECHO))
        b[3] &= ~termios.ICANON
        b[6][termios.VMIN] = 1
        b[6][termios.VTIME] = 0
        t("non-canonical", termios.tcsetattr, s, termios.TCSADRAIN, b)
        c = termios.tcgetattr(s)
        print(type(c[6][termios.VMIN]).__name__, c[6][termios.VMIN], type(c[6][termios.VTIME]).__name__, c[6][termios.VTIME], sorted(set(type(x).__name__ for x in c[6])))
        t("restore", termios.tcsetattr, s, termios.TCSAFLUSH, a)
        print("restored", termios.tcgetattr(s) == a)
        t("attributes tuple", termios.tcsetattr, s, termios.TCSANOW, tuple(a))
        t("attributes 6", termios.tcsetattr, s, termios.TCSANOW, a[:6])
        t("cc tuple", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [tuple(a[6])])
        t("cc 31", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [a[6][:31]])
        t("cc str", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [["a"] * 32])
        t("cc 2 bytes", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [[b"ab"] * 32])
        t("cc int 300", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [[300] * 32])
        print("cc", termios.tcgetattr(s)[6][:3])
        t("flag str", termios.tcsetattr, s, termios.TCSANOW, ["x"] + a[1:])
        t("bad speed", termios.tcsetattr, s, termios.TCSANOW, a[:4] + [12345, 12345] + [a[6]])
        t("bad when", termios.tcsetattr, s, 99, a)
        t("when str", termios.tcsetattr, s, "x", a)
        t("restore", termios.tcsetattr, s, termios.TCSANOW, a)
        print("restored", termios.tcgetattr(s) == a)
        t("tcsetattr pipe", termios.tcsetattr, rp, termios.TCSANOW, a)
        t("tcgetattr pipe", termios.tcgetattr, rp)
        t("tcgetattr closed", termios.tcgetattr, 9999)
        t("tcgetattr -1", termios.tcgetattr, -1)
        t("tcgetattr str", termios.tcgetattr, "x")
        t("tcgetattr keyword", lambda: termios.tcgetattr(fd=s))

        class F:
            def fileno(self): return s

        t("tcgetattr fileno()", lambda: termios.tcgetattr(F()) == a)

        t("winsize", termios.tcgetwinsize, s)
        t("set winsize", termios.tcsetwinsize, s, (24, 80))
        t("winsize", termios.tcgetwinsize, s)
        t("winsize master", termios.tcgetwinsize, m)
        t("os.get_terminal_size", lambda: tuple(os.get_terminal_size(s)))
        t("set winsize list", termios.tcsetwinsize, s, [30, 100])
        t("TIOCGWINSZ", lambda: struct.unpack("HHHH", fcntl.ioctl(s, termios.TIOCGWINSZ, b"\0" * 8)))
        t("TIOCSWINSZ", fcntl.ioctl, m, termios.TIOCSWINSZ, struct.pack("HHHH", 5, 6, 0, 0))
        t("winsize", termios.tcgetwinsize, s)
        t("winsize 3 items", termios.tcsetwinsize, s, (1, 2, 3))
        t("winsize int", termios.tcsetwinsize, s, 5)
        t("winsize str items", termios.tcsetwinsize, s, ("a", "b"))
        t("winsize 70000", termios.tcsetwinsize, s, (70000, 1))
        t("winsize -1", termios.tcsetwinsize, s, (-1, 1))
        t("winsize 2**70", termios.tcsetwinsize, s, (2**70, 1))
        t("winsize pipe", termios.tcgetwinsize, rp)

        t("tcdrain", termios.tcdrain, s)
        t("tcflush", termios.tcflush, s, termios.TCIOFLUSH)
        t("tcflush 99", termios.tcflush, s, 99)
        t("tcflow off", termios.tcflow, s, termios.TCOOFF)
        t("tcflow on", termios.tcflow, s, termios.TCOON)
        t("tcflow 99", termios.tcflow, s, 99)
        t("tcflow str", termios.tcflow, s, "x")
        t("tcsendbreak", termios.tcsendbreak, s, 0)
        t("tcdrain pipe", termios.tcdrain, rp)
        t("tcflush pipe", termios.tcflush, rp, termios.TCIFLUSH)
        t("tcsendbreak pipe", termios.tcsendbreak, rp, 0)
        e = None
        try:
            termios.tcgetattr(rp)
        except termios.error as x:
            e = x
        print(type(e).__name__, e.args, str(e), repr(e), hasattr(e, "errno"))

        # data through the pty: canonical mode without echo, then with echo, then discarded input
        b = termios.tcgetattr(s)
        b[3] &= ~termios.ECHO
        termios.tcsetattr(s, termios.TCSANOW, b)
        os.write(m, b"line\n")
        print("slave read", os.read(s, 100))
        os.write(s, b"out\n")
        print("master read", os.read(m, 100))
        termios.tcsetattr(s, termios.TCSANOW, a)
        os.write(m, b"echo\n")
        print("slave read", os.read(s, 100), "echoed", os.read(m, 100))
        # A pty moves data between its two ends asynchronously (a kernel worker). Flushing before the input has
        # arrived would discard nothing, or discard it before the echo exists: so echo is off here, and the script
        # waits until the slave is readable before it flushes. Never read an echo that a flush may have cancelled.
        termios.tcsetattr(s, termios.TCSANOW, b)
        os.write(m, b"discard\n")
        print("arrived", select.select([s], [], [], 30)[0] == [s], struct.unpack("i", fcntl.ioctl(s, termios.FIONREAD, b"\0" * 4)))
        termios.tcflush(s, termios.TCIFLUSH)
        os.set_blocking(s, False)
        t("read after flush", os.read, s, 100)
        os.set_blocking(s, True)
        print("FIONREAD", struct.unpack("i", fcntl.ioctl(s, termios.FIONREAD, b"\0" * 4)), select.select([m], [], [], 0)[0])
        termios.tcsetattr(s, termios.TCSANOW, a)

        old = t("tty.setraw", lambda: tty.setraw(s) == a)
        r = termios.tcgetattr(s)
        print("raw", r[3] & (termios.ECHO | termios.ICANON | termios.ISIG | termios.IEXTEN), r[1] & termios.OPOST, r[6][termios.VMIN], r[6][termios.VTIME])
        os.write(m, b"x")
        print("raw read", os.read(s, 10))
        t("tty.setcbreak", lambda: type(tty.setcbreak(s)).__name__)
        termios.tcsetattr(s, termios.TCSANOW, a)

        import pty
        pm, ps = pty.openpty()
        print("pty.openpty", os.isatty(pm), os.isatty(ps), os.get_inheritable(ps))
        os.close(pm)
        os.close(ps)
        if hasattr(os, "posix_openpt"):
            pm = os.posix_openpt(os.O_RDWR | os.O_NOCTTY)
            print("posix_openpt", pm > 2, os.get_inheritable(pm), os.isatty(pm))
            t("grantpt", os.grantpt, pm)
            t("unlockpt", os.unlockpt, pm)
            t("ptsname", lambda: os.ptsname(pm).startswith("/dev/pts/"))
            t("ptsname pipe", os.ptsname, rp)
            t("grantpt pipe", os.grantpt, rp)
            t("posix_openpt -1", os.posix_openpt, -1)
            os.close(pm)
        os.close(m)
        m = -1
        t("tcgetattr after master close", termios.tcgetattr, s)
        t("read after master close", os.read, s, 10)
    finally:
        for f in (m, s, rp, wp):
            if f >= 0:
                os.close(f)
    print("done")
