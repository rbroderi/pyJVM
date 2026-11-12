# SPDX-License-Identifier: GPL-3.0-or-later
"""P3-04: the stdlib manifest tooling's resume and aggregation logic (no JVM needed)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import stdlib_status as st  # noqa: E402


def _lib(tmp_path: Path) -> Path:
    lib = tmp_path / "Lib"
    files = ["os.py", "json/__init__.py", "json/decoder.py", "test/test_x.py", "pkg/tests/test_y.py", "idlelib/a.py", "bad-name.py", "pkg/__init__.py",
             "pkg/mod.py"]
    for f in files:
        (lib / f).parent.mkdir(parents=True, exist_ok=True)
        (lib / f).write_text("x = 1\n")
    return lib


def test_enumerate_modules_names_packages_and_exclusions(tmp_path):
    mods, excluded = st.enumerate_modules(_lib(tmp_path))
    assert sorted(mods) == ["json", "json.decoder", "os", "pkg", "pkg.mod"]
    assert mods["json"] == {"path": "json/__init__.py", "pkg": True}
    assert mods["json.decoder"] == {"path": "json/decoder.py", "pkg": False}
    assert set(excluded) == {"test", "tests", "idlelib"}


def test_records_resume_and_truncated_tail(tmp_path):
    path = tmp_path / "r.jsonl"
    st.append_record(path, {"module": "a", "stage": "compile", "status": "ok", "key": "k1", "src": "s1"})
    st.append_record(path, {"module": "b", "stage": "compile", "status": "fail", "key": "k1", "src": "s2"})
    with open(path, "a") as f:
        f.write('{"module": "c", "stage": "comp')          # a writer killed mid-line
    recs = st.load_records(path)
    assert sorted(recs) == [("a", "compile"), ("b", "compile")]
    names = ["a", "b", "c"]
    srcs = {"a": "s1", "b": "s2", "c": "s3"}
    assert st.pending(names, recs, "compile", "k1", srcs) == ["c"]
    # another build key, an edited source or --redo make a record unusable
    assert st.pending(names, recs, "compile", "k2", srcs) == names
    assert st.pending(names, recs, "compile", "k1", {**srcs, "a": "edited"}) == ["a", "c"]
    assert st.pending(names, recs, "compile", "k1", srcs, redo=True) == names
    assert st.pending(names, recs, "probe", "k1") == names
    # the latest record of a (module, stage) wins
    st.append_record(path, {"module": "b", "stage": "compile", "status": "ok", "key": "k1", "src": "s2"})
    assert st.load_records(path)[("b", "compile")]["status"] == "ok"


def test_classification():
    known = {"zipfile", "os"}
    assert st.classify_import_failure("ModuleNotFoundError: No module named '_socket'", known, "socket") == "missing-native: _socket"
    assert st.classify_import_failure("ModuleNotFoundError: No module named 'zipfile'", known, "x") == "missing-dependency: zipfile"
    assert st.classify_import_failure("ImportError: cannot import name 'foo' from 'bar'", known, "x") == "missing-native: bar.foo"
    assert st.classify_import_failure("ValueError: boom", known, "x").startswith("runtime-error: ValueError")
    assert st.classify_import_failure("timeout", known, "x").startswith("timeout")
    assert st.classify_compile_failure("FrameError", "code length 70000 is not in 1..65535 bytes").startswith("method-too-large")
    assert st.classify_compile_failure("CompileError", "unsupported: foo").startswith("compiler-gap")
    assert st.classify_compile_failure("SyntaxError", "bad").startswith("syntax-error")


def test_compare_probe():
    jvm = {"NAMES": "a b c", "SMOKE": "1"}
    assert st.compare_probe(jvm, {"NAMES": "c b a", "SMOKE": "1"}) == ("smoke", "")
    status, reason = st.compare_probe(jvm, {"NAMES": "a b d", "SMOKE": "2"})
    assert status == "imports" and "missing d" in reason and "extra c" in reason and "check differs" in reason
    assert st.compare_probe(jvm, {"IMPORT-FAIL": "x"}) == ("smoke", "")        # nothing to compare against


def _data():
    ok = lambda m, key="k": {"module": m, "stage": "compile", "status": "ok", "key": key}          # noqa: E731
    bad = lambda m, r: {"module": m, "stage": "compile", "status": "fail", "reason": r, "key": "k"}   # noqa: E731
    probe = lambda m, s, r="": {"module": m, "stage": "probe", "status": s, "reason": r, "key": "k"}  # noqa: E731
    recs = {}
    for r in (ok("a"), probe("a", "smoke"), ok("b"), probe("b", "imports", "smoke-mismatch: names differ"), ok("c"),
              probe("c", "import-fail", "missing-native: _ssl"), ok("d"), probe("d", "import-fail", "missing-native: _ssl"),
              bad("e", "compiler-gap: unsupported: match"), bad("f", "compiler-gap: unsupported: match"), ok("g")):
        recs[(r["module"], r["stage"])] = r
    mods = {n: {"path": n + ".py", "pkg": False} for n in "abcdefg"}
    lane = {"python": "Python 3.13", "library": "x", "modules": mods, "excluded": {"test": "suite"}, "records": recs}
    only15 = {"python": "Python 3.15", "library": "y", "modules": {"a": mods["a"], "new15": mods["a"]}, "excluded": {}, "records": {}}
    return {"3.13": lane, "3.15": only15}


def test_manifest_aggregation_and_report():
    manifest = st.build_manifest(_data())
    m = manifest["modules"]
    assert m["a"] == {"3.13": "smoke", "3.15": ["unsupported", "not-built: compile stage not run"]}
    assert m["b"]["3.13"] == ["imports", "smoke-mismatch: names differ"]
    assert m["c"]["3.13"] == ["compiles", "missing-native: _ssl"]
    assert m["e"]["3.13"] == ["unsupported", "compiler-gap: unsupported: match"]
    assert m["g"]["3.13"] == ["compiles", "not-probed: import stage not run"]
    assert "3.15" not in m["e"] and "new15" in m                        # a module absent from a lane has no entry for it
    assert manifest["lanes"]["3.13"]["modules"] == 7 and manifest["excluded"] == {"test": "suite"}

    text = st.dump_manifest(manifest)
    assert json.loads(text) == manifest                                  # machine-readable and lossless
    assert all(line.count('"3.1') <= 2 for line in text.splitlines())     # one module per line

    summ = st.summarize(manifest)["3.13"]
    assert summ["counts"] == {"smoke": 1, "imports": 1, "compiles": 3, "unsupported": 2}
    assert sorted(summ["blockers"]["missing-native: _ssl"]) == ["c", "d"]
    report = st.render_report(manifest)
    assert "| smoke | 1 | 0 |" in report and "| **total** | **7** | **2** |" in report
    assert "| 2 | missing-native: _ssl | `c`, `d` |" in report
    assert "| 2 | compiler-gap: unsupported: match | `e`, `f` |" in report
    # the blockers table lists the biggest groups first
    assert report.index("missing-native: _ssl") < report.index("smoke-mismatch")


def test_checked_in_manifest_is_consistent_with_report():
    manifest_path, report_path = ROOT / "tools" / "stdlib_manifest.json", ROOT / "STDLIB_STATUS.md"
    if not manifest_path.exists():
        return
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["schema"] == 1
    for name, entry in manifest["modules"].items():
        for lane, value in entry.items():
            status, reason = st.entry_status(value)
            assert lane in manifest["lanes"] and status in st.PREFIX_STATUSES, (name, lane, value)
            assert (status in ("smoke",)) == (reason == ""), (name, lane, value)
    assert report_path.read_text(encoding="utf-8") == st.render_report(manifest)      # regenerate with `stdlib_status.py report`
    assert manifest_path.read_text(encoding="utf-8") == st.dump_manifest(manifest)
