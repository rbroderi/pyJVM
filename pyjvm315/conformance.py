from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import json
import re
import subprocess
import sys
import tempfile
import shutil

from .compiler import CompileError, compile_file
from .cli import build_runtime
from .classfile import DEFAULT_TARGET, JAVA_TARGETS


@dataclass
class Result:
    name: str
    status: str
    detail: str = ""


def _run(cmd: list[str], *, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)


def _stdout(proc: subprocess.CompletedProcess[str]) -> str:
    return proc.stdout.replace("\r\n", "\n")


def _python_exception(stderr: str) -> str | None:
    lines = [line.strip() for line in stderr.replace("\r\n", "\n").splitlines() if line.strip()]
    for line in reversed(lines):
        match = re.match(r"([A-Za-z_][A-Za-z0-9_]*(?:Error|Exception))(?::.*)?$", line)
        if match:
            return match.group(1)
    return None


def _jvm_exception(stderr: str) -> str | None:
    lines = [line.strip() for line in stderr.replace("\r\n", "\n").splitlines() if line.strip()]
    for line in lines:
        line = re.sub(r'^Exception in thread "[^"]+"\s+', "", line)
        # PyRuntime.PyException renders its Python exception type in the message.
        match = re.search(r"(?:^|\s)([A-Za-z_][A-Za-z0-9_]*(?:Error|Exception))(?::|$)", line)
        if match:
            return match.group(1)
        # Map common uncaught JVM implementation exceptions to Python categories.
        if "ArithmeticException" in line:
            return "ZeroDivisionError"
        if "IndexOutOfBoundsException" in line:
            return "IndexError"
        if "NoSuchElementException" in line:
            return "KeyError"
        if "IllegalArgumentException" in line or "ClassCastException" in line:
            return "TypeError"
    return None


def compare_file(source: Path, python_exe: str, java_exe: str = "java", runtime_cache: Path | None = None, *,
                 target: int = DEFAULT_TARGET) -> Result:
    source = source.resolve()
    py = _run([python_exe, str(source)], cwd=source.parent)

    with tempfile.TemporaryDirectory(prefix="pyjvm315-") as td:
        out = Path(td)
        try:
            compile_file(source, out, "ConformanceMain", target=target)
            if runtime_cache is None:
                build_runtime(out)
            else:
                cached_pkg = runtime_cache / "pyjvm315"
                if cached_pkg.exists():
                    shutil.copytree(cached_pkg, out / "pyjvm315", dirs_exist_ok=True)
        except CompileError as exc:
            return Result(source.name, "UNSUPPORTED", str(exc))
        except Exception as exc:
            return Result(source.name, "ERROR", f"build failed: {exc}")
        jvm = _run([java_exe, "-Xverify:all", "-cp", str(out), "ConformanceMain"], cwd=source.parent)

    if _stdout(jvm) != _stdout(py):
        return Result(source.name, "FAIL", f"stdout differs\nCPython: {py.stdout!r}\nJVM:     {jvm.stdout!r}\nJVM stderr: {jvm.stderr!r}")

    if py.returncode == 0:
        if jvm.returncode != 0:
            return Result(source.name, "FAIL", f"JVM exited {jvm.returncode}: {jvm.stderr.strip()}")
        return Result(source.name, "PASS")

    if jvm.returncode == 0:
        return Result(source.name, "FAIL", "CPython raised but JVM completed successfully")

    py_exc = _python_exception(py.stderr)
    jvm_exc = _jvm_exception(jvm.stderr)
    if py_exc is None or jvm_exc is None:
        return Result(source.name, "FAIL", f"could not normalize exception\nCPython: {py.stderr!r}\nJVM: {jvm.stderr!r}")
    if py_exc != jvm_exc:
        return Result(source.name, "FAIL", f"exception differs: CPython={py_exc}, JVM={jvm_exc}")
    return Result(source.name, "PASS", f"matched exception {py_exc}")


def run_suite(path: Path, python_exe: str, java_exe: str = "java", *,
              target: int = DEFAULT_TARGET) -> list[Result]:
    if path.is_file():
        files = [path]
    else:
        candidates = sorted(path.rglob("*.py"))
        # A directory with main.py is a multi-file conformance case; helper
        # modules and package __init__.py files are dependencies, not separate
        # executable cases.
        files = [f for f in candidates if f.name != "__init__.py" and not (f.name != "main.py" and (f.parent / "main.py").exists())]
    # javac startup dominated the old runner because the tiny support runtime was
    # rebuilt for every file. Build it once per suite and copy the class package
    # into each isolated test output directory.
    with tempfile.TemporaryDirectory(prefix="pyjvm315-runtime-") as td:
        runtime_cache = Path(td)
        build_runtime(runtime_cache)
        return [compare_file(f, python_exe, java_exe, runtime_cache, target=target) for f in files]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Differential CPython -> JVM conformance runner")
    ap.add_argument("path", type=Path, help="A .py file or directory of .py cases")
    ap.add_argument("--python", default=sys.executable, help="Reference CPython executable (use python3.15 for 3.15 conformance)")
    ap.add_argument("--java", default="java")
    ap.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    ap.add_argument("--unsupported-summary", action="store_true", help="Group unsupported cases by compiler reason")
    ap.add_argument("--target", type=int, choices=JAVA_TARGETS, default=DEFAULT_TARGET)
    ns = ap.parse_args(argv)
    results = run_suite(ns.path, ns.python, ns.java, target=ns.target)

    passed = sum(r.status == "PASS" for r in results)
    unsupported = sum(r.status == "UNSUPPORTED" for r in results)
    failed = sum(r.status in {"FAIL", "ERROR"} for r in results)

    if ns.json:
        print(json.dumps({
            "summary": {"passed": passed, "unsupported": unsupported, "failed": failed},
            "results": [asdict(r) for r in results],
        }, indent=2))
    else:
        for r in results:
            print(f"{r.status:11} {r.name}" + (f" - {r.detail}" if r.detail else ""))
        print(f"\n{passed} passed, {unsupported} unsupported, {failed} failed")
        if ns.unsupported_summary and unsupported:
            print("\nUnsupported feature summary:")
            reasons = Counter(r.detail for r in results if r.status == "UNSUPPORTED")
            for reason, count in reasons.most_common():
                print(f"  {count:4}  {reason}")
    return 1 if (failed or unsupported) else 0


if __name__ == "__main__":
    raise SystemExit(main())
