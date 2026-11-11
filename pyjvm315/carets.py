# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
"""Source positions for traceback carets.

CPython records a column range for every bytecode instruction; pyJVM records only the current line of a frame.  So that
the printed tracebacks can still underline the failing expression, the compiler stores, for every source line, the
single-line Call / BinOp / Subscript expressions on it together with CPython's caret anchors (the operator, the
brackets).  At print time the runtime picks the candidate that can have raised (see TracebackFormat); when the line is
ambiguous it prints no carets.  Table format, one record per candidate, joined by ';':

    line,kind,start,end,left,right,suppress      kind: c (call), b (binary operator), s (subscript)
    line,x,end_line                              a raise / assert / import statement spans line..end_line

Columns are character offsets into the physical line (CPython reports UTF-8 byte offsets).
"""
from __future__ import annotations

import ast


def _chars(line: str, byte_offset: int) -> int:
    return len(line.encode("utf-8")[:byte_offset].decode("utf-8", "ignore"))


def _binop_anchors(line: str, node: ast.BinOp) -> tuple[int, int]:
    col = _chars(line, node.left.end_col_offset)
    while col < len(line) and (line[col].isspace() or line[col] == ")"):
        col += 1
    right = col + 1
    if right < len(line) and right < _chars(line, node.right.col_offset) and not line[right].isspace() and line[right] not in "\\#":
        right += 1
    return col, right


def _bracket_anchors(line: str, inner: ast.expr, node: ast.expr, bracket: str) -> tuple[int, int]:
    col = _chars(line, inner.end_col_offset)
    while col < len(line) and line[col] != bracket:
        col += 1
    return col, _chars(line, node.end_col_offset)


def build_caret_table(tree: ast.AST, source: str) -> str:
    lines = source.split("\n")
    records: dict[int, list[str]] = {}
    suppressed: set[tuple[int, int, int]] = set()   # CPython shows no carets under the call of "return f(...)" / "x = f(...)"
    for node in ast.walk(tree):
        if isinstance(node, (ast.Return, ast.Assign)) and isinstance(node.value, ast.Call):
            value = node.value
            if isinstance(node, ast.Return):
                if isinstance(value.func, ast.Name): suppressed.add((value.lineno, value.col_offset, value.end_col_offset))
            elif len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                suppressed.add((value.lineno, value.col_offset, value.end_col_offset))
    for node in ast.walk(tree):
        if isinstance(node, (ast.Raise, ast.Assert, ast.Import, ast.ImportFrom)):
            records.setdefault(node.lineno, []).append(f"{node.lineno},x,{node.end_lineno}")
            continue
        if not isinstance(node, (ast.Call, ast.BinOp, ast.Subscript)): continue
        if node.lineno != node.end_lineno or node.lineno > len(lines): continue
        line = lines[node.lineno - 1].rstrip("\r")
        start = _chars(line, node.col_offset)
        end = _chars(line, node.end_col_offset)
        if isinstance(node, ast.BinOp):
            kind = "b"; left, right = _binop_anchors(line, node)
        elif isinstance(node, ast.Subscript):
            kind = "s"; left, right = _bracket_anchors(line, node.value, node, "[")
        else:
            kind = "c"; left, right = _bracket_anchors(line, node.func, node, "(")
        flag = 1 if (node.lineno, node.col_offset, node.end_col_offset) in suppressed else 0
        records.setdefault(node.lineno, []).append(f"{node.lineno},{kind},{start},{end},{left},{right},{flag}")
    out: list[str] = []
    for lineno in sorted(records):
        out.extend(records[lineno])
    return ";".join(out)
