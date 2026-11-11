# SPDX-License-Identifier: GPL-3.0-or-later
"""Every source file declares its license; runtime files carry the linking exception."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from pyjvm315.selfhost import CORE  # noqa: E402
RUNTIME_ID = "SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception"
COMPILER_ID = "SPDX-License-Identifier: GPL-3.0-or-later"


def _head(path: Path) -> str:
    return "".join(path.read_text(encoding="utf-8").splitlines(keepends=True)[:3])


def test_runtime_java_files_carry_linking_exception():
    bad = [str(p) for p in (ROOT / "runtime").rglob("*.java") if RUNTIME_ID not in _head(p)]
    assert not bad, bad


def test_inprocess_compiler_modules_carry_linking_exception():
    bad = [m for m in CORE if RUNTIME_ID not in _head(ROOT / "pyjvm315" / f"{m}.py")]
    assert not bad, bad


def test_other_compiler_and_tool_files_are_plain_gpl():
    core = {ROOT / "pyjvm315" / f"{m}.py" for m in CORE}
    paths = (list((ROOT / "pyjvm315").rglob("*.py")) + list((ROOT / "tools").glob("*.py"))
             + list((ROOT / "tests").glob("*.py")))
    bad = [str(p) for p in paths if p not in core
           and (COMPILER_ID not in _head(p) or "AdditionRef" in _head(p))]
    assert not bad, bad


def test_license_files_present():
    for name in ("LICENSE", "LICENSE-EXCEPTION", "THIRD_PARTY_LICENSES", "CLA.md", "CONTRIBUTING.md", "runtime/LICENSE"):
        assert (ROOT / name).is_file(), name
