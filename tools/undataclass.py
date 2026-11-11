"""Rewrite @dataclass classes as plain classes with explicit __init__ (self-hosting needs no `dataclasses`)."""
from __future__ import annotations

import ast
import sys
from pathlib import Path


def rewrite(path: Path) -> int:
    src = path.read_text()
    tree = ast.parse(src)
    lines = src.split("\n")
    edits = []   # (start, end, replacement) 0-based inclusive line indexes
    for node in tree.body:
        if not isinstance(node, ast.ClassDef):
            continue
        deco = next((d for d in node.decorator_list if (isinstance(d, ast.Name) and d.id == "dataclass")
                     or (isinstance(d, ast.Call) and isinstance(d.func, ast.Name) and d.func.id == "dataclass")), None)
        if deco is None:
            continue
        frozen = isinstance(deco, ast.Call) and any(k.arg == "frozen" and getattr(k.value, "value", False) for k in deco.keywords)
        fields = [(s.target.id, s.value) for s in node.body if isinstance(s, ast.AnnAssign) and isinstance(s.target, ast.Name)]
        required = [f for f in fields if f[1] is None]
        optional = [f for f in fields if f[1] is not None]
        params = ["self"] + [n for n, _ in required] + [f"{n}={ast.unparse(d)}" for n, d in optional]
        gen = [f"    def __init__({', '.join(params)}):"]
        gen += [f"        self.{n} = {n}" for n, _ in fields]
        if frozen:
            names = ", ".join(f"self.{n}" for n, _ in fields)
            gen += ["", "    def __eq__(self, other):",
                    f"        return isinstance(other, {node.name}) and ({names},) == ({', '.join('other.' + n for n, _ in fields)},)",
                    "", "    def __hash__(self):", f"        return hash(({names},))"]
        ann_lines = [s for s in node.body if isinstance(s, ast.AnnAssign)]
        first, last = ann_lines[0].lineno - 1, ann_lines[-1].end_lineno - 1
        edits.append((first, last, gen))
        edits.append((deco.lineno - 1, deco.lineno - 1, None))
    for start, end, repl in sorted(edits, key=lambda e: -e[0]):
        lines[start:end + 1] = repl if repl is not None else []
    out = "\n".join(lines)
    out = out.replace("from dataclasses import dataclass\n", "")
    path.write_text(out)
    return len(edits) // 2


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(p, rewrite(Path(p)), "classes")
