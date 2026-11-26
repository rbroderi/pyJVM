# SPDX-License-Identifier: GPL-3.0-or-later
# N2 measurement script: m1.py and m2.py of work/n2/measure/ in one file (N2 plan, ticket 0). CPython only, Linux only.
# SAFETY (N2 plan 2.3): terminal calls only on a pty this script opens with os.openpty(); never fd 0/1/2, /dev/tty or
# /dev/console. Resource limits: the only changes to this process are a soft RLIMIT_NOFILE round trip (lower by one,
# restore) and RLIMIT_CORE written back unchanged; hard limits are never lowered; real lowering is done with prlimit
# on a `sleep 30` child this script starts and kills by its own pid. No signal is sent to anything else (never pid
# <= 1, 0, a negative pid, pgid 0/1 or the own group). m2 reads pid 1's RLIMIT_NOFILE and tries to set it to the same
# values, which must fail with EPERM: never run as root or where pid 1 has the caller's uid (both refused below).
# Run only in a new session with stdin from /dev/null, under `timeout 120`: setsid timeout 120 python this.py m1 < /dev/null
"""Print CPython's facts about fcntl, termios, resource, pwd, grp and the os pty functions, one fact per line.

usage: python posix_measure_n2.py m1|m2      (exactly one part per process)

Part m1: module surfaces (names, signatures, integer constants).  Part m2: behaviour, exception texts, audit events.
Each part below the "#@@PART" marker is the unedited text of the same-named script, executed in a fresh namespace;
the output of ``posix_measure_n2.py mN`` equals the committed ``work/n2/measure/mN-31x.txt`` except for pids,
descriptor numbers in audit tuples and memory addresses.  Run one part per process (m2 installs an audit hook), under
each reference interpreter (3.13, 3.14, 3.15), with stdin from /dev/null, PYTHONUNBUFFERED unset or set, stdout and
stderr to the same file.
"""
import os
import sys

PARTS = ("m1", "m2")


def _sections():
    with open(__file__, encoding="utf-8") as f:
        text = f.read()
    out = {}
    for chunk in text.split("\n#@@PART ")[1:]:
        name, _, body = chunk.partition("\n")
        out[name.strip()] = body
    return out


def main(argv):
    if not sys.platform.startswith("linux"):
        print("posix_measure_n2.py: run under Linux CPython", file=sys.stderr)
        return 2
    if os.getuid() == 0:
        print("posix_measure_n2.py: refusing to run as root (N2 plan 2.3 B5)", file=sys.stderr)
        return 2
    # m2's "prlimit pid 1 set" line must fail with EPERM; on a host where pid 1 runs as this user (a container) it would
    # change pid 1's limits, so refuse there.
    if os.stat("/proc/1").st_uid == os.getuid():
        print("posix_measure_n2.py: refusing to run where pid 1 has this uid (m2 tries prlimit on pid 1)", file=sys.stderr)
        return 2
    if len(argv) != 1 or argv[0] not in PARTS:
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    import linecache
    import signal
    signal.alarm(120)   # backstop if the caller forgot `timeout 120`
    name = argv[0]
    src = _sections()[name]
    fname = name + ".py"
    # A real-looking file name plus a linecache entry, so warnings show the source line as when mN.py runs directly.
    linecache.cache[fname] = (len(src), None, src.splitlines(True), fname)
    exec(compile(src, fname, "exec"), {"__name__": "__main__", "__file__": fname})
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

#@@PART m1
# N2 measurement 1: module surfaces. CPython only. SAFE: read-only, no terminal or rlimit change.
import sys, os
print("VERSION", sys.version_info[:3])
for name in ("fcntl", "termios", "resource", "pwd", "grp"):
    m = __import__(name)
    ints = {}; other = []
    for n in sorted(dir(m)):
        if n.startswith("__"): continue
        v = getattr(m, n)
        if isinstance(v, int) and not isinstance(v, bool): ints[n] = v
        else:
            sig = getattr(v, "__text_signature__", None)
            other.append((n, type(v).__name__, sig))
    print("MODULE", name, "ints", len(ints), "other", len(other))
    for n, t, s in other: print("  NAME", name, n, t, s)
    for n, v in ints.items(): print("  INT", name, n, v)
    print("  DOCNONE", name, m.__doc__ is None, getattr(m, "__file__", None) is None)
import termios, resource, pwd, grp, fcntl
print("termios.error", termios.error, termios.error.__mro__, termios.error.__module__, termios.error.__qualname__)
print("resource.error", resource.error is OSError)
for c in (pwd.struct_passwd, grp.struct_group, resource.struct_rusage):
    print("SEQ", c.__module__, c.__qualname__, c.n_fields, c.n_sequence_fields, c.n_unnamed_fields, getattr(c, "__match_args__", None))
for n in ("openpty", "ttyname", "login_tty", "ctermid", "tcgetpgrp", "tcsetpgrp", "forkpty", "posix_openpt", "grantpt", "unlockpt", "ptsname", "getlogin", "getgroups", "getgrouplist", "setsid", "initgroups", "get_terminal_size", "isatty", "fork"):
    f = getattr(os, n, None)
    print("OS", n, f is not None, getattr(f, "__text_signature__", None))

#@@PART m2
# N2 measurement 2: behaviour of fcntl / resource / pwd / grp / termios / os pty functions. CPython only.
# SAFETY: terminal calls only on a pty this script opens (os.openpty); never fd 0/1/2 or /dev/tty.
# RLIMIT: the only changes to this process are a soft-limit round trip on RLIMIT_NOFILE (lower by 1, restore) and
# RLIMIT_CORE (same values written back); hard limits are never lowered here; lowering is done with prlimit on a
# `sleep 30` child that this script starts and kills by its own pid. No signal is sent to anything else.
import sys, os, struct, errno, tempfile, subprocess, array

def t(label, f, *a, **k):
    try:
        r = f(*a, **k)
        print(label, "->", repr(r))
        return r
    except BaseException as e:
        print(label, "!!", type(e).__name__, e.args, getattr(e, "errno", None), getattr(e, "filename", None))

print("VERSION", sys.version_info[:2])
if os.getuid() == 0: raise SystemExit("refusing to run as root: the error-path lines below would succeed and change limits")
import fcntl, resource, pwd, grp, termios

events = []
def hook(ev, args):
    if ev.split(".")[0] in ("fcntl", "resource", "termios", "pwd", "grp") or ev in ("os.openpty", "os.ttyname"):
        events.append((ev, args))
sys.addaudithook(hook)

print("== fcntl")
d = tempfile.mkdtemp()
p = os.path.join(d, "f")
fd = os.open(p, os.O_RDWR | os.O_CREAT, 0o600)
t("getfd", fcntl.fcntl, fd, fcntl.F_GETFD)
t("setfd0", fcntl.fcntl, fd, fcntl.F_SETFD, 0)
t("inheritable", os.get_inheritable, fd)
t("setfd1", fcntl.fcntl, fd, fcntl.F_SETFD, fcntl.FD_CLOEXEC)
t("getfl&acc", lambda: fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_ACCMODE)
t("getfl raw", lambda: fcntl.fcntl(fd, fcntl.F_GETFL))
t("setfl nonblock", lambda: fcntl.fcntl(fd, fcntl.F_SETFL, fcntl.fcntl(fd, fcntl.F_GETFL) | os.O_NONBLOCK))
t("get_blocking", os.get_blocking, fd)
nd = t("dupfd_cloexec", lambda: fcntl.fcntl(fd, fcntl.F_DUPFD_CLOEXEC, 100))
if isinstance(nd, int):
    t("dup inheritable", os.get_inheritable, nd); os.close(nd)
nd = t("dupfd", lambda: fcntl.fcntl(fd, fcntl.F_DUPFD, 100))
if isinstance(nd, int):
    t("dupfd inheritable", os.get_inheritable, nd); os.close(nd)
fl = struct.pack("hhqqi4x", fcntl.F_WRLCK, 0, 0, 0, 0)
t("getlk bytes", fcntl.fcntl, fd, fcntl.F_GETLK, fl)
t("getlk bytearray", fcntl.fcntl, fd, fcntl.F_GETLK, bytearray(fl))
t("getlk memoryview", fcntl.fcntl, fd, fcntl.F_GETLK, memoryview(fl))
t("arg str", fcntl.fcntl, fd, fcntl.F_GETLK, fl.decode("latin-1"))
t("arg str nonascii", fcntl.fcntl, fd, fcntl.F_GETFD, "\xe9")
t("arg 1024", lambda: len(fcntl.fcntl(fd, fcntl.F_GETFD, b"\0" * 1024)))
t("arg 1025", fcntl.fcntl, fd, fcntl.F_GETFD, b"\0" * 1025)
t("arg empty", fcntl.fcntl, fd, fcntl.F_GETFD, b"")
t("arg float", fcntl.fcntl, fd, fcntl.F_GETFD, 1.5)
t("arg None", fcntl.fcntl, fd, fcntl.F_GETFD, None)
t("arg 2**31", fcntl.fcntl, fd, fcntl.F_GETFD, 2**31)
t("arg 2**32", fcntl.fcntl, fd, fcntl.F_GETFD, 2**32)
t("arg -1", fcntl.fcntl, fd, fcntl.F_GETFD, -1)
t("arg 2**64", fcntl.fcntl, fd, fcntl.F_GETFD, 2**64)
t("arg True", fcntl.fcntl, fd, fcntl.F_SETFD, True)
t("cmd str", fcntl.fcntl, fd, "x")
t("cmd 2**40", fcntl.fcntl, fd, 2**40)
t("cmd bad", fcntl.fcntl, fd, 99999)
t("fd -1", fcntl.fcntl, -1, fcntl.F_GETFD)
t("fd 9999", fcntl.fcntl, 9999, fcntl.F_GETFD)
t("fd str", fcntl.fcntl, "x", fcntl.F_GETFD)
t("fd float", fcntl.fcntl, 1.0, fcntl.F_GETFD)
t("fd 2**40", fcntl.fcntl, 2**40, fcntl.F_GETFD)
t("fd None", fcntl.fcntl, None, fcntl.F_GETFD)
class F:
    def __init__(self, v): self.v = v
    def fileno(self): return self.v
t("fd fileno obj", fcntl.fcntl, F(fd), fcntl.F_GETFD)
t("fd fileno str", fcntl.fcntl, F("x"), fcntl.F_GETFD)
t("fd fileno -1", fcntl.fcntl, F(-1), fcntl.F_GETFD)
with open(p, "rb") as fo:
    t("fd file object", fcntl.fcntl, fo, fcntl.F_GETFD)
t("kw", lambda: fcntl.fcntl(fd=fd, cmd=1))
t("noargs", fcntl.fcntl)
t("4 args", fcntl.fcntl, fd, 1, 0, 0)
print("-- flock")
fd2 = os.open(p, os.O_RDWR)
t("flock ex", fcntl.flock, fd, fcntl.LOCK_EX)
t("flock conflict", fcntl.flock, fd2, fcntl.LOCK_EX | fcntl.LOCK_NB)
t("flock sh conflict", fcntl.flock, fd2, fcntl.LOCK_SH | fcntl.LOCK_NB)
t("flock un", fcntl.flock, fd, fcntl.LOCK_UN)
t("flock sh", fcntl.flock, fd, fcntl.LOCK_SH)
t("flock sh 2", fcntl.flock, fd2, fcntl.LOCK_SH | fcntl.LOCK_NB)
t("flock un2", fcntl.flock, fd2, fcntl.LOCK_UN)
t("flock un1", fcntl.flock, fd, fcntl.LOCK_UN)
t("flock bad op", fcntl.flock, fd, 0)
t("flock bad op 99", fcntl.flock, fd, 64)
t("flock bad fd", fcntl.flock, 9999, fcntl.LOCK_EX)
t("flock op str", fcntl.flock, fd, "x")
t("flock op 2**40", fcntl.flock, fd, 2**40)
t("flock kw", lambda: fcntl.flock(fd, operation=1))
print("-- lockf")
t("lockf ex", fcntl.lockf, fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
ofd = struct.pack("hhqqi4x", fcntl.F_WRLCK, 0, 0, 0, 0)
r = t("ofd_getlk vs own posix lock", lambda: struct.unpack("hhqqi4x", fcntl.fcntl(fd2, 36, ofd)))
print("  pid matches", isinstance(r, tuple) and r[4] == os.getpid(), "type is WRLCK", isinstance(r, tuple) and r[0] == fcntl.F_WRLCK)
t("ofd_setlk conflict", fcntl.fcntl, fd2, 37, ofd)
t("lockf un", fcntl.lockf, fd, fcntl.LOCK_UN)
t("ofd_getlk after unlock", lambda: struct.unpack("hhqqi4x", fcntl.fcntl(fd2, 36, ofd))[0] == fcntl.F_UNLCK)
t("lockf range", fcntl.lockf, fd, fcntl.LOCK_EX | fcntl.LOCK_NB, 10, 5, 0)
t("ofd_getlk range", lambda: struct.unpack("hhqqi4x", fcntl.fcntl(fd2, 36, ofd))[:4])
t("lockf un range", fcntl.lockf, fd, fcntl.LOCK_UN, 10, 5, 0)
t("lockf sh", fcntl.lockf, fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
t("ofd_getlk sh", lambda: struct.unpack("hhqqi4x", fcntl.fcntl(fd2, 36, ofd))[0] == fcntl.F_RDLCK)
t("lockf un", fcntl.lockf, fd, fcntl.LOCK_UN)
rfd = os.open(p, os.O_RDONLY)
t("lockf ex on rdonly", fcntl.lockf, rfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
os.close(rfd)
t("lockf cmd 0", fcntl.lockf, fd, 0)
t("lockf cmd NB only", fcntl.lockf, fd, fcntl.LOCK_NB)
t("lockf cmd 64", fcntl.lockf, fd, 64)
t("lockf len str", fcntl.lockf, fd, fcntl.LOCK_EX, "x")
t("lockf len float", fcntl.lockf, fd, fcntl.LOCK_EX, 1.5)
t("lockf len 2**70", fcntl.lockf, fd, fcntl.LOCK_EX, 2**70)
t("lockf whence 9", fcntl.lockf, fd, fcntl.LOCK_EX | fcntl.LOCK_NB, 0, 0, 9)
t("lockf bad fd", fcntl.lockf, 9999, fcntl.LOCK_EX)
t("lockf kw", lambda: fcntl.lockf(fd, fcntl.LOCK_UN, len=0))
os.close(fd2)
print("-- ioctl")
r, w = os.pipe()
os.write(w, b"hello")
buf = array.array("i", [0])
t("fionread mutable", fcntl.ioctl, r, termios.FIONREAD, buf); print("  buf", buf[0])
ba = bytearray(4)
t("fionread bytearray", fcntl.ioctl, r, termios.FIONREAD, ba); print("  ba", bytes(ba))
ba = bytearray(4)
t("fionread bytearray mutate False", fcntl.ioctl, r, termios.FIONREAD, ba, False); print("  ba", bytes(ba))
t("fionread bytes", fcntl.ioctl, r, termios.FIONREAD, b"\0\0\0\0")
t("fionread memoryview ro", fcntl.ioctl, r, termios.FIONREAD, memoryview(b"\0\0\0\0"))
big = bytearray(2000)
t("fionread big mutable", fcntl.ioctl, r, termios.FIONREAD, big); print("  big", bytes(big[:4]))
t("fionread big mutable False", fcntl.ioctl, r, termios.FIONREAD, bytearray(2000), False)
t("fionread big bytes", fcntl.ioctl, r, termios.FIONREAD, b"\0" * 1025)
t("fionread 1024 bytes", lambda: len(fcntl.ioctl(r, termios.FIONREAD, b"\0" * 1024)))
t("ioctl str arg", fcntl.ioctl, r, termios.FIONREAD, "\0\0\0\0")
t("ioctl int arg", fcntl.ioctl, r, termios.FIONREAD, 0)
t("ioctl winsz on pipe", fcntl.ioctl, r, termios.TIOCGWINSZ, b"\0" * 8)
t("ioctl bad fd", fcntl.ioctl, 9999, termios.FIONREAD, b"\0" * 4)
t("ioctl req -1", fcntl.ioctl, r, -1)
t("ioctl req 2**32", fcntl.ioctl, r, 2**32)
t("ioctl req 2**31", fcntl.ioctl, r, 2**31)
t("ioctl req -2**31", fcntl.ioctl, r, -2**31)
t("ioctl req -2**31-1", fcntl.ioctl, r, -2**31 - 1)
t("ioctl req 2**64", fcntl.ioctl, r, 2**64)
t("ioctl req str", fcntl.ioctl, r, "x")
t("ioctl arg float", fcntl.ioctl, r, termios.FIONREAD, 1.5)
t("ioctl arg None", fcntl.ioctl, r, termios.FIONREAD, None)
t("ioctl kw", lambda: fcntl.ioctl(r, termios.FIONREAD, arg=0))
t("ioctl FIOCLEX", fcntl.ioctl, r, termios.FIOCLEX)
t("ioctl FIONCLEX", fcntl.ioctl, r, termios.FIONCLEX); t("  inh", os.get_inheritable, r)
t("F_GETPIPE_SZ", fcntl.fcntl, w, fcntl.F_GETPIPE_SZ)
t("F_SETPIPE_SZ", fcntl.fcntl, w, fcntl.F_SETPIPE_SZ, 8192)
os.close(r); os.close(w); os.close(fd)
print("AUDIT fcntl", events); events.clear()

print("== resource")
names = sorted(n for n in dir(resource) if n.startswith("RLIMIT_"))
for n in names:
    try:
        v = resource.getrlimit(getattr(resource, n))
        print("getrlimit", n, type(v).__name__, len(v), [type(x).__name__ for x in v], "soft<=hard", v[0] <= v[1] or v[1] == resource.RLIM_INFINITY, "inf", v[0] == resource.RLIM_INFINITY, v[1] == resource.RLIM_INFINITY)
    except BaseException as e:
        print("getrlimit", n, "!!", type(e).__name__, e)
print("RLIM_INFINITY", resource.RLIM_INFINITY)
print("stack", resource.getrlimit(resource.RLIMIT_STACK), "core", resource.getrlimit(resource.RLIMIT_CORE), "nofile", resource.getrlimit(resource.RLIMIT_NOFILE))
t("getrlimit -1", resource.getrlimit, -1)
t("getrlimit 16", resource.getrlimit, 16)
t("getrlimit 99", resource.getrlimit, 99)
t("getrlimit str", resource.getrlimit, "x")
t("getrlimit float", resource.getrlimit, 1.0)
t("getrlimit 2**40", resource.getrlimit, 2**40)
t("getrlimit kw", lambda: resource.getrlimit(resource=1))
t("getrlimit none", resource.getrlimit)
soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
t("setrlimit same", resource.setrlimit, resource.RLIMIT_NOFILE, (soft, hard))
t("setrlimit lower soft", resource.setrlimit, resource.RLIMIT_NOFILE, (soft - 1, hard))
print("  lowered", resource.getrlimit(resource.RLIMIT_NOFILE) == (soft - 1, hard))
t("setrlimit restore", resource.setrlimit, resource.RLIMIT_NOFILE, (soft, hard))
print("  restored", resource.getrlimit(resource.RLIMIT_NOFILE) == (soft, hard))
t("setrlimit list", resource.setrlimit, resource.RLIMIT_NOFILE, [soft, hard])
if hard != resource.RLIM_INFINITY:
    t("setrlimit soft>hard", resource.setrlimit, resource.RLIMIT_NOFILE, (hard + 1, hard))
    t("setrlimit raise hard", resource.setrlimit, resource.RLIMIT_NOFILE, (soft, hard + 1))
t("setrlimit 1-tuple", resource.setrlimit, resource.RLIMIT_NOFILE, (soft,))
t("setrlimit 3-tuple", resource.setrlimit, resource.RLIMIT_NOFILE, (soft, hard, 1))
t("setrlimit int", resource.setrlimit, resource.RLIMIT_NOFILE, 5)
t("setrlimit str items", resource.setrlimit, resource.RLIMIT_NOFILE, ("a", "b"))
t("setrlimit float items", resource.setrlimit, resource.RLIMIT_NOFILE, (float(soft), hard))
t("setrlimit bad res", resource.setrlimit, 99, (1, 1))
t("setrlimit bad res -1", resource.setrlimit, -1, (1, 1))
t("setrlimit 2**70", resource.setrlimit, resource.RLIMIT_NOFILE, (2**70, 2**70))
t("setrlimit 2**64", resource.setrlimit, resource.RLIMIT_NOFILE, (soft, 2**64))
t("setrlimit 2**63", resource.setrlimit, resource.RLIMIT_NOFILE, (soft, 2**63))
t("setrlimit hard -2 (soft same)", resource.setrlimit, resource.RLIMIT_NOFILE, (soft, -2))
cs, ch = resource.getrlimit(resource.RLIMIT_CORE)
t("setrlimit core same", resource.setrlimit, resource.RLIMIT_CORE, (cs, ch))
t("setrlimit core (cs, -1)", resource.setrlimit, resource.RLIMIT_CORE, (cs, -1))
print("  core now", resource.getrlimit(resource.RLIMIT_CORE) == (cs, ch), "ch is inf", ch == resource.RLIM_INFINITY)
t("setrlimit core (cs, RLIM_INFINITY)", resource.setrlimit, resource.RLIMIT_CORE, (cs, resource.RLIM_INFINITY))
t("setrlimit kw", lambda: resource.setrlimit(resource.RLIMIT_CORE, limits=(cs, ch)))
t("prlimit self get", lambda: resource.prlimit(0, resource.RLIMIT_NOFILE) == (soft, hard))
t("prlimit self pid get", lambda: resource.prlimit(os.getpid(), resource.RLIMIT_NOFILE) == (soft, hard))
t("prlimit self set same", lambda: resource.prlimit(0, resource.RLIMIT_NOFILE, (soft, hard)) == (soft, hard))
t("prlimit None", lambda: resource.prlimit(0, resource.RLIMIT_NOFILE, None) == (soft, hard))
t("prlimit bad res", resource.prlimit, 0, 99)
t("prlimit missing pid", resource.prlimit, 2**22 + 12345, resource.RLIMIT_NOFILE)
t("prlimit pid 1 get", lambda: type(resource.prlimit(1, resource.RLIMIT_NOFILE)).__name__)
t("prlimit pid 1 set", resource.prlimit, 1, resource.RLIMIT_NOFILE, (soft, hard))
t("prlimit pid str", resource.prlimit, "x", 1)
t("prlimit pid 2**40", resource.prlimit, 2**40, 1)
t("prlimit limits 1-tuple", resource.prlimit, 0, resource.RLIMIT_NOFILE, (1,))
t("prlimit limits int", resource.prlimit, 0, resource.RLIMIT_NOFILE, 5)
t("prlimit soft>hard", resource.prlimit, 0, resource.RLIMIT_NOFILE, (hard + 1, hard)) if hard != resource.RLIM_INFINITY else None
child = subprocess.Popen(["sleep", "30"])
try:
    t("prlimit child lower", resource.prlimit, child.pid, resource.RLIMIT_NOFILE, (64, 128))
    t("prlimit child read", resource.prlimit, child.pid, resource.RLIMIT_NOFILE)
    t("prlimit child raise hard", resource.prlimit, child.pid, resource.RLIMIT_NOFILE, (64, 256))
finally:
    child.kill(); child.wait(timeout=30)
print("  self unchanged", resource.getrlimit(resource.RLIMIT_NOFILE) == (soft, hard))
# a child started with a lowered limit sees it (limits are inherited; set through prlimit on the shell itself)
r = subprocess.run(["sh", "-c", "ulimit -n 77; ulimit -n"], capture_output=True, timeout=60)
print("child ulimit", r.stdout)
ru = resource.getrusage(resource.RUSAGE_SELF)
print("rusage", type(ru).__module__, type(ru).__qualname__, len(ru), [type(x).__name__ for x in ru], ru.ru_maxrss > 0, repr(ru)[:32])
t("rusage children type", lambda: type(resource.getrusage(resource.RUSAGE_CHILDREN)).__name__)
t("rusage thread", lambda: type(resource.getrusage(resource.RUSAGE_THREAD)).__name__)
t("rusage 5", resource.getrusage, 5)
t("rusage -2", resource.getrusage, -2)
t("rusage str", resource.getrusage, "x")
t("rusage 2**40", resource.getrusage, 2**40)
t("rusage kw", lambda: resource.getrusage(who=0))
t("pagesize", lambda: resource.getpagesize() == os.sysconf("SC_PAGESIZE"))
t("pagesize arg", resource.getpagesize, 1)
t("struct_rusage()", resource.struct_rusage)
t("struct_rusage(range16)", lambda: resource.struct_rusage(range(16)))
t("struct_rusage(range3)", lambda: resource.struct_rusage(range(3)))
print("AUDIT resource", events); events.clear()

print("== pwd")
me = pwd.getpwuid(os.getuid())
print("me", type(me).__module__, type(me).__qualname__, len(me), [type(x).__name__ for x in me], me == pwd.getpwnam(me.pw_name), me[0] == me.pw_name, repr(me).startswith("pwd.struct_passwd(pw_name='"))
root = pwd.getpwuid(0); print("root", root)
t("root by name", lambda: pwd.getpwnam("root") == root)
t("uid missing", pwd.getpwuid, 4000000123)
t("uid -1", pwd.getpwuid, -1)
t("uid -2", pwd.getpwuid, -2)
t("uid 2**32", pwd.getpwuid, 2**32)
t("uid 2**32-1", pwd.getpwuid, 2**32 - 1)
t("uid 2**64", pwd.getpwuid, 2**64)
t("uid -2**70", pwd.getpwuid, -2**70)
t("uid str", pwd.getpwuid, "0")
t("uid float", pwd.getpwuid, 0.0)
t("uid None", pwd.getpwuid, None)
t("uid True", lambda: pwd.getpwuid(False) == root)
t("uid kw", lambda: pwd.getpwuid(uidobj=0))
t("uid noarg", pwd.getpwuid)
t("nam missing", pwd.getpwnam, "pyjvm-no-such-user")
t("nam empty", pwd.getpwnam, "")
t("nam nul", pwd.getpwnam, "a\0b")
t("nam bytes", pwd.getpwnam, b"root")
t("nam int", pwd.getpwnam, 0)
t("nam nonascii", pwd.getpwnam, "\xe9€")
t("nam surrogate", pwd.getpwnam, "\udcff")
t("nam kw", lambda: pwd.getpwnam(name="root"))
al = pwd.getpwall()
print("all", type(al).__name__, len(al) > 0, all(type(x) is pwd.struct_passwd for x in al), any(x.pw_uid == os.getuid() for x in al), any(x.pw_name == "root" for x in al))
t("all arg", pwd.getpwall, 1)
t("struct_passwd(range7)", lambda: pwd.struct_passwd(range(7)))
t("struct_passwd()", pwd.struct_passwd)
import pickle, copy
t("pickle", lambda: pickle.loads(pickle.dumps(root)) == root)
t("reduce", lambda: root.__reduce__()[0])
t("copy", lambda: type(copy.copy(root)).__name__)
t("tuple eq", lambda: tuple(root) == root)
t("hash", lambda: hash(root) == hash(tuple(root)))
t("slice", lambda: type(root[0:2]).__name__)
t("setattr", lambda: setattr(root, "pw_uid", 5))
t("n_fields", lambda: (root.n_fields, root.n_sequence_fields, root.n_unnamed_fields))
t("_replace", lambda: hasattr(root, "_replace"))
t("__replace__", lambda: type(root.__replace__(pw_uid=7)).__name__)
print("== grp")
g = grp.getgrgid(os.getgid())
print("g", type(g).__module__, type(g).__qualname__, len(g), [type(x).__name__ for x in g], g == grp.getgrnam(g.gr_name), repr(g).startswith("grp.struct_group(gr_name='"))
rg = grp.getgrgid(0); print("root", rg)
t("gid missing", grp.getgrgid, 4000000123)
t("gid -1", grp.getgrgid, -1)
t("gid 2**32", grp.getgrgid, 2**32)
t("gid 2**64", grp.getgrgid, 2**64)
t("gid str", grp.getgrgid, "0")
t("gid float", grp.getgrgid, 0.0)
t("gid kw id", lambda: grp.getgrgid(id=0) == rg)
t("gid noarg", grp.getgrgid)
t("nam missing", grp.getgrnam, "pyjvm-no-such-group")
t("nam nul", grp.getgrnam, "a\0b")
t("nam bytes", grp.getgrnam, b"root")
t("nam int", grp.getgrnam, 0)
t("nam kw", lambda: grp.getgrnam(name="root") == rg)
t("nam surrogate", grp.getgrnam, "\udcff")
ga = grp.getgrall()
print("all", type(ga).__name__, len(ga) > 0, all(type(x) is grp.struct_group for x in ga), all(type(x.gr_mem) is list for x in ga), any(x.gr_mem and all(type(m) is str for m in x.gr_mem) for x in ga))
t("all arg", grp.getgrall, 1)
print("AUDIT pwd/grp", events); events.clear()

print("== pty / termios")
m, s = os.openpty()
print("openpty", m > 2, s > 2, os.get_inheritable(m), os.get_inheritable(s), os.isatty(m), os.isatty(s), os.get_blocking(m), os.get_blocking(s))
t("ttyname slave", lambda: os.ttyname(s).startswith("/dev/pts/"))
t("ttyname master", os.ttyname, m)
rp, wp = os.pipe()
t("ttyname pipe", os.ttyname, rp)
t("ttyname bad", os.ttyname, 9999)
t("ttyname str", os.ttyname, "x")
t("openpty arg", os.openpty, 1)
a = termios.tcgetattr(s)
print("attr shape", type(a).__name__, len(a), [type(x).__name__ for x in a[:6]], type(a[6]).__name__, len(a[6]), sorted(set(type(x).__name__ for x in a[6])), [len(x) for x in a[6] if isinstance(x, bytes)] == [1] * 32)
print("attr values", a[:6], a[6])
print("lflag ECHO ICANON", bool(a[3] & termios.ECHO), bool(a[3] & termios.ICANON), "speeds", a[4] == termios.B38400, a[5] == termios.B38400)
t("tcgetattr master", lambda: termios.tcgetattr(m)[:6] == a[:6])
b = termios.tcgetattr(s); b[3] &= ~termios.ECHO
t("tcsetattr echo off", termios.tcsetattr, s, termios.TCSANOW, b)
print("  echo now", bool(termios.tcgetattr(s)[3] & termios.ECHO))
b[3] &= ~termios.ICANON; b[6][termios.VMIN] = 1; b[6][termios.VTIME] = 0
t("tcsetattr raw-ish (int cc)", termios.tcsetattr, s, termios.TCSADRAIN, b)
c = termios.tcgetattr(s)
print("  noncanon cc types", type(c[6][termios.VMIN]).__name__, c[6][termios.VMIN], type(c[6][termios.VTIME]).__name__, c[6][termios.VTIME], sorted(set(type(x).__name__ for x in c[6])))
t("tcsetattr restore", termios.tcsetattr, s, termios.TCSAFLUSH, a)
print("  restored", termios.tcgetattr(s) == a)
t("tcsetattr tuple", termios.tcsetattr, s, termios.TCSANOW, tuple(a))
t("tcsetattr 6 list", termios.tcsetattr, s, termios.TCSANOW, a[:6])
t("tcsetattr cc tuple", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [tuple(a[6])])
t("tcsetattr cc 31", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [a[6][:31]])
t("tcsetattr cc str", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [["a"] * 32])
t("tcsetattr cc 2bytes", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [[b"ab"] * 32])
t("tcsetattr cc int 300", termios.tcsetattr, s, termios.TCSANOW, a[:6] + [[300] * 32])
print("  cc after 300", termios.tcgetattr(s)[6][:3])
t("tcsetattr restore", termios.tcsetattr, s, termios.TCSANOW, a)
t("tcsetattr flag str", termios.tcsetattr, s, termios.TCSANOW, ["x"] + a[1:])
t("tcsetattr flag 2**40", termios.tcsetattr, s, termios.TCSANOW, [2**40] + a[1:])
t("tcsetattr flag -1", termios.tcsetattr, s, termios.TCSANOW, [-1] + a[1:])
t("tcsetattr restore", termios.tcsetattr, s, termios.TCSANOW, a)
t("tcsetattr bad speed", termios.tcsetattr, s, termios.TCSANOW, a[:4] + [12345, 12345] + [a[6]])
t("tcsetattr bad when", termios.tcsetattr, s, 99, a)
t("tcsetattr when str", termios.tcsetattr, s, "x", a)
t("tcsetattr pipe", termios.tcsetattr, rp, termios.TCSANOW, a)
t("tcgetattr pipe", termios.tcgetattr, rp)
t("tcgetattr bad fd", termios.tcgetattr, 9999)
t("tcgetattr -1", termios.tcgetattr, -1)
t("tcgetattr str", termios.tcgetattr, "x")
t("tcgetattr fileno obj", lambda: termios.tcgetattr(F(s)) == a)
t("tcgetattr kw", lambda: termios.tcgetattr(fd=s))
t("winsize get", termios.tcgetwinsize, s)
t("winsize set", termios.tcsetwinsize, s, (24, 80))
t("winsize get2", termios.tcgetwinsize, s)
t("winsize via master", termios.tcgetwinsize, m)
t("os.get_terminal_size", lambda: tuple(os.get_terminal_size(s)))
t("winsize set list", termios.tcsetwinsize, s, [30, 100])
t("winsize set 3", termios.tcsetwinsize, s, (1, 2, 3))
t("winsize set int", termios.tcsetwinsize, s, 5)
t("winsize set str items", termios.tcsetwinsize, s, ("a", "b"))
t("winsize set 70000", termios.tcsetwinsize, s, (70000, 1))
t("winsize set -1", termios.tcsetwinsize, s, (-1, 1))
t("winsize set 2**70", termios.tcsetwinsize, s, (2**70, 1))
t("winsize pipe", termios.tcgetwinsize, rp)
t("TIOCGWINSZ ioctl", lambda: struct.unpack("HHHH", fcntl.ioctl(s, termios.TIOCGWINSZ, b"\0" * 8)))
t("tcdrain", termios.tcdrain, s)
t("tcflush", termios.tcflush, s, termios.TCIOFLUSH)
t("tcflush bad", termios.tcflush, s, 99)
t("tcflow off", termios.tcflow, s, termios.TCOOFF)
t("tcflow on", termios.tcflow, s, termios.TCOON)
t("tcflow bad", termios.tcflow, s, 99)
t("tcsendbreak", termios.tcsendbreak, s, 0)
t("tcdrain pipe", termios.tcdrain, rp)
t("tcflush pipe", termios.tcflush, rp, termios.TCIFLUSH)
t("tcsendbreak pipe", termios.tcsendbreak, rp, 0)
t("tcflow str", termios.tcflow, s, "x")
# data through the pty with echo off, canonical mode
b = termios.tcgetattr(s); b[3] &= ~termios.ECHO; termios.tcsetattr(s, termios.TCSANOW, b)
os.write(m, b"line\n"); print("slave read", os.read(s, 100))
os.write(s, b"out\n"); print("master read", os.read(m, 100))
import tty, pty
t("tty.setraw", lambda: type(tty.setraw(s)).__name__)
print("  raw lflag", termios.tcgetattr(s)[3] & (termios.ECHO | termios.ICANON | termios.ISIG))
t("tty.setcbreak", lambda: type(tty.setcbreak(s)).__name__)
t("pty.openpty", lambda: [os.close(x) for x in pty.openpty()])
e = None
try: termios.tcgetattr(rp)
except termios.error as x: e = x
print("termios.error", type(e).__name__, type(e).__module__, e.args, isinstance(e, OSError), str(e), repr(e))
for n in ("posix_openpt", "grantpt", "unlockpt", "ptsname"):
    print("os has", n, hasattr(os, n))
if hasattr(os, "posix_openpt"):
    pm = os.posix_openpt(os.O_RDWR | os.O_NOCTTY)
    print("posix_openpt", pm > 2, os.get_inheritable(pm)); t("grantpt", os.grantpt, pm); t("unlockpt", os.unlockpt, pm); t("ptsname", lambda: os.ptsname(pm).startswith("/dev/pts/"))
    t("ptsname pipe", os.ptsname, rp); t("grantpt pipe", os.grantpt, rp); t("posix_openpt bad", os.posix_openpt, -1)
    os.close(pm)
os.close(m)
t("tcgetattr slave after master close", termios.tcgetattr, s)
t("read slave after master close", os.read, s, 10)
os.close(s); os.close(rp); os.close(wp)
print("AUDIT pty/termios", events)
os.unlink(p); os.rmdir(d)
print("DONE")
