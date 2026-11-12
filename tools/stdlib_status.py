# SPDX-License-Identifier: GPL-3.0-or-later
"""Stdlib jar build and per-module manifest (roadmap P3-04).

For one language lane (3.13 or 3.15) this tool takes a CPython ``Lib`` checkout and works in resumable stages, each
recording one JSON line per module in ``<work>/<lane>/records.jsonl`` (appended and flushed as results arrive, so a
killed run resumes where it stopped and a re-run only does what is missing):

    compile   every module is compiled by the host compiler in its own subprocess (with a timeout); the class bytes are kept
    jar       the modules that compiled go into ``pyjvm-stdlib-<lane>.jar`` pinned to the JVM compiler/runtime fingerprint
    probe     every module is imported on the JVM *from that jar* in its own process (with a timeout) and compared with the
              reference interpreter by ``tools/stdlib_probe.py`` (public names plus a short behavioural check)
    manifest  merges the records of all lanes into ``tools/stdlib_manifest.json`` (checked in)
    report    renders ``STDLIB_STATUS.md`` from the manifest

Typical use (the lane's own interpreter runs the tool, so the compiler sees the right language version)::

    python3    tools/stdlib_status.py all --lane 3.13 [--lib /usr/local/lib/python3.13]
    python3.15 tools/stdlib_status.py all --lane 3.15 --lib /path/to/cpython/Lib
    python3    tools/stdlib_status.py manifest && python3 tools/stdlib_status.py report

Each stage takes ``--limit N`` (do at most N missing modules, then stop) and ``-j JOBS``. Running the jar::

    PYJVM_STDLIB=<Lib> PYJVM_STDLIB_JAR=<work>/<lane>/pyjvm-stdlib-<lane>.jar java -cp ... Main

A jar built by another compiler/runtime build or for another language version is reported on stderr and ignored.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = Path(__file__).resolve().parent
MANIFEST_PATH = TOOLS / "stdlib_manifest.json"
REPORT_PATH = ROOT / "STDLIB_STATUS.md"
LANES = ("3.13", "3.15")

# Directories that are not part of the importable library: test suites and GUI applications (tagged "excluded" in the manifest).
EXCLUDED_DIRS = {
    "test": "CPython's own regression suite",
    "tests": "test suite of a package",
    "idle_test": "test suite of a package",
    "idlelib": "IDLE (needs tkinter)",
    "turtledemo": "turtle demo programs (need tkinter)",
    "__phello__": "frozen-module test package",
    "site-packages": "not part of the library",
    "__pycache__": "not source",
    "_bundled": "wheel files",
}
PREFIX_STATUSES = ("smoke", "imports", "compiles", "unsupported", "excluded")


# ---- module enumeration ------------------------------------------------------------------------------------------

def enumerate_modules(lib: Path) -> tuple[dict[str, dict], dict[str, str]]:
    """({module name: {"path": relative source, "pkg": bool}}, {excluded path prefix: reason}) for a Lib checkout."""
    mods: dict[str, dict] = {}
    excluded: dict[str, str] = {}
    for path in sorted(lib.rglob("*.py")):
        rel = path.relative_to(lib)
        parts = list(rel.with_suffix("").parts)
        bad = next((p for p in rel.parts[:-1] if p in EXCLUDED_DIRS), None)
        if bad is not None:
            excluded[bad] = EXCLUDED_DIRS[bad]
            continue
        is_pkg = parts[-1] == "__init__"
        if is_pkg:
            parts.pop()
        if not parts or not all(p.isidentifier() for p in parts):
            continue
        mods[".".join(parts)] = {"path": rel.as_posix(), "pkg": is_pkg}
    return mods, excluded


def source_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:12]


# ---- resumable JSONL store ---------------------------------------------------------------------------------------

def load_records(path: Path) -> dict[tuple[str, str], dict]:
    """Latest record per (module, stage). A truncated last line (killed writer) is ignored."""
    out: dict[tuple[str, str], dict] = {}
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            rec = json.loads(line)
            out[(rec["module"], rec["stage"])] = rec
        except (ValueError, KeyError, TypeError):
            continue
    return out


def append_record(path: Path, rec: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = (json.dumps(rec, sort_keys=True) + "\n").encode("utf-8")
    if path.exists() and path.stat().st_size:
        with open(path, "rb") as f:
            f.seek(-1, os.SEEK_END)
            if f.read(1) != b"\n":      # a writer killed mid-line left a partial record: end it so ours starts a fresh line
                data = b"\n" + data
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o644)
    try:
        # a lone write(2) with O_APPEND: lines from concurrent writers never interleave
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)


def pending(names: list[str], records: dict[tuple[str, str], dict], stage: str, key: str, srcs: dict[str, str] | None = None,
            redo: bool = False) -> list[str]:
    """Modules with no usable record for ``stage``: none yet, or one made with another build key / another source."""
    todo = []
    for n in names:
        r = records.get((n, stage))
        if redo or r is None or r.get("key") != key or (srcs is not None and r.get("src") != srcs.get(n)):
            todo.append(n)
    return todo


# ---- failure classification --------------------------------------------------------------------------------------

def classify_import_failure(text: str, known_modules: frozenset[str] | set[str] = frozenset(), own: str = "") -> str:
    """'category: detail' for an ``IMPORT-FAIL <Type: message>`` line produced on the JVM."""
    import re
    m = re.match(r"(?:ModuleNotFoundError|ImportError): No module named '([\w.]+)'", text)
    if m:
        missing = m.group(1)
        if missing == own or own.startswith(missing + "."):
            return f"runtime-error: {text[:140]}"
        if missing in known_modules:
            return f"missing-dependency: {missing}"
        return f"missing-native: {missing}"
    m = re.match(r"ImportError: cannot import name '(\w+)' from '([\w.]+)'", text)
    if m:
        return f"missing-native: {m.group(2)}.{m.group(1)}"
    if text.startswith("timeout"):
        return "timeout: import did not finish"
    return f"runtime-error: {text[:140]}"


def classify_compile_failure(kind: str, msg: str) -> str:
    msg = " ".join(msg.split())[:160]
    if kind == "FrameError" and "1..65535" in msg:
        return "method-too-large: a function or module body exceeds the JVM 64KB method limit"
    if kind == "CompileError":
        return f"compiler-gap: {msg}"
    if kind == "SyntaxError":
        return f"syntax-error: {msg}"
    if kind == "RecursionError":
        return "compiler-gap: source too deeply nested for the compiler"
    return f"compiler-crash: {kind}: {msg}"


# ---- deriving the manifest ---------------------------------------------------------------------------------------

def derive(compile_rec: dict | None, probe_rec: dict | None) -> tuple[str, str]:
    """(status, reason) for one module in one lane; status is one of PREFIX_STATUSES minus excluded."""
    if compile_rec is None:
        return "unsupported", "not-built: compile stage not run"
    if compile_rec["status"] != "ok":
        return "unsupported", compile_rec.get("reason", "compile failed")
    if probe_rec is None:
        return "compiles", "not-probed: import stage not run"
    st = probe_rec["status"]
    if st == "smoke":
        return "smoke", ""
    if st == "imports":
        return "imports", probe_rec.get("reason", "")
    return "compiles", probe_rec.get("reason", "import failed")


def build_manifest(lane_data: dict[str, dict]) -> dict:
    """``lane_data[lane] = {"python": str, "modules": {name: {...}}, "excluded": {...}, "records": {(m, stage): rec}}``."""
    names: set[str] = set()
    for d in lane_data.values():
        names |= set(d["modules"])
    modules: dict[str, dict] = {}
    for name in sorted(names):
        entry: dict[str, object] = {}
        for lane, d in lane_data.items():
            if name not in d["modules"]:
                continue
            status, reason = derive(d["records"].get((name, "compile")), d["records"].get((name, "probe")))
            entry[lane] = status if not reason else [status, reason]
        modules[name] = entry
    return {
        "schema": 1,
        "lanes": {lane: {"python": d["python"], "library": d.get("library", ""), "modules": len(d["modules"])} for lane, d in lane_data.items()},
        "excluded": {p: r for p, r in sorted({k: v for d in lane_data.values() for k, v in d["excluded"].items()}.items())},
        "modules": modules,
    }


def entry_status(value) -> tuple[str, str]:
    return (value, "") if isinstance(value, str) else (value[0], value[1])


def dump_manifest(manifest: dict) -> str:
    """Stable, diff-friendly JSON: one line per module."""
    head = {k: v for k, v in manifest.items() if k != "modules"}
    lines = ["{"]
    for k, v in head.items():
        lines.append(f"  {json.dumps(k)}: {json.dumps(v, sort_keys=True)},")
    lines.append('  "modules": {')
    items = list(manifest["modules"].items())
    for i, (name, entry) in enumerate(items):
        lines.append(f"    {json.dumps(name)}: {json.dumps(entry, sort_keys=True)}{',' if i + 1 < len(items) else ''}")
    lines.append("  }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def blocker_key(reason: str) -> str:
    """Group key for the blockers table: the category plus the thing that is missing, not the full message."""
    cat, _, detail = reason.partition(": ")
    if cat in ("missing-native", "missing-dependency"):
        return f"{cat}: {detail}"
    if cat == "smoke-mismatch":
        return cat
    return f"{cat}: {detail[:90]}" if detail else cat


def summarize(manifest: dict) -> dict:
    out: dict[str, dict] = {}
    for lane in manifest["lanes"]:
        counts: Counter[str] = Counter()
        blockers: dict[str, list[str]] = defaultdict(list)
        for name, entry in manifest["modules"].items():
            if lane not in entry:
                continue
            status, reason = entry_status(entry[lane])
            counts[status] += 1
            if reason and status != "smoke":
                blockers[blocker_key(reason)].append(name)
        out[lane] = {"counts": counts, "blockers": blockers}
    return out


def render_report(manifest: dict, top: int = 25) -> str:
    summ = summarize(manifest)
    L = ["# Standard library status", "",
         "Generated from `tools/stdlib_manifest.json` by `python3 tools/stdlib_status.py report`; do not edit by hand.",
         "The manifest records, per module and per language lane, how far the CPython `Lib` source gets on pyJVM", "",
         "- **smoke**: compiles, imports from the stdlib jar on the JVM, and its public names and a short behavioural check match the reference interpreter",
         "- **imports**: imports from the jar, but the names or the behavioural check differ from the reference (reason given)",
         "- **compiles**: compiles to a class file, but the import fails on the JVM (reason given)",
         "- **unsupported**: the compiler rejects it (reason given)",
         "- **excluded**: not part of the build (test suites, IDLE and the like)", ""]
    L += ["## Lanes", "", "| Lane | Reference interpreter | Library | Modules |", "|---|---|---|---|"]
    for lane, info in manifest["lanes"].items():
        L.append(f"| {lane} | {info['python']} | {info.get('library') or '-'} | {info['modules']} |")
    L += ["", "## Module counts by status", "", "| Status | " + " | ".join(manifest["lanes"]) + " |", "|---|" + "---|" * len(manifest["lanes"])]
    for st in PREFIX_STATUSES:
        if st == "excluded":
            continue
        L.append(f"| {st} | " + " | ".join(str(summ[lane]["counts"].get(st, 0)) for lane in manifest["lanes"]) + " |")
    L.append("| **total** | " + " | ".join(f"**{sum(summ[lane]['counts'].values())}**" for lane in manifest["lanes"]) + " |")
    if manifest.get("excluded"):
        L += ["", "Excluded directories: " + ", ".join(f"`{p}` ({r})" for p, r in manifest["excluded"].items()) + "."]
    for lane in manifest["lanes"]:
        L += ["", f"## Top blockers, lane {lane}", ""]
        blockers = sorted(summ[lane]["blockers"].items(), key=lambda kv: (-len(kv[1]), kv[0]))
        if not blockers:
            L.append("None.")
            continue
        L += ["| Modules | Blocker | Examples |", "|---|---|---|"]
        for key, mods in blockers[:top]:
            ex = ", ".join(f"`{m}`" for m in sorted(mods)[:5]) + (", ..." if len(mods) > 5 else "")
            L.append(f"| {len(mods)} | {key.replace('|', '/')} | {ex} |")
        if len(blockers) > top:
            L.append(f"| {sum(len(m) for _, m in blockers[top:])} | {len(blockers) - top} further reasons | |")
    L += ["", "## Modules that are not at `smoke`", ""]
    for lane in manifest["lanes"]:
        rows = [(n, *entry_status(e[lane])) for n, e in manifest["modules"].items() if lane in e and entry_status(e[lane])[0] != "smoke"]
        L += [f"<details><summary>Lane {lane}: {len(rows)} modules</summary>", "", "| Module | Status | Reason |", "|---|---|---|"]
        for n, st, reason in rows:
            L.append(f"| `{n}` | {st} | {reason.replace('|', '/')} |")
        L += ["", "</details>", ""]
    return "\n".join(L).rstrip() + "\n"


# ---- lane / environment ------------------------------------------------------------------------------------------

def lane_python(lane: str) -> str:
    for exe in (f"python{lane}", "python3", sys.executable):
        path = shutil.which(exe)
        if path:
            out = subprocess.run([path, "-c", "import sys;print('%d.%d' % sys.version_info[:2])"], capture_output=True, text=True).stdout.strip()
            if out == lane:
                return path
    raise SystemExit(f"no Python {lane} interpreter found")


def lane_library(py: str) -> Path:
    return Path(subprocess.run([py, "-c", "import sysconfig;print(sysconfig.get_path('stdlib'))"], capture_output=True, text=True,
                               check=True).stdout.strip())


def ensure_lane(lane: str) -> None:
    """Re-run under the lane's own interpreter: the compiler's output depends on the host language version."""
    if "%d.%d" % sys.version_info[:2] != lane:
        py = lane_python(lane)
        os.execv(py, [py, *sys.argv])


class Lane:
    def __init__(self, lane: str, work: Path, lib: Path | None, label: str = "", only: list[str] | None = None):
        self.lane = lane
        self.dir = work / lane
        self.dir.mkdir(parents=True, exist_ok=True)
        self.py = lane_python(lane)
        self.lib = (lib or lane_library(self.py)).resolve()
        self.records_path = self.dir / "records.jsonl"
        self.classes = self.dir / "classes"
        self.jar = self.dir / f"pyjvm-stdlib-{lane}.jar"
        self.modules, self.excluded = enumerate_modules(self.lib)
        if only:
            self.modules = {n: m for n, m in self.modules.items() if n in only}
        meta = {"lane": lane, "library": str(self.lib), "label": label or "the interpreter's own Lib", "python": subprocess.run([self.py, "-VV"], capture_output=True, text=True).stdout.strip().splitlines()[0]}
        (self.dir / "lane.json").write_text(json.dumps({**meta, "modules": self.modules, "excluded": self.excluded}, sort_keys=True))
        self.meta = meta

    def key(self) -> str:
        sys.path.insert(0, str(ROOT))
        from pyjvm315.selfhost import build_key
        return build_key()

    def compile_key(self) -> str:
        """The class bytes depend on the compiler sources and the host language version only (not on the runtime)."""
        h = hashlib.sha256(("pyjvm-stdlib-compile-1 " + self.lane).encode())
        for path in sorted((ROOT / "pyjvm315").glob("*.py")):
            h.update(path.name.encode() + b"\0" + hashlib.sha256(path.read_bytes()).digest())
        return h.hexdigest()[:16]

    def records(self) -> dict[tuple[str, str], dict]:
        return load_records(self.records_path)


# ---- stage: compile ----------------------------------------------------------------------------------------------

COMPILE_BOOT = r"""
import sys, json, threading, time
root, lib, rel, out = sys.argv[1:5]
sys.path.insert(0, root)
sys.setrecursionlimit(12000)
threading.stack_size(512 * 1024 * 1024)
result = {}
def work():
    t0 = time.time()
    try:
        from pyjvm315.exec_compile import compile_for_exec
        from pyjvm315.compiler import CompileError
        from pyjvm315.stackmap import FrameError
        text = open(lib + "/" + rel, encoding="utf-8").read()
        try:
            data = compile_for_exec(text, rel, "exec", 0, -1)
        except CompileError as e:
            result.update(status="fail", kind="CompileError", msg=str(e))
        except FrameError as e:
            result.update(status="fail", kind="FrameError", msg=str(e))
        except SyntaxError as e:
            result.update(status="fail", kind="SyntaxError", msg="%s (line %s)" % (e.msg, e.lineno))
        except RecursionError as e:
            result.update(status="fail", kind="RecursionError", msg="")
        else:
            open(out, "wb").write(data)
            result.update(status="ok", size=len(data))
    except BaseException as e:
        result.update(status="fail", kind=type(e).__name__, msg=str(e))
    result["ms"] = int((time.time() - t0) * 1000)
t = threading.Thread(target=work)
t.start(); t.join()
print(json.dumps(result))
"""


def compile_one(ln: Lane, name: str, timeout: float) -> dict:
    info = ln.modules[name]
    out = ln.classes / (name + ".class")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.unlink(missing_ok=True)
    rec = {"module": name, "stage": "compile", "key": ln.ckey, "src": ln.srcs[name]}
    t0 = time.time()
    try:
        proc = subprocess.run([ln.py, "-I", "-c", COMPILE_BOOT, str(ROOT), str(ln.lib), info["path"], str(out)], capture_output=True,
                              text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {**rec, "status": "fail", "reason": f"timeout: compile exceeded {int(timeout)}s", "ms": int((time.time() - t0) * 1000)}
    try:
        res = json.loads(proc.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        tail = (proc.stderr.strip().splitlines() or ["no output"])[-1][:140]
        return {**rec, "status": "fail", "reason": f"compiler-crash: exit {proc.returncode}: {tail}", "ms": int((time.time() - t0) * 1000)}
    if res["status"] == "ok":
        return {**rec, "status": "ok", "size": res["size"], "ms": res["ms"]}
    return {**rec, "status": "fail", "reason": classify_compile_failure(res["kind"], res["msg"]), "ms": res["ms"]}


def run_batch(todo: list[str], fn, ln: Lane, jobs: int, limit: int | None, label: str) -> int:
    if limit is not None:
        todo = todo[:limit]
    print(f"[{ln.lane}] {label}: {len(todo)} to do", flush=True)
    done = 0
    with cf.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(fn, n): n for n in todo}
        for fut in cf.as_completed(futures):
            rec = fut.result()
            append_record(ln.records_path, rec)
            done += 1
            if done % 25 == 0 or done == len(todo):
                print(f"[{ln.lane}] {label}: {done}/{len(todo)}", flush=True)
    return done


def stage_compile(ln: Lane, a) -> None:
    ln.ckey = ln.compile_key()
    ln.srcs = {n: source_hash(ln.lib / m["path"]) for n, m in ln.modules.items()}
    todo = pending(sorted(ln.modules), ln.records(), "compile", ln.ckey, ln.srcs, a.redo)
    run_batch(todo, lambda n: compile_one(ln, n, a.timeout), ln, a.jobs, a.limit, "compile")


# ---- stage: jar --------------------------------------------------------------------------------------------------

def stage_jar(ln: Lane, a) -> Path:
    from pyjvm315.jarbuild import write_jar
    kit = Kit(ln, a)
    recs = ln.records()
    key = ln.compile_key()
    entries, skipped = [], {}
    for name in sorted(ln.modules):
        r = recs.get((name, "compile"))
        if r is None or r.get("key") != key:
            skipped[name] = "not compiled by this build"
        elif r["status"] != "ok":
            skipped[name] = r.get("reason", "compile failed")
        else:
            info = ln.modules[name]
            entries.append((name, info["pkg"], info["path"], (ln.classes / (name + ".class")).read_bytes()))
    if skipped.keys() & {n for n in ln.modules if (n, "compile") not in recs}:
        print(f"[{ln.lane}] warning: the compile stage has not covered every module", flush=True)
    fp = kit.fingerprint()
    write_jar(ln.jar, entries, skipped, fingerprint=fp, lang=ln.lane)
    print(f"[{ln.lane}] {ln.jar}: {len(entries)} modules, {len(skipped)} left out, fingerprint {fp[:12]}", flush=True)
    return ln.jar


def jar_fingerprint(jar: Path) -> str | None:
    with zipfile.ZipFile(jar) as z:
        for line in z.read("META-INF/pyjvm-modules.txt").decode().splitlines():
            if line.startswith("fingerprint\t"):
                return line.split("\t")[1]
    return None


# ---- stage: probe ------------------------------------------------------------------------------------------------

class Kit:
    """The JVM side: runtime, self-hosted compiler and the compiled probe program (built once, keyed by the sources)."""

    def __init__(self, ln: Lane, a):
        from pyjvm315.driver import compile_file
        from pyjvm315.selfhost import cached_build
        self.ln = ln
        cache = Path(os.environ.get("PYJVM_BUILD_CACHE") or (ln.dir.parent / "build-cache"))
        self.runtime, self.stage1 = cached_build(cache, True)
        self.prog = ln.dir / f"probe-{self.runtime.name}"
        if not (self.prog / "ConformanceMain.class").exists():
            tmp = ln.dir / f".probe-{os.getpid()}.tmp"
            shutil.rmtree(tmp, ignore_errors=True)
            tmp.mkdir(parents=True)
            compile_file(TOOLS / "stdlib_probe.py", tmp, "ConformanceMain")
            shutil.copytree(self.runtime / "pyjvm315", tmp / "pyjvm315")
            shutil.rmtree(self.prog, ignore_errors=True)
            os.rename(tmp, self.prog)
        self.cache = ln.dir / "jvm-cache"
        self._fp: str | None = None

    def env(self, module: str, jar: Path | None) -> dict[str, str]:
        env = {k: v for k, v in os.environ.items() if not k.startswith("PYJVM_") and k not in ("PYTHONPATH", "PYTHONDONTWRITEBYTECODE")}
        env.update(PYJVM_STDLIB=str(self.ln.lib), PYJVM_LANG_VERSION=self.ln.lane, PYJVM_CACHE=str(self.cache), PYJVM_PROBE_MODULE=module)
        if jar is not None:
            env["PYJVM_STDLIB_JAR"] = str(jar)
        return env

    def run(self, module: str, jar: Path | None, timeout: float) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["java", "-Xss64m", "-Dstdout.encoding=UTF-8", "-cp", f"{self.prog}{os.pathsep}{self.stage1}", "ConformanceMain"],
                              capture_output=True, text=True, timeout=timeout, env=self.env(module, jar), cwd=self.ln.dir)

    def fingerprint(self) -> str:
        if self._fp is None:
            out = self.run("--fingerprint", None, 600)
            lines = [l for l in out.stdout.splitlines() if l.startswith("FINGERPRINT\t")]
            if not lines:
                raise SystemExit("cannot obtain the JVM fingerprint: " + out.stderr[-300:])
            self._fp = lines[0].split("\t")[1]
        return self._fp


def parse_probe(stdout: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in stdout.splitlines():
        marker, _, rest = line.partition("\t")
        if marker in ("IMPORT-FAIL", "ORIGIN", "NAMES", "SMOKE", "FINGERPRINT") and marker not in out:
            out[marker] = rest
    return out


def compare_probe(jvm: dict[str, str], host: dict[str, str]) -> tuple[str, str]:
    """('smoke' | 'imports', reason) for a module that imported on the JVM."""
    problems = []
    if "IMPORT-FAIL" in host:
        return "smoke", ""          # the reference cannot import it either (platform module): nothing to compare
    jn, hn = set(jvm.get("NAMES", "").split()), set(host.get("NAMES", "").split())
    if jn != hn:
        miss, extra = sorted(hn - jn), sorted(jn - hn)
        problems.append("names differ: missing " + (",".join(miss[:6]) + ("..." if len(miss) > 6 else "") if miss else "none")
                        + "; extra " + (",".join(extra[:4]) + ("..." if len(extra) > 4 else "") if extra else "none"))
    if jvm.get("SMOKE") != host.get("SMOKE"):
        problems.append(f"check differs: jvm {jvm.get('SMOKE', '?')[:70]!r} vs reference {host.get('SMOKE', '?')[:70]!r}")
    return ("imports", "smoke-mismatch: " + "; ".join(problems)) if problems else ("smoke", "")


def probe_one(ln: Lane, kit: Kit, name: str, timeout: float, known: frozenset[str]) -> dict:
    rec = {"module": name, "stage": "probe", "key": ln.key_cached}
    t0 = time.time()
    host_env = {"PYJVM_PROBE_MODULE": name, "PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    try:
        h = subprocess.run([ln.py, "-I", str(TOOLS / "stdlib_probe.py")], capture_output=True, text=True, timeout=60, env=host_env, cwd=tempfile.gettempdir())
        host = parse_probe(h.stdout)
    except subprocess.TimeoutExpired:
        host = {"IMPORT-FAIL": "timeout"}
    try:
        proc = kit.run(name, ln.jar, timeout)
    except subprocess.TimeoutExpired:
        return {**rec, "status": "import-fail", "reason": classify_import_failure("timeout", known, name), "ms": int((time.time() - t0) * 1000)}
    jvm = parse_probe(proc.stdout)
    ms = int((time.time() - t0) * 1000)
    if "ORIGIN" not in jvm and "IMPORT-FAIL" not in jvm:
        tail = [l for l in proc.stderr.strip().splitlines() if l.strip()][-1:] or [f"exit {proc.returncode}"]
        return {**rec, "status": "import-fail", "reason": classify_import_failure(f"{tail[0][:200]}", known, name), "ms": ms}
    if "IMPORT-FAIL" in jvm:
        return {**rec, "status": "import-fail", "reason": classify_import_failure(jvm["IMPORT-FAIL"], known, name), "ms": ms}
    if "ignoring module archive" in proc.stderr:
        return {**rec, "status": "import-fail", "reason": "stale-jar: the jar was ignored", "ms": ms}
    status, reason = compare_probe(jvm, host)
    return {**rec, "status": status, "reason": reason, "from_jar": ".jar" in jvm["ORIGIN"], "ms": ms}


def stage_probe(ln: Lane, a) -> None:
    if not ln.jar.exists():
        raise SystemExit(f"{ln.jar} does not exist: run the compile and jar stages first")
    ln.key_cached = ln.key()
    kit = Kit(ln, a)
    if jar_fingerprint(ln.jar) != kit.fingerprint():
        raise SystemExit(f"{ln.jar} is stale (built by another compiler/runtime): rebuild it with the jar stage")
    recs = ln.records()
    ok = sorted(n for n in ln.modules if (n, "compile") in recs and recs[(n, "compile")]["status"] == "ok" and recs[(n, "compile")].get("key") == ln.compile_key())
    todo = pending(ok, recs, "probe", ln.key_cached, None, a.redo)
    known = frozenset(ln.modules)
    run_batch(todo, lambda n: probe_one(ln, kit, n, a.timeout, known), ln, a.jobs, a.limit, "probe")


# ---- manifest / report / status ----------------------------------------------------------------------------------

def stage_manifest(work: Path, lanes: list[str]) -> None:
    data = {}
    for lane in lanes:
        d = work / lane
        if not (d / "lane.json").exists():
            continue
        meta = json.loads((d / "lane.json").read_text())
        recs = load_records(d / "records.jsonl")
        for stage in ("compile", "probe"):      # keep the records of the build that produced most of the stage
            keys = Counter(r.get("key") for (m, st), r in recs.items() if st == stage)
            current = keys.most_common(1)[0][0] if keys else None
            recs = {k: r for k, r in recs.items() if k[1] != stage or r.get("key") == current}
        data[lane] = {"python": meta["python"], "library": meta["label"], "modules": meta["modules"],
                      "excluded": meta["excluded"], "records": recs}
    if not data:
        raise SystemExit(f"no lane data under {work}")
    MANIFEST_PATH.write_text(dump_manifest(build_manifest(data)), encoding="utf-8")
    print(f"wrote {MANIFEST_PATH}")


def stage_report() -> None:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    REPORT_PATH.write_text(render_report(manifest), encoding="utf-8")
    print(f"wrote {REPORT_PATH}")


def stage_status(ln: Lane) -> None:
    recs = ln.records()
    for stage in ("compile", "probe"):
        c = Counter(r["status"] for (m, s), r in recs.items() if s == stage)
        print(f"[{ln.lane}] {stage}: {dict(sorted(c.items()))} of {len(ln.modules)} modules")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("stage", choices=("compile", "jar", "probe", "all", "status", "manifest", "report"))
    ap.add_argument("--lane", choices=LANES)
    ap.add_argument("--lib", type=Path, help="CPython Lib checkout (default: the lane interpreter's own library)")
    ap.add_argument("--work", type=Path, default=Path(os.environ.get("PYJVM_STDLIB_WORK") or ROOT / "build" / "stdlib"))
    ap.add_argument("--only", help="comma-separated module names: restrict every stage to these (for experiments and tests)")
    ap.add_argument("--label", default="", help="description of the Lib checkout recorded in the manifest (e.g. 'CPython 5b28ebd1 Lib')")
    ap.add_argument("-j", "--jobs", type=int, default=3)
    ap.add_argument("--limit", type=int, help="process at most this many missing modules, then stop")
    ap.add_argument("--timeout", type=float, help="seconds per module (default 300 to compile, 240 to import)")
    ap.add_argument("--redo", action="store_true", help="ignore existing records of the stage")
    a = ap.parse_args(argv)
    if a.stage == "manifest":
        stage_manifest(a.work.resolve(), [a.lane] if a.lane else list(LANES))
        return 0
    if a.stage == "report":
        stage_report()
        return 0
    if not a.lane:
        ap.error("--lane is required")
    ensure_lane(a.lane)
    sys.path.insert(0, str(ROOT))
    ln = Lane(a.lane, a.work.resolve(), a.lib, a.label, a.only.split(",") if a.only else None)
    ln.key_cached = ln.ckey = None
    if a.stage == "status":
        stage_status(ln)
        return 0
    stages = ["compile", "jar", "probe"] if a.stage == "all" else [a.stage]
    for st in stages:
        a.timeout = a.timeout or (300 if st == "compile" else 240)
        if st == "compile":
            stage_compile(ln, a)
        elif st == "jar":
            stage_jar(ln, a)
        else:
            stage_probe(ln, a)
        a.timeout = None
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
