# SPDX-License-Identifier: GPL-3.0-or-later
"""Every source file declares its license; runtime files carry the linking exception."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNTIME_ID = "SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception"
COMPILER_ID = "SPDX-License-Identifier: GPL-3.0-or-later"


def _head(path: Path) -> str:
    return "".join(path.read_text(encoding="utf-8").splitlines(keepends=True)[:3])


def test_runtime_java_files_carry_linking_exception():
    bad = [str(p) for p in (ROOT / "runtime").rglob("*.java") if RUNTIME_ID not in _head(p)]
    assert not bad, bad


def test_compiler_and_tool_files_are_plain_gpl():
    paths = (list((ROOT / "pyjvm315").rglob("*.py")) + list((ROOT / "tools").glob("*.py"))
             + list((ROOT / "tests").glob("*.py")))
    bad = [str(p) for p in paths if COMPILER_ID not in _head(p) or "AdditionRef" in _head(p)]
    assert not bad, bad


def test_license_files_present():
    for name in ("LICENSE", "THIRD_PARTY_LICENSES", "runtime/LICENSE", "runtime/LICENSE-EXCEPTION"):
        assert (ROOT / name).is_file(), name
