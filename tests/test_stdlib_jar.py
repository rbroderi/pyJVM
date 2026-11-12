# SPDX-License-Identifier: GPL-3.0-or-later
"""P3-04: a small stdlib jar built by tools/stdlib_status.py and imported under pyJVM on both lanes.

Each lane runs the real pipeline (compile, jar, probe) for a handful of modules with the lane's own interpreter, then checks that
every module imported from the jar behaves like the reference interpreter, and that a jar from another compiler build or another
language version is reported and ignored instead of being used silently.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "tools" / "stdlib_status.py"
MODULES = ["json", "json.decoder", "textwrap", "bisect", "string", "heapq"]
LANES = ["3.13", "3.15"]


def _python(lane: str) -> str:
    exe = shutil.which(f"python{lane}")
    if not exe:
        pytest.skip(f"no python{lane}")
    return exe


@pytest.fixture(scope="module")
def build_cache(tmp_path_factory):
    return os.environ.get("PYJVM_BUILD_CACHE") or str(tmp_path_factory.mktemp("build-cache"))


@pytest.fixture(scope="module", params=LANES)
def lane(request, tmp_path_factory, build_cache):
    name = request.param
    py = _python(name)
    work = tmp_path_factory.mktemp(f"work{name}")
    env = {**os.environ, "PYJVM_BUILD_CACHE": build_cache}
    for key in ("PYJVM_CACHE", "PYJVM_STDLIB_JAR"):
        env.pop(key, None)
    cmd = [py, str(TOOL), "all", "--lane", name, "--work", str(work), "--only", ",".join(MODULES), "-j", "2"]
    lib = os.environ.get(f"PYJVM_STDLIB_{name.replace('.', '_')}")
    if lib:
        cmd += ["--lib", lib]
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=1500)
    assert proc.returncode == 0, proc.stdout[-800:] + proc.stderr[-800:]
    d = work / name
    records = [json.loads(line) for line in (d / "records.jsonl").read_text().splitlines()]
    kit = json.loads((d / "kit.json").read_text())
    return {"name": name, "dir": d, "jar": d / f"pyjvm-stdlib-{name}.jar", "records": records, "kit": kit, "work": work, "env": env, "py": py,
            "lib": json.loads((d / "lane.json").read_text())["library"]}


def _java(lane, module, jar, extra=None):
    env = {k: v for k, v in lane["env"].items() if not k.startswith("PYJVM_")}
    env.update(PYJVM_STDLIB=lane["lib"], PYJVM_LANG_VERSION=lane["name"], PYJVM_CACHE=str(lane["dir"] / "jvm-cache"), PYJVM_PROBE_MODULE=module)
    if jar is not None:
        env["PYJVM_STDLIB_JAR"] = str(jar)
    env.update(extra or {})
    kit = lane["kit"]
    return subprocess.run(["java", "-Xss64m", "-cp", f"{kit['program']}{os.pathsep}{kit['compiler']}", "ConformanceMain"],
                          capture_output=True, text=True, env=env, timeout=600)


def _marker(proc, name):
    return next((line.split("\t", 1)[1] for line in proc.stdout.splitlines() if line.startswith(name + "\t")), None)


def test_every_module_compiles_and_imports_from_the_jar(lane):
    by_stage = {(r["module"], r["stage"]): r for r in lane["records"]}
    for m in MODULES:
        assert by_stage[(m, "compile")]["status"] == "ok", by_stage[(m, "compile")]
        probe = by_stage[(m, "probe")]
        assert probe["status"] == "smoke" and probe["from_jar"] is True, probe
    with zipfile.ZipFile(lane["jar"]) as z:
        manifest = z.read("META-INF/pyjvm-modules.txt").decode().splitlines()
    assert f"lang\t{lane['name']}" in manifest
    assert any(line.startswith("fingerprint\t") and len(line) > 20 for line in manifest)
    assert {line.split("\t")[1] for line in manifest if line.startswith("module\t")} == set(MODULES)


def test_jar_import_equals_source_import(lane):
    for module in ("json", "textwrap", "heapq"):
        from_jar = _java(lane, module, lane["jar"])
        from_src = _java(lane, module, None)
        assert from_jar.returncode == 0 and from_src.returncode == 0, from_jar.stderr[-500:] + from_src.stderr[-500:]
        assert ".jar" in _marker(from_jar, "ORIGIN") and ".jar" not in _marker(from_src, "ORIGIN")
        for marker in ("NAMES", "SMOKE"):
            assert _marker(from_jar, marker) == _marker(from_src, marker)
        assert _marker(from_jar, "SMOKE") not in (None, "-")
        ref = subprocess.run([lane["py"], "-I", str(ROOT / "tools" / "stdlib_probe.py")], capture_output=True, text=True,
                             env={"PYJVM_PROBE_MODULE": module, "PATH": os.environ["PATH"]})
        assert _marker(ref, "SMOKE") == _marker(from_jar, "SMOKE")


def _rewrite(jar: Path, out: Path, edit) -> Path:
    with zipfile.ZipFile(jar) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "META-INF/pyjvm-modules.txt":
                data = edit(data.decode()).encode()
            zout.writestr(item, data)
    return out


def test_stale_jar_is_reported_and_ignored(lane, tmp_path):
    fresh = _java(lane, "json", lane["jar"])
    assert "ignoring module archive" not in fresh.stderr
    stale = _rewrite(lane["jar"], tmp_path / "stale.jar", lambda t: "\n".join(
        "fingerprint\t" + "0" * 64 if line.startswith("fingerprint\t") else line for line in t.splitlines()) + "\n")
    proc = _java(lane, "json", stale)
    assert proc.returncode == 0, proc.stderr[-500:]
    assert "ignoring module archive" in proc.stderr and "different compiler or runtime" in proc.stderr
    assert ".jar" not in _marker(proc, "ORIGIN")                 # imported from the sources instead
    assert _marker(proc, "SMOKE") == _marker(fresh, "SMOKE")


def test_jar_for_another_language_version_is_reported_and_ignored(lane, tmp_path):
    other = "3.14" if lane["name"] != "3.14" else "3.13"
    wrong = _rewrite(lane["jar"], tmp_path / "wrong.jar", lambda t: t.replace(f"lang\t{lane['name']}", f"lang\t{other}"))
    proc = _java(lane, "json", wrong)
    assert proc.returncode == 0, proc.stderr[-500:]
    assert f"built for Python {other}" in proc.stderr
    assert ".jar" not in _marker(proc, "ORIGIN")
