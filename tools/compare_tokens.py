# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare pyjvm315.parser.Tokenizer with CPython's tokenize over a source tree (roadmap P1-04 acceptance)."""
from __future__ import annotations

import io
import subprocess
import sys
import tokenize
from pathlib import Path


def host_tokens(path: Path) -> list[str] | None:
    try:
        text = path.read_text(encoding="utf-8")
        lines = []
        for t in tokenize.generate_tokens(io.StringIO(text).readline):
            if t.type in (tokenize.ERRORTOKEN, tokenize.ENCODING):
                continue
            s = t.string.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            lines.append(f"{tokenize.tok_name[t.type]}\t{s}\t{t.start[0]},{t.start[1]}\t{t.end[0]},{t.end[1]}")
        return lines
    except (SyntaxError, tokenize.TokenError, UnicodeDecodeError, IndentationError):
        return None


def main(argv: list[str]) -> int:
    classes, root, limit = argv[0], Path(argv[1]), int(argv[2]) if len(argv) > 2 else 10**9
    files = sorted(p for p in root.rglob("*.py") if "site-packages" not in p.parts)[:limit]
    java = subprocess.run(["java", "-cp", classes, "pyjvm315.parser.TokenDump", *map(str, files)],
                          text=True, capture_output=True, encoding="utf-8")
    got: dict[str, list[str]] = {}
    current = None
    for line in java.stdout.split("\n"):
        if line.startswith("#FILE\t"):
            current = line[6:]; got[current] = []
        elif current is not None and line:
            got[current].append(line)
    same = diff = skipped = 0
    shown = 0
    for p in files:
        want = host_tokens(p)
        if want is None:
            skipped += 1; continue
        have = got.get(str(p), [])
        if have == want:
            same += 1
        else:
            diff += 1
            if shown < int(__import__("os").environ.get("SHOW", "3")):
                shown += 1
                for k, (a, b) in enumerate(zip(want, have)):
                    if a != b:
                        print(f"{p}: token #{k}\n  want {a!r}\n  have {b!r}"); break
                else:
                    print(f"{p}: length want {len(want)} have {len(have)}")
    print(f"identical {same}  different {diff}  skipped(host cannot tokenize) {skipped}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
