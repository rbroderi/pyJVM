# SPDX-License-Identifier: GPL-3.0-or-later
"""P3-02: the persistent compile cache and the jar finder.

Every test runs real JVM processes: a main program imports modules that were never compiled ahead of time, and the
runtime's ``PYJVM_CACHE_VERBOSE`` trace says, per source file, whether its classes came from the cache (``hit``),
had to be compiled (``miss``) or were written (``stored``).
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from pyjvm315.classfile import DEFAULT_TARGET
from pyjvm315.cli import build_runtime
from pyjvm315.driver import compile_file
from pyjvm315.hostver import reference_stdlib, reference_version
from pyjvm315.jarbuild import build_jar
from pyjvm315.selfhost import build_selfhosted


def _lane() -> str:
    for exe in (shutil.which("python3.15"), shutil.which("python3.13"), shutil.which("python3"), sys.executable):
        if exe:
            try:
                if reference_version(exe) >= (3, 13):
                    return exe
            except (subprocess.CalledProcessError, OSError):
                continue
    pytest.skip("no reference Python >= 3.13")


class Kit:
    def __init__(self, runtime: Path, selfhosted: Path, base: Path):
        self.runtime, self.selfhosted, self.base = runtime, selfhosted, base
        self.lane = _lane()
        self.stdlib = os.environ.get("PYJVM_STDLIB") or reference_stdlib(self.lane)
        self.user_cache = base / "user-cache"        # XDG_CACHE_HOME: holds importlib's own (file-less) modules
        self.count = 0
        self.env_for: dict[Path, dict[str, str]] = {}      # per-program default environment

    def program(self, source: str) -> Path:
        """Compile a main program once; returns the class directory."""
        self.count += 1
        out = self.base / f"prog{self.count}"
        src = out.with_suffix(".py")
        src.write_text(textwrap.dedent(source), encoding="utf-8")
        out.mkdir()
        compile_file(src, out, "ConformanceMain", target=DEFAULT_TARGET)
        shutil.copytree(self.runtime / "pyjvm315", out / "pyjvm315")
        return out

    def run(self, program: Path, *, env: dict[str, str] | None = None, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
        version = "%d.%d" % reference_version(self.lane)
        full = {**os.environ, "PYJVM_STDLIB": self.stdlib, "PYJVM_LANG_VERSION": version, "XDG_CACHE_HOME": str(self.user_cache),
                "PYJVM_CACHE_VERBOSE": "1"}
        full.pop("PYJVM_CACHE", None)
        full.pop("PYTHONDONTWRITEBYTECODE", None)
        full.update(self.env_for.get(program, {}))
        full.update(env or {})
        return subprocess.run(["java", "-Xss64m", "-Dstdout.encoding=UTF-8", "-cp", f"{program}{os.pathsep}{self.selfhosted}", "ConformanceMain"],
                              capture_output=True, text=True, cwd=cwd or self.base, env=full, timeout=900)


def _events(proc: subprocess.CompletedProcess[str], needle: str) -> list[str]:
    """Cache trace lines (``hit``/``miss``/``stored``/``stale``) for files whose path contains ``needle``."""
    return [m.group(1) for line in proc.stderr.splitlines() if needle in line and (m := re.match(r"\[pyjvm-cache\] (\w+) ", line))]


@pytest.fixture(scope="module")
def kit():
    with tempfile.TemporaryDirectory(prefix="pyjvm-cache-test-") as td:
        shared = os.environ.get("PYJVM_CONFORMANCE_CACHE")
        if shared and (Path(shared) / ".built").exists() and (Path(shared) / "selfhosted" / "stage1").exists():
            runtime, selfhosted = Path(shared), Path(shared) / "selfhosted" / "stage1"
        else:
            runtime = Path(td) / "runtime"
            build_runtime(runtime)
            selfhosted = build_selfhosted(Path(td) / "selfhosted")
        base = Path(td) / "work"
        base.mkdir()
        yield Kit(runtime, selfhosted, base)


def _libs(tmp_path: Path, files: dict[str, str]) -> Path:
    root = tmp_path / "libs"
    for rel, text in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")
    return root


MAIN = """
    import os, sys
    sys.path.insert(0, os.environ["ROOT"])
    import cmod
    print(cmod.answer(), cmod.__file__.rsplit("/", 1)[-1])
"""


def _main_for(kit: Kit, root: Path) -> Path:
    prog = kit.program(MAIN)
    kit.env_for[prog] = {"ROOT": str(root)}
    return prog


def test_second_process_loads_cached_classes(kit, tmp_path):
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 6 * 7\n"})
    prog = _main_for(kit, root)
    first = kit.run(prog)
    assert first.returncode == 0, first.stderr
    assert first.stdout == "42 cmod.py\n"
    assert _events(first, "cmod.py") == ["miss", "stored"]
    entries = list((root / "__pycache__").glob("cmod.py.pyjvm-*.cls"))
    assert len(entries) == 1
    assert not list((root / "__pycache__").glob(".pyjvm-*.tmp"))

    second = kit.run(prog)
    assert second.returncode == 0, second.stderr
    assert second.stdout == first.stdout
    assert _events(second, "cmod.py") == ["hit"]           # no compile, no write
    assert _events(second, "importlib") == ["hit", "hit"]    # importlib itself comes from the user cache too
    assert list((root / "__pycache__").glob("*")) == entries


def test_source_edit_invalidates(kit, tmp_path):
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 1\n"})
    prog = _main_for(kit, root)
    assert kit.run(prog).stdout == "1 cmod.py\n"
    assert _events(kit.run(prog), "cmod.py") == ["hit"]
    # same size, same second: only the content hash can tell the difference
    (root / "cmod.py").write_text("def answer():\n    return 2\n", encoding="utf-8")
    edited = kit.run(prog)
    assert edited.stdout == "2 cmod.py\n"
    assert _events(edited, "cmod.py") == ["stale", "miss", "stored"]
    again = kit.run(prog)
    assert again.stdout == "2 cmod.py\n" and _events(again, "cmod.py") == ["hit"]
    assert len(list((root / "__pycache__").glob("cmod.py.pyjvm-*.cls"))) == 1


def _entries(root: Path, name: str = "cmod.py") -> list[Path]:
    return sorted((root / "__pycache__").glob(f"{name}.pyjvm-*.cls"))


def _fp(entry: Path) -> str:
    return re.search(r"pyjvm-([0-9a-f]{8})-", entry.name).group(1)


def test_compiler_change_invalidates(kit, tmp_path):
    """Another compiler/runtime build has another fingerprint: entries of a foreign fingerprint are never read, but are kept."""
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 5\n"})
    prog = _main_for(kit, root)
    kit.run(prog)
    [entry] = _entries(root)
    renamed = entry.with_name(re.sub(r"pyjvm-[0-9a-f]{8}-", "pyjvm-deadbeef-", entry.name))
    entry.rename(renamed)
    result = kit.run(prog)
    assert result.stdout == "5 cmod.py\n"
    assert _events(result, "cmod.py") == ["miss", "stored"]
    assert len(_entries(root)) == 2
    assert renamed.exists()                       # a worktree with another build may still want it


def _overlay(kit: Kit, prog: Path, name: str, *edits: tuple[str, str]) -> Path:
    """A copy of ``prog`` whose ClassCache class was recompiled from the real source with ``edits`` applied."""
    src = (Path(__file__).resolve().parents[1] / "runtime" / "pyjvm315" / "runtime" / "ClassCache.java").read_text(encoding="utf-8")
    for old, new in edits:
        assert old in src
        src = src.replace(old, new, 1)
    work = kit.base / name
    out = kit.base / f"{name}-src"
    (out / "pyjvm315" / "runtime").mkdir(parents=True)
    java = out / "pyjvm315" / "runtime" / "ClassCache.java"
    java.write_text(src, encoding="utf-8")
    shutil.copytree(prog, work)
    subprocess.run(["javac", "--release", "21", "-nowarn", "-cp", str(prog), "-d", str(work), str(java)], check=True, capture_output=True)
    kit.env_for[work] = dict(kit.env_for.get(prog, {}))
    return work


PRIVATE_EDIT = ("    private static int keep() {", "    private static int bodyOnlyProbe() { return 41 + 1; }\n    private static int keep() {")
SURFACE_EDIT = ("    private static int keep() {", "    static int surfaceProbe() { return 7; }\n    private static int keep() {")


def test_two_fingerprints_coexist(kit, tmp_path):
    """Two builds sharing one __pycache__ (worktrees, parallel runs) do not evict each other's entries."""
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 21 * 2\n"})
    a = _main_for(kit, root)
    b = _overlay(kit, a, "fp-b", PRIVATE_EDIT)
    kit.env_for[b]["ROOT"] = str(root)
    assert _events(kit.run(a), "cmod.py") == ["miss", "stored"]
    assert _events(kit.run(b), "cmod.py") == ["miss", "stored"]
    entries = _entries(root)
    assert len({_fp(p) for p in entries}) == 2 and len(entries) == 2
    for prog in (a, b, a, b):
        r = kit.run(prog)
        assert r.stdout == "42 cmod.py\n" and _events(r, "cmod.py") == ["hit"]
    assert _entries(root) == entries


def test_old_fingerprints_are_bounded_by_recency(kit, tmp_path):
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 4\n"})
    prog = _main_for(kit, root)
    kit.run(prog)
    [real] = _entries(root)
    for i in range(6):                              # six foreign fingerprints, oldest first
        fake = real.with_name(re.sub(r"pyjvm-[0-9a-f]{8}-", f"pyjvm-0000000{i}-", real.name))
        fake.write_bytes(real.read_bytes())
        os.utime(fake, (1_000_000 + i, 1_000_000 + i))
    real.unlink()
    result = kit.run(prog, env={"PYJVM_CACHE_KEEP": "3"})
    assert result.stdout == "4 cmod.py\n" and _events(result, "cmod.py") == ["miss", "stored"] + ["evicted"] * 4
    assert sorted(_fp(p) for p in _entries(root))[:2] == ["00000004", "00000005"] and len(_entries(root)) == 3


def test_concurrent_writers_keep_foreign_entries(kit, tmp_path):
    body = "\n".join(f"def f{i}(x):\n    return x + {i}\n" for i in range(60))
    root = _libs(tmp_path, {"cmod.py": body + "\ndef answer():\n    return f59(1) + f0(1)\n"})
    prog = _main_for(kit, root)
    other = _overlay(kit, prog, "fp-c", PRIVATE_EDIT)
    kit.env_for[other]["ROOT"] = str(root)
    assert kit.run(other).stdout == "61 cmod.py\n"          # the foreign build wrote its entry first
    [foreign] = _entries(root)
    before = foreign.read_bytes()
    with ThreadPoolExecutor(6) as pool:
        results = list(pool.map(lambda i: kit.run(other if i % 3 == 2 else prog), range(6)))
    for r in results:
        assert r.returncode == 0 and r.stdout == "61 cmod.py\n", r.stderr
    cache = root / "__pycache__"
    assert len(_entries(root)) == 2 and foreign.read_bytes() == before
    assert not [p for p in cache.iterdir() if p.suffix == ".tmp"]
    assert _events(kit.run(prog), "cmod.py") == ["hit"] and _events(kit.run(other), "cmod.py") == ["hit"]


def test_api_fingerprint_ignores_body_edits_only(kit, tmp_path):
    """PYJVM_CACHE_FINGERPRINT=api: a runtime edit that leaves the linkable surface alone keeps the cache warm."""
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 6 * 7\n"})
    a = _main_for(kit, root)
    body = _overlay(kit, a, "api-body", PRIVATE_EDIT, ("return v == null ? 4 : Math.max(1, Integer.parseInt(v.trim()));", "return v == null ? 2 + 2 : Math.max(1, Integer.parseInt(v.trim()));"))
    surface = _overlay(kit, a, "api-surface", SURFACE_EDIT)
    for p in (body, surface):
        kit.env_for[p]["ROOT"] = str(root)
    env = {"PYJVM_CACHE_FINGERPRINT": "api"}
    cold = kit.run(a, env=env)
    assert _events(cold, "cmod.py") == ["miss", "stored"]
    warm_body = kit.run(body, env=env)
    assert warm_body.stdout == "42 cmod.py\n" and _events(warm_body, "cmod.py") == ["hit"]
    assert _events(kit.run(body), "cmod.py") == ["miss", "stored"]           # default (full) mode: the edit invalidates
    assert _events(kit.run(surface, env=env), "cmod.py") == ["miss", "stored"]   # a new member is part of the surface


def test_unwritable_pycache_falls_back_to_user_cache(kit, tmp_path):
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 9\n"})
    (root / "__pycache__").write_text("a file, so no directory can be created there")
    prog = _main_for(kit, root)
    first = kit.run(prog)
    assert first.returncode == 0 and first.stdout == "9 cmod.py\n", first.stderr
    assert any(str(kit.user_cache) in line and "cmod.py" in line for line in first.stderr.splitlines() if "stored" in line)
    second = kit.run(prog)
    assert _events(second, "cmod.py") == ["hit"]


def test_explicit_cache_dir_and_off(kit, tmp_path):
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 11\n"})
    prog = _main_for(kit, root)
    central = tmp_path / "central"
    first = kit.run(prog, env={"PYJVM_CACHE": str(central)})
    assert first.stdout == "11 cmod.py\n"
    assert not (root / "__pycache__").exists()
    assert list(central.glob("cmod.py.*.cls"))
    assert _events(kit.run(prog, env={"PYJVM_CACHE": str(central)}), "cmod.py") == ["hit"]
    off = kit.run(prog, env={"PYJVM_CACHE": "off"})
    assert off.stdout == "11 cmod.py\n" and _events(off, "") == []


def test_dont_write_bytecode_stops_writes_but_not_reads(kit, tmp_path):
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 8\n"})
    quiet = kit.program(MAIN.replace("import cmod", "sys.dont_write_bytecode = True\n    import cmod"))
    kit.env_for[quiet] = {"ROOT": str(root)}
    result = kit.run(quiet)
    assert result.stdout == "8 cmod.py\n" and _events(result, "cmod.py") == ["miss"]
    assert not (root / "__pycache__").exists()
    env_only = kit.run(_main_for(kit, root), env={"PYTHONDONTWRITEBYTECODE": "1"})
    assert _events(env_only, "cmod.py") == ["miss"] and not (root / "__pycache__").exists()
    kit.run(_main_for(kit, root))                      # writes
    assert _events(kit.run(quiet), "cmod.py") == ["hit"]


def test_concurrent_processes_do_not_corrupt_the_cache(kit, tmp_path):
    body = "\n".join(f"def f{i}(x):\n    return x + {i}\n" for i in range(60))
    root = _libs(tmp_path, {"cmod.py": body + "\ndef answer():\n    return f59(1) + f0(1)\n"})
    prog = _main_for(kit, root)
    with ThreadPoolExecutor(4) as pool:
        results = list(pool.map(lambda _: kit.run(prog), range(4)))
    for r in results:
        assert r.returncode == 0, r.stderr
        assert r.stdout == "61 cmod.py\n"
    cache = root / "__pycache__"
    assert len(list(cache.glob("cmod.py.pyjvm-*.cls"))) == 1
    assert not [p for p in cache.iterdir() if p.suffix == ".tmp"]
    assert _events(kit.run(prog), "cmod.py") == ["hit"]


def test_corrupt_entry_is_recompiled(kit, tmp_path):
    root = _libs(tmp_path, {"cmod.py": "def answer():\n    return 3\n"})
    prog = _main_for(kit, root)
    kit.run(prog)
    [entry] = (root / "__pycache__").glob("cmod.py.pyjvm-*.cls")
    entry.write_bytes(entry.read_bytes()[:50])             # torn file
    result = kit.run(prog)
    assert result.stdout == "3 cmod.py\n" and _events(result, "cmod.py") == ["stale", "miss", "stored"]


# ---- jar finder --------------------------------------------------------------------------------

JAR_MAIN = """
    import os, sys
    sys.path.insert(0, os.environ["JAR"])
    import jpkg
    from jpkg import sub, leaf
    import jpkg.sub.deep as deep
    print(jpkg.NAME, sub.twice(4), leaf.value(), deep.DEPTH)
    for m in (jpkg, sub, leaf, deep):
        print(m.__name__, m.__spec__.origin.split(".jar/", 1)[-1], type(m.__spec__.loader).__name__, m.__file__ == m.__spec__.origin)
    print([p.rsplit(".jar", 1)[-1] for p in sub.__path__], jpkg.__spec__.submodule_search_locations[0].endswith(".jar/jpkg"))
    import broken
    print(broken.WHERE)
    try:
        import jpkg.missing
    except ImportError as e:
        print(type(e).__name__, e)
"""

def _lang(kit):
    return "%d.%d" % reference_version(kit.lane)


JAR_FILES = {
    "jpkg/__init__.py": "NAME = 'jpkg'\nfrom . import sub\n",
    "jpkg/sub/__init__.py": "def twice(x):\n    return 2 * x\n",
    "jpkg/sub/deep.py": "DEPTH = 3\n",
    "jpkg/leaf.py": "from .sub import twice\n\ndef value():\n    return twice(21)\n",
    "broken.py": "WHERE = 'source'\n",
}


def test_jar_finder_serves_precompiled_modules(kit, tmp_path):
    src = _libs(tmp_path, JAR_FILES)
    jar = tmp_path / "mods.jar"
    # `broken` fails to compile into the jar (not valid Python): the import must fall through to the next sys.path entry
    (src / "broken.py").write_text("WHERE = 'jar'\ndef (:\n", encoding="utf-8")
    included, skipped = build_jar(jar, [src], lang=_lang(kit))
    assert "broken" in skipped and sorted(included) == ["jpkg", "jpkg.leaf", "jpkg.sub", "jpkg.sub.deep"]
    shutil.rmtree(src / "jpkg")                    # prove the package comes from the jar, not from the directory
    (src / "broken.py").write_text("WHERE = 'source'\n", encoding="utf-8")
    prog = kit.program(JAR_MAIN)
    result = kit.run(prog, env={"JAR": str(jar), "PYTHONPATH": str(src)}, cwd=tmp_path)
    assert result.returncode == 0, result.stderr
    lines = result.stdout.splitlines()
    assert lines[0] == "jpkg 8 42 3"
    assert lines[1] == "jpkg jpkg/__init__.py JarLoader True"
    assert lines[2] == "jpkg.sub jpkg/sub/__init__.py JarLoader True"
    assert lines[3] == "jpkg.leaf jpkg/leaf.py JarLoader True"
    assert lines[4] == "jpkg.sub.deep jpkg/sub/deep.py JarLoader True"
    assert lines[5] == "['/jpkg/sub'] True"
    assert lines[6] == "source"
    assert lines[7].startswith("ModuleNotFoundError No module named 'jpkg.missing'")
    assert _events(result, "mods.jar") == []              # nothing from the jar was compiled or cached
    assert _events(result, str(src)) == ["miss", "stored"]  # only the source module that fell through


def test_jar_with_foreign_fingerprint_is_ignored(kit, tmp_path):
    src = _libs(tmp_path, {"cmod.py": "def answer():\n    return 'jar'\n"})
    stale = tmp_path / "stale.jar"
    build_jar(stale, [src], fingerprint="0" * 64, lang=_lang(kit))
    (src / "cmod.py").write_text("def answer():\n    return 'source'\n", encoding="utf-8")
    prog = kit.program(MAIN)
    result = kit.run(prog, env={"ROOT": str(stale), "PYTHONPATH": str(src)}, cwd=tmp_path)
    assert result.returncode == 0, result.stderr
    assert result.stdout == "source cmod.py\n"


def test_jarbuild_manifest(tmp_path):
    src = _libs(tmp_path, {"p/__init__.py": "", "p/m.py": "X = 1\n", "bad.py": "def (:\n"})
    jar = tmp_path / "out.jar"
    included, skipped = build_jar(jar, [src], fingerprint="ab" * 32)
    assert included == ["p", "p.m"] and list(skipped) == ["bad"]
    import zipfile
    with zipfile.ZipFile(jar) as zf:
        manifest = zf.read("META-INF/pyjvm-modules.txt").decode().splitlines()
        assert zf.read("p.m.class")[:4] == b"\xca\xfe\xba\xbe"
    assert manifest[0] == "pyjvm-jar\t1" and f"fingerprint\t{'ab' * 32}" in manifest
    assert "module\tp\tpkg\tp.class\tp/__init__.py" in manifest and "module\tp.m\tmod\tp.m.class\tp/m.py" in manifest
    assert any(line.startswith("# skipped bad:") for line in manifest)
    assert not list(tmp_path.glob(".pyjvm-*.tmp"))
