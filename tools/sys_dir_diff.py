# SPDX-License-Identifier: GPL-3.0-or-later
"""Diff dir(sys) of pyJVM against a reference CPython.

Usage: PYTHONPATH=. python3 tools/sys_dir_diff.py python3.15 [python3 ...]

pyJVM runs through the conformance runner (self-hosted lane), so honour PYJVM_CACHE / PYJVM_BUILD_CACHE.
Prints the names CPython has and pyJVM lacks, and the names only pyJVM has.
"""
from __future__ import annotations

import ast
import re
import subprocess
import sys
import tempfile
from pathlib import Path

PROBE = 'import sys\nprint("\\n".join(sorted(dir(sys))))\n'


def cpython_names(exe: str) -> set[str]:
    out = subprocess.run([exe, "-c", PROBE], capture_output=True, text=True, check=True).stdout
    return set(out.split())


def pyjvm_names(exe: str) -> set[str]:
    from pyjvm315.conformance import run_suite
    with tempfile.TemporaryDirectory() as td:
        (Path(td) / "sys_dir_probe.py").write_text(PROBE)
        result = run_suite(Path(td), exe, selfhosted=True)[0]
    if result.status == "PASS":
        return cpython_names(exe)
    m = re.search(r"JVM:\s+('.*?')\nJVM stderr", result.detail, re.S)
    if not m:
        raise SystemExit(f"probe failed: {result.status} {result.detail[:300]}")
    return set(ast.literal_eval(m.group(1)).split())


def main(argv: list[str]) -> int:
    for exe in argv or ["python3"]:
        ref, jvm = cpython_names(exe), pyjvm_names(exe)
        print(f"== {exe}: CPython {len(ref)} names, pyJVM {len(jvm)}")
        print("missing in pyJVM:", " ".join(sorted(ref - jvm)) or "(none)")
        print("only in pyJVM:   ", " ".join(sorted(jvm - ref)) or "(none)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
