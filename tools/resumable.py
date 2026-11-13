# SPDX-License-Identifier: GPL-3.0-or-later
"""Resumable, restart-tolerant runners for the slow lanes (stdlib only; no JVM needed to import this file).

    python3 tools/resumable.py pytest      --out DIR [--skip test_x ...] [--then methodsize probe] [--cpython-root ROOT]
    python3 tools/resumable.py conformance --out DIR [--python python3.15] [--group-size 4] [--jobs 2]
    python3 tools/resumable.py strict      --out DIR --cpython-root ROOT [--manifest FILE] [--piece-size 25] [--jobs 2]

Each runner cuts its work into small units, runs every unit as its own process, writes the unit's output to
``DIR/<unit>.out`` and treats the unit as finished only when that output contains the unit's *completion marker*
(the final summary line the tool prints). A unit killed half way (container restart, Ctrl-C, ``nohup`` job reaped) has
no marker, so running the same command again redoes exactly the unfinished units. ``--force`` ignores markers,
``--dry-run`` lists what would run, and every runner ends with an aggregated summary (exit status 1 when anything is
unfinished or not passing). If the input of a unit changed (files added to a group, an edited manifest) its old output
is discarded.

Children run with SIGINT reset to the default action: a job started with ``nohup ... &`` inherits SIGINT=SIG_IGN, which
the JVM passes on to the program under test and which breaks the signal tests.

Run it detached so it survives a closed terminal::

    nohup python3 tools/resumable.py conformance --out /tmp/conf > /tmp/conf.log 2>&1 &

Set ``PYJVM_CACHE`` / ``PYJVM_BUILD_CACHE`` to private directories when other jobs share the machine; the conformance and
strict runners default ``PYJVM_BUILD_CACHE`` to ``DIR/build`` (keyed by the sources, see README "Compile cache").
"""
from __future__ import annotations

import argparse
import ast
import json
import os
import re
import shutil
import signal
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PYTEST_DONE = " passed"                       # "12 passed in 40s"; also "1 failed, 3 passed"
GROUP_DONE = " passed, "                      # conformance summary: "N passed, M unsupported, K failed"
PIECE_DONE = "{'PASS':"                       # cpython_execution prints the summary dict last
METHODSIZE_DONE = "methods checked, "
PROBE_DONE = "COMPILES"


# ---- pure helpers (unit tested) ---------------------------------------------------------------

def chunks(items: list, size: int) -> list[list]:
    """Consecutive groups of ``size`` items (the last one may be shorter)."""
    if size < 1:
        raise ValueError("group size must be positive")
    return [items[i:i + size] for i in range(0, len(items), size)]


def split_manifest(text: str, size: int) -> list[list[str]]:
    """Pieces of ``size`` case lines each. Blank and comment lines are dropped; an ``@imports`` directive applies to
    the cases after it, so every piece starts with the directive that is active at its first case (an ``@imports``
    with no modules switches it off again)."""
    if size < 1:
        raise ValueError("piece size must be positive")
    pieces: list[list[str]] = []
    active: str | None = None
    current: list[str] = []
    cases = 0
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@imports"):
            if cases == size:                    # a full piece ends here: the directive belongs to the next one
                pieces.append(current)
                current, cases = [], 0
            active = line if len(line.split()) > 1 else None
            current.append(line)
            continue
        if cases == size:
            pieces.append(current)
            current, cases = ([active] if active else []), 0
        current.append(line)
        cases += 1
    if cases:
        pieces.append(current)
    return pieces


def is_done(output: Path, marker: str) -> bool:
    try:
        return marker in output.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False


def sync_input(path: Path, content: str, output: Path | None = None) -> None:
    """Write a unit's input file; when it differs from what an earlier run saw, the stale output is dropped."""
    try:
        if path.read_text(encoding="utf-8") == content:
            return
    except OSError:
        pass
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    if output is not None:
        output.unlink(missing_ok=True)


def parse_summary_dict(output: str) -> dict[str, int] | None:
    """The last ``{'PASS': ..}`` dict a strict-lane piece printed, or None."""
    found = None
    for line in output.splitlines():
        if line.startswith(PIECE_DONE):
            try:
                value = ast.literal_eval(line.strip())
            except (ValueError, SyntaxError):
                continue
            if isinstance(value, dict):
                found = {str(k): int(v) for k, v in value.items()}
    return found


def parse_conformance_summary(output: str) -> dict[str, int] | None:
    m = None
    for m in re.finditer(r"^(\d+) passed, (\d+) unsupported, (\d+) failed(?:, (\d+) skipped)?", output, re.M):
        pass
    if m is None:
        return None
    return {"passed": int(m[1]), "unsupported": int(m[2]), "failed": int(m[3]), "skipped": int(m[4] or 0)}


def parse_pytest_summary(output: str) -> dict[str, int] | None:
    last = None
    for line in output.splitlines():
        if re.search(r"\d+ (passed|failed|error|skipped)", line) and re.search(r" in [\d.]+s", line):
            last = line
    if last is None:
        return None
    return {kind: int(n) for n, kind in re.findall(r"(\d+) (passed|failed|errors?|skipped|xfailed|xpassed|deselected)", last)}


def add_counts(total: dict[str, int], part: dict[str, int] | None) -> None:
    for k, v in (part or {}).items():
        total[k] = total.get(k, 0) + v


# ---- execution --------------------------------------------------------------------------------

@dataclass
class Unit:
    name: str
    command: list[str]
    output: Path
    marker: str
    cwd: Path = ROOT
    env: dict[str, str] = field(default_factory=dict)

    def done(self) -> bool:
        return is_done(self.output, self.marker)


def _reset_sigint() -> None:
    signal.signal(signal.SIGINT, signal.SIG_DFL)


def run_unit(unit: Unit) -> int:
    unit.output.parent.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, **unit.env}
    with open(unit.output, "w", encoding="utf-8") as out:
        return subprocess.call(unit.command, cwd=unit.cwd, env=env, stdout=out, stderr=subprocess.STDOUT, preexec_fn=_reset_sigint)


def run_units(units: list[Unit], jobs: int, *, force: bool = False, dry_run: bool = False, label: str = "") -> list[Unit]:
    """Run the units that are not finished, ``jobs`` at a time; returns those that are still unfinished afterwards."""
    pending = [u for u in units if force or not u.done()]
    print(f"{label}{len(units) - len(pending)} of {len(units)} units already done, {len(pending)} to run", flush=True)
    if dry_run:
        for u in pending:
            print("  would run", u.name)
        return pending
    if pending:
        def one(u: Unit) -> None:
            rc = run_unit(u)
            print(f"  {u.name}: {'done' if u.done() else 'UNFINISHED'} (exit {rc})", flush=True)
        with ThreadPoolExecutor(max(1, jobs)) as pool:
            list(pool.map(one, pending))
    return [u for u in units if not u.done()]


def _env_cache(out: Path) -> dict[str, str]:
    return {} if os.environ.get("PYJVM_BUILD_CACHE") else {"PYJVM_BUILD_CACHE": str(out / "build")}


def _pythonpath() -> dict[str, str]:
    prev = os.environ.get("PYTHONPATH")
    return {"PYTHONPATH": str(ROOT) + (os.pathsep + prev if prev else "")}


def _tail(path: Path, n: int = 3) -> str:
    try:
        return " | ".join(path.read_text(encoding="utf-8", errors="replace").strip().splitlines()[-n:])[:300]
    except OSError:
        return "(no output)"


# ---- pytest per file, then methodsize and the CPython probe -----------------------------------

def pytest_units(ns: argparse.Namespace) -> list[Unit]:
    out = ns.out
    units: list[Unit] = []
    files = sorted(Path(ns.tests).glob(ns.pattern)) if not ns.files else [Path(f) for f in ns.files]
    for f in files:
        if f.stem in set(ns.skip) or f.name in set(ns.skip):
            continue
        units.append(Unit(f"pytest:{f.stem}", [ns.python, "-m", "pytest", "-q", str(f)], out / f"pyt_{f.stem}.out", PYTEST_DONE,
                          env=_env_cache(out)))
    if "methodsize" in ns.then:
        units.append(Unit("methodsize", [ns.python, "-m", "pyjvm315.methodsize", "--runtime"], out / "methodsize.out", METHODSIZE_DONE,
                          env=_pythonpath()))
    if "probe" in ns.then:
        if not ns.cpython_root:
            raise SystemExit("--then probe needs --cpython-root")
        units.append(Unit("probe", [ns.probe_python, "-m", "pyjvm315.regression_probe", "--manifest", "tools/cpython_probe_3000_manifest.txt",
                                    "--cpython-root", ns.cpython_root, "--baseline", "tools/cpython_probe_3000_baseline.json",
                                    "--expect-cases", "3000", "--min-compiles", str(ns.min_compiles), "--fail-on-error"],
                          out / "probe.out", PROBE_DONE, env=_pythonpath()))
    return units


def cmd_pytest(ns: argparse.Namespace) -> int:
    units = pytest_units(ns)
    left = run_units(units, ns.jobs, force=ns.force, dry_run=ns.dry_run, label="pytest: ")
    if ns.dry_run:
        return 0
    total: dict[str, int] = {}
    bad: list[str] = []
    for u in units:
        if not u.done():
            continue
        if u.name.startswith("pytest:"):
            counts = parse_pytest_summary(u.output.read_text(encoding="utf-8", errors="replace")) or {}
            add_counts(total, counts)
            if counts.get("failed") or counts.get("error") or counts.get("errors"):
                bad.append(u.name)
        elif u.name == "methodsize" and not re.search(r", 0 over ", u.output.read_text(encoding="utf-8", errors="replace")):
            bad.append(u.name)
    print("pytest totals:", total)
    for u in units:
        if u.name in ("methodsize", "probe"):
            print(f"{u.name}: {_tail(u.output)}")
    for name in bad:
        print("NOT PASSING:", name)
    for u in left:
        print("UNFINISHED:", u.name)
    return 1 if bad or left else 0


# ---- conformance, groups of N files -----------------------------------------------------------

def conformance_units(ns: argparse.Namespace) -> tuple[Unit | None, list[Unit]]:
    out, tests = ns.out, Path(ns.tests)
    files = sorted(tests.glob("*.py"))
    env = {**_pythonpath(), **_env_cache(out)}
    if ns.class_cache:
        env["PYJVM_CACHE"] = str(ns.class_cache)
    base = [ns.python, "-m", "pyjvm315.conformance"]
    tail = ["--python", ns.python, "--selfhosted"]

    def link_dir(name: str, members: list[Path]) -> Path:
        d = out / "dirs" / name
        sync_input(out / "dirs" / f"{name}.list", "\n".join(str(m.resolve()) for m in members) + "\n", out / f"{name}.out")
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
        for m in members:
            (d / m.name).symlink_to(m.resolve())
        return d

    warm = None
    if files and not ns.no_warm:
        d = link_dir("warm", files[:1])
        warm = Unit("warm", base + [str(d)] + tail, out / "warm.out", GROUP_DONE, env=env)
    units = []
    for i, group in enumerate(chunks(files, ns.group_size)):
        name = f"g_{i:03d}"
        units.append(Unit(name, base + [str(link_dir(name, group))] + tail, out / f"{name}.out", GROUP_DONE, env=env))
    return warm, units


def cmd_conformance(ns: argparse.Namespace) -> int:
    warm, units = conformance_units(ns)
    if warm is not None and (ns.force or not warm.done()):
        print("warming the caches with a single case", flush=True)
        if not ns.dry_run:
            run_unit(warm)
            print(f"  warm: {'done' if warm.done() else 'UNFINISHED'}", flush=True)
    left = run_units(units, ns.jobs, force=ns.force, dry_run=ns.dry_run, label="conformance: ")
    if ns.dry_run:
        return 0
    total: dict[str, int] = {}
    failing: list[str] = []
    for u in units:
        if u.done():
            counts = parse_conformance_summary(u.output.read_text(encoding="utf-8", errors="replace"))
            add_counts(total, counts)
            if counts is None or counts["failed"]:
                failing.append(u.name)
    print("conformance totals:", total)
    for name in failing:
        print("FAILING GROUP:", name, _tail(next(u for u in units if u.name == name).output))
    for u in left:
        print("UNFINISHED:", u.name)
    return 1 if failing or left else 0


# ---- strict lane, pieces of a manifest --------------------------------------------------------

def strict_units(ns: argparse.Namespace) -> list[Unit]:
    out = ns.out
    pieces = split_manifest(Path(ns.manifest).read_text(encoding="utf-8"), ns.piece_size)
    env = {**_pythonpath(), **_env_cache(out)}
    units = []
    for i, lines in enumerate(pieces):
        name = f"q{i:03d}"
        path, output = out / "pieces" / f"{name}.txt", out / f"{name}.out"
        sync_input(path, "\n".join(lines) + "\n", output)
        units.append(Unit(name, [ns.python, "-m", "pyjvm315.cpython_execution", "--cpython-root", str(ns.cpython_root),
                                 "--manifest", str(path), "--target", "25", "--selfhosted"], output, PIECE_DONE, env=env))
    return units


def cmd_strict(ns: argparse.Namespace) -> int:
    units = strict_units(ns)
    left = run_units(units, ns.jobs, force=ns.force, dry_run=ns.dry_run, label="strict: ")
    if ns.dry_run:
        return 0
    total: dict[str, int] = {}
    problems: list[str] = []
    for u in units:
        if not u.done():
            continue
        text = u.output.read_text(encoding="utf-8", errors="replace")
        counts = parse_summary_dict(text)
        add_counts(total, counts)
        problems += [f"{u.name}: {line}" for line in text.splitlines() if re.match(r"(FAIL|ERROR|UNSUPPORTED)\b", line)]
    print("strict totals:", total)
    for p in problems[:50]:
        print(" ", p[:240])
    for u in left:
        print("UNFINISHED:", u.name)
    (ns.out / "summary.json").write_text(json.dumps({"totals": total, "problems": problems, "unfinished": [u.name for u in left]}, indent=2) + "\n")
    return 1 if problems or left else 0


# ---- command line -----------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--out", type=Path, required=True, help="directory for unit inputs and outputs (reuse it to resume)")
        p.add_argument("--jobs", type=int, default=1, help="units run in parallel")
        p.add_argument("--force", action="store_true", help="rerun units even if their output is complete")
        p.add_argument("--dry-run", action="store_true", help="list the units that would run")

    p = sub.add_parser("pytest", help="pytest one test file at a time, then methodsize and the CPython probe")
    common(p)
    p.add_argument("--python", default="python3")
    p.add_argument("--tests", default=str(ROOT / "tests"))
    p.add_argument("--pattern", default="test_*.py")
    p.add_argument("--files", nargs="*", help="explicit test files (instead of --tests/--pattern)")
    p.add_argument("--skip", nargs="*", default=[], help="test files (stem or name) to leave out, e.g. test_threads_stress")
    p.add_argument("--then", nargs="*", default=[], choices=["methodsize", "probe"])
    p.add_argument("--cpython-root", help="CPython Lib checkout for the probe")
    p.add_argument("--probe-python", default="python3.15")
    p.add_argument("--min-compiles", type=int, default=2918)
    p.set_defaults(fn=cmd_pytest)

    p = sub.add_parser("conformance", help="tests/conformance/*.py in groups, run with --selfhosted")
    common(p)
    p.add_argument("--python", default="python3.15")
    p.add_argument("--tests", default=str(ROOT / "tests" / "conformance"))
    p.add_argument("--group-size", type=int, default=4)
    p.add_argument("--class-cache", type=Path, help="PYJVM_CACHE for every job (default: the usual locations)")
    p.add_argument("--no-warm", action="store_true", help="skip the single-case cache warm-up")
    p.set_defaults(fn=cmd_conformance, jobs=2)

    p = sub.add_parser("strict", help="the strict CPython execution lane, manifest split into pieces")
    common(p)
    p.add_argument("--python", default="python3.15")
    p.add_argument("--cpython-root", type=Path, required=True)
    p.add_argument("--manifest", type=Path, default=ROOT / "tools" / "cpython_execution_manifest.txt")
    p.add_argument("--piece-size", type=int, default=25, help="case lines per piece")
    p.set_defaults(fn=cmd_strict, jobs=2)
    return ap


def main(argv: list[str] | None = None) -> int:
    ns = build_parser().parse_args(argv)
    ns.out = ns.out.resolve()
    ns.out.mkdir(parents=True, exist_ok=True)
    return ns.fn(ns)


if __name__ == "__main__":
    raise SystemExit(main())
