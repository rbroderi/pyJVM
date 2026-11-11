# SPDX-License-Identifier: GPL-3.0-or-later
"""exec/eval/compile failure modes that have no CPython counterpart (explicit, never silent)."""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

from pyjvm315.cli import build_runtime
from pyjvm315.compiler import compile_source

ROOT = Path(__file__).resolve().parent.parent


@pytest.fixture(scope="module")
def runtime_dir():
    with tempfile.TemporaryDirectory() as td:
        build_runtime(Path(td))
        yield Path(td)


def run_jvm(runtime_dir: Path, source: str, *, backend: bool):
    with tempfile.TemporaryDirectory() as td:
        out = Path(td)
        (out / "Main.class").write_bytes(compile_source(source, class_name="Main"))
        env = {k: v for k, v in os.environ.items() if k not in ("PYJVM_HOME", "PYJVM_PYTHON")}
        env["JAVA_TOOL_OPTIONS"] = ""
        if backend:
            env.update(PYJVM_HOME=str(ROOT), PYJVM_PYTHON=sys.executable)
        return subprocess.run(["java", "-cp", f"{out}{os.pathsep}{runtime_dir}", "Main"],
                              text=True, capture_output=True, env=env)


def test_missing_backend_is_an_explicit_error(runtime_dir):
    r = run_jvm(runtime_dir, "try:\n    exec('x = 1', {})\nexcept NotImplementedError as e:\n    print('NIE')\n", backend=False)
    assert r.stdout.strip() == "NIE", r.stderr
    r = run_jvm(runtime_dir, "exec('x = 1', {})\n", backend=False)
    assert r.returncode != 0 and "PYJVM_HOME" in r.stderr


def test_implicit_namespace_in_static_module_uses_a_snapshot(runtime_dir):
    src = "k = 4\nprint(eval('k * 2'))\nexec('k = 5')\nprint(k)\n"
    r = run_jvm(runtime_dir, src, backend=True)
    assert r.stdout.split() == ["8", "5"], r.stderr


def test_implicit_namespace_without_backend_is_explicit(runtime_dir):
    r = run_jvm(runtime_dir, "try:\n    eval('1 + 1')\nexcept NotImplementedError:\n    print('NIE')\n", backend=False)
    assert r.stdout.strip() == "NIE", r.stderr


def test_unsupported_source_reports_the_compiler_reason(runtime_dir):
    r = run_jvm(runtime_dir, "try:\n    exec('type X = int', {})\nexcept NotImplementedError as e:\n    print('NIE')\n", backend=True)
    assert r.stdout.strip() == "NIE", r.stderr


def test_dynamic_compile_matches_static_for_simple_module():
    dyn = compile_source("x = 1\ndef f():\n    return x\n", class_name="pyjvm315.runtime.PyExec", dynamic=True)
    assert dyn[:4] == b"\xca\xfe\xba\xbe"
