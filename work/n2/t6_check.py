# N2 ticket 6: the os pty functions (openpty, ttyname, posix_openpt, grantpt, unlockpt, ptsname) on a pty this script opens.
# Run under CPython and pyJVM (same output), in its own session, stdin from /dev/null, under `timeout 300`.
# SAFETY (work/n2_plan.md 2.3): every terminal call is made on the pair from os.openpty() / os.posix_openpt(); never on
# descriptors 0/1/2, never on /dev/tty; no termios call at all; the pty is opened with O_NOCTTY by the runtime and the
# script checks that no controlling terminal appears. Only data that certainly arrives is read (select first).
# The window-size step needs fcntl.ioctl (N2 ticket 5): it runs only with T6_WINSIZE=1 in the environment.
import os
import select
import struct
import subprocess
import sys

print(os.name)
if os.name == "posix":
    def t(label, f, *a):
        try:
            r = f(*a)
            print(label, "->", repr(r))
            return r
        except Exception as e:
            print(label, "!!", type(e).__name__, e.args, isinstance(e, OSError))

    def ctty():
        with open("/proc/self/stat") as f:
            return int(f.read().rsplit(")", 1)[1].split()[4])

    def readable(fd, secs=10):
        return bool(select.select([fd], [], [], secs)[0])

    def read_until(fd, n):
        data = b""
        while len(data) < n and readable(fd):
            data += os.read(fd, 100)
        return data

    print("ctty before", ctty())
    print([n for n in ("openpty", "ttyname", "posix_openpt", "grantpt", "unlockpt", "ptsname") if hasattr(os, n)])

    m, s = os.openpty()
    rp, wp = os.pipe()
    try:
        print("openpty", m > 2, s > 2, m != s, os.get_inheritable(m), os.get_inheritable(s), os.isatty(m), os.isatty(s),
              os.get_blocking(m), os.get_blocking(s))
        print("ctty after openpty", ctty())
        t("ttyname slave", lambda: os.ttyname(s).startswith("/dev/pts/"))
        t("ttyname master", os.ttyname, m)
        t("ttyname pipe", os.ttyname, rp)
        t("ttyname closed", os.ttyname, 9999)
        t("ttyname str", os.ttyname, "x")
        t("ttyname noargs", os.ttyname)
        t("openpty arg", os.openpty, 1)
        print("same tty", os.fstat(s).st_rdev == os.stat(os.ttyname(s)).st_rdev)
        print("ptsname == ttyname", os.ptsname(m) == os.ttyname(s))
        print("isatty pipe", os.isatty(rp), os.isatty(m), os.isatty(s))

        # data both ways, default (canonical, echo) mode
        os.write(s, b"out\n")
        print("master reads", repr(read_until(m, 5)))
        os.write(m, b"line\n")
        print("slave reads", repr(read_until(s, 5)))
        print("master echo", repr(read_until(m, 6)))
        print("select master idle", select.select([m], [], [], 0)[0] == [])
        os.write(s, b"x")
        print("select master ready", select.select([m], [], [], 10)[0] == [m], repr(os.read(m, 10)))

        print("terminal_size fresh", tuple(os.get_terminal_size(s)))
        if os.environ.get("T6_WINSIZE") == "1":
            import fcntl
            fcntl.ioctl(m, 0x5414, struct.pack("HHHH", 24, 80, 0, 0))
            print("terminal_size set", tuple(os.get_terminal_size(s)), tuple(os.get_terminal_size(m)))

        # a child given the slave as stdin sees a terminal
        r = subprocess.run(["sh", "-c", "tty; test -t 0 && echo yes"], stdin=s, stdout=subprocess.PIPE, timeout=60)
        out = r.stdout.decode()
        print("child", r.returncode, out.startswith("/dev/pts/"), out.rstrip().endswith("yes"))
        print("ctty after child", ctty())

        # close-on-exec: the child does not see either end
        r = subprocess.run(["sh", "-c", "readlink /proc/self/fd/%d; echo --; readlink /proc/self/fd/%d" % (m, s)],
                           stdout=subprocess.PIPE, timeout=60, close_fds=False)
        mine, theirs = r.stdout.decode().split("--")
        print("child fds", mine.strip() == "/dev/ptmx", theirs.strip().startswith("/dev/pts/"))
    finally:
        for fd in (m, s, rp, wp):
            os.close(fd)

    # posix_openpt / grantpt / unlockpt / ptsname
    pm = os.posix_openpt(os.O_RDWR | os.O_NOCTTY)
    try:
        print("posix_openpt", pm > 2, os.get_inheritable(pm), os.isatty(pm))
        t("ptsname before unlock", lambda: os.ptsname(pm).startswith("/dev/pts/"))
        t("grantpt", os.grantpt, pm)
        t("unlockpt", os.unlockpt, pm)
        t("ptsname", lambda: os.ptsname(pm).startswith("/dev/pts/"))
        sl = os.open(os.ptsname(pm), os.O_RDWR | os.O_NOCTTY)
        try:
            print("manual slave", os.isatty(sl), os.ttyname(sl) == os.ptsname(pm))
        finally:
            os.close(sl)
        r2, w2 = os.pipe()
        try:
            t("ptsname pipe", os.ptsname, r2)
            t("grantpt pipe", os.grantpt, r2)
            t("unlockpt pipe", os.unlockpt, r2)

            class F:
                def __init__(self, n):
                    self.n = n

                def fileno(self):
                    return self.n
            t("grantpt -1", os.grantpt, -1)
            t("unlockpt -1", os.unlockpt, -1)
            t("ptsname -1", os.ptsname, -1)
            t("ttyname -1", os.ttyname, -1)
            t("grantpt fileno pipe", os.grantpt, F(r2))
            t("ptsname fileno pipe", os.ptsname, F(r2))
            t("grantpt fileno pty", os.grantpt, F(pm))
            t("ptsname fileno pty", lambda: os.ptsname(F(pm)) == os.ptsname(pm))
            t("grantpt noattr", os.grantpt, object())
        finally:
            os.close(r2)
            os.close(w2)
        t("posix_openpt -1", os.posix_openpt, -1)
        t("posix_openpt str", os.posix_openpt, "x")
        t("ptsname noargs", os.ptsname)
    finally:
        os.close(pm)
    print("ctty end", ctty())
