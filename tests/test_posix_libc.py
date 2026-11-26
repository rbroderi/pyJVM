# SPDX-License-Identifier: GPL-3.0-or-later
"""The small libc modules on Linux (N2): shared plumbing now; each later ticket appends the pyJVM-specific tests of its
module here. Every test runs pyJVM on a small script; where it says so the output is compared with the reference CPython.

SAFETY (n2_plan 2.3): no terminal, no resource limit, no lock and no signal is touched by the tests of this ticket."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

from pyjvm315.cli import build_runtime
from pyjvm315.conformance import compare_file
from pyjvm315.driver import compile_file
from pyjvm315.hostver import reference_stdlib, reference_version
from pyjvm315.selfhost import build_selfhosted

pytestmark = pytest.mark.skipif(not sys.platform.startswith("linux"), reason="the N2 libc modules are Linux-only")


@pytest.fixture(scope="module")
def jvm_kit():
    with tempfile.TemporaryDirectory(prefix="pyjvm-posix-libc-test-") as td:
        runtime = Path(td) / "runtime"
        build_runtime(runtime)
        yield runtime, build_selfhosted(Path(td) / "selfhosted")


def _compare(kit, tmp_path: Path, monkeypatch, script: str) -> None:
    """Run ``script`` under pyJVM and the reference CPython; the outputs must be identical."""
    runtime, selfhosted = kit
    main = tmp_path / "main.py"
    main.write_text(script, encoding="utf-8")
    monkeypatch.delenv("PYJVM_POSIX_FDS", raising=False)
    # compare_file starts CPython and the JVM without a timeout: give every subprocess of this test one
    real_run = subprocess.run
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: real_run(*a, **{"timeout": 900, **k}))
    result = compare_file(main, sys.executable, runtime_cache=runtime, selfhosted=selfhosted)
    assert result.status == "PASS", result.detail


STRUCTSEQ = '''
import copy
import os
import pickle


def t(label, f):
    try:
        print(label, "->", repr(f()))
    except BaseException as e:
        print(label, "!!", type(e).__name__, e.args)


cls = os.waitid_result
n = cls.n_fields
t("list", lambda: cls([1, 2, 3, 4, 5]))
t("tuple", lambda: cls((1, 2, 3, 4, 5)))
t("generator", lambda: cls(i for i in range(n)))
t("range", lambda: cls(range(n)))
t("str", lambda: cls("abcde"))
t("too short", lambda: cls(range(n - 1)))
t("too long", lambda: cls(range(n + 1)))
t("empty", lambda: cls(()))
t("no args", lambda: cls())
t("two args", lambda: cls(range(n), range(n)))
t("dict arg", lambda: cls(range(n), {}))
t("dict arg 2", lambda: cls(range(n), {"x": 1}))
t("dict arg non-dict", lambda: cls(range(n), 5))
t("keyword sequence", lambda: cls(sequence=range(n)))
t("keyword dict", lambda: cls(range(n), dict={}))
t("non-iterable", lambda: cls(5))
t("None", lambda: cls(None))
r = cls([10, 20, 30, 40, 50])
print(type(r).__name__, r[0], r[-1], r[:2], type(r[:2]).__name__, len(r), tuple(r))
print(r == (10, 20, 30, 40, 50), r != (10, 20, 30, 40, 50), hash(r) == hash((10, 20, 30, 40, 50)), r == cls(range(10, 60, 10)))
print(r.si_pid, r.si_code, repr(r))
t("reduce", lambda: r.__reduce__())
for proto in range(6):
    t("pickle %d" % proto, lambda: (pickle.loads(pickle.dumps(r, proto)) == r, type(pickle.loads(pickle.dumps(r, proto))) is cls))
t("copy", lambda: (copy.copy(r) == r, type(copy.copy(r)) is cls, copy.copy(r) is r))
t("deepcopy", lambda: (copy.deepcopy(r) == r, type(copy.deepcopy(r)) is cls))
t("attr after copy", lambda: copy.copy(r).si_status)
t("attr after pickle", lambda: pickle.loads(pickle.dumps(r)).si_code)
t("setattr", lambda: setattr(r, "si_pid", 1))
t("setattr field", lambda: setattr(r, "si_pid", 1))
t("setattr n_fields", lambda: setattr(r, "n_fields", 1))
t("setattr foo", lambda: setattr(r, "foo", 1))
t("delattr field", lambda: delattr(r, "si_pid"))
t("delattr n_fields", lambda: delattr(r, "n_fields"))
t("delattr foo", lambda: delattr(r, "foo"))
t("5, 5", lambda: cls(5, 5))
t("5, None", lambda: cls(5, None))
t("range, None", lambda: cls(range(n), None))
t("pos+name sequence", lambda: cls(range(n), sequence=range(n)))
t("three args", lambda: cls(range(n), {}, 1))
t("unknown keyword", lambda: cls(range(n), foo=1))


def bad_gen(exc):
    yield 1
    raise exc


class BadIter:
    def __iter__(self):
        raise TypeError("iter failed")


t("iteration TypeError", lambda: cls(bad_gen(TypeError("inner"))))
t("iteration ZeroDivisionError", lambda: cls(bad_gen(ZeroDivisionError("zd"))))
t("__iter__ raises TypeError", lambda: cls(BadIter()))
t("dir", lambda: [n for n in dir(r) if n.startswith("si_")])
t("no dict", lambda: hasattr(r, "__dict__"))
t("subclass", lambda: type("S", (cls,), {}))
t("match args",lambda: cls.__match_args__)
match r:
    case cls(si_pid=10, si_code=c):
        print("match", c)
'''


def test_structseq_construct_pickle_copy(jvm_kit, tmp_path, monkeypatch):
    """os.waitid_result is the one public PosixStructSeq type before ticket 2: construction, errors, pickle, copy."""
    _compare(jvm_kit, tmp_path, monkeypatch, STRUCTSEQ)


# ---- resource (N2 ticket 2). SAFETY (n2_plan 2.3 B): no limit of the test process is changed; the only limit change is
# prlimit on the test's own `sleep 30` child (killed and waited for). RESOURCE_WAIT3 reaps with os.wait3, which takes any
# child; its process has no other children.

RESOURCE_WAIT3 = '''
import os
import resource
import sys

pid = os.posix_spawn("/bin/true", ["true"], {})
got, status, ru = os.wait3(0)
print(got == pid, status, type(ru) is resource.struct_rusage, type(resource.getrusage(resource.RUSAGE_SELF)) is type(ru))
print(hasattr(resource, "RLIM_SAVED_CUR"), hasattr(resource, "RLIM_SAVED_MAX"))
print(type(resource.RLIM_INFINITY).__name__, resource.RLIM_INFINITY)
print(resource.getrlimit(resource.RLIMIT_STACK)[1] == resource.RLIM_INFINITY)
'''


def test_resource_wait3_class_and_lane_constants(jvm_kit, tmp_path, monkeypatch):
    """(b) wait3's rusage is the one resource.struct_rusage class; (c) RLIM_SAVED_* exist on 3.15 only and RLIM_INFINITY
    is -1 on 3.13/3.14 and 2**64 - 1 on 3.15 (both compared with the reference CPython of the lane)."""
    _compare(jvm_kit, tmp_path, monkeypatch, RESOURCE_WAIT3)


RESOURCE_HIGH_LIMITS = '''
import resource
import subprocess

R = resource.RLIMIT_CORE
child = subprocess.Popen(["sleep", "30"])
try:
    hard = resource.prlimit(child.pid, R)[1]
    if hard != resource.RLIM_INFINITY:
        print("hard limit is not infinity: skipped")
    else:
        for soft in (2**63, 2**64 - 2, 2**63 - 1, 12345, resource.RLIM_INFINITY):
            resource.prlimit(child.pid, R, (soft, hard))
            print(soft, "->", resource.prlimit(child.pid, R))
finally:
    child.kill()
    print("child", child.wait(timeout=30))
'''


def test_resource_high_limits_read_unsigned(jvm_kit, tmp_path, monkeypatch):
    """rlim_t is unsigned: a child's soft limit of 2**63 or 2**64 - 2 reads back unsigned on every lane; only infinity is -1
    on 3.13/3.14 (2**64 - 1 on 3.15). Set with prlimit on the test's own sleep child only; compared with CPython."""
    _compare(jvm_kit, tmp_path, monkeypatch, RESOURCE_HIGH_LIMITS)


def test_resource_nio_rollback_raises_not_implemented(jvm_kit, tmp_path):
    """(a) Under PYJVM_POSIX_FDS=nio the shell stays importable but returns no made-up values."""
    runtime, selfhosted = jvm_kit
    main = tmp_path / "main.py"
    main.write_text(
        "import resource\n"
        "for f, a in ((resource.getrlimit, (0,)), (resource.setrlimit, (0, (1, 1))), (resource.getpagesize, ()), (resource.getrusage, (0,))):\n"
        "    try:\n        f(*a)\n    except NotImplementedError as e:\n        print('NotImplementedError', e)\n",
        encoding="utf-8")
    out = tmp_path / "out"
    compile_file(main, out, "ConformanceMain")
    shutil.copytree(runtime / "pyjvm315", out / "pyjvm315", dirs_exist_ok=True)
    env = os.environ.copy()
    env["PYJVM_POSIX_FDS"] = "nio"
    env["PYJVM_LANG_VERSION"] = "%d.%d" % reference_version(sys.executable)
    env["PYJVM_STDLIB"] = os.environ.get("PYJVM_STDLIB") or reference_stdlib(sys.executable)
    env.setdefault("PYTHONIOENCODING", "utf-8")
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "-Xss64m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-cp", str(out) + os.pathsep + str(selfhosted), "ConformanceMain"]
    proc = subprocess.run(cmd, cwd=tmp_path, capture_output=True, encoding="utf-8", errors="replace", env=env, timeout=600)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout.splitlines() == [
        "NotImplementedError resource.getrlimit is not supported by pyJVM",
        "NotImplementedError resource.setrlimit is not supported by pyJVM",
        "NotImplementedError resource.getpagesize is not supported by pyJVM",
        "NotImplementedError resource.getrusage is not supported by pyJVM",
    ]
# ---- ticket 3: pwd and grp (PosixAccounts) ---------------------------------------------------------------------------


def _accounts_run(kit, main: Path, script: str, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    """Run ``script`` under pyJVM with ``env`` added (PYJVM_POSIX_FDS dropped unless ``env`` sets it); stdout is returned."""
    import os
    import shutil

    from pyjvm315.driver import compile_file
    from pyjvm315.hostver import reference_stdlib, reference_version

    runtime, selfhosted = kit
    main.write_text(script, encoding="utf-8")
    out = main.parent / "out"
    compile_file(main, out, "ConformanceMain")
    shutil.copytree(runtime / "pyjvm315", out / "pyjvm315", dirs_exist_ok=True)
    full = os.environ.copy()
    full.pop("PYJVM_POSIX_FDS", None)
    full.update(env)
    full["PYJVM_LANG_VERSION"] = "%d.%d" % reference_version(sys.executable)
    full["PYJVM_STDLIB"] = os.environ.get("PYJVM_STDLIB") or reference_stdlib(sys.executable)
    full.setdefault("PYTHONIOENCODING", "utf-8")
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "-Xss64m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-cp", str(out) + os.pathsep + str(selfhosted), "ConformanceMain"]
    return subprocess.run(cmd, cwd=main.parent, capture_output=True, encoding="utf-8", errors="replace", env=full, timeout=600)


ACCOUNT_LOOKUPS = '''
import grp, pwd
print(repr(pwd.getpwuid(0)))
print(repr(pwd.getpwnam("root")))
print(repr(grp.getgrgid(0)))
print(repr(grp.getgrnam("root")))
try:
    pwd.getpwnam("pyjvm-no-such-user")
except KeyError as e:
    print("KeyError", e.args)
'''


def test_account_lookup_buffer_growth(jvm_kit, tmp_path):
    """A 16-byte first buffer (test hook pyjvm.test.pwbuf, via JAVA_TOOL_OPTIONS) forces ERANGE doubling; same results."""
    normal = _accounts_run(jvm_kit, tmp_path / "main.py", ACCOUNT_LOOKUPS, {})
    assert normal.returncode == 0, normal.stderr
    assert "pw_name='root'" in normal.stdout and "gr_name='root'" in normal.stdout
    small = _accounts_run(jvm_kit, tmp_path / "main.py", ACCOUNT_LOOKUPS, {"JAVA_TOOL_OPTIONS": "-Dpyjvm.test.pwbuf=16"})
    assert small.returncode == 0, small.stderr
    assert small.stdout == normal.stdout


ACCOUNT_THREADS = '''
import grp, pwd, threading
want_pw = [tuple(x) for x in pwd.getpwall()]
want_gr = [tuple((*tuple(x)[:3], tuple(x[3]))) for x in grp.getgrall()]
bad = []
def work():
    for _ in range(20):
        if [tuple(x) for x in pwd.getpwall()] != want_pw:
            bad.append("pw")
        if [tuple((*tuple(x)[:3], tuple(x[3]))) for x in grp.getgrall()] != want_gr:
            bad.append("gr")
ts = [threading.Thread(target=work) for _ in range(8)]
for t in ts:
    t.start()
for t in ts:
    t.join(120)
print(bad, len(want_pw) > 0, len(want_gr) > 0, any(t.is_alive() for t in ts))
'''


def test_account_enumeration_from_threads(jvm_kit, tmp_path):
    """Eight threads enumerating both databases 20 times each all see the same lists (ENT_LOCK)."""
    proc = _accounts_run(jvm_kit, tmp_path / "main.py", ACCOUNT_THREADS, {})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == "[] True True False\n"


def test_account_enumeration_not_implemented_under_nio(jvm_kit, tmp_path):
    """Under PYJVM_POSIX_FDS=nio the shells stay, but getpwall/getgrall no longer return an empty list."""
    script = ('import grp, pwd\n'
              'for f in (pwd.getpwall, grp.getgrall):\n'
              '    try:\n        f()\n    except NotImplementedError as e:\n        print("NotImplementedError", e.args)\n')
    proc = _accounts_run(jvm_kit, tmp_path / "main.py", script, {"PYJVM_POSIX_FDS": "nio"})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == ("NotImplementedError ('pwd.getpwall is not supported by pyJVM',)\n"
                           "NotImplementedError ('grp.getgrall is not supported by pyJVM',)\n")


# ---- N2 ticket 4: fcntl (fcntl, flock, lockf) and the lock-wait rule -------------------------------------------------
# SAFETY (n2_plan 2.3 C, D): locks only on temporary files the scripts create; every waiter has a holder that releases
# within two seconds (the flock holder keeps its lock 2 s, so the 0.1 s timer and its handler always fire first, even
# on a loaded host); threads are joined with timeouts; the only signal is SIGALRM to the own process through setitimer,
# reset in `finally`; every subprocess has a timeout. stdout is compared (CPython reports the stale file object's failed
# close on stderr, as in test_posix_fds).

def _t4_pyjvm(kit, main: Path, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    """``main`` compiled and run under pyJVM with ``env`` added (an inherited PYJVM_POSIX_FDS is dropped first)."""
    import os
    import shutil

    from pyjvm315.driver import compile_file
    from pyjvm315.hostver import reference_stdlib, reference_version

    runtime, selfhosted = kit
    out = main.parent / "out"
    compile_file(main, out, "ConformanceMain")
    shutil.copytree(runtime / "pyjvm315", out / "pyjvm315", dirs_exist_ok=True)
    full = os.environ.copy()
    full.pop("PYJVM_POSIX_FDS", None)
    full.update(env)
    full["PYJVM_LANG_VERSION"] = "%d.%d" % reference_version(sys.executable)
    full["PYJVM_STDLIB"] = os.environ.get("PYJVM_STDLIB") or reference_stdlib(sys.executable)
    full.setdefault("PYTHONIOENCODING", "utf-8")
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "-Xss64m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-cp", str(out) + os.pathsep + str(selfhosted), "ConformanceMain"]
    return subprocess.run(cmd, cwd=main.parent, capture_output=True, encoding="utf-8", errors="replace", env=full,
                          timeout=600, stdin=subprocess.DEVNULL)


def _t4_same_stdout(kit, tmp_path: Path, script: str, expected: str) -> None:
    """``script`` prints ``expected`` under the reference CPython and under pyJVM (kernel descriptors)."""
    main = tmp_path / "main.py"
    main.write_text(script, encoding="utf-8")
    ref = subprocess.run([sys.executable, str(main)], capture_output=True, encoding="utf-8", timeout=120,
                         stdin=subprocess.DEVNULL, cwd=tmp_path)
    assert (ref.returncode, ref.stdout) == (0, expected), ref.stderr
    proc = _t4_pyjvm(kit, main, {})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == expected, proc.stderr


T4_DUPFD = '''
import fcntl, gc, os, tempfile
d = tempfile.mkdtemp()
p1, p2 = os.path.join(d, "one"), os.path.join(d, "two")
for p, data in ((p1, b"first"), (p2, b"second")):
    with open(p, "wb") as w:
        w.write(data)
base = os.open(p1, os.O_RDONLY)
for cmd in (fcntl.F_DUPFD, fcntl.F_DUPFD_CLOEXEC):
    nd = fcntl.fcntl(base, cmd, 50)
    print("listed", nd >= 50, str(nd) in os.listdir("/proc/self/fd"), os.readlink("/proc/self/fd/%d" % nd) == os.path.realpath(p1))
    os.closerange(nd, nd + 1)          # pyJVM's closerange skips numbers it never adopted
    try:
        os.fstat(nd)
        print("closerange left it open")
    except OSError as e:
        print("closed by closerange", e.errno)
for cmd in (fcntl.F_DUPFD, fcntl.F_DUPFD_CLOEXEC):
    nd = fcntl.fcntl(base, cmd, 50)
    f = os.fdopen(nd, "rb")
    os.close(nd)
    del f                              # stale file object: its late close must not hit the reused number
    src = os.open(p2, os.O_RDONLY)
    os.dup2(src, nd)
    os.close(src)
    for _ in range(3):
        gc.collect()
    os.lseek(nd, 0, 0)
    print("reused number intact", os.read(nd, 100))
    os.close(nd)
os.close(base)
for p in (p1, p2):
    os.remove(p)
os.rmdir(d)
'''


def test_fcntl_dupfd_descriptors_are_adopted(jvm_kit, tmp_path):
    """(a) F_DUPFD / F_DUPFD_CLOEXEC results are adopted: listed, closed by closerange, guarded by their generation."""
    _t4_same_stdout(jvm_kit, tmp_path, T4_DUPFD,
                    "listed True True True\nclosed by closerange 9\n" * 2 + "reused number intact b'second'\n" * 2)


T4_LOCK_WAIT = '''
import fcntl, os, signal, struct, tempfile, threading, time
d = tempfile.mkdtemp()
p = os.path.join(d, "f")
fd = os.open(p, os.O_RDWR | os.O_CREAT, 0o600)
fd2 = os.open(p, os.O_RDWR)
fd3 = os.open(p, os.O_RDWR)
state = {"released": False}
log = []


class Boom(Exception):
    pass


def holder(got, hold):
    fcntl.flock(fd, fcntl.LOCK_EX)     # not the main thread: one blocking call (the lock is free)
    got.set()
    time.sleep(hold)
    state["released"] = True
    fcntl.flock(fd, fcntl.LOCK_UN)


def note(signum, frame):
    log.append(("alarm", state["released"]))


def boom(signum, frame):
    log.append(("alarm", state["released"]))
    raise Boom()


def main_thread_wait(label, handler):
    # (b) a handler runs while the main thread waits in flock; (c) a raising handler propagates, the lock not taken
    state["released"] = False
    got = threading.Event()
    th = threading.Thread(target=holder, args=(got, 2.0))
    th.start()
    try:
        assert got.wait(10)
        signal.signal(signal.SIGALRM, handler)
        signal.setitimer(signal.ITIMER_REAL, 0.1)
        try:
            fcntl.flock(fd2, fcntl.LOCK_EX)
            log.append(("acquired", state["released"]))
            fcntl.flock(fd2, fcntl.LOCK_UN)
        except Boom:
            log.append(("raised", state["released"]))
            try:
                fcntl.flock(fd2, fcntl.LOCK_EX | fcntl.LOCK_NB)
                log.append("nb acquired")
                fcntl.flock(fd2, fcntl.LOCK_UN)
            except BlockingIOError as e:
                log.append(("nb conflict", e.errno, state["released"]))
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, signal.SIG_DFL)
    finally:
        th.join(10)
    print(label, log, th.is_alive())
    del log[:]


main_thread_wait("handler", note)
main_thread_wait("raising handler", boom)

# (d) the same wait on a thread that is not the main thread acquires the lock
res = []


def waiter():
    fcntl.flock(fd2, fcntl.LOCK_EX)
    res.append(("thread acquired", state["released"]))
    fcntl.flock(fd2, fcntl.LOCK_UN)


state["released"] = False
fcntl.flock(fd, fcntl.LOCK_EX)
w = threading.Thread(target=waiter)
w.start()
time.sleep(0.3)
state["released"] = True
fcntl.flock(fd, fcntl.LOCK_UN)
w.join(10)
print(res, w.is_alive())

# (e) lockf / F_SETLKW / F_OFD_SETLKW waiting on the main thread against an OFD lock that a thread releases after 0.3 s
F_OFD_SETLK, F_OFD_SETLKW = 37, 38
WR = struct.pack("hhqqi4x", fcntl.F_WRLCK, 0, 0, 0, 0)
UN = struct.pack("hhqqi4x", fcntl.F_UNLCK, 0, 0, 0, 0)


def releaser():
    time.sleep(0.3)
    state["released"] = True
    fcntl.fcntl(fd2, F_OFD_SETLK, UN)


def blocked_by_ofd(label, take, give):
    state["released"] = False
    fcntl.fcntl(fd2, F_OFD_SETLK, WR)
    th = threading.Thread(target=releaser)
    th.start()
    try:
        r = take()
        print(label, "acquired", state["released"], repr(r))
        give()
    finally:
        th.join(10)
    print(label, "releaser done", not th.is_alive())


blocked_by_ofd("lockf", lambda: fcntl.lockf(fd, fcntl.LOCK_EX), lambda: fcntl.lockf(fd, fcntl.LOCK_UN))
blocked_by_ofd("F_SETLKW", lambda: fcntl.fcntl(fd, fcntl.F_SETLKW, WR), lambda: fcntl.fcntl(fd, fcntl.F_SETLK, UN))
blocked_by_ofd("F_OFD_SETLKW", lambda: fcntl.fcntl(fd3, F_OFD_SETLKW, WR), lambda: fcntl.fcntl(fd3, F_OFD_SETLK, UN))


# (f) no descriptor is used up by integer or buffer calls
def count():
    return len(os.listdir("/proc/self/fd"))


fcntl.fcntl(fd, fcntl.F_GETFD)
fcntl.fcntl(fd, fcntl.F_GETLK, WR)
before = count()
for _ in range(1000):
    fcntl.fcntl(fd, fcntl.F_GETFD)
for _ in range(100):
    fcntl.fcntl(fd, fcntl.F_GETLK, WR)
print("descriptors unchanged", count() == before)
for x in (fd, fd2, fd3):
    os.close(x)
os.remove(p)
os.rmdir(d)
'''

T4_LOCK_WAIT_EXPECTED = (
    "handler [('alarm', False), ('acquired', True)] False\n"
    "raising handler [('alarm', False), ('raised', False), ('nb conflict', 11, False)] False\n"
    "[('thread acquired', True)] False\n"
    "lockf acquired True None\nlockf releaser done True\n"
    "F_SETLKW acquired True %r\nF_SETLKW releaser done True\n"
    "F_OFD_SETLKW acquired True %r\nF_OFD_SETLKW releaser done True\n"
    "descriptors unchanged True\n" % (b"\x01" + b"\0" * 31, b"\x01" + b"\0" * 31))


def test_fcntl_lock_waits(jvm_kit, tmp_path):
    """(b)-(f): handlers run during a polled main-thread wait, a raising handler propagates, other threads block, lockf
    and the two SETLKW commands wait for a release, and no descriptor leaks (all compared with CPython)."""
    _t4_same_stdout(jvm_kit, tmp_path, T4_LOCK_WAIT, T4_LOCK_WAIT_EXPECTED)


T4_INDEX_SUBCLASSES = '''
import fcntl, os, tempfile


class B(bytes):
    def __index__(self): return 1


class BA(bytearray):
    def __index__(self): return 1


class S(str):
    def __index__(self): return 1


d = tempfile.mkdtemp()
p = os.path.join(d, "f")
fd = os.open(p, os.O_RDWR | os.O_CREAT, 0o600)
for name, v in (("bytes sub", B(b"ab")), ("bytearray sub", BA(b"ab")), ("str sub", S("ab"))):
    try:
        print(name, "->", repr(fcntl.fcntl(fd, fcntl.F_GETFD, v)))
    except Exception as e:
        print(name, "!!", type(e).__name__, e.args)
os.close(fd)
os.unlink(p)
os.rmdir(d)
'''


def test_fcntl_buffer_subclass_with_index(jvm_kit, tmp_path, monkeypatch):
    """A bytes/bytearray/str subclass defining __index__: 3.14+ takes the integer form, 3.13 tries the buffer first."""
    _compare(jvm_kit, tmp_path, monkeypatch, T4_INDEX_SUBCLASSES)


def test_fcntl_nio_mode_is_a_shell(jvm_kit, tmp_path):
    """Under PYJVM_POSIX_FDS=nio the module stays the StubModules shell: import works, fcntl.fcntl is unsupported."""
    main = tmp_path / "main.py"
    main.write_text('import fcntl\ntry:\n    fcntl.fcntl(5, fcntl.F_GETFD)\nexcept NotImplementedError as e:\n'
                    '    print("NotImplementedError", e)\nprint(fcntl.LOCK_EX)\n', encoding="utf-8")
    proc = _t4_pyjvm(jvm_kit, main, {"PYJVM_POSIX_FDS": "nio"})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == "NotImplementedError fcntl.fcntl is not supported by pyJVM\n2\n"


# ---- ticket 6: the os pty functions (PosixOsTty) ---------------------------------------------------------------------
# SAFETY (n2_plan 2.3 A): every terminal call is on a pty the script opened itself; the JVM runs in its own session
# (start_new_session=True: it is the session leader, the case that matters for O_NOCTTY), stdin /dev/null, under a timeout.


def _pty_run(kit, main: Path, script: str, java_options: str = "") -> subprocess.CompletedProcess[str]:
    import os
    import shutil

    from pyjvm315.driver import compile_file
    from pyjvm315.hostver import reference_stdlib, reference_version

    runtime, selfhosted = kit
    main.write_text(script, encoding="utf-8")
    out = main.parent / "out"
    compile_file(main, out, "ConformanceMain")
    shutil.copytree(runtime / "pyjvm315", out / "pyjvm315", dirs_exist_ok=True)
    full = os.environ.copy()
    full.pop("PYJVM_POSIX_FDS", None)
    if java_options:
        full["JAVA_TOOL_OPTIONS"] = java_options
    full["PYJVM_LANG_VERSION"] = "%d.%d" % reference_version(sys.executable)
    full["PYJVM_STDLIB"] = os.environ.get("PYJVM_STDLIB") or reference_stdlib(sys.executable)
    full.setdefault("PYTHONIOENCODING", "utf-8")
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "-Xss64m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-cp", str(out) + os.pathsep + str(selfhosted), "ConformanceMain"]
    return subprocess.run(cmd, cwd=main.parent, capture_output=True, encoding="utf-8", errors="replace", env=full, timeout=300,
                          start_new_session=True, stdin=subprocess.DEVNULL)


PTY_NO_CTTY = '''
import os
def stat_fields():
    with open("/proc/self/stat") as f:
        return f.read().rsplit(")", 1)[1].split()
before = stat_fields()
print("leader", int(before[3]) == os.getpid(), "tty_nr before", before[4])
m, s = os.openpty()
try:
    print("tty_nr after openpty", stat_fields()[4])
    os.write(s, b"x")
    os.read(m, 10)
    print("tty_nr after io", stat_fields()[4])
finally:
    os.close(m)
    os.close(s)
print("tty_nr after close", stat_fields()[4])
pm = os.posix_openpt(os.O_RDWR | os.O_NOCTTY)
os.grantpt(pm)
os.unlockpt(pm)
sl = os.open(os.ptsname(pm), os.O_RDWR | os.O_NOCTTY)
os.close(sl)
os.close(pm)
print("tty_nr end", stat_fields()[4])
'''


def test_openpty_never_gives_the_session_leader_a_controlling_terminal(jvm_kit, tmp_path):
    """(a) The JVM is a session leader (own session) and still has tty_nr 0 after openpty, I/O, close."""
    proc = _pty_run(jvm_kit, tmp_path / "main.py", PTY_NO_CTTY)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == ("leader True tty_nr before 0\ntty_nr after openpty 0\ntty_nr after io 0\n"
                           "tty_nr after close 0\ntty_nr end 0\n")


PTY_FDS = '''
import os
def links():
    out = {}
    for n in os.listdir("/proc/self/fd"):
        try:
            out[int(n)] = os.readlink("/proc/self/fd/" + n)
        except OSError:
            pass
    return out
def settle():
    m, s = os.openpty()
    os.close(m)
    os.close(s)
settle()
base = links()
m, s = os.openpty()
now = links()
new = sorted(set(now) - set(base))
print("two new", len(new), m in new and s in new, now.get(m), now.get(s, "").startswith("/dev/pts/"))
print("inheritable", os.get_inheritable(m), os.get_inheritable(s))
os.close(m)
os.close(s)
after = links()
print("gone", m not in after and s not in after, len(after) == len(base))
for _ in range(200):
    a, b = os.openpty()
    os.close(a)
    os.close(b)
print("200 rounds", len(links()) == len(base))
pm = os.posix_openpt(os.O_RDWR | os.O_NOCTTY)
print("posix_openpt", os.get_inheritable(pm), links().get(pm))
os.close(pm)
print("posix_openpt closed", pm not in links())
# every failing step of openpty must close what was opened: an unusable descriptor table entry cannot be forced here, so
# the failure path is covered by the ptsname_r growth test; ttyname/ptsname on a bad number must not leak either
for bad in (os.ttyname, os.ptsname, os.grantpt, os.unlockpt):
    try:
        bad(9999)
    except OSError as e:
        pass
print("no leak on errors", len(links()) == len(base))
'''


def test_openpty_descriptors_are_adopted_cloexec_and_closed(jvm_kit, tmp_path):
    """(b) both ends appear in /proc/self/fd, are non-inheritable, vanish on close, and 200 rounds leave the count unchanged."""
    proc = _pty_run(jvm_kit, tmp_path / "main.py", PTY_FDS)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == ("two new 2 True /dev/ptmx True\ninheritable False False\ngone True True\n200 rounds True\n"
                           "posix_openpt False /dev/ptmx\nposix_openpt closed True\nno leak on errors True\n")


PTY_CHILD = '''
import os, subprocess
LIST = 'for f in /proc/self/fd/*; do echo "${f##*/} $(readlink $f)"; done'
def pty_links():
    out = set()
    for n in os.listdir("/proc/self/fd"):
        try:
            link = os.readlink("/proc/self/fd/" + n)
        except OSError:
            continue
        if link.startswith("/dev/p"):
            out.add((n, link))
    return out
# pty descriptors present before openpty (under WSL the launcher passes /dev/ptmx as fd 7): the only ones a child may see
base = pty_links()
m, s = os.openpty()
try:
    r = subprocess.run(["sh", "-c", "readlink /proc/self/fd/%d; echo --; readlink /proc/self/fd/%d" % (m, s)],
                       stdout=subprocess.PIPE, timeout=60, close_fds=False)
    mine, theirs = r.stdout.decode().split("--")
    print("child sees master", mine.strip() == "/dev/ptmx", "child sees slave", theirs.strip().startswith("/dev/pts/"))
    out = subprocess.run(["sh", "-c", LIST], stdout=subprocess.PIPE, timeout=60, close_fds=False).stdout.decode()
    seen = {tuple(l.split(" ", 1)) for l in out.splitlines() if " /dev/p" in l}
    print("child pty set within baseline", seen <= base, "no pts", not any(l.startswith("/dev/pts/") for _, l in seen))
    r = subprocess.run(["sh", "-c", "tty; test -t 0 && echo yes"], stdin=s, stdout=subprocess.PIPE, timeout=60)
    out = r.stdout.decode()
    print("stdin=slave", r.returncode, out.startswith("/dev/pts/"), out.rstrip().endswith("yes"))
finally:
    os.close(m)
    os.close(s)
'''


def test_openpty_is_close_on_exec_for_children(jvm_kit, tmp_path):
    """(c) a close_fds=False child does not see either end; a child given the slave as stdin sees a terminal."""
    proc = _pty_run(jvm_kit, tmp_path / "main.py", PTY_CHILD)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == ("child sees master False child sees slave False\n"
                           "child pty set within baseline True no pts True\nstdin=slave 0 True True\n")


PTY_NAMES = '''
import os
m, s = os.openpty()
try:
    print(os.ptsname(m) == os.ttyname(s), os.ttyname(m))
    print(os.ptsname(m).startswith("/dev/pts/"))
finally:
    os.close(m)
    os.close(s)
'''


def test_pty_names_grow_the_buffer(jvm_kit, tmp_path):
    """A 1-byte first buffer (test hook pyjvm.test.ptsbuf) makes ptsname_r/ttyname_r answer ERANGE; openpty still succeeds."""
    proc = _pty_run(jvm_kit, tmp_path / "main.py", PTY_NAMES, "-Dpyjvm.test.ptsbuf=1")
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == "True /dev/ptmx\nTrue\n"
    plain = _pty_run(jvm_kit, tmp_path / "main.py", PTY_NAMES)
    assert plain.returncode == 0, plain.stderr
    assert plain.stdout == proc.stdout
