# SPDX-License-Identifier: GPL-3.0-or-later
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
    assert "identical 12, different 0, unmatched 0" in proc.stdout, proc.stdout


@pytest.mark.selfhost
def test_stage1_does_not_depend_on_the_build_directory(tmp_path):
    """ClassCache.fingerprint() hashes these class files: they must not embed where they were built."""
    env = {"PATH": __import__("os").environ["PATH"], "PYTHONPATH": str(ROOT)}
    code = ("import sys, hashlib; from pathlib import Path; from pyjvm315.selfhost import build_selfhosted, tree_bytes; "
            "t = tree_bytes(build_selfhosted(Path(sys.argv[1]))); "
            "print('TREE', len(t), hashlib.sha256(repr(sorted(t.items())).encode()).hexdigest())")
    seen = []
    for work in (tmp_path / "a", tmp_path / "elsewhere" / "deeper"):
        work.mkdir(parents=True)
        proc = subprocess.run([sys.executable, "-c", code, str(work)], text=True, capture_output=True, env=env)
        assert proc.returncode == 0, proc.stdout + proc.stderr
        seen.append([line for line in proc.stdout.splitlines() if line.startswith("TREE")])
        assert str(tmp_path).encode() not in b"".join(p.read_bytes() for p in (work / "stage1").rglob("*.class"))
    assert seen[0] == seen[1] and seen[0][0].startswith("TREE 12 "), seen
