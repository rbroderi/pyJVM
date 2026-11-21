# SPDX-License-Identifier: GPL-3.0-or-later
"""Print CPython's POSIX descriptor/select/epoll/signal facts, one fact per line (Linux only).

CPython only: run it under a reference interpreter (3.13, 3.14, 3.15) and diff the outputs.
Used by the N0 plan (kernel descriptors for pyJVM) to measure constants, module surfaces,
signatures and the error behaviour that pyJVM must match.  Output is deterministic: no pids,
addresses or paths that vary from run to run.
"""

from __future__ import annotations

import errno
import io
import os
import select
import shutil
import signal
import stat
import sys
import tempfile
import threading
import time
import warnings


def show(value: object) -> str:
    if isinstance(value, BaseException):
        extra = ""
        fn = getattr(value, "filename", None)
        if fn is not None:
            extra = f" filename={fn!r}"
        chars = getattr(value, "characters_written", None)
        if chars is not None:
            extra += f" characters_written={chars}"
        return f"{type(value).__name__}{value.args!r}{extra}"
    return repr(value)


def fact(name: str, fn) -> None:
    try:
        result = fn()
    except BaseException as exc:  # noqa: BLE001 - the exception itself is the fact
        result = exc
    print(f"{name}: {show(result)}")


def fact_exc(name: str, fn) -> None:
    """Like fact, but a missing exception is reported as 'no error' with the value."""
    try:
        result = fn()
    except BaseException as exc:  # noqa: BLE001
        print(f"{name}: {show(exc)}")
    else:
        print(f"{name}: no error -> {show(result)}")


class _Scrub:
    """stdout filter: the per-run temporary directory is printed as <tmp>, so outputs diff cleanly."""

    def __init__(self, out, tmp: str) -> None:
        self.out, self.tmp = out, tmp

    def write(self, s: str) -> int:
        return self.out.write(s.replace(self.tmp, "<tmp>"))

    def flush(self) -> None:
        self.out.flush()


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="posix_measure_")
    real = sys.stdout
    sys.stdout = _Scrub(real, tmp)
    try:
        return _measure(tmp)
    finally:
        sys.stdout = real
        shutil.rmtree(tmp, ignore_errors=True)


def _measure(tmp: str) -> int:
    if not sys.platform.startswith("linux"):
        print("posix_measure.py: Linux only", file=sys.stderr)
        return 2
    print(f"python: {sys.version_info[0]}.{sys.version_info[1]}")

    # -- module surfaces -------------------------------------------------
    names = sorted(n for n in dir(select) if not n.startswith("__"))
    print(f"select.dir: {names}")
    print(f"epoll.dir: {sorted(n for n in dir(select.epoll) if not n.startswith('__') or n in ('__enter__', '__exit__'))}")
    pollobj = select.poll()
    print(f"poll.dir: {sorted(n for n in dir(pollobj) if not n.startswith('__'))}")
    print(f"os.has readinto: {hasattr(os, 'readinto')}")
    print(f"select.has EPOLLWAKEUP: {hasattr(select, 'EPOLLWAKEUP')}")
    print(f"select.has EPOLLEXCLUSIVE: {hasattr(select, 'EPOLLEXCLUSIVE')}")
    for n in sorted(names):
        v = getattr(select, n)
        if isinstance(v, int):
            print(f"select.{n}: {v}")
    for n in ("O_RDONLY", "O_WRONLY", "O_RDWR", "O_ACCMODE", "O_CREAT", "O_EXCL", "O_NOCTTY", "O_TRUNC",
              "O_APPEND", "O_NONBLOCK", "O_NDELAY", "O_DSYNC", "O_ASYNC", "O_DIRECT", "O_LARGEFILE",
              "O_DIRECTORY", "O_NOFOLLOW", "O_NOATIME", "O_CLOEXEC", "O_SYNC", "O_RSYNC", "O_FSYNC",
              "O_PATH", "O_TMPFILE", "SEEK_SET", "SEEK_CUR", "SEEK_END", "SEEK_DATA", "SEEK_HOLE"):
        print(f"os.{n}: {getattr(os, n, 'absent')}")
    import fcntl
    for n in ("F_DUPFD", "F_GETFD", "F_SETFD", "F_GETFL", "F_SETFL", "F_DUPFD_CLOEXEC", "FD_CLOEXEC"):
        print(f"fcntl.{n}: {getattr(fcntl, n, 'absent')}")
    for n in ("EPERM", "ENOENT", "EINTR", "EBADF", "EAGAIN", "EEXIST", "ENOTDIR", "EISDIR", "EINVAL", "ENOTTY",
              "ESPIPE", "EPIPE"):
        print(f"errno.{n}: {getattr(errno, n, 'absent')}")
    # -- signatures --------------------------------------------------------
    for owner, nm in ((os, "dup2"), (os, "pipe2"), (os, "set_inheritable"), (os, "get_inheritable"),
                      (os, "set_blocking"), (os, "get_blocking"), (os, "pread"), (os, "pwrite"),
                      (os, "closerange"), (os, "readinto"), (os, "stat"), (os, "lstat"),
                      (select.epoll, "register"), (select.epoll, "modify"), (select.epoll, "unregister"),
                      (select.epoll, "poll"), (select.epoll, "fromfd"), (select, "epoll"),
                      (signal, "set_wakeup_fd")):
        obj = getattr(owner, nm, None)
        print(f"sig {getattr(owner, '__name__', owner)}.{nm}: {getattr(obj, '__text_signature__', 'absent')}")

    path = os.path.join(tmp, "f")
    with open(path, "wb") as fh:
        fh.write(b"0123456789")
    old_umask = os.umask(0o022)

    # -- os.open / read / write -------------------------------------------
    fd = os.open(path, os.O_RDWR)
    import fcntl as F
    print(f"open inheritable: {os.get_inheritable(fd)} F_GETFD={F.fcntl(fd, F.F_GETFD)}")
    fact("open dir O_RDONLY", lambda: (lambda d: (os.close(d), 'ok')[1])(os.open(tmp, os.O_RDONLY)))
    fact("open dir O_WRONLY", lambda: os.open(tmp, os.O_WRONLY))
    fact("open file O_DIRECTORY", lambda: os.open(path, os.O_RDONLY | os.O_DIRECTORY))
    fact("open empty path", lambda: os.open("", os.O_RDONLY))
    fact("open NUL path", lambda: os.open("a\0b", os.O_RDONLY))
    newp = os.path.join(tmp, "created")
    cfd = os.open(newp, os.O_CREAT | os.O_WRONLY, 0o666)
    print(f"create mode: {oct(stat.S_IMODE(os.fstat(cfd).st_mode))}")
    os.close(cfd)
    dfd = os.open(tmp, os.O_RDONLY)
    fact("read dirfd", lambda: os.read(dfd, 10))
    fact("lseek dirfd", lambda: os.lseek(dfd, 0, 1))
    fact("fsync dirfd", lambda: os.fsync(dfd))
    fact("FileIO(dir)", lambda: io.FileIO(tmp))
    fact("FileIO(dirfd)", lambda: io.FileIO(dfd))
    fact("open(dir)", lambda: open(tmp))
    os.close(dfd)
    r, w = os.pipe()
    fact("lseek pipe", lambda: os.lseek(r, 0, 0))
    fact("lseek bad whence", lambda: os.lseek(fd, 0, 99))
    ro = os.open(path, os.O_RDONLY)
    wo = os.open(path, os.O_WRONLY)
    fact("write read-only fd", lambda: os.write(ro, b"x"))
    fact("read write-only fd", lambda: os.read(wo, 1))
    fact("ftruncate -1", lambda: os.ftruncate(fd, -1))
    fact("ftruncate read-only", lambda: os.ftruncate(ro, 0))
    fact("fsync read-only", lambda: os.fsync(ro))
    fact("read -1", lambda: os.read(fd, -1))
    os.close(ro)
    os.close(wo)
    for nm in ("close", "fstat", "get_inheritable", "dup"):
        fact(f"bad fd {nm}", lambda nm=nm: getattr(os, nm)(9999))
    fact("bad fd set_blocking", lambda: os.set_blocking(9999, True))
    fact("bad fd dup2", lambda: os.dup2(9999, 100))
    fact("isatty 9999", lambda: os.isatty(9999))
    fact("close -1", lambda: os.close(-1))
    fact("close 2**40", lambda: os.close(2**40))
    fact("closerange 2**40", lambda: os.closerange(2**40, 2**41))
    d1 = os.dup(fd)
    print(f"dup inheritable: {os.get_inheritable(d1)}")
    print(f"dup2 returns fd2 / inheritable: {os.dup2(fd, 200)} {os.get_inheritable(200)}")
    print(f"dup2 inheritable=False: {os.dup2(fd, 201, inheritable=False)} {os.get_inheritable(201)}")
    fact("dup2 same", lambda: os.dup2(fd, fd))
    fact("dup2 same non-inheritable", lambda: os.dup2(fd, fd, inheritable=False))
    fact("dup2 -1", lambda: os.dup2(fd, -1))
    for x in (d1, 200, 201):
        os.close(x)
    # -- pipes -------------------------------------------------------------
    print(f"pipe inheritable: {os.get_inheritable(r)} {os.get_inheritable(w)}")
    print(f"pipe F_GETFL access: {F.fcntl(r, F.F_GETFL) & 3} {F.fcntl(w, F.F_GETFL) & 3}")
    pr, pw = os.pipe2(os.O_NONBLOCK | os.O_CLOEXEC)
    print(f"pipe2 nonblock: inheritable={os.get_inheritable(pr)} blocking={os.get_blocking(pr)}")
    fact("read empty nonblocking pipe", lambda: os.read(pr, 1))
    pr0, pw0 = os.pipe2(0)
    print(f"pipe2(0): inheritable={os.get_inheritable(pr0)} blocking={os.get_blocking(pr0)}")
    fact("pipe2 bad flags", lambda: os.pipe2(12345678))
    st = os.fstat(r)
    print(f"pipe fstat: fifo={stat.S_ISFIFO(st.st_mode)} size={st.st_size}")
    os.close(pr0)
    os.close(pw0)
    # write to a pipe whose reader is closed
    r2, w2 = os.pipe()
    os.close(r2)
    fact("write broken pipe", lambda: os.write(w2, b"x"))
    os.close(w2)
    fact("set_inheritable True", lambda: (os.set_inheritable(r, True), F.fcntl(r, F.F_GETFD))[1])
    fact("closerange a>b", lambda: os.closerange(500, 400))
    fact("get_terminal_size(pipe)", lambda: os.get_terminal_size(r))
    fact("umask(0o7777777)", lambda: (os.umask(0o7777777), oct(os.umask(0o022)))[1])
    fact("float fd", lambda: os.close(1.5))
    fact("str fd", lambda: os.close("1"))
    fact("write str", lambda: os.write(w, "x"))
    # -- O_APPEND, pread/pwrite -----------------------------------------------
    ap = os.open(path, os.O_RDWR | os.O_APPEND)
    os.lseek(ap, 0, 0)
    os.write(ap, b"Z")
    print(f"O_APPEND offset after write: {os.lseek(ap, 0, 1)} size={os.fstat(ap).st_size}")
    os.lseek(ap, 0, 0)
    os.pwrite(ap, b"hello", 3)
    print(f"pread/pwrite keep offset: {os.pread(ap, 4, 2)!r} off={os.lseek(ap, 0, 1)}")
    fact("pread -1", lambda: os.pread(ap, -1, 0))
    os.close(ap)
    # -- path stat vs fstat -------------------------------------------------
    fact("stat==fstat", lambda: os.stat(path) == os.fstat(fd))
    fact("stat(fd)==fstat(fd)", lambda: os.stat(fd) == os.fstat(fd))
    fact("stat(9999)", lambda: os.stat(9999))
    fact("lstat(int)", lambda: os.lstat(0))
    fact("stat(1.5)", lambda: os.stat(1.5))
    fact("stat missing", lambda: os.stat(os.path.join(tmp, "nope")))
    fact("stat NUL", lambda: os.stat("a\0b"))
    fact("lstat NUL", lambda: os.lstat("a\0b"))
    fact("stat empty", lambda: os.stat(""))
    fact("stat long component", lambda: os.stat("x" * 300))
    fact("stat through file", lambda: os.stat(os.path.join(path, "x")))
    link = os.path.join(tmp, "dangling")
    os.symlink("missing-target", link)
    fact("lstat dangling", lambda: (stat.S_ISLNK(os.lstat(link).st_mode), os.lstat(link).st_size))
    fact("stat dangling", lambda: os.stat(link))
    fact("stat /dev/null", lambda: (stat.S_ISCHR(os.stat("/dev/null").st_mode), os.stat("/dev/null").st_rdev == os.makedev(1, 3)))
    ents = {e.name: e for e in os.scandir(tmp)}
    e = ents["f"]
    fact("DirEntry inode", lambda: e.inode() == os.lstat(e.path).st_ino)
    fact("DirEntry stat", lambda: e.stat() == os.stat(e.path))
    fact("DirEntry stat nofollow", lambda: ents["dangling"].stat(follow_symlinks=False) == os.lstat(ents["dangling"].path))
    fact("DirEntry dangling stat", lambda: ents["dangling"].stat())
    # -- non-blocking _io -------------------------------------------------------
    fact("FileIO.read nb", lambda: io.FileIO(pr, closefd=False).read(10))
    fact("FileIO.readall nb", lambda: io.FileIO(pr, closefd=False).readall())
    fact("FileIO.readinto nb", lambda: io.FileIO(pr, closefd=False).readinto(bytearray(4)))
    br = io.BufferedReader(io.FileIO(pr, closefd=False))
    fact("Buffered.read(10) nb", lambda: br.read(10))
    fact("Buffered.read() nb", lambda: br.read())
    fact("Buffered.readinto nb", lambda: br.readinto(bytearray(4)))
    fact("Buffered.read1 nb", lambda: br.read1(10))
    fact("Buffered.readline nb", lambda: br.readline())
    fact("Buffered.peek nb", lambda: br.peek(1))
    tw = io.TextIOWrapper(io.BufferedReader(io.FileIO(pr, closefd=False)))
    fact("Text.read(5) nb", lambda: tw.read(5))
    fact("Text.readline nb", lambda: tw.readline())
    os.write(pw, b"abc")
    fact("Buffered.read(10) 3 bytes", lambda: io.BufferedReader(io.FileIO(pr, closefd=False)).read(10))
    os.write(pw, b"abc")
    fact("FileIO.readall 3 bytes", lambda: io.FileIO(pr, closefd=False).readall())
    # fill the pipe
    fio = io.FileIO(pw, "wb", closefd=False)
    total = 0
    while True:
        n = fio.write(b"x" * 70000)
        if n is None:
            break
        total += n
    print(f"FileIO.write full pipe: returned None after {total} bytes written")
    os.set_blocking(pr, False)
    bw = io.BufferedWriter(fio, 16)
    fact("BufferedWriter full pipe 8", lambda: bw.write(b"12345678"))
    fact("BufferedWriter 25 bytes", lambda: bw.write(b"y" * 25))
    fact("BufferedWriter flush", lambda: bw.flush())
    while True:
        try:
            if not os.read(pr, 65536):
                break
        except BlockingIOError:
            break
    fact("BufferedWriter flush drained", lambda: bw.flush())
    for x in (r, w, pr, pw):
        try:
            os.close(x)
        except OSError:
            pass

    # -- select.select -----------------------------------------------------------
    a, b = os.pipe()
    os.write(b, b"x")
    fact("select data", lambda: select.select([a], [b], [], 0))
    c, d = os.pipe()
    os.close(d)
    fact("select writer closed", lambda: select.select([c], [], [c], 0))
    e1, e2 = os.pipe()
    os.close(e1)
    fact("select reader closed", lambda: select.select([], [e2], [e2], 0))
    fact("select closed fd", lambda: select.select([e1], [], [], 0))
    fact("select fd>=1024", lambda: select.select([1024], [], [], 0))
    fact("select negative timeout", lambda: select.select([], [], [], -1))
    fact("select timeout str", lambda: select.select([], [], [], "x"))
    for x in (a, b, c, e2):
        os.close(x)
    # -- poll ------------------------------------------------------------------------
    p = select.poll()
    q, s = os.pipe()
    p.register(q, select.POLLIN)
    p.register(s, select.POLLOUT)
    p.register(9999)
    fact("poll order", lambda: p.poll(0))
    fact("poll float timeout", lambda: p.poll(0.5))
    fact("poll modify unregistered", lambda: p.modify(12345, select.POLLIN))
    fact("poll unregister unknown", lambda: p.unregister(12345))
    fact("poll register 70000", lambda: select.poll().register(q, 70000))
    fact("poll register -1", lambda: select.poll().register(q, -1))
    fact("poll 2**40", _poll_big)
    fact("poll timeout str", lambda: select.poll().poll("x"))
    os.close(s)
    fact("poll HUP", lambda: [x for x in (lambda pp: (pp.register(q, select.POLLIN), pp.poll(0))[1])(select.poll())])
    os.close(q)
    # -- epoll -------------------------------------------------------------------------
    ep = select.epoll()
    print(f"epoll fileno>2/inheritable: {ep.fileno() > 2} {os.get_inheritable(ep.fileno())}")
    q, s = os.pipe()
    ep.register(q)
    fact("epoll register twice", lambda: ep.register(q))
    fact("epoll modify unknown", lambda: ep.modify(12345, select.EPOLLIN))
    fact("epoll unregister unknown", lambda: ep.unregister(12345))
    rf = os.open(path, os.O_RDONLY)
    fact("epoll register regular file", lambda: ep.register(rf))
    os.close(rf)
    fact("epoll register 9999", lambda: ep.register(9999))
    fact("epoll register -1", lambda: ep.register(-1))
    fact("epoll register str", lambda: ep.register("x"))
    fact("epoll register mask -1", lambda: ep.register(s, -1))
    fact("epoll register mask 2**32", lambda: _epoll_big(ep, s))
    fact("epoll poll(0,0)", lambda: ep.poll(0, 0))
    fact("epoll poll(0,-2)", lambda: ep.poll(0, -2))
    fact("epoll poll(0,-1)", lambda: ep.poll(0, -1))
    fact("epoll poll timeout str", lambda: ep.poll("x"))
    os.write(s, b"x")
    fact("epoll poll data", lambda: [(fdv == q, ev) for fdv, ev in ep.poll(0)])
    fact("epoll ONESHOT", lambda: _oneshot())
    fact("epoll repr", lambda: repr(ep).split(" at ")[0])
    ep.close()
    print(f"epoll closed: {ep.closed}")
    fact("epoll second close", lambda: ep.close())
    fact("epoll poll after close", lambda: ep.poll(0))
    fact("epoll fileno after close", lambda: ep.fileno())
    fact("epoll register after close", lambda: ep.register(q))
    fact("epoll(0)", lambda: select.epoll(0))
    fact("epoll(flags=12)", lambda: select.epoll(flags=12))
    fact("epoll(-1, EPOLL_CLOEXEC)", lambda: select.epoll(-1, select.EPOLL_CLOEXEC).close())

    def with_epoll():
        with select.epoll() as e2:
            pass
        return e2.closed

    fact("epoll with", with_epoll)
    os.close(q)
    os.close(s)

    # -- signals -----------------------------------------------------------------------------
    wr, ww = os.pipe()
    fact("wakeup blocking fd", lambda: signal.set_wakeup_fd(ww))
    os.set_blocking(ww, False)
    fact("wakeup 9999", lambda: signal.set_wakeup_fd(9999))
    fact("wakeup first", lambda: signal.set_wakeup_fd(ww))
    fact("wakeup previous", lambda: signal.set_wakeup_fd(-1))
    rw = os.open(path, os.O_RDWR)
    fact("wakeup regular file", lambda: (signal.set_wakeup_fd(rw), signal.set_wakeup_fd(-1))[1])
    os.close(rw)
    os.set_blocking(wr, False)
    signal.signal(signal.SIGUSR1, lambda *a: None)
    signal.set_wakeup_fd(ww)
    signal.raise_signal(signal.SIGUSR1)
    fact("wakeup byte is signum", lambda: os.read(wr, 10) == bytes([int(signal.SIGUSR1)]))
    # full pipe
    try:
        while True:
            os.write(ww, b"x")
    except BlockingIOError:
        pass
    import subprocess
    fact("wakeup full pipe warn=True", lambda: _wakeup_full(path, True))
    fact("wakeup full pipe warn=False", lambda: _wakeup_full(path, False))
    signal.set_wakeup_fd(-1)
    os.close(wr)
    os.close(ww)
    # main thread blocked in read woken by another thread's pthread_kill
    r3, w3 = os.pipe()
    main_id = threading.get_ident()

    def on_usr1(signum, frame):
        os.write(w3, b"H")

    signal.signal(signal.SIGUSR1, on_usr1)
    threading.Timer(0.2, lambda: signal.pthread_kill(main_id, signal.SIGUSR1)).start()
    fact("read interrupted by pthread_kill handler", lambda: os.read(r3, 1))
    # handler that raises interrupts os.read / select / epoll
    class Boom(Exception):
        pass

    def boom(signum, frame):
        raise Boom

    signal.signal(signal.SIGALRM, boom)
    for label, call in (("os.read", lambda: os.read(r3, 1)),
                        ("select.select", lambda: select.select([r3], [], [], 5)),
                        ("epoll.poll", lambda: _epoll_wait(r3))):
        signal.setitimer(signal.ITIMER_REAL, 0.2)
        t0 = time.monotonic()
        try:
            call()
            res = "returned"
        except Boom:
            res = "Boom"
        signal.setitimer(signal.ITIMER_REAL, 0)
        print(f"setitimer raising handler interrupts {label}: {res} fast={time.monotonic() - t0 < 2}")
    signal.signal(signal.SIGALRM, lambda *a: None)
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    t0 = time.monotonic()
    select.select([r3], [], [], 0.5)
    el = time.monotonic() - t0
    signal.setitimer(signal.ITIMER_REAL, 0)
    print(f"returning handler does not shorten select: {el >= 0.45}")
    os.close(r3)
    os.close(w3)
    os.umask(old_umask)
    os.close(fd)
    return 0


def _poll_big():
    p = select.poll()
    # a huge timeout: either OverflowError (<=3.13) or clamped (blocks) -- only probe with a pipe that is ready
    r, w = os.pipe()
    os.write(w, b"x")
    p.register(r, select.POLLIN)
    try:
        return p.poll(2**40)
    finally:
        os.close(r)
        os.close(w)


def _epoll_big(ep, s):
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        ep.register(s, 2**32)
    ep.unregister(s)
    return [w.category.__name__ for w in caught]


def _oneshot():
    ep = select.epoll()
    r, w = os.pipe()
    ep.register(r, select.EPOLLIN | select.EPOLLONESHOT)
    os.write(w, b"x")
    first = len(ep.poll(0))
    second = len(ep.poll(0))
    ep.close()
    os.close(r)
    os.close(w)
    return (first, second)


def _epoll_wait(fd):
    ep = select.epoll()
    ep.register(fd, select.EPOLLIN)
    try:
        return ep.poll(5)
    finally:
        ep.close()


def _wakeup_full(path: str, warn: bool) -> str:
    """Child interpreter: full wakeup pipe, capture its stderr header lines (3.7)."""
    import subprocess
    code = (
        "import os, signal, sys\n"
        "r, w = os.pipe(); os.set_blocking(w, False)\n"
        "try:\n"
        "    while True: os.write(w, b'x')\n"
        "except BlockingIOError: pass\n"
        "signal.signal(signal.SIGUSR1, lambda *a: None)\n"
        f"signal.set_wakeup_fd(w, warn_on_full_buffer={warn})\n"
        "signal.raise_signal(signal.SIGUSR1)\n"
    )
    res = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True)
    return repr(res.stderr.strip().splitlines())


if __name__ == "__main__":
    raise SystemExit(main())
