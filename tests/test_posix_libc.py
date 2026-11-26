# SPDX-License-Identifier: GPL-3.0-or-later
"""The small libc modules on Linux (N2): shared plumbing now; each later ticket appends the pyJVM-specific tests of its
module here. Every test runs pyJVM on a small script; where it says so the output is compared with the reference CPython.

SAFETY (n2_plan 2.3): no terminal, no resource limit, no lock and no signal is touched by the tests of this ticket."""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

from pyjvm315.cli import build_runtime
from pyjvm315.conformance import compare_file
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
