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
