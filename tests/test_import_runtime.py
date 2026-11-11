# SPDX-License-Identifier: GPL-3.0-or-later
"""Run-time imports of uncompiled .py files: a package written to a temp dir at test time, imported by a program
that the compiler never saw, must behave exactly like CPython on every available reference lane, with no host
Python on the JVM side (the compiler runs in-process). The package sources are read from the reference
interpreter's own library through PYJVM_STDLIB (importlib itself is CPython's unmodified _bootstrap)."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

import pytest

from pyjvm315.cli import build_runtime
from pyjvm315.conformance import compare_file
from pyjvm315.hostver import reference_version
from pyjvm315.selfhost import build_selfhosted


def _lanes() -> list[str]:
    found: dict[tuple[int, int], str] = {}
    for exe in (sys.executable, shutil.which("python3.13"), shutil.which("python3.15"), shutil.which("python3")):
        if not exe:
            continue
        try:
            found.setdefault(reference_version(exe), exe)
        except (subprocess.CalledProcessError, OSError):
            continue
    return [exe for version, exe in sorted(found.items()) if version >= (3, 13)]


@pytest.fixture(scope="module")
def jvm_kit():
    with tempfile.TemporaryDirectory(prefix="pyjvm-import-test-") as td:
        runtime = Path(td) / "runtime"
        build_runtime(runtime)
        yield runtime, build_selfhosted(Path(td) / "selfhosted")


PACKAGE = {
    "tpkg/__init__.py": """
        print("init tpkg", __name__, __package__)
        from . import core
        from .util import helpers as H
        VERSION = (1, 2)
    """,
    "tpkg/core.py": """
        print("init core", __name__)
        from .util import helpers
        from . import ping            # circular: ping imports core back


        def run(n):
            return helpers.double(n) + ping.PONG
    """,
    "tpkg/ping.py": """
        print("init ping")
        from . import core            # core is partially initialised here
        PONG = 1000
        SEEN_CORE = core.__name__
    """,
    "tpkg/util/__init__.py": "",
    "tpkg/util/helpers.py": """
        def double(n):
            return n * 2
    """,
    "tpkg/broken.py": """
        raise RuntimeError("broken on purpose")
    """,
}

MAIN = '''
import sys
sys.path.insert(0, {root!r})
import tpkg
import tpkg.core as core
from tpkg import VERSION, H
from tpkg.util.helpers import double
print(VERSION, core.run(21), double(4), H.double(5), tpkg.ping.SEEN_CORE)
print(tpkg.__name__, tpkg.__package__, tpkg.__file__.replace({root!r}, "<root>"), len(tpkg.__path__))
print(core.__spec__.name, core.__spec__.parent, core.__spec__.origin.replace({root!r}, "<root>"))
print(sorted(m for m in sys.modules if m.startswith("tpkg")))
m = __import__("tpkg.util.helpers", fromlist=["double"])
print(m.double(7), m is sys.modules["tpkg.util.helpers"])
import importlib
print(importlib.import_module("tpkg.core") is core, importlib.import_module(".ping", "tpkg").PONG)
for name in ("tpkg.missing", "tpkg.core.inner", "tpkg.broken", "no_such_top_level"):
    try:
        importlib.import_module(name)
    except Exception as exc:
        print(type(exc).__name__, exc)
try:
    from tpkg import not_there
except ImportError as exc:
    print(str(exc).replace({root!r}, "<root>"), exc.name, exc.name_from)
print("tpkg.broken" in sys.modules)
'''


@pytest.mark.parametrize("python_exe", _lanes())
def test_temp_dir_package_matches_cpython(python_exe, jvm_kit, tmp_path):
    runtime, selfhosted = jvm_kit
    root = tmp_path / "libs"
    for rel, text in PACKAGE.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")
    main = tmp_path / "main.py"
    main.write_text(MAIN.format(root=str(root)), encoding="utf-8")
    result = compare_file(main, python_exe, runtime_cache=runtime, selfhosted=selfhosted)
    assert result.status == "PASS", result.detail


@pytest.mark.parametrize("python_exe", _lanes())
def test_native_stdlib_modules_match_cpython(python_exe, jvm_kit):
    runtime, selfhosted = jvm_kit
    main = Path(__file__).parent / "conformance" / "native_stdlib_modules" / "main.py"
    result = compare_file(main, python_exe, runtime_cache=runtime, selfhosted=selfhosted)
    assert result.status == "PASS", result.detail
