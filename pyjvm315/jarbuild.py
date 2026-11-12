# SPDX-License-Identifier: GPL-3.0-or-later
"""Build a jar of precompiled modules for the run-time jar finder (roadmap P3-02; P3-04 uses it for the stdlib).

    python3 -m pyjvm315.jarbuild out.jar ROOT [ROOT ...] [--skip NAME ...] [--fingerprint HEX]

Every ``*.py`` below each ROOT becomes a module (``pkg/__init__.py`` is the package ``pkg``). The archive holds one
class file per module (the same bytes ``compile(source, filename, "exec")`` produces at run time) and the manifest
``META-INF/pyjvm-modules.txt`` that ``pyjvm315.runtime.JarImport`` reads. A jar on ``sys.path`` (or in ``PYTHONPATH``)
is then searched like a directory but its modules load without compiling. Files the compiler cannot handle are left
out and listed on stderr and as comments in the manifest, so the import falls through to the next path entry.

The archive is written to a temporary file and renamed into place. ``--fingerprint`` pins the archive to one
compiler/runtime build (the finder ignores a jar whose fingerprint differs from the running ``ClassCache.fingerprint()``).
"""
from __future__ import annotations

import argparse
import os
import sys
import tempfile
import zipfile
from pathlib import Path

from .compiler import CompileError
from .exec_compile import compile_for_exec
from .stackmap import FrameError

MANIFEST = "META-INF/pyjvm-modules.txt"


def module_name(root: Path, path: Path) -> tuple[str, bool]:
    parts = list(path.relative_to(root).with_suffix("").parts)
    is_package = parts[-1] == "__init__"
    if is_package:
        parts.pop()
    return ".".join(parts), is_package


def build_jar(out: Path, roots: list[Path], *, skip: set[str] | frozenset[str] = frozenset(), fingerprint: str | None = None,
              feature_minor: int = -1) -> tuple[list[str], dict[str, str]]:
    """Write ``out``; returns (module names included, {module name: reason} for the modules left out)."""
    lines = ["pyjvm-jar\t1", f"lang\t{sys.version_info[0]}.{sys.version_info[1]}"]
    if fingerprint:
        lines.append(f"fingerprint\t{fingerprint}")
    included: list[str] = []
    skipped: dict[str, str] = {}
    seen: set[str] = set()
    out.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=".pyjvm-", suffix=".tmp", dir=out.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(tmp_name, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nCreated-By: pyjvm315 jarbuild\r\n\r\n")
            for root in roots:
                for path in sorted(root.rglob("*.py")):
                    name, is_package = module_name(root, path)
                    if not name or name in seen:
                        continue
                    seen.add(name)
                    if name in skip or any(name.startswith(s + ".") for s in skip):
                        skipped[name] = "excluded"
                        continue
                    relative = path.relative_to(root).as_posix()
                    try:
                        data = compile_for_exec(path.read_text(encoding="utf-8"), relative, "exec", 0, feature_minor)
                    except (SyntaxError, CompileError, FrameError, RecursionError, UnicodeDecodeError) as exc:
                        skipped[name] = f"{type(exc).__name__}: {exc}".splitlines()[0]
                        continue
                    entry = name + ".class"
                    zf.writestr(entry, data)
                    lines.append(f"module\t{name}\t{'pkg' if is_package else 'mod'}\t{entry}\t{relative}")
                    included.append(name)
            for name, reason in skipped.items():
                lines.append(f"# skipped {name}: {reason}")
            zf.writestr(MANIFEST, "\n".join(lines) + "\n")
        os.replace(tmp_name, out)
    except BaseException:
        Path(tmp_name).unlink(missing_ok=True)
        raise
    return included, skipped


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build a jar of precompiled pyJVM modules")
    ap.add_argument("out", type=Path)
    ap.add_argument("roots", type=Path, nargs="+")
    ap.add_argument("--skip", nargs="*", default=[], help="module names (and their submodules) to leave out")
    ap.add_argument("--fingerprint", help="pin the jar to a compiler/runtime build")
    ns = ap.parse_args(argv)
    included, skipped = build_jar(ns.out, [r.resolve() for r in ns.roots], skip=set(ns.skip), fingerprint=ns.fingerprint)
    for name, reason in skipped.items():
        print(f"skipped {name}: {reason}", file=sys.stderr)
    print(f"{ns.out}: {len(included)} modules, {len(skipped)} skipped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
