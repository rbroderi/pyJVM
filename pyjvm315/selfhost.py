# SPDX-License-Identifier: GPL-3.0-or-later
"""Self-hosting build (roadmap P1-07/P1-08).

Stage 1: the host-run compiler compiles the compiler's own core (package `pyjvm315` minus host-only modules)
to class files. Stage 2: that JVM-run compiler compiles the core again. The bootstrap gate is that stage 2's
class files are byte-identical to stage 1's.

The command line is tools/selfhost.py; the in-process exec/eval backend and the conformance runner use
build_selfhosted() to get the compiler's class files.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORE = ["compiler", "classfile", "stackmap", "patma", "annot", "excstar", "carets", "typeparams", "astutil", "versions"]
ENTRY = '''import sys
import jvm_io
from pyjvm315.compiler import compile_source


def main():
    # jobs file: "map <module> <jvm class>", "export <module> <a,b,..>" / "exportnone <module>",
    # "job <source> <output .class> <jvm class> <module> <package> <file name recorded in the class>"
    import_map = {}
    import_exports = {}
    jobs = []
    for line in jvm_io.read_text(sys.argv[1]).split("\\n"):
        if not line:
            continue
        parts = line.split(" ")
        if parts[0] == "map":
            import_map[parts[1]] = parts[2]
        elif parts[0] == "export":
            import_exports[parts[1]] = parts[2].split(",") if parts[2] else []
        elif parts[0] == "exportnone":
            import_exports[parts[1]] = None
        elif parts[0] == "job":
            jobs.append(parts)
    for job in jobs:
        data = compile_source(jvm_io.read_text(job[1]), job[3], job[6], import_map=import_map,
                              import_exports=import_exports, module_name=job[4], package_name=job[5])
        jvm_io.write_bytes(job[2], data)


main()
'''


def stage_sources(work: Path) -> Path:
    src = work / "src"
    if src.exists():
        shutil.rmtree(src)
    (src / "pyjvm315").mkdir(parents=True)
    (src / "pyjvm315" / "__init__.py").write_text("")
    for name in CORE:
        shutil.copy(ROOT / "pyjvm315" / f"{name}.py", src / "pyjvm315" / f"{name}.py")
    (src / "selfhost_main.py").write_text(ENTRY)
    return src


def build_runtime_classes(work: Path) -> Path:
    from .cli import build_runtime
    rt = work / "runtime"
    if rt.exists():
        shutil.rmtree(rt)
    rt.mkdir(parents=True)
    build_runtime(rt)
    return rt


def write_jobs(plan, out_dir: Path, jobs_file: Path, embed_root: Path | None = None) -> None:
    lines = []
    for name, jvm in plan.import_map.items():
        lines.append(f"map {name} {jvm}")
    for name, exports in plan.import_exports.items():
        lines.append(f"exportnone {name}" if exports is None else f"export {name} {','.join(exports)}")
    from .driver import class_file_path, embedded_name
    for path, jvm_name, module_name, package_name in plan.jobs:
        lines.append(f"job {path} {class_file_path(out_dir, jvm_name)} {jvm_name} {module_name} {package_name} {embedded_name(path, embed_root)}")
    jobs_file.write_text("\n".join(lines) + "\n")


def tree_bytes(root: Path) -> dict[str, bytes]:
    return {str(p.relative_to(root)): p.read_bytes() for p in sorted(root.rglob("*.class"))}


def stage1(work: Path, runtime: bool = True) -> int:
    """The host-run compiler builds the JVM compiler (and, for stage 2, the runtime it runs on).

    File names are recorded relative to the staged source root (``pyjvm315/compiler.py``), not as absolute paths: the
    class files are then byte-identical wherever they are built, which keeps ``ClassCache.fingerprint()`` (a hash of
    these files) and with it the persistent compile cache stable across builds from the same sources."""
    from .driver import compile_file, plan_compile
    src = stage_sources(work)
    out = work / "stage1"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    compile_file(src / "selfhost_main.py", out, "SelfHost", embed_root=src)
    if runtime:
        build_runtime_classes(work)
    print(f"stage 1: {len(tree_bytes(out))} class files in {out}")
    return 0


def stage2(work: Path) -> int:
    """The JVM-run compiler compiles the compiler again; its class files must be identical to stage 1's."""
    from .driver import plan_compile
    src = work / "src"
    plan = plan_compile(src / "selfhost_main.py", "SelfHost")
    out2 = work / "stage2"
    if out2.exists():
        shutil.rmtree(out2)
    out2.mkdir(parents=True)
    # the program itself (selfhost_main) and every module it imports
    write_jobs(plan, out2, work / "jobs.txt", embed_root=src)
    proc = subprocess.run(["java", "-Xss128m", "-cp", f"{work / 'runtime'}:{work / 'stage1'}", "pyjvm315.runtime.Launcher",
                           "SelfHost", str(work / "jobs.txt")], text=True, capture_output=True,
                          # the JVM-run compiler must make the same version-dependent choices (deferred annotations) as the host
                          env=dict(os.environ, PYJVM_LANG_VERSION="%d.%d" % sys.version_info[:2]))
    if proc.returncode != 0:
        print(proc.stderr[-2000:])
        return 1
    one, two = tree_bytes(work / "stage1"), tree_bytes(out2)
    same = sorted(k for k in one if k in two and one[k] == two[k])
    different = sorted(k for k in one if k in two and one[k] != two[k])
    missing = sorted(set(one) ^ set(two))
    print(f"stage 2: {len(two)} class files; identical {len(same)}, different {len(different)}, unmatched {len(missing)}")
    for k in different + missing:
        print("  ", k)
    return int(bool(different or missing))


def build_selfhosted(work: Path) -> Path:
    """Stage 1 only: class files of the JVM-hosted compiler (put the returned directory on the classpath)."""
    stage1(work, runtime=False)
    return work / "stage1"


def build_key() -> str:
    """Identifies what a build is made from: the compiler sources, the runtime sources and the host Python that runs
    stage 1 (its version selects version-dependent code generation)."""
    h = hashlib.sha256(("pyjvm-build-1 " + sys.version).encode())
    files = sorted((ROOT / "pyjvm315").glob("*.py")) + sorted((ROOT / "runtime").rglob("*.java"))
    for path in files:
        h.update(path.relative_to(ROOT).as_posix().encode() + b"\0")
        h.update(hashlib.sha256(path.read_bytes()).digest())
    return h.hexdigest()[:16]


def _publish(tmp: Path, final: Path) -> None:
    """Rename a finished build into place; a build another process finished first wins."""
    try:
        os.rename(tmp, final)
    except OSError:
        if not final.exists():
            raise
        shutil.rmtree(tmp, ignore_errors=True)


def cached_build(cache_root: Path, selfhosted: bool = False) -> tuple[Path, Path | None]:
    """A persistent build shared by many runs: returns (runtime class directory, stage-1 class directory or None).

    The build lives in ``cache_root/<build_key()>`` and is reused as long as the sources are unchanged; other keys are
    left alone (delete the directory to reclaim space). Each part is built in a scratch directory and renamed into
    place when complete, so an interrupted build is never mistaken for a finished one."""
    from .cli import build_runtime
    cache_root = Path(cache_root).resolve()
    cache_root.mkdir(parents=True, exist_ok=True)
    home = cache_root / build_key()
    if not home.is_dir():
        tmp = cache_root / f".{home.name}.{os.getpid()}.tmp"
        shutil.rmtree(tmp, ignore_errors=True)
        tmp.mkdir()
        build_runtime(tmp)
        _publish(tmp, home)
    stage = home / "selfhosted" / "stage1"
    if not selfhosted:
        return home, None
    if not stage.is_dir():
        tmp = cache_root / f".{home.name}.selfhosted.{os.getpid()}.tmp"
        shutil.rmtree(tmp, ignore_errors=True)
        build_selfhosted(tmp)
        _publish(tmp, home / "selfhosted")
    return home, stage
