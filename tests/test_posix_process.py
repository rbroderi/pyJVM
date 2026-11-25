# SPDX-License-Identifier: GPL-3.0-or-later
"""Process creation on Linux (N1): os.posix_spawn[p] and _posixsubprocess.fork_exec through glibc's posix_spawn, the
emulated cwd, the live C environment, no leaked descriptors or children, and the JVM's own inheritable descriptors kept
out of children.

SAFETY (n1_plan 2.3): every child is waited for by the script that started it; the only signal sent is SIGKILL to such a
child by pid (test_waits_on_two_threads, only if its waiting thread is still blocked after 30 s)."""
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

pytestmark = pytest.mark.skipif(not sys.platform.startswith("linux"), reason="os.posix_spawn under pyJVM is Linux-only")


@pytest.fixture(scope="module")
def jvm_kit():
    with tempfile.TemporaryDirectory(prefix="pyjvm-posix-process-test-") as td:
        runtime = Path(td) / "runtime"
        build_runtime(runtime)
        yield runtime, build_selfhosted(Path(td) / "selfhosted")


def _run_pyjvm(kit, main: Path, env: dict[str, str], pass_fds: tuple[int, ...] = ()) -> subprocess.CompletedProcess[str]:
    """Compile ``main`` and run it under pyJVM (as tests/test_posix_fds.py does) with ``env`` added to this process's
    environment; ``pass_fds`` are inherited by the JVM under their own numbers."""
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
    return subprocess.run(cmd, cwd=main.parent, capture_output=True, encoding="utf-8", errors="replace", env=full, timeout=600,
                          pass_fds=pass_fds)


def _compare(kit, tmp_path: Path, monkeypatch, script: str) -> None:
    runtime, selfhosted = kit
    main = tmp_path / "main.py"
    main.write_text(script, encoding="utf-8")
    monkeypatch.delenv("PYJVM_POSIX_FDS", raising=False)
    result = compare_file(main, sys.executable, runtime_cache=runtime, selfhosted=selfhosted)
    assert result.status == "PASS", result.detail


SPAWN_OUT = '''
import os
def out(path, argv, env):
    r, w = os.pipe()
    try:
        pid = os.posix_spawn(path, argv, env, file_actions=[(os.POSIX_SPAWN_DUP2, w, 1)])
    finally:
        os.close(w)
    data = b""
    while True:
        b = os.read(r, 65536)
        if not b:
            break
        data += b
    os.close(r)
    return os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]), data
'''


def test_spawn_runs_in_the_emulated_cwd(jvm_kit, tmp_path, monkeypatch):
    _compare(jvm_kit, tmp_path, monkeypatch, SPAWN_OUT + '''
import tempfile
d = os.path.realpath(tempfile.mkdtemp())
start = os.getcwd()
os.chdir(d)
code, data = out("/bin/pwd", ["pwd"], os.environ)
print(code, data == (d + "\\n").encode(), os.getcwd() == d)
os.chdir(start)
os.rmdir(d)
''')


def test_spawn_with_env_none_sees_putenv(jvm_kit, tmp_path, monkeypatch):
    _compare(jvm_kit, tmp_path, monkeypatch, SPAWN_OUT + '''
os.putenv("PYJVM_T4_RAW", "raw value")
print(out("/bin/sh", ["sh", "-c", 'echo "[$PYJVM_T4_RAW]"'], None))
os.unsetenv("PYJVM_T4_RAW")
print(out("/bin/sh", ["sh", "-c", 'echo "[$PYJVM_T4_RAW]"'], None))
''')


def test_spawn_rounds_leak_nothing(jvm_kit, tmp_path, monkeypatch):
    _compare(jvm_kit, tmp_path, monkeypatch, '''
import os
def count():
    return len(os.listdir("/proc/self/fd"))
before = count()
codes = set()
for i in range(200):
    pid = os.posix_spawn("/bin/true", ["true"], os.environ)
    codes.add(os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]))
    if i % 2:
        try:
            os.posix_spawn("/no/such/program", ["x"], os.environ)
        except FileNotFoundError:
            pass
print(codes, count() == before)
try:
    os.waitpid(-1, os.WNOHANG)
except ChildProcessError as e:
    print("no children", e.errno)
''')


def test_rejected_file_action_wins_over_later_errors(jvm_kit, tmp_path, monkeypatch):
    # CPython adds each file action while parsing: glibc's EBADF for an invalid descriptor (no filename) comes before
    # every later argument error and before the audit event
    _compare(jvm_kit, tmp_path, monkeypatch, '''
import os, sys, warnings
C, D2, O = os.POSIX_SPAWN_CLOSE, os.POSIX_SPAWN_DUP2, os.POSIX_SPAWN_OPEN
events = []
sys.addaudithook(lambda ev, args: events.append(ev) if ev == "os.posix_spawn" else None)
class B:
    def __bool__(self):
        raise RuntimeError("bool called")
def show(label, **kw):
    del events[:]
    try:
        pid = os.posix_spawn("/bin/true", ["true"], os.environ, **kw)
        print(label, "ok", os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]), events)
    except Exception as e:
        print(label, type(e).__name__, getattr(e, "errno", None), getattr(e, "filename", None), events)
show("C-1 sigmask0", file_actions=[(C, -1)], setsigmask=[0])
show("C-1 unknown", file_actions=[(C, -1), (9,)])
show("C-1 pgroup", file_actions=[(C, -1)], setpgroup="a")
show("C-1 setsid", file_actions=[(C, -1)], setsid=True)
show("D2 -1", file_actions=[(D2, 1, -1)])
show("D2 -1 first", file_actions=[(D2, -1, 1)])
show("O -1", file_actions=[(O, -1, os.devnull, os.O_RDONLY, 0)])
show("C 1 then bad", file_actions=[(C, 9999), ("x", 1)])
show("bool before sched", resetids=B(), scheduler=5)
show("setsid before sched", setsid=B(), scheduler=5)
for mode in (2**40, 2**64 - 1, 2**70, -2**63, -2**63 - 1):
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        show("mode %d" % mode, file_actions=[(O, 5, os.devnull, os.O_RDONLY, mode)])
    print([(x.category.__name__, str(x.message)) for x in w])
''')


def test_nio_rollback_has_no_posix_spawn(jvm_kit, tmp_path):
    main = tmp_path / "main.py"
    main.write_text("import os\nprint(hasattr(os, 'posix_spawn'), hasattr(os, 'posix_spawnp'))\n", encoding="utf-8")
    proc = _run_pyjvm(jvm_kit, main, {"PYJVM_POSIX_FDS": "nio"})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == "False False\n"


# The child lists the descriptors of its own shell ($$) above 2 without opening any itself ([ -L ] is lstat).
CHILD_FDS = 'f=3; while [ $f -lt 1024 ]; do [ -L /proc/$$/fd/$f ] && echo "child $f $(readlink /proc/$$/fd/$f)"; f=$((f + 1)); done'

JVM_FDS_SCRIPT = '''
import os, sys
inherited = int(os.environ["INHERITED_FD"])
r, w = os.pipe()
os.set_inheritable(w, True)
private = os.open("/dev/null", os.O_RDONLY)
held = []
for name in os.listdir("/proc/self/fd"):
    f = int(name)
    try:
        if f > 2 and os.get_inheritable(f) and f not in (inherited, w):
            held.append(os.readlink("/proc/self/fd/%d" % f))
    except OSError:
        pass
print("jvm-held", sorted(held))
print("expect", sorted([inherited, w]))
for spawn, path in ((os.posix_spawn, "/bin/sh"), (os.posix_spawnp, "sh")):
    sys.stdout.flush()
    pid = spawn(path, ["sh", "-c", os.environ["CHILD_FDS"]], os.environ)
    print(spawn.__name__, os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]))
    sys.stdout.flush()
for f in (r, w, private):
    os.close(f)
'''


def test_children_do_not_inherit_the_jvms_descriptors(jvm_kit, tmp_path):
    main = tmp_path / "main.py"
    main.write_text(JVM_FDS_SCRIPT, encoding="utf-8")
    shared = tmp_path / "inherited.txt"
    shared.write_text("passed in", encoding="utf-8")
    fd = os.open(shared, os.O_RDONLY)
    try:
        assert fd > 2
        proc = _run_pyjvm(jvm_kit, main, {"INHERITED_FD": str(fd), "CHILD_FDS": CHILD_FDS}, pass_fds=(fd,))
    finally:
        os.close(fd)
    assert proc.returncode == 0, proc.stderr
    lines = proc.stdout.splitlines()
    held = next(line for line in lines if line.startswith("jvm-held"))
    assert "lib/modules" in held, held      # the rule below is exercised: the JVM really holds an inheritable descriptor
    expect = next(line for line in lines if line.startswith("expect")).split(" ", 1)[1]
    # each spawn's child lines come before its "posix_spawn 0" / "posix_spawnp 0" line
    blocks, current = [], []
    for line in lines:
        if line.startswith("child "):
            current.append(line)
        elif line.startswith(("posix_spawn ", "posix_spawnp ")):
            assert line.endswith(" 0"), proc.stdout
            blocks.append(current)
            current = []
    assert len(blocks) == 2, proc.stdout
    for block in blocks:
        seen = sorted(int(line.split()[1]) for line in block)
        assert str(seen) == expect, proc.stdout
        assert any(line.endswith(str(shared)) for line in block), proc.stdout


# N1 ticket 5: blocking waits on the main thread with Python signal handlers active (plan 1.5). Every child is started
# by the script, exits on its own within 1 s and is waited for; the only signal is SIGKILL to the script's own child if
# the waiting thread is still blocked after 30 s (it then gets the child's status).

def test_waits_on_two_threads(jvm_kit, tmp_path, monkeypatch):
    # (e) a second thread blocked in waitpid(child, 0) while the main thread spawns and waits for 50 other children
    _compare(jvm_kit, tmp_path, monkeypatch, '''
import os, signal, threading
signal.signal(signal.SIGUSR1, lambda s, f: None)
env = dict(os.environ)
slow = os.posix_spawn("/bin/sh", ["sh", "-c", "sleep 1; exit 7"], env)
res = []
t = threading.Thread(target=lambda: res.append(os.waitpid(slow, 0)), daemon=True)
t.start()
codes = []
for i in range(50):
    p = os.posix_spawn("/bin/sh", ["sh", "-c", "exit %d" % (i % 5)], env)
    r = os.waitpid(p, 0)
    codes.append((r[0] == p, os.waitstatus_to_exitcode(r[1])))
print(codes == [(True, i % 5) for i in range(50)])
t.join(30)
if t.is_alive():
    os.kill(slow, signal.SIGKILL)
    t.join(30)
print(not t.is_alive(), res[0][0] == slow, os.waitstatus_to_exitcode(res[0][1]))
''')


def test_main_thread_waits_close_their_pidfds(jvm_kit, tmp_path, monkeypatch):
    # (f) 200 blocking main-thread waits (each child still running when the wait starts) leave no descriptor behind
    _compare(jvm_kit, tmp_path, monkeypatch, '''
import os, signal
signal.signal(signal.SIGUSR1, lambda s, f: None)
env = dict(os.environ)
def count():
    return len(os.listdir("/proc/self/fd"))
os.waitpid(os.posix_spawn("/bin/true", ["true"], env), 0)   # warm-up: lazily opened JDK descriptors
before = count()
codes = set()
for i in range(200):
    p = os.posix_spawn("/bin/sleep", ["sleep", "0.01"], env)
    codes.add(os.waitstatus_to_exitcode(os.waitpid(p, 0)[1]))
for i in range(5):
    p = os.posix_spawn("/bin/sleep", ["sleep", "0.01"], env)
    r = os.waitid(os.P_PID, p, os.WEXITED)
    codes.add(r.si_status)
print(codes, count() == before)
try:
    os.waitpid(-1, os.WNOHANG)
except ChildProcessError as e:
    print("no children", e.errno)
''')


def test_main_thread_wait_any_child_latency(jvm_kit, tmp_path, monkeypatch):
    # (g) os.wait() on the main thread (back-off, no pidfd) returns the first child within 0.15 s of its exit
    _compare(jvm_kit, tmp_path, monkeypatch, '''
import os, signal, time
signal.signal(signal.SIGUSR1, lambda s, f: None)
env = dict(os.environ)
a = os.posix_spawn("/bin/sleep", ["sleep", "0.3"], env)
t0 = time.monotonic()
b = os.posix_spawn("/bin/sleep", ["sleep", "0.5"], env)
r = os.wait()
took = time.monotonic() - t0
print(r[0] == a, os.waitstatus_to_exitcode(r[1]), 0.29 <= took < 0.3 + 0.15)
r = os.wait()
print(r[0] == b, os.waitstatus_to_exitcode(r[1]))
''')


# N1 ticket 6: _posixsubprocess.fork_exec on posix_spawn (plan 1.7). The texts below are pyJVM's own (no fork), so they
# are compared with fixed strings, not with CPython. Every child exits on its own and is waited for; no signal is sent.

UNSUPPORTED_SCRIPT = '''
import os, subprocess, sys, _posixsubprocess
def count():
    return len(os.listdir("/proc/self/fd"))
def no_child():
    try:
        os.waitpid(-1, os.WNOHANG)
        return False
    except ChildProcessError:
        return True
subprocess.run(["true"], timeout=30)                  # warm-up: lazily opened JDK descriptors
before = count()
def show(label, f):
    try:
        f()
        print(label, "no error")
    except Exception as e:
        print(label, type(e).__name__, e)
    print(label, count() == before, no_child())
show("preexec_fn", lambda: subprocess.run(["true"], timeout=30, preexec_fn=lambda: None))
show("umask", lambda: subprocess.run(["true"], timeout=30, umask=0o77))
# subprocess itself rejects these before fork_exec (pyJVM has no os.setreuid/setregid/setgroups)
show("user", lambda: subprocess.run(["true"], timeout=30, user=os.getuid()))
show("group", lambda: subprocess.run(["true"], timeout=30, group=os.getgid()))
show("extra_groups", lambda: subprocess.run(["true"], timeout=30, extra_groups=[]))
# fork_exec called directly: the four NotImplementedError texts
def direct(gid=None, extra=None, uid=None, umask=-1, preexec=None):
    r, w = os.pipe()
    try:
        extra_args = [True] if sys.version_info < (3, 14) else []       # 3.13: allow_vfork
        _posixsubprocess.fork_exec([b"/bin/true"], [b"/bin/true"], True, (w,), None, None, -1, -1, -1, -1, -1, -1,
                                   r, w, True, False, -1, gid, extra, uid, umask, preexec, *extra_args)
    finally:
        os.close(r)
        os.close(w)
show("direct uid", lambda: direct(uid=os.getuid()))
show("direct gid", lambda: direct(gid=os.getgid()))
show("direct extra_groups", lambda: direct(extra=()))
show("direct umask", lambda: direct(umask=0o22))
show("direct preexec_fn", lambda: direct(preexec=lambda: None))
show("direct uid+umask", lambda: direct(uid=0, umask=0))
'''

UNSUPPORTED_OUT = '''\
preexec_fn RuntimeError preexec_fn is not supported by pyJVM: there is no fork, so no Python code can run in the child
preexec_fn True True
umask NotImplementedError subprocess: umask is not supported by pyJVM (posix_spawn cannot change the umask)
umask True True
user ValueError The 'user' parameter is not supported on the current platform
user True True
group ValueError The 'group' parameter is not supported on the current platform
group True True
extra_groups ValueError The 'extra_groups' parameter is not supported on the current platform
extra_groups True True
direct uid NotImplementedError subprocess: user is not supported by pyJVM (posix_spawn cannot change the user id)
direct uid True True
direct gid NotImplementedError subprocess: group is not supported by pyJVM (posix_spawn cannot change the group id)
direct gid True True
direct extra_groups NotImplementedError subprocess: extra_groups is not supported by pyJVM (posix_spawn cannot change the supplementary groups)
direct extra_groups True True
direct umask NotImplementedError subprocess: umask is not supported by pyJVM (posix_spawn cannot change the umask)
direct umask True True
direct preexec_fn RuntimeError preexec_fn is not supported by pyJVM: there is no fork, so no Python code can run in the child
direct preexec_fn True True
direct uid+umask NotImplementedError subprocess: user is not supported by pyJVM (posix_spawn cannot change the user id)
direct uid+umask True True
'''


def test_fork_exec_unsupported_arguments(jvm_kit, tmp_path):
    main = tmp_path / "main.py"
    main.write_text(UNSUPPORTED_SCRIPT, encoding="utf-8")
    proc = _run_pyjvm(jvm_kit, main, {})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == UNSUPPORTED_OUT


# fork_exec's children (a bare program name takes the fork_exec path even with close_fds=False, plan 3.7): with
# close_fds=False the JVM's own inheritable descriptors are still closed by PosixSpawn (plan 1.8); with close_fds=True
# only pass_fds survive.
FORK_EXEC_FDS_SCRIPT = '''
import os, subprocess, sys
inherited = int(os.environ["INHERITED_FD"])
r, w = os.pipe()
os.set_inheritable(w, True)
private = os.open("/dev/null", os.O_RDONLY)
child = ["sh", "-c", os.environ["CHILD_FDS"]]
for label, kw in (("nokeep", dict(close_fds=False)), ("default", dict()),
                  ("closefds", dict(close_fds=True, pass_fds=(w,)))):
    sys.stdout.flush()
    code = subprocess.run(child, timeout=30, **kw).returncode
    print("done", label, code)
    sys.stdout.flush()
print("fds", inherited, w, private)
for f in (r, w, private):
    os.close(f)
'''


def test_fork_exec_children_do_not_inherit_the_jvms_descriptors(jvm_kit, tmp_path):
    main = tmp_path / "main.py"
    main.write_text(FORK_EXEC_FDS_SCRIPT, encoding="utf-8")
    shared = tmp_path / "inherited.txt"
    shared.write_text("passed in", encoding="utf-8")
    fd = os.open(shared, os.O_RDONLY)
    try:
        assert fd > 2
        proc = _run_pyjvm(jvm_kit, main, {"INHERITED_FD": str(fd), "CHILD_FDS": CHILD_FDS}, pass_fds=(fd,))
    finally:
        os.close(fd)
    assert proc.returncode == 0, proc.stderr
    lines = proc.stdout.splitlines()
    inherited, w, private = (int(x) for x in next(line for line in lines if line.startswith("fds ")).split()[1:])
    blocks, current = {}, []
    for line in lines:
        if line.startswith("child "):
            current.append(int(line.split()[1]))
        elif line.startswith("done "):
            _, label, code = line.split()
            assert code == "0", proc.stdout
            blocks[label] = sorted(current)
            current = []
    assert blocks == {"nokeep": sorted([inherited, w]), "default": [], "closefds": [w]}, proc.stdout


# N1 ticket 7: failed starts. (h) every failure returns a stand-in child that subprocess reaps, so nothing is left to
# wait for and no descriptor leaks; (i) the audit events of subprocess.run (lane-dependent: _posixsubprocess.fork_exec
# from 3.14 on), compared with CPython. No signal is sent; every child exits on its own.

FAILED_STARTS_SCRIPT = '''
import os, subprocess
def count():
    return len(os.listdir("/proc/self/fd"))
try:
    subprocess.run(["/no/such"], timeout=30)    # warm-up: lazily opened descriptors
except FileNotFoundError:
    pass
before = count()
errors = set()
for i in range(100):
    try:
        subprocess.run(["/no/such"], timeout=30)
    except OSError as e:
        errors.add((type(e).__name__, e.errno, e.filename))
print(sorted(errors))
try:
    os.waitpid(-1, os.WNOHANG)
    print("child left")
except ChildProcessError as e:
    print("no children", e.errno)
print(count() == before)
'''


def test_failed_starts_leave_no_child(jvm_kit, tmp_path, monkeypatch):
    _compare(jvm_kit, tmp_path, monkeypatch, FAILED_STARTS_SCRIPT)


AUDIT_SCRIPT = '''
import os, subprocess, sys, _posixsubprocess
names = ("subprocess.Popen", "_posixsubprocess.fork_exec", "os.posix_spawn")
seen = []
def hook(ev, args):
    if ev in names:
        seen.append((ev, args))
sys.addaudithook(hook)
subprocess.run(["true"], timeout=30)
subprocess.run(["true"], close_fds=False, timeout=30)         # bare name: still fork_exec (plan 3.7)
subprocess.run(["/bin/true"], close_fds=False, timeout=30)    # os.posix_spawn
try:
    subprocess.run(["/no/such"], timeout=30)
except FileNotFoundError:
    pass
for ev, args in seen:
    print(ev, args)
# fork_exec called directly: no event when one of CPython's checks fails first
NARGS = 23 if sys.version_info[:2] == (3, 13) else 22
def direct(args, exes, keep, env_list=None, pre=None):
    seen.clear()
    r, w = os.pipe()
    try:
        a = [args, exes, True, keep, None, env_list, -1, -1, -1, -1, -1, -1, r, w, True, False, -1, None, None, None, -1, pre]
        _posixsubprocess.fork_exec(*(a + [True] * (NARGS - 22)))
    except Exception as e:
        print(type(e).__name__, e, len(seen))
    finally:
        os.close(r)
        os.close(w)
direct([b"true"], (b"/bin/true",), [3])
direct([b"true"], (b"/bin/true",), (-1,))
direct([5], (b"/bin/true",), ())
direct([b"true"], ("/bin/true",), ())
direct([b"true"], (b"/bin/true",), (), ["A=1"])
direct([5], (b"/bin/true",), (), pre=lambda: None)          # CPython's error wins over preexec_fn
direct([b"true"], (b"/bin/true",), (-1,), pre=lambda: None)
# args None: a NULL argv in CPython (an empty one for the kernel); /bin/true still runs
r, w = os.pipe()
a = [None, (b"/bin/true",), True, (w,), None, None, -1, -1, -1, -1, -1, -1, r, w, True, False, -1, None, None, None, -1, None]
pid = _posixsubprocess.fork_exec(*(a + [True] * (NARGS - 22)))
os.close(w)
print("args None", os.read(r, 100), os.waitpid(pid, 0)[1])
os.close(r)
'''


def test_fork_exec_audit_events(jvm_kit, tmp_path, monkeypatch):
    _compare(jvm_kit, tmp_path, monkeypatch, AUDIT_SCRIPT)
