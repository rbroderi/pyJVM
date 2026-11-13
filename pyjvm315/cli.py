# SPDX-License-Identifier: GPL-3.0-or-later
from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess
import sys

from .compiler import CompileError
from .driver import compile_file
from .classfile import DEFAULT_TARGET, JAVA_TARGETS
from .stackmap import FrameError


def _runtime_class_source() -> Path:
    return Path(__file__).resolve().parent.parent / "runtime" / "pyjvm315" / "runtime" / "PyRuntime.java"


def _runtime_sources() -> list[Path]:
    """Every runtime source file (PyRuntime plus the per-type files split out of it)."""
    return sorted(_runtime_class_source().parent.parent.rglob("*.java"))


def build_runtime(output_dir: Path) -> None:
    source = _runtime_class_source()
    if not source.exists():
        raise RuntimeError(f"Runtime source not found: {source}")
    javac = shutil.which("javac")
    if not javac:
        raise RuntimeError("javac is required once to build the pyjvm315 runtime support class")
    subprocess.run([javac, "--release", "25", "-d", str(output_dir), *map(str, _runtime_sources())], check=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pyjvm315", description="Python (3.13+) AST -> JVM classfile transpiler")
    parser.add_argument("source", help="Python source file")
    parser.add_argument("-o", "--output", default="build/jvm", help="Output directory")
    parser.add_argument("-c", "--class-name", help="Generated JVM class name, e.g. demo.Main")
    parser.add_argument("--no-runtime", action="store_true", help="Do not build runtime support class")
    parser.add_argument("--target", type=int, choices=JAVA_TARGETS, default=DEFAULT_TARGET,
                        help="Java classfile target (25 only; runtime requires Java 25+)")
    parser.add_argument("--trace", action="store_true",
                        help="Instrumented mode: line/handler events for sys.settrace, setprofile and sys.monitoring "
                             "(also PYJVM_TRACE=1; modules mentioning settrace/setprofile/monitoring are instrumented automatically)")
    ns = parser.parse_args(argv)

    out = Path(ns.output)
    out.mkdir(parents=True, exist_ok=True)
    try:
        class_file = compile_file(ns.source, out, ns.class_name, target=ns.target, trace=ns.trace)
        if not ns.no_runtime:
            build_runtime(out)
    except (CompileError, FrameError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"pyjvm315: error: {exc}", file=sys.stderr)
        return 2

    print(class_file)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
