# SPDX-License-Identifier: GPL-3.0-or-later
"""Lower annotated assignments (PEP 526) to plain statements, recording module/class annotations.

``x: T = v`` becomes ``x = v`` plus ``__annotations__['x'] = T`` at module and class level (T is stored as
source text under ``from __future__ import annotations``). Annotations of locals and of non-name targets are
not stored, as in CPython; those that CPython evaluates (without the future import) are evaluated.
Deferred evaluation of annotations (PEP 649/749, roadmap P1-09) is a separate, later step.
"""
from __future__ import annotations

import ast


def has_future_annotations(tree: ast.Module) -> bool:
    return any(isinstance(n, ast.ImportFrom) and n.module == "__future__" and n.level == 0
               and any(a.name == "annotations" for a in n.names) for n in tree.body)


def _scope_has_simple_annotation(body: list[ast.stmt]) -> bool:
    for stmt in body:
        if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name) and stmt.simple:
            return True
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        for field in ("body", "orelse", "finalbody", "handlers", "cases"):
            for child in getattr(stmt, field, []) or []:
                inner = child.body if isinstance(child, (ast.ExceptHandler, ast.match_case)) else [child]
                if _scope_has_simple_annotation(inner):
                    return True
    return False


class _Lowering(ast.NodeTransformer):
    def __init__(self, future: bool) -> None:
        self.future = future
        self.scope = "module"   # module | class | function

    def _body(self, body: list[ast.stmt], scope: str) -> list[ast.stmt]:
        outer, self.scope = self.scope, scope
        try:
            out: list[ast.stmt] = []
            if scope in ("module", "class") and _scope_has_simple_annotation(body):
                init = ast.Assign(targets=[ast.Name(id="__annotations__", ctx=ast.Store())], value=ast.Dict(keys=[], values=[]))
                out.append(ast.copy_location(init, body[0]))
            for stmt in body:
                result = self.visit(stmt)
                if result is None:
                    continue
                out.extend(result if isinstance(result, list) else [result])
            return out or [ast.Pass()]
        finally:
            self.scope = outer

    def visit_Module(self, node: ast.Module):
        node.body = self._body(node.body, "module")
        return node

    def visit_ClassDef(self, node: ast.ClassDef):
        node.decorator_list = [self.visit(d) for d in node.decorator_list]
        node.body = self._body(node.body, "class")
        return node

    def visit_FunctionDef(self, node):
        node.body = self._body(node.body, "function")
        return node

    visit_AsyncFunctionDef = visit_FunctionDef

    def visit_AnnAssign(self, node: ast.AnnAssign):
        out: list[ast.stmt] = []
        simple_name = isinstance(node.target, ast.Name) and node.simple
        if node.value is not None:
            out.append(ast.copy_location(ast.Assign(targets=[node.target], value=node.value), node))
        if simple_name and self.scope in ("module", "class"):
            annotation: ast.expr = ast.Constant(value=ast.unparse(node.annotation)) if self.future else node.annotation
            store = ast.Assign(
                targets=[ast.Subscript(value=ast.Name(id="__annotations__", ctx=ast.Load()),
                                       slice=ast.Constant(value=node.target.id), ctx=ast.Store())],
                value=annotation)
            out.append(ast.copy_location(store, node))
        elif not self.future and self.scope != "function":
            out.append(ast.copy_location(ast.Expr(value=node.annotation), node))   # evaluated, not stored
        return out or [ast.copy_location(ast.Pass(), node)]


def lower_annotations(tree: ast.Module) -> ast.Module:
    if not any(isinstance(n, ast.AnnAssign) for n in ast.walk(tree)):
        return tree
    tree = _Lowering(has_future_annotations(tree)).visit(tree)
    ast.fix_missing_locations(tree)
    return tree
