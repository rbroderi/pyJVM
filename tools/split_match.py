# SPDX-License-Identifier: GPL-3.0-or-later
"""Split a giant `match` dispatcher method into chunk methods (JVM methods are limited to 64KB of bytecode).

usage: python tools/split_match.py <file> <class> <method> <arms-per-chunk>

The method must end with a single top-level `match <subject>:` statement; arms keep their order. Each chunk
becomes `<method>_partN(self, ...)` with the same match; bare `return` in an arm becomes `return True`, an arm
that finishes returns True, and a chunk without a wildcard arm ends with `case _: return False`. The original
method keeps any statements before the match and calls the chunks in order.
"""
from __future__ import annotations

import ast
import sys
from pathlib import Path


def main(path: str, cls_name: str, method: str, per_chunk: int) -> None:
    src = Path(path).read_text()
    lines = src.split("\n")
    tree = ast.parse(src)
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == cls_name)
    func = next(n for n in cls.body if isinstance(n, ast.FunctionDef) and n.name == method)
    match = func.body[-1]
    assert isinstance(match, ast.Match), "method must end with a match statement"
    params = [a.arg for a in func.args.args]
    defaults = ast.unparse(func.args)       # keeps default values for the signature
    subject = ast.unparse(match.subject)
    arm_start = [c.pattern.lineno - 1 for c in match.cases]
    arm_end = arm_start[1:] + [match.end_lineno]
    chunks = [list(range(i, min(i + per_chunk, len(match.cases)))) for i in range(0, len(match.cases), per_chunk)]
    methods = []
    for number, idxs in enumerate(chunks):
        body: list[str] = []
        for i in idxs:
            arm = lines[arm_start[i]:arm_end[i]]
            while arm and not arm[-1].strip():
                arm.pop()
            # bare returns (not inside nested functions) report "handled"
            fixes = []
            def collect(node, nested=False):
                for child in ast.iter_child_nodes(node):
                    inner = nested or isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef))
                    if isinstance(child, ast.Return) and not nested and child.value is None:
                        fixes.append(child)
                    collect(child, inner)
            case = match.cases[i]
            for stmt in case.body:
                if isinstance(stmt, ast.Return) and stmt.value is None:
                    fixes.append(stmt)
                collect(stmt, isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)))
            for r in sorted(fixes, key=lambda n: (-n.lineno, -n.col_offset)):
                row = r.lineno - 1 - arm_start[i]
                text = arm[row]
                col = r.col_offset
                assert text[col:col + 6] == "return", (path, r.lineno)
                arm[row] = text[:col] + "return True" + text[col + 6:]
            case_col = case.pattern.col_offset - len("case ")
            if case.body[0].lineno == case.pattern.lineno:       # inline arm "case P: stmt": expand to block form
                first, col = arm[0], case.body[0].col_offset
                arm[0:1] = [first[:col].rstrip(), " " * (case_col + 4) + first[col:]]
            body_indent = " " * (case_col + 4)
            wildcard = isinstance(case.pattern, ast.MatchAs) and case.pattern.pattern is None and case.pattern.name is None
            last_is_terminal = isinstance(case.body[-1], (ast.Raise, ast.Return))
            body += ["    " + l if l.strip() else l for l in arm]
            if not last_is_terminal:
                body.append("    " + body_indent + "return True")
        last_case = match.cases[idxs[-1]]
        has_wildcard = isinstance(last_case.pattern, ast.MatchAs) and last_case.pattern.pattern is None and last_case.pattern.name is None
        if not has_wildcard:
            case_indent = " " * (match.cases[idxs[0]].pattern.col_offset - len("case "))
            body += [f"    {case_indent}case _:", f"    {case_indent}    return False"]
        sig = f"    def {method}_part{number}({', '.join(params)}):"
        methods.append([sig, f"        match {subject}:"] + body + [""])
    calls = [f"        if self.{method}_part{n}({', '.join(params[1:])}):\n            return" for n in range(len(chunks))]
    new_func = lines[func.lineno - 1:match.lineno - 1] + calls
    # the last chunk's wildcard arm already raises for unknown input, so the final call needs no fallback
    replacement = new_func + [""] + [l for m in methods for l in m]
    lines[func.lineno - 1:func.end_lineno] = replacement[:-1] if replacement[-1] == "" else replacement
    Path(path).write_text("\n".join(lines))
    print(f"{cls_name}.{method}: {len(match.cases)} arms -> {len(chunks)} chunks")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]))
