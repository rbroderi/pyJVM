"""Bootstrap gate (roadmap P1-07): the compiler, compiled by itself on the JVM, reproduces the host-built class files.

Stage 1 builds the JVM compiler with the host-run compiler; stage 2 runs it on the JVM to compile its own source
again. Every class file must be byte-identical. Takes about a minute (it runs the compiler on the JVM).
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent


@pytest.mark.selfhost
def test_stage2_is_byte_identical_to_stage1(tmp_path):
    env = {"PATH": __import__("os").environ["PATH"], "PYTHONPATH": str(ROOT)}
    for stage in ("stage1", "stage2"):
        proc = subprocess.run([sys.executable, str(ROOT / "tools/selfhost.py"), stage, str(tmp_path)],
                              text=True, capture_output=True, env=env)
        assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "identical 9, different 0, unmatched 0" in proc.stdout, proc.stdout
