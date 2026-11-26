# N2 ticket 4 check: the lines of work/n2/cases/fcntl_basic.py above its "# ioctl on a pipe" comment, plus its final
# audit lines restricted to fcntl.fcntl, fcntl.flock and fcntl.lockf (ioctl arrives with ticket 5, so the list of
# callables leaves "ioctl" out). Run under CPython and pyJVM on each lane; the outputs must be identical.
# Prints only facts that are the same for CPython and pyJVM on one host (no descriptor numbers, no pids).
# SAFETY (work/n2_plan.md 2.3): touches only a temporary file it creates; no terminal, no signal; every lock is LOCK_NB.
import os
import sys

print(os.name)
if os.name == "posix":
    import fcntl
    import struct
    import tempfile
    import warnings

    def t(label, f, *a):
        try:
            r = f(*a)
            print(label, "->", repr(r))
            return r
        except Exception as e:
            print(label, "!!", type(e).__name__, e.args)

    OFD_GETLK, OFD_SETLK = 36, 37                                              # not in the fcntl module

    print(sorted(n for n in dir(fcntl) if not n.startswith("_") and callable(getattr(fcntl, n)) and n != "ioctl"))
    print(len([n for n in dir(fcntl) if isinstance(getattr(fcntl, n), int)]),
          fcntl.F_GETFD, fcntl.F_SETFD, fcntl.F_GETFL, fcntl.F_SETFL, fcntl.F_DUPFD_CLOEXEC, fcntl.FD_CLOEXEC,
          fcntl.F_GETLK, fcntl.F_SETLK, fcntl.F_SETLKW, fcntl.F_RDLCK, fcntl.F_WRLCK, fcntl.F_UNLCK,
          fcntl.LOCK_SH, fcntl.LOCK_EX, fcntl.LOCK_NB, fcntl.LOCK_UN, fcntl.F_GETPIPE_SZ, fcntl.F_SETPIPE_SZ)

    events = []
    sys.addaudithook(lambda ev, args: events.append((ev, len(args), args[1:])) if ev.startswith("fcntl.") else None)

    d = tempfile.mkdtemp()
    p = os.path.join(d, "f")
    fd = os.open(p, os.O_RDWR | os.O_CREAT, 0o600)
    fd2 = os.open(p, os.O_RDWR)
    try:
        # descriptor flags and status flags agree with the os functions
        t("getfd", fcntl.fcntl, fd, fcntl.F_GETFD)
        t("setfd 0", fcntl.fcntl, fd, fcntl.F_SETFD, 0)
        print("inheritable", os.get_inheritable(fd))
        t("setfd cloexec", fcntl.fcntl, fd, fcntl.F_SETFD, fcntl.FD_CLOEXEC)
        print("inheritable", os.get_inheritable(fd))
        print("accmode", fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_ACCMODE == os.O_RDWR)
        t("setfl nonblock", lambda: fcntl.fcntl(fd, fcntl.F_SETFL, fcntl.fcntl(fd, fcntl.F_GETFL) | os.O_NONBLOCK))
        print("blocking", os.get_blocking(fd))
        os.set_blocking(fd, True)
        print("nonblock bit", bool(fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_NONBLOCK))
        for cmd, inh in ((fcntl.F_DUPFD_CLOEXEC, False), (fcntl.F_DUPFD, True)):
            nd = fcntl.fcntl(fd, cmd, 100)
            print("dup", nd >= 100, os.get_inheritable(nd) == inh, os.fstat(nd).st_ino == os.fstat(fd).st_ino)
            os.close(nd)
        # buffer arguments
        fl = struct.pack("hhqqi4x", fcntl.F_WRLCK, 0, 0, 0, 0)
        t("getlk bytes", fcntl.fcntl, fd, fcntl.F_GETLK, fl)
        t("getlk bytearray", fcntl.fcntl, fd, fcntl.F_GETLK, bytearray(fl))
        t("getlk str", fcntl.fcntl, fd, fcntl.F_GETLK, fl.decode("latin-1"))
        t("arg nonascii str", fcntl.fcntl, fd, fcntl.F_GETFD, "\xe9")
        t("arg 1024", lambda: len(fcntl.fcntl(fd, fcntl.F_GETFD, b"\0" * 1024)))
        t("arg 1025", lambda: len(fcntl.fcntl(fd, fcntl.F_GETFD, b"\0" * 1025)))
        t("arg empty", fcntl.fcntl, fd, fcntl.F_GETFD, b"")
        t("arg float", fcntl.fcntl, fd, fcntl.F_GETFD, 1.5)
        t("arg None", fcntl.fcntl, fd, fcntl.F_GETFD, None)
        t("arg 2**31", fcntl.fcntl, fd, fcntl.F_GETFD, 2**31)
        t("arg -1", fcntl.fcntl, fd, fcntl.F_GETFD, -1)
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            t("arg 2**32", fcntl.fcntl, fd, fcntl.F_GETFD, 2**32)
            print("warnings", [(x.category.__name__, str(x.message)) for x in w])
        t("cmd str", fcntl.fcntl, fd, "x")
        t("cmd 2**40", fcntl.fcntl, fd, 2**40)
        t("cmd unknown", fcntl.fcntl, fd, 99999)
        # the descriptor argument
        t("fd -1", fcntl.fcntl, -1, fcntl.F_GETFD)
        t("fd closed", fcntl.fcntl, 9999, fcntl.F_GETFD)
        t("fd str", fcntl.fcntl, "x", fcntl.F_GETFD)
        t("fd float", fcntl.fcntl, 1.0, fcntl.F_GETFD)
        t("fd 2**40", fcntl.fcntl, 2**40, fcntl.F_GETFD)

        class F:
            def __init__(self, v): self.v = v
            def fileno(self): return self.v

        t("fd fileno()", fcntl.fcntl, F(fd), fcntl.F_GETFD)
        t("fd fileno() str", fcntl.fcntl, F("x"), fcntl.F_GETFD)
        t("fd fileno() -1", fcntl.fcntl, F(-1), fcntl.F_GETFD)
        with open(p, "rb") as fo:
            t("fd file object", fcntl.fcntl, fo, fcntl.F_GETFD)
        t("keyword", lambda: fcntl.fcntl(fd=fd, cmd=1))
        t("no args", fcntl.fcntl)
        t("4 args", fcntl.fcntl, fd, 1, 0, 0)

        # flock: two open file descriptions of one file conflict inside one process
        t("flock ex", fcntl.flock, fd, fcntl.LOCK_EX)
        t("flock ex conflict", fcntl.flock, fd2, fcntl.LOCK_EX | fcntl.LOCK_NB)
        t("flock sh conflict", fcntl.flock, fd2, fcntl.LOCK_SH | fcntl.LOCK_NB)
        t("flock un", fcntl.flock, fd, fcntl.LOCK_UN)
        t("flock sh", fcntl.flock, fd, fcntl.LOCK_SH)
        t("flock sh shared", fcntl.flock, fd2, fcntl.LOCK_SH | fcntl.LOCK_NB)
        t("flock un", fcntl.flock, fd2, fcntl.LOCK_UN)
        t("flock un", fcntl.flock, fd, fcntl.LOCK_UN)
        t("flock op 0", fcntl.flock, fd, 0)
        t("flock closed", fcntl.flock, 9999, fcntl.LOCK_EX)
        t("flock op str", fcntl.flock, fd, "x")
        t("flock op 2**40", fcntl.flock, fd, 2**40)
        t("flock keyword", lambda: fcntl.flock(fd, operation=1))

        # lockf: a POSIX record lock of this process is seen through an open-file-description query on fd2
        probe = struct.pack("hhqqi4x", fcntl.F_WRLCK, 0, 0, 0, 0)

        def seen():
            ty, wh, start, ln, pid = struct.unpack("hhqqi4x", fcntl.fcntl(fd2, OFD_GETLK, probe))
            return {fcntl.F_RDLCK: "RDLCK", fcntl.F_WRLCK: "WRLCK", fcntl.F_UNLCK: "UNLCK"}[ty], start, ln, pid == os.getpid() or ty == fcntl.F_UNLCK

        t("lockf ex", fcntl.lockf, fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print("seen", seen())
        t("ofd setlk conflict", fcntl.fcntl, fd2, OFD_SETLK, probe)
        t("lockf un", fcntl.lockf, fd, fcntl.LOCK_UN)
        print("seen", seen())
        t("lockf range", fcntl.lockf, fd, fcntl.LOCK_EX | fcntl.LOCK_NB, 10, 5, 0)
        print("seen", seen())
        t("lockf un range", fcntl.lockf, fd, fcntl.LOCK_UN, 10, 5, 0)
        t("lockf sh", fcntl.lockf, fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        print("seen", seen())
        t("lockf un", fcntl.lockf, fd, fcntl.LOCK_UN)
        rfd = os.open(p, os.O_RDONLY)
        t("lockf ex read-only", fcntl.lockf, rfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        os.close(rfd)
        t("lockf cmd 0", fcntl.lockf, fd, 0)
        t("lockf cmd NB", fcntl.lockf, fd, fcntl.LOCK_NB)
        t("lockf len str", fcntl.lockf, fd, fcntl.LOCK_EX, "x")
        t("lockf len 2**70", fcntl.lockf, fd, fcntl.LOCK_EX, 2**70)
        t("lockf whence 9", fcntl.lockf, fd, fcntl.LOCK_EX | fcntl.LOCK_NB, 0, 0, 9)
        t("lockf closed", fcntl.lockf, 9999, fcntl.LOCK_EX)
        t("lockf keyword", lambda: fcntl.lockf(fd, fcntl.LOCK_UN, len=0))
    finally:
        os.close(fd)
        os.close(fd2)
        os.unlink(p)
        os.rmdir(d)
    names = [e[0] for e in events]
    print("audit", sorted(set(names)), [n for n in ("fcntl.fcntl", "fcntl.flock", "fcntl.lockf") if n in names])
    print("audit first", events[0][:2], repr(events[0][2]), "second", repr(events[1][2]))
    print([e for e in events if e[0] == "fcntl.flock"][0][1:], [e for e in events if e[0] == "fcntl.lockf"][0][1:])
