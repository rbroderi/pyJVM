"""Validate the harness's correctness and regression guard, not speed on CI."""
import copy
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("benchmark_runner", ROOT / "benchmarks/run.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def report(value=100):
    return {"schema": 1, "suite_sha256": "abc", "config": {}, "environment": {},
            "results": {case: {"generated": {"median_ns": value,
                                              "min_fork_ns": value * .9,
                                              "max_fork_ns": value * 1.1}}
                        for case in bench.CASES}}


def test_guard_detects_slowdown_but_requires_nonoverlapping_forks():
    base = report()
    assert all(x["regression"] for x in bench.compare(report(200), base, 1.5).values())
    noisy = report(160)
    assert not any(x["regression"] for x in bench.compare(noisy, base, 1.5).values())
    assert not any(x["regression"] for x in bench.compare(report(80), base, 1.5).values())


@pytest.mark.parametrize("key", ["schema", "suite_sha256", "config", "environment"])
def test_guard_rejects_incompatible_baselines(key):
    current = copy.deepcopy(report())
    current[key] = "changed"
    with pytest.raises(ValueError, match="incompatible benchmark baseline"):
        bench.compare(current, report(), 1.5)


def test_timed_checksum_is_verified_against_cpython():
    kernel = bench.reference().range_loop
    checks = "\n".join(f"CHECK {n} {kernel(n)}" for n in (0, 1, 7, 8, 9))
    with pytest.raises(ValueError, match="timed checksum"):
        bench.parse_output(checks + "\nSAMPLE 100 2 0", kernel, 8)
    good = bench.parse_output(checks + "\nSAMPLE 100 2 64", kernel, 8)
    assert good["median_ns"] == 100


@pytest.mark.skipif(not shutil.which("javac"), reason="JDK required")
def test_all_three_modes_match_cpython(tmp_path):
    result = subprocess.run([sys.executable, str(ROOT / "benchmarks/run.py"),
                             "--output", str(tmp_path), "--forks", "1", "--samples", "1",
                             "--warmup-ms", "10", "--sample-ms", "1", "--size", "8"],
                            cwd=ROOT, capture_output=True, text=True, timeout=120)
    assert result.returncode == 0, result.stdout + result.stderr
    assert (tmp_path / "Kernels.javap.txt").is_file()
    assert "Generated / Runtime" in (tmp_path / "report.md").read_text()
