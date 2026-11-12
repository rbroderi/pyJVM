# SPDX-License-Identifier: GPL-3.0-or-later
"""tools/resumable.py: splitting and resume logic (no JVM involved; units are stand-in shell commands)."""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("resumable", ROOT / "tools" / "resumable.py")
R = importlib.util.module_from_spec(_spec)
sys.modules["resumable"] = R
_spec.loader.exec_module(R)


MANIFEST = """\
# header
Lib/test/a.py::A.t1
Lib/test/a.py::A.t2 bytes

@imports sys os
Lib/test/b.py::B.t1
Lib/test/b.py::B.t2
Lib/test/b.py::B.t3
@imports
Lib/test/c.py::C.t1
Lib/test/c.py::C.t2
@imports gc
Lib/test/d.py::D.t1
"""


def test_chunks():
    assert R.chunks(list("abcdefg"), 3) == [list("abc"), list("def"), list("g")]
    assert R.chunks([], 4) == []


def test_split_manifest_keeps_active_imports_at_top_of_each_piece():
    pieces = R.split_manifest(MANIFEST, 2)
    assert pieces == [
        ["Lib/test/a.py::A.t1", "Lib/test/a.py::A.t2 bytes"],
        ["@imports sys os", "Lib/test/b.py::B.t1", "Lib/test/b.py::B.t2"],
        ["@imports sys os", "Lib/test/b.py::B.t3", "@imports", "Lib/test/c.py::C.t1"],   # directive switched off mid-piece
        ["Lib/test/c.py::C.t2", "@imports gc", "Lib/test/d.py::D.t1"],
    ]
    # every case appears exactly once, in order
    cases = [l for p in pieces for l in p if not l.startswith("@")]
    assert cases == [l for l in MANIFEST.splitlines() if l.startswith("Lib/")]


def test_split_manifest_single_piece_and_empty():
    assert R.split_manifest("", 5) == []
    assert R.split_manifest("@imports x\n", 5) == []
    assert R.split_manifest("@imports x\na::b\nc::d\n", 10) == [["@imports x", "a::b", "c::d"]]


def test_parse_summaries():
    assert R.parse_summary_dict("PASS x\n{'PASS': 3, 'UNSUPPORTED': 1, 'FAIL': 0, 'ERROR': 0}\n") == {"PASS": 3, "UNSUPPORTED": 1, "FAIL": 0, "ERROR": 0}
    assert R.parse_summary_dict("PASS x\n") is None
    assert R.parse_conformance_summary("noise\n\n12 passed, 1 unsupported, 0 failed, 2 skipped (version-gated)\n") == \
        {"passed": 12, "unsupported": 1, "failed": 0, "skipped": 2}
    assert R.parse_pytest_summary("..F\n1 failed, 2 passed in 1.20s\n") == {"failed": 1, "passed": 2}
    assert R.parse_pytest_summary("Killed") is None


def test_sync_input_discards_output_when_input_changes(tmp_path):
    inp, out = tmp_path / "p.txt", tmp_path / "p.out"
    R.sync_input(inp, "a\n", out)
    out.write_text("{'PASS': 1}")
    R.sync_input(inp, "a\n", out)
    assert out.exists()
    R.sync_input(inp, "a\nb\n", out)
    assert not out.exists()


def _unit(tmp_path, name, script, marker):
    return R.Unit(name, [sys.executable, "-c", script], tmp_path / f"{name}.out", marker)


def test_run_units_is_resumable(tmp_path, capsys):
    flag = tmp_path / "flag"
    ok = _unit(tmp_path, "ok", "print('3 passed, 0 failed')", " passed, ")
    # first run: dies before printing its summary (a killed job); second run: succeeds
    flaky = _unit(tmp_path, "flaky", f"import os,sys,pathlib\nf=pathlib.Path({str(flag)!r})\nprint('partial')\n"
                                     "if not f.exists():\n    f.write_text('x'); sys.exit(9)\nprint('2 passed, 0 failed')", " passed, ")
    left = R.run_units([ok, flaky], 2)
    assert [u.name for u in left] == ["flaky"]
    assert ok.done() and not flaky.done()
    ok.output.write_text("3 passed, 0 failed (kept)\n")           # a finished unit is not run again
    left = R.run_units([ok, flaky], 2)
    assert left == [] and "kept" in ok.output.read_text() and flaky.done()
    assert R.run_units([ok, flaky], 2, dry_run=True) == []
    R.run_units([ok, flaky], 2, force=True)
    assert "kept" not in ok.output.read_text()


def test_children_get_default_sigint(tmp_path):
    import signal
    prev = signal.signal(signal.SIGINT, signal.SIG_IGN)       # what a nohup'd parent hands down
    try:
        script = ("import re; m = re.search(r'SigIgn:\\s*([0-9a-f]+)', open('/proc/self/status').read())\n"
                  "print('ignored', bool(int(m[1], 16) & 2), \"{'PASS':\")")
        u = _unit(tmp_path, "sig", script, "{'PASS':")
        R.run_unit(u)
    finally:
        signal.signal(signal.SIGINT, prev)
    assert "ignored False" in u.output.read_text()


def test_strict_units_pieces_and_resume(tmp_path):
    manifest = tmp_path / "m.txt"
    manifest.write_text(MANIFEST)
    ns = R.build_parser().parse_args(["strict", "--out", str(tmp_path / "out"), "--cpython-root", str(tmp_path), "--manifest", str(manifest), "--piece-size", "2"])
    ns.out = ns.out.resolve()
    units = R.strict_units(ns)
    assert [u.name for u in units] == ["q000", "q001", "q002", "q003"]
    assert (ns.out / "pieces" / "q001.txt").read_text().startswith("@imports sys os\n")
    units[0].output.write_text("PASS a\n{'PASS': 2, 'UNSUPPORTED': 0, 'FAIL': 0, 'ERROR': 0}\n")
    assert [u.name for u in R.strict_units(ns) if not u.done()] == ["q001", "q002", "q003"]
    manifest.write_text(MANIFEST.replace("A.t1", "A.t1x"))      # edited manifest: the stale piece output is dropped
    assert [u.name for u in R.strict_units(ns) if not u.done()] == ["q000", "q001", "q002", "q003"]


def test_conformance_units_groups_of_four(tmp_path):
    tests = tmp_path / "conf"
    tests.mkdir()
    for i in range(9):
        (tests / f"t{i}.py").write_text("print(1)\n")
    ns = R.build_parser().parse_args(["conformance", "--out", str(tmp_path / "out"), "--tests", str(tests)])
    ns.out = ns.out.resolve()
    warm, units = R.conformance_units(ns)
    assert warm is not None and [u.name for u in units] == ["g_000", "g_001", "g_002"]
    assert sorted(p.name for p in (ns.out / "dirs" / "g_002").iterdir()) == ["t8.py"]
    assert len(list((ns.out / "dirs" / "g_000").iterdir())) == 4
    assert all("--selfhosted" in u.command and u.env["PYJVM_BUILD_CACHE"].endswith("build") for u in units)
    assert units[0].marker == " passed, "
