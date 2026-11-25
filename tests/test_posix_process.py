# SPDX-License-Identifier: GPL-3.0-or-later
"""Process creation on Linux (N1): os.posix_spawn[p] through glibc's posix_spawn, the emulated cwd, the live C
environment, no leaked descriptors or children, and the JVM's own inheritable descriptors kept out of children.

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
