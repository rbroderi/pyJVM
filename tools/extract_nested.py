# SPDX-License-Identifier: GPL-3.0-or-later
"""Move nested classes of PyRuntime into package-level files (P0-01).

Usage: python tools/extract_nested.py Name [Name...]
Extracts `[modifiers] class|record|interface Name ...{...}` found at 4-space indent,
re-indents it, makes it package-private (top-level) and keeps PyRuntime's imports.
"""
import re, sys
from pathlib import Path

DIR = Path(__file__).resolve().parent.parent / "runtime/pyjvm315/runtime"
SRC = DIR / "PyRuntime.java"

def extract(text, name):
    m = re.search(rf"^    ((?:public |private |protected )?(?:static )?(?:final |abstract )?(?:class|record|interface|enum) {name}\b)", text, re.M)
    if not m:
        raise SystemExit(f"{name}: not found")
    start = m.start()
    i = text.index("{", m.end())
    depth = 0
    j = i
    while True:  # naive brace matcher that skips strings/chars/comments
        c = text[j]
        if text.startswith("//", j):
            j = text.index("\n", j); continue
        if text.startswith("/*", j):
            j = text.index("*/", j) + 2; continue
        if c == '"':
            if text.startswith('"""', j):
                j = text.index('"""', j + 3) + 3; continue
            j += 1
            while text[j] != '"':
                j += 2 if text[j] == "\\" else 1
        elif c == "'":
            j += 1
            while text[j] != "'":
                j += 2 if text[j] == "\\" else 1
        elif c == "{": depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0: break
        j += 1
    end = j + 1
    if text[end:end+1] == "\n": end += 1
    return start, end

def main(names):
    text = SRC.read_text()
    header = text[:text.index("public final class PyRuntime")].replace("import java.math", "import static pyjvm315.runtime.PyRuntime.*;\nimport java.math", 1)
    for name in names:
        s, e = extract(text, name)
        body = text[s:e]
        text = text[:s] + text[e:]
        lines = [l[4:] if l.startswith("    ") else l for l in body.splitlines()]
        decl = re.sub(r"^(public |private |protected )?(static )?", "", lines[0])
        lines[0] = decl
        out = header + "\n".join(lines) + "\n"
        out = re.sub(r"\bprivate (?!static final class)", "", out)
        (DIR / f"{name}.java").write_text(out)
        print("extracted", name)
    SRC.write_text(text)

main(sys.argv[1:])
