# SPDX-License-Identifier: GPL-3.0-or-later
"""Run CPython Lib/test modules on pyJVM and compare per-test results with CPython (roadmap P3-05).

usage: python3.15 tools/run_cpython_test.py [--corpus DIR] [--python EXE] [--jvm-only | --cpython-only]
                                            [-v] [-k PATTERN] test_bool test_tuple ...

The corpus is a CPython checkout's ``Lib`` directory (default /tmp/claude-0/cpython/Lib, or $PYJVM_CPYTHON_LIB). Only its
``test`` package is put on the path (via a symlink directory); the standard library comes from the reference interpreter.
PYJVM_CACHE / PYJVM_BUILD_CACHE select the persistent compile caches (default ~/.cache/pyjvm). Exit status is 0 when
every compared test has the same status on both lanes.
"""
import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from pyjvm315.classfile import DEFAULT_TARGET  # noqa: E402
from pyjvm315.driver import compile_file  # noqa: E402
from pyjvm315.hostver import reference_stdlib, reference_version  # noqa: E402
from pyjvm315.selfhost import cached_build  # noqa: E402

RUNNER = ROOT / "tools" / "pyjvm_regrtest.py"


def parse(output):
    results, summaries = {}, {}
    for line in output.splitlines():
        if line.startswith("RESULT "):
            _, test_id, status = line.split(" ", 2)
            results[test_id] = status
        elif line.startswith("SUMMARY "):
            _, name, rest = line.split(" ", 2)
            summaries[name] = rest
    return results, summaries


def run_cpython(python, env, args, timeout):
    return subprocess.run([python, str(RUNNER), *args], env=env, capture_output=True, text=True, timeout=timeout)


def run_jvm(python, env, args, timeout):
    build_root = Path(os.environ.get("PYJVM_BUILD_CACHE") or Path.home() / ".cache" / "pyjvm-build")
    runtime_dir, stage1 = cached_build(build_root, selfhosted=True)
    with tempfile.TemporaryDirectory(prefix="pyjvm-regrtest-") as td:
        out = Path(td)
        compile_file(RUNNER, out, "RunTest", target=DEFAULT_TARGET)
        classpath = os.pathsep.join([str(out), str(runtime_dir), str(stage1)])
        jenv = dict(env, PYJVM_LANG_VERSION="%d.%d" % reference_version(python),
                    PYJVM_STDLIB=os.environ.get("PYJVM_STDLIB") or reference_stdlib(python))
        return subprocess.run(["java", "-Xss64m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", classpath,
                               "pyjvm315.runtime.Launcher", "RunTest", *args],
                              env=jenv, capture_output=True, text=True, timeout=timeout)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("tests", nargs="+", help="test module names, e.g. test_bool")
    ap.add_argument("--corpus", default=os.environ.get("PYJVM_CPYTHON_LIB", "/tmp/claude-0/cpython/Lib"))
    ap.add_argument("--python", default="python3.15", help="reference interpreter (also supplies the stdlib)")
    ap.add_argument("--jvm-only", action="store_true")
    ap.add_argument("--cpython-only", action="store_true")
    ap.add_argument("-v", action="store_true")
    ap.add_argument("-k", dest="pattern")
    ap.add_argument("--timeout", type=int, default=1800)
    ns = ap.parse_args(argv)
    args = (["-v"] if ns.v else []) + (["-k", ns.pattern] if ns.pattern else []) + ns.tests
    status = 0
    with tempfile.TemporaryDirectory(prefix="pyjvm-testpath-") as path:
        (Path(path) / "test").symlink_to(Path(ns.corpus) / "test")
        env = dict(os.environ, PYTHONPATH=path)
        cpy = jvm = None
        if not ns.jvm_only:
            cpy = run_cpython(ns.python, env, args, ns.timeout)
        if not ns.cpython_only:
            jvm = run_jvm(ns.python, env, args, ns.timeout)
    cres, csum = parse(cpy.stdout) if cpy else ({}, {})
    jres, jsum = parse(jvm.stdout) if jvm else ({}, {})
    if jvm and not jres:
        print("JVM produced no results; stderr tail:\n" + "\n".join(jvm.stderr.splitlines()[-15:]))
        status = 1
    for name in ns.tests:
        print("%-24s CPython: %s" % (name, csum.get(name, "-")))
        print("%-24s JVM:     %s" % ("", jsum.get(name, "-")))
    if cpy and jvm:
        diffs = sorted(t for t in set(cres) | set(jres) if cres.get(t) != jres.get(t))
        for t in diffs:
            print("DIFF %s: CPython=%s JVM=%s" % (t, cres.get(t), jres.get(t)))
        same = len(set(cres) & set(jres)) - len([t for t in diffs if t in cres and t in jres])
        print("%d tests match, %d differ" % (same, len(diffs)))
        status = status or (1 if diffs else 0)
    elif jvm:
        counts = {}
        for s in jres.values():
            counts[s] = counts.get(s, 0) + 1
        print(counts)
    return status


if __name__ == "__main__":
    sys.exit(main())
