# SPDX-License-Identifier: GPL-3.0-or-later
"""The Java tokenizer/parser must agree with CPython's tokenize and ast on real code (roadmap P1-04/P1-05)."""
from __future__ import annotations

import ast
import json
import subprocess
import sys
import sysconfig
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
import compare_ast  # noqa: E402
import compare_tokens  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
STDLIB = Path(sysconfig.get_paths()["stdlib"])


@pytest.fixture(scope="module")
def classes():
    with tempfile.TemporaryDirectory() as td:
        sources = sorted((ROOT / "runtime/pyjvm315/parser").glob("*.java"))
        subprocess.run(["javac", "--release", "25", "-d", td, *map(str, sources)], check=True,
                       capture_output=True, env={"PATH": __import__("os").environ["PATH"]})
        yield td


def sample(count: int) -> list[Path]:
    files = sorted(p for p in STDLIB.glob("*.py") if p.stat().st_size < 200_000)
    step = max(1, len(files) // count)
    return files[::step][:count]


def run_java(classes: str, main: str, files: list[Path]) -> dict[str, list[str]]:
    out = subprocess.run(["java", "-Xss64m", "-cp", classes, f"pyjvm315.parser.{main}", *map(str, files)],
                         text=True, capture_output=True, encoding="utf-8", env={"PATH": __import__("os").environ["PATH"]}).stdout
    got: dict[str, list[str]] = {}
    cur = None
    for line in out.split("\n"):
        if line.startswith("#FILE\t"):
            cur = line[6:]; got[cur] = []
        elif cur is not None and line:
            got[cur].append(line)
    return got


def test_tokens_match_tokenize(classes):
    files = sample(60)
    got = run_java(classes, "TokenDump", files)
    for p in files:
        want = compare_tokens.host_tokens(p)
        assert want is not None
        assert got[str(p)] == want, p


def test_trees_match_ast_parse(classes):
    files = sample(60)
    got = run_java(classes, "AstDump", files)
    for p in files:
        want = compare_ast.conv(ast.parse(p.read_text(encoding="utf-8")))
        have = compare_ast.prune(json.loads(got[str(p)][0]), want)
        assert have == want, (p, compare_ast.first_diff(have, want))


@pytest.mark.parametrize("source", [
    "x = (1,\n", "def f(:\n  pass\n", "if x\n  pass\n", "a b\n", "class C:\npass\n", "x = 'abc\n", "1 +\n",
    "def f():\n  return\n    x\n", "print 'hi'\n", "for in x: pass\n", "x = [1, 2\n", "f(a=1, 2)\n", "lambda: (yield)\nx +=\n",
])
def test_invalid_source_is_rejected_like_cpython(classes, source):
    with pytest.raises(SyntaxError):
        ast.parse(source)
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False, encoding="utf-8") as f:
        f.write(source)
    line = run_java(classes, "AstDump", [Path(f.name)])[f.name][0]
    assert line.startswith("#ERROR"), line


def test_utf8_byte_columns_and_nfkc(classes):
    source = "s = 'héllo🙂'; ｗｉｄｔｈ = s\n"
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False, encoding="utf-8") as f:
        f.write(source)
    want = compare_ast.conv(ast.parse(source))
    have = compare_ast.prune(json.loads(run_java(classes, "AstDump", [Path(f.name)])[f.name][0]), want)
    assert have == want
