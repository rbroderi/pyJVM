"""Self-hosting build (roadmap P1-07).

Stage 1: the host-run compiler compiles the compiler's own core (package `pyjvm315` minus host-only modules)
to class files. Stage 2: that JVM-run compiler compiles the core again. The bootstrap gate is that stage 2's
class files are byte-identical to stage 1's.

usage: python tools/selfhost.py stage1 <workdir>     build the JVM compiler with the host compiler
       python tools/selfhost.py stage2 <workdir>     rebuild with the JVM compiler and compare
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
CORE = ["compiler", "classfile", "stackmap", "patma", "annot", "astutil", "versions"]
ENTRY = '''import sys
import jvm_io
from pyjvm315.compiler import compile_source


def main():
    # jobs file: "map <module> <jvm class>", "export <module> <a,b,..>" / "exportnone <module>",
    # "job <source> <output .class> <jvm class> <module> <package>"
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
        data = compile_source(jvm_io.read_text(job[1]), job[3], job[1], import_map=import_map,
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
    from pyjvm315.cli import build_runtime
    rt = work / "runtime"
    if rt.exists():
        shutil.rmtree(rt)
    rt.mkdir(parents=True)
    build_runtime(rt)
    return rt


def write_jobs(plan, out_dir: Path, jobs_file: Path) -> None:
    lines = []
    for name, jvm in plan.import_map.items():
        lines.append(f"map {name} {jvm}")
    for name, exports in plan.import_exports.items():
        lines.append(f"exportnone {name}" if exports is None else f"export {name} {','.join(exports)}")
    from pyjvm315.driver import class_file_path
    for path, jvm_name, module_name, package_name in plan.jobs:
        lines.append(f"job {path} {class_file_path(out_dir, jvm_name)} {jvm_name} {module_name} {package_name}")
    jobs_file.write_text("\n".join(lines) + "\n")


def tree_bytes(root: Path) -> dict[str, bytes]:
    return {str(p.relative_to(root)): p.read_bytes() for p in sorted(root.rglob("*.class"))}


def stage1(work: Path) -> int:
    """The host-run compiler builds the JVM compiler."""
    from pyjvm315.driver import compile_file, plan_compile
    src = stage_sources(work)
    out = work / "stage1"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    compile_file(src / "selfhost_main.py", out, "SelfHost")
    build_runtime_classes(work)
    print(f"stage 1: {len(tree_bytes(out))} class files in {out}")
    return 0


def stage2(work: Path) -> int:
    """The JVM-run compiler compiles the compiler again; its class files must be identical to stage 1's."""
    from pyjvm315.driver import plan_compile
    src = work / "src"
    plan = plan_compile(src / "selfhost_main.py", "SelfHost")
    out2 = work / "stage2"
    if out2.exists():
        shutil.rmtree(out2)
    out2.mkdir(parents=True)
    # the program itself (selfhost_main) and every module it imports
    write_jobs(plan, out2, work / "jobs.txt")
    proc = subprocess.run(["java", "-Xss128m", "-cp", f"{work / 'runtime'}:{work / 'stage1'}", "pyjvm315.runtime.Launcher",
                           "SelfHost", str(work / "jobs.txt")], text=True, capture_output=True)
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


if __name__ == "__main__":
    cmd, work = sys.argv[1], Path(sys.argv[2]).resolve()
    work.mkdir(parents=True, exist_ok=True)
    raise SystemExit({"stage1": stage1, "stage2": stage2}[cmd](work))
