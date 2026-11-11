# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
"""try/except* (ast.TryStar): flow checks and lowering to ordinary try/except.

The lowering follows CPython's CHECK_EG_MATCH / PREP_RERAISE_STAR protocol.  The whole except* statement becomes

    try:
        BODY
    except BaseException as $eg_e:          # catches everything, naked exceptions included
        $eg_o = $eg_e; $eg_r = $eg_e; $eg_x = []
        $eg_m, $eg_r = __py_eg_internal("match", $eg_r, TYPE, LINE)     # once per clause
        if $eg_m is not None:
            try: enter($eg_m); [NAME = $eg_m]; HANDLER BODY      # sys.exception() is the matched group
            except BaseException as $eg_n: __py_eg_internal("collect", $eg_x, $eg_n, ($eg_m, $eg_o))
            finally: __py_eg_internal("leave", $eg_m)
        $eg_p = __py_eg_internal("prep", $eg_o, $eg_x, $eg_r)
        if $eg_p is not None: __py_eg_internal("throw", $eg_p)
    else: ORELSE
    finally: FINALBODY

so generators, coroutines and every other construct work through the existing Try handling.  A bare `raise` in an
except* body re-raises the matched group, as in CPython.
"""
from __future__ import annotations

import ast

_TEMPLATE_PREFIX = "ZZeg_"   # legal identifier while templates are parsed; renamed to "$eg_" (unspellable by user code) afterwards
STAR_FLOW_MESSAGE = "'break', 'continue' and 'return' cannot appear in an except* block"
_SCOPES = (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef)
_LOOPS = (ast.For, ast.AsyncFor, ast.While)


def _violates(node: ast.AST, in_loop: bool) -> bool:
    if isinstance(node, _SCOPES):
        return False
    if isinstance(node, ast.Return):
        return True
    if isinstance(node, (ast.Break, ast.Continue)):
        return not in_loop
    is_loop = isinstance(node, _LOOPS)
    for name, value in ast.iter_fields(node):
        # the body of a loop is inside it; its else clause belongs to the enclosing context
        inner = True if (is_loop and name == "body") else in_loop
        if isinstance(value, list):
            for item in value:
                if isinstance(item, ast.AST) and _violates(item, inner):
                    return True
        elif isinstance(value, ast.AST) and _violates(value, inner):
            return True
    return False


def _has_return(node: ast.AST) -> bool:
    if isinstance(node, _SCOPES):
        return False
    if isinstance(node, ast.Return):
        return True
    for child in ast.iter_child_nodes(node):
        if _has_return(child):
            return True
    return False


def _check(node: ast.AST, in_function: bool) -> None:
    if isinstance(node, ast.TryStar):
        for handler in node.handlers:
            for stmt in handler.body:
                if _violates(stmt, False):
                    # CPython reports a return outside any function before the except* restriction
                    if not in_function and _has_return(stmt):
                        raise SyntaxError("'return' outside function")
                    raise SyntaxError(STAR_FLOW_MESSAGE)
    inner = in_function or isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda))
    if isinstance(node, ast.ClassDef):
        inner = False
    for child in ast.iter_child_nodes(node):
        _check(child, inner)


def check_except_star_flow(tree: ast.AST) -> None:
    """return / break / continue may not leave an except* clause."""
    _check(tree, False)


def _relocate(node: ast.AST, line: int) -> None:
    for sub in ast.walk(node):
        if "lineno" in sub._attributes:
            sub.lineno = line
            sub.end_lineno = line
            sub.col_offset = 0
            sub.end_col_offset = 0


def _relocate_all(stmts: list, line: int) -> None:
    for stmt in stmts:
        _relocate(stmt, line)


class _ReRaise(ast.NodeTransformer):
    """A bare raise in an except* body re-raises the matched group."""

    def __init__(self, matched: str) -> None:
        self.matched = matched

    def visit_Raise(self, node):
        if node.exc is not None:
            return node
        call = ast.parse(f'__py_eg_internal("throw", {self.matched})').body[0]
        return ast.copy_location(call, node)

    def visit_FunctionDef(self, node):
        return node

    visit_AsyncFunctionDef = visit_Lambda = visit_ClassDef = visit_ExceptHandler = visit_FunctionDef


class _Lower(ast.NodeTransformer):
    def __init__(self) -> None:
        self.counter = 0

    def visit_TryStar(self, node):
        self.generic_visit(node)
        self.counter += 1
        n = self.counter
        e, o, r, x, m, p, exc = (f"{_TEMPLATE_PREFIX}{c}{n}" for c in "eorxmpn")
        head = ast.parse(f"{o} = {e}\n{r} = {e}\n{x} = []").body
        _relocate_all(head, node.handlers[0].lineno)
        stmts = list(head)
        for handler in node.handlers:
            line = handler.lineno
            clause = ast.parse(
                f'{m}, {r} = __py_eg_internal("match", {r}, __T__, {line})\n'
                f"if {m} is not None:\n"
                f"    try:\n"
                f"        pass\n"
                f"    except BaseException as {exc}:\n"
                f'        __py_eg_internal("collect", {x}, {exc}, ({m}, {o}))\n').body
            _relocate_all(clause, line)
            clause[0].value.args[2] = handler.type
            inner = clause[1].body[0]
            rewriter = _ReRaise(m)
            # the handler body runs with the matched group as the handled exception (sys.exception())
            enter = ast.parse(f'__py_eg_internal("enter", {m})').body[0]
            leave = ast.parse(f'__py_eg_internal("leave", {m})').body[0]
            _relocate(enter, line)
            _relocate(leave, line)
            body = [enter] + [rewriter.visit(s) for s in handler.body]
            if handler.name is not None:
                bind = ast.parse(f"{handler.name} = {m}").body[0]
                _relocate(bind, line)
                cleanup = ast.parse(f"{handler.name} = None\ndel {handler.name}").body
                _relocate_all(cleanup, line)
                inner.body = [bind] + body
                inner.finalbody = [leave] + cleanup
            else:
                inner.body = body
                inner.finalbody = [leave]
            stmts.extend(clause)
        tail = ast.parse(
            f'{p} = __py_eg_internal("prep", {o}, {x}, {r})\n'
            f"if {p} is not None:\n"
            f'    __py_eg_internal("throw", {p})\n').body
        _relocate_all(tail, node.handlers[-1].lineno)
        stmts.extend(tail)
        catch = ast.ExceptHandler(type=ast.Name(id="BaseException", ctx=ast.Load()), name=e, body=stmts)
        _relocate(catch, node.handlers[0].lineno)
        try_node = ast.Try(body=node.body, handlers=[catch], orelse=node.orelse, finalbody=[])
        ast.copy_location(try_node, node)
        result = try_node
        if node.finalbody:
            result = ast.copy_location(ast.Try(body=[try_node], handlers=[], orelse=[], finalbody=node.finalbody), node)
        # the hidden variables must not outlive the statement (module globals, locals())
        hidden = [o, r, x, m, p]
        init = ast.parse(" = ".join(hidden) + " = None").body
        cleanup = ast.parse("del " + ", ".join(hidden)).body
        _relocate_all(init, node.lineno)
        _relocate_all(cleanup, node.lineno)
        guarded = ast.copy_location(ast.Try(body=[result], handlers=[], orelse=[], finalbody=cleanup), node)
        return init + [guarded]


def lower_try_star(tree: ast.AST) -> ast.AST:
    if not any(isinstance(n, ast.TryStar) for n in ast.walk(tree)):
        return tree
    tree = ast.fix_missing_locations(_Lower().visit(tree))
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and node.id.startswith(_TEMPLATE_PREFIX):
            node.id = "$eg_" + node.id[len(_TEMPLATE_PREFIX):]
        elif isinstance(node, ast.ExceptHandler) and node.name is not None and node.name.startswith(_TEMPLATE_PREFIX):
            node.name = "$eg_" + node.name[len(_TEMPLATE_PREFIX):]
    return tree
