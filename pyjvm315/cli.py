from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess
import sys

from .compiler import CompileError, compile_file
from .classfile import DEFAULT_TARGET, JAVA_TARGETS
from .stackmap import FrameError


def _runtime_class_source() -> Path:
    return Path(__file__).resolve().parent.parent / "runtime" / "pyjvm315" / "runtime" / "PyRuntime.java"


def build_runtime(output_dir: Path) -> None:
    source = _runtime_class_source()
    if not source.exists():
        raise RuntimeError(f"Runtime source not found: {source}")
    javac = shutil.which("javac")
    if not javac:
        raise RuntimeError("javac is required once to build the pyjvm315 runtime support class")
    subprocess.run([javac, "--release", "17", "-d", str(output_dir), str(source)], check=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pyjvm315", description="Python 3.15 AST -> JVM classfile transpiler")
    parser.add_argument("source", help="Python source file")
    parser.add_argument("-o", "--output", default="build/jvm", help="Output directory")
    parser.add_argument("-c", "--class-name", help="Generated JVM class name, e.g. demo.Main")
    parser.add_argument("--no-runtime", action="store_true", help="Do not build runtime support class")
    parser.add_argument("--target", type=int, choices=JAVA_TARGETS, default=DEFAULT_TARGET,
                        help="Java classfile target (default: 17; runtime requires Java 17+)")
    ns = parser.parse_args(argv)

    out = Path(ns.output)
    out.mkdir(parents=True, exist_ok=True)
    try:
        class_file = compile_file(ns.source, out, ns.class_name, target=ns.target)
        if not ns.no_runtime:
            build_runtime(out)
    except (CompileError, FrameError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"pyjvm315: error: {exc}", file=sys.stderr)
        return 2

    print(class_file)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
