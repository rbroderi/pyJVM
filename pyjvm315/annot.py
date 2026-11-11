# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
"""Lower annotations to plain statements and expressions.

Two behaviours, selected by the language version the program targets (``versions.deferred_annotations()``):

*Eager* (Python 3.13, PEP 526): ``x: T = v`` becomes ``x = v`` plus ``__annotations__['x'] = T`` at module and class
level, and function annotations are evaluated when the ``def`` runs (the marker ``__pyjvm_annotations__({...})``
below). Under ``from __future__ import annotations`` the annotation is stored as source text.

*Deferred* (Python 3.14+, PEP 649/749): nothing is evaluated up front.
  * a function with annotations is given an ``__annotate__`` function (the marker ``__pyjvm_annotate__(lambda)``);
  * a module gets ``def __annotate__(format, /)`` and the set ``__conditional_annotations__``, to which every
    annotated assignment adds its index when it executes (so only executed annotations are reported);
  * a class gets ``__annotate_func__`` at the end of its body; only annotations in conditional blocks are tracked;
  * annotations of a class body are resolved in the class namespace first (the CPython ``__classdict__``);
  * ``from __future__ import annotations`` keeps modules and classes eager (strings) and gives functions an
    ``__annotate__`` returning the strings, as CPython does.
Generated names start with __pyjvm_ (and end with __, so they are never private-mangled). The compiler recognises the two
``__pyjvm_*__`` markers in the decorator list of a ``def`` (compiler._emit_function_definition) and the
``__pyjvm_classdict__()`` intrinsic; ``__pyjvm_class_annotate__`` / ``__pyjvm_cond_add__`` / ``__pyjvm_not_implemented__``
are runtime functions (AnnotationSupport.java).
"""
from __future__ import annotations

import ast

FORMAT = "__pyjvm_fmt__"
CLASSDICT = "__pyjvm_cd__"
CLASS_COND = "__pyjvm_cc__"
MODULE_COND = "__conditional_annotations__"
MARK_ANNOTATE = "__pyjvm_annotate__"
MARK_ANNOTATIONS = "__pyjvm_annotations__"
CLASSDICT_INTRINSIC = "__pyjvm_classdict__"
ANNOTATION_MARKERS = (MARK_ANNOTATE, MARK_ANNOTATIONS)


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


def mangle(class_name: str | None, name: str) -> str:
    """Private-name mangling of an annotation key inside a class body (CPython _Py_Mangle)."""
    if class_name is None or not name.startswith("__") or name.endswith("__") or "." in name:
        return name
    stripped = class_name.lstrip("_")
    if not stripped:
        return name
    return "_" + stripped + name


def _syntax_error(message: str, node: ast.AST) -> SyntaxError:
    # the JVM runtime's SyntaxError does not take attribute assignment, so the position is not attached
    return SyntaxError(message)


def _scan_annotation(node: ast.AST, in_comprehension: bool) -> None:
    """CPython rejects yield, await, walrus and async comprehensions inside an annotation (outside lambdas)."""
    for child in ast.iter_child_nodes(node):
        if isinstance(child, ast.Lambda):
            continue
        if isinstance(child, (ast.Yield, ast.YieldFrom)):
            raise _syntax_error("yield expression cannot be used within an annotation", child)
        if isinstance(child, ast.Await):
            raise _syntax_error("await expression cannot be used within an annotation", child)
        if isinstance(child, ast.NamedExpr) and not in_comprehension:
            raise _syntax_error("named expression cannot be used within an annotation", child)
        if isinstance(child, ast.comprehension) and child.is_async:
            raise _syntax_error("asynchronous comprehension outside of an asynchronous function", node)
        _scan_annotation(child, in_comprehension or isinstance(child, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)))


def check_annotation(expr: ast.expr) -> None:
    holder = ast.Expr(value=expr)
    _scan_annotation(holder, False)


ALWAYS_VISIBLE = {"__name__", "__file__", "__doc__", "__builtins__", "__spec__", "__package__", "__loader__", "__debug__",
                  "__class__", "NotImplemented", "Ellipsis", "super"}
GLOBAL_LOOKUP = "__pyjvm_global__"


def _target_names(target: ast.AST, out: set[str]) -> None:
    for node in ast.walk(target):
        if isinstance(node, ast.Name) and not isinstance(node.ctx, ast.Load):
            out.add(node.id)


def scope_bindings(body: list[ast.stmt], out: set[str]) -> None:
    """Names bound directly in a scope (not inside nested def/class/lambda bodies)."""
    for stmt in body:
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            out.add(stmt.name)
            continue
        if isinstance(stmt, (ast.Import, ast.ImportFrom)):
            for alias in stmt.names:
                out.add((alias.asname or alias.name).split(".")[0])
        elif isinstance(stmt, (ast.Global, ast.Nonlocal)):
            for name in stmt.names:
                out.add(name)
        elif isinstance(stmt, (ast.Assign, ast.AugAssign, ast.AnnAssign, ast.For, ast.AsyncFor, ast.With, ast.AsyncWith, ast.Delete)):
            for field in ("targets", "target"):
                value = getattr(stmt, field, None)
                for one in value if isinstance(value, list) else ([value] if value is not None else []):
                    _target_names(one, out)
            for item in getattr(stmt, "items", []):
                if item.optional_vars is not None:
                    _target_names(item.optional_vars, out)
        for field in ("body", "orelse", "finalbody"):
            inner = getattr(stmt, field, None)
            if isinstance(inner, list):
                scope_bindings(inner, out)
        for handler in getattr(stmt, "handlers", []) or []:
            if handler.name:
                out.add(handler.name)
            scope_bindings(handler.body, out)


def function_bindings(node, out: set[str]) -> None:
    a = node.args
    for arg in a.posonlyargs + a.args + a.kwonlyargs:
        out.add(arg.arg)
    if a.vararg is not None:
        out.add(a.vararg.arg)
    if a.kwarg is not None:
        out.add(a.kwarg.arg)
    scope_bindings(node.body, out)


class _Names(ast.NodeTransformer):
    """Rewrite the free names of an annotation expression that live inside a lazily evaluated lambda.

    * class scope: look the name up in the class namespace first (CPython LOAD_FROM_DICT_OR_GLOBALS);
    * a name no enclosing function or the module binds statically becomes a runtime global lookup, so that it raises
      NameError when the annotation is evaluated (not a compile-time error) and still finds builtins."""

    def __init__(self, classdict: bool, visible: set[str] | None) -> None:
        self.classdict = classdict
        self.visible = visible
        self.bound: list[set[str]] = []

    def _is_bound(self, name: str) -> bool:
        for names in self.bound:
            if name in names:
                return True
        return False

    def visit_Name(self, node: ast.Name):
        if not isinstance(node.ctx, ast.Load) or self._is_bound(node.id):
            return node
        fallback: ast.expr = node
        if self.visible is not None and node.id not in self.visible and node.id not in ALWAYS_VISIBLE:
            fallback = ast.copy_location(_call(GLOBAL_LOOKUP, [ast.Constant(value=node.id)]), node)
        if not self.classdict:
            return fallback
        key = ast.Constant(value=node.id)
        lookup = ast.IfExp(
            test=ast.Compare(left=key, ops=[ast.In()], comparators=[ast.Name(id=CLASSDICT, ctx=ast.Load())]),
            body=ast.Subscript(value=ast.Name(id=CLASSDICT, ctx=ast.Load()), slice=ast.Constant(value=node.id), ctx=ast.Load()),
            orelse=fallback)
        return ast.copy_location(lookup, node)

    def visit_Lambda(self, node: ast.Lambda):
        a = node.args
        a.defaults = [self.visit(d) for d in a.defaults]
        a.kw_defaults = [self.visit(d) if d is not None else None for d in a.kw_defaults]
        names = set()
        for arg in a.posonlyargs + a.args + a.kwonlyargs:
            names.add(arg.arg)
        if a.vararg is not None:
            names.add(a.vararg.arg)
        if a.kwarg is not None:
            names.add(a.kwarg.arg)
        self.bound.append(names)
        node.body = self.visit(node.body)
        self.bound.pop()
        return node

    def _comprehension(self, node, parts: list[str]):
        gens = node.generators
        gens[0].iter = self.visit(gens[0].iter)
        names = set()
        for gen in gens:
            for target in ast.walk(gen.target):
                if isinstance(target, ast.Name):
                    names.add(target.id)
        self.bound.append(names)
        index = 1
        for gen in gens:
            if index > 1:
                gen.iter = self.visit(gen.iter)
            gen.ifs = [self.visit(cond) for cond in gen.ifs]
            index += 1
        for part in parts:
            setattr(node, part, self.visit(getattr(node, part)))
        self.bound.pop()
        return node

    def visit_ListComp(self, node):
        return self._comprehension(node, ["elt"])

    def visit_SetComp(self, node):
        return self._comprehension(node, ["elt"])

    def visit_GeneratorExp(self, node):
        return self._comprehension(node, ["elt"])

    def visit_DictComp(self, node):
        return self._comprehension(node, ["key", "value"])


# ---- source text of an annotation (``from __future__ import annotations``) ------------------------------------
# A pure-Python port of the expression part of CPython's ast.unparse, so that the JVM-hosted compiler (whose ast
# module has no unparse) produces the same strings as the host. Anything outside this subset falls back to the host's
# ast.unparse when there is one, and is a compile error otherwise.
P_NAMED, P_TUPLE, P_TEST, P_OR, P_AND, P_NOT, P_CMP, P_BOR, P_BXOR, P_BAND, P_SHIFT, P_ARITH, P_TERM, P_FACTOR, P_POWER, P_AWAIT, P_ATOM = range(17)

_BINOPS = (
    (ast.Add, "+", P_ARITH), (ast.Sub, "-", P_ARITH), (ast.Mult, "*", P_TERM), (ast.MatMult, "@", P_TERM),
    (ast.Div, "/", P_TERM), (ast.Mod, "%", P_TERM), (ast.FloorDiv, "//", P_TERM), (ast.Pow, "**", P_POWER),
    (ast.LShift, "<<", P_SHIFT), (ast.RShift, ">>", P_SHIFT), (ast.BitOr, "|", P_BOR), (ast.BitXor, "^", P_BXOR),
    (ast.BitAnd, "&", P_BAND))
_CMPOPS = (
    (ast.Eq, "=="), (ast.NotEq, "!="), (ast.Lt, "<"), (ast.LtE, "<="), (ast.Gt, ">"), (ast.GtE, ">="),
    (ast.Is, "is"), (ast.IsNot, "is not"), (ast.In, "in"), (ast.NotIn, "not in"))


def _lookup(table, op: ast.AST):
    for entry in table:
        if isinstance(op, entry[0]):
            return entry
    return None


def _paren(text: str, wanted: bool) -> str:
    return "(" + text + ")" if wanted else text


def _unparse_items(items: list[ast.expr], prec: int) -> str:
    return ", ".join([unparse_expr(item, prec) for item in items])


def _unparse_slice(node: ast.expr) -> str:
    if isinstance(node, ast.Tuple) and node.elts:
        text = _unparse_items(node.elts, P_TEST)
        return text + "," if len(node.elts) == 1 else text
    return unparse_expr(node, P_TUPLE)


def unparse_expr(node: ast.expr, prec: int = P_TEST) -> str:
    """Source text of an expression in a context that binds at least as tightly as ``prec``."""
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Constant):
        return "..." if node.value is Ellipsis else repr(node.value)
    if isinstance(node, ast.Attribute):
        spacer = " " if isinstance(node.value, ast.Constant) and isinstance(node.value.value, int) else ""
        return unparse_expr(node.value, P_ATOM) + spacer + "." + node.attr
    if isinstance(node, ast.Subscript):
        return unparse_expr(node.value, P_ATOM) + "[" + _unparse_slice(node.slice) + "]"
    if isinstance(node, ast.Slice):
        text = (unparse_expr(node.lower, P_TEST) if node.lower is not None else "") + ":"
        if node.upper is not None:
            text += unparse_expr(node.upper, P_TEST)
        if node.step is not None:
            text += ":" + unparse_expr(node.step, P_TEST)
        return text
    if isinstance(node, ast.Starred):
        return "*" + unparse_expr(node.value, P_BOR)
    if isinstance(node, ast.Tuple):
        text = _unparse_items(node.elts, P_TEST)
        if len(node.elts) == 1:
            text += ","
        return _paren(text, not node.elts or prec > P_TUPLE)
    if isinstance(node, ast.List):
        return "[" + _unparse_items(node.elts, P_TEST) + "]"
    if isinstance(node, ast.Set) and node.elts:
        return "{" + _unparse_items(node.elts, P_TEST) + "}"
    if isinstance(node, ast.Dict):
        parts = []
        for key, value in zip(node.keys, node.values):
            parts.append("**" + unparse_expr(value, P_BOR) if key is None else unparse_expr(key, P_TEST) + ": " + unparse_expr(value, P_TEST))
        return "{" + ", ".join(parts) + "}"
    if isinstance(node, ast.Call):
        parts = [unparse_expr(arg, P_TEST) for arg in node.args]
        for keyword in node.keywords:
            parts.append(("**" if keyword.arg is None else keyword.arg + "=") + unparse_expr(keyword.value, P_TEST))
        return unparse_expr(node.func, P_ATOM) + "(" + ", ".join(parts) + ")"
    if isinstance(node, ast.BinOp):
        entry = _lookup(_BINOPS, node.op)
        own = entry[2]
        right_assoc = own == P_POWER
        text = (unparse_expr(node.left, own + 1 if right_assoc else own) + " " + entry[1] + " "
                + unparse_expr(node.right, own if right_assoc else own + 1))
        return _paren(text, prec > own)
    if isinstance(node, ast.UnaryOp):
        if isinstance(node.op, ast.Not):
            return _paren("not " + unparse_expr(node.operand, P_NOT), prec > P_NOT)
        sign = "~" if isinstance(node.op, ast.Invert) else "+" if isinstance(node.op, ast.UAdd) else "-"
        return _paren(sign + unparse_expr(node.operand, P_FACTOR), prec > P_FACTOR)
    if isinstance(node, ast.BoolOp):
        own = P_OR if isinstance(node.op, ast.Or) else P_AND
        level = own
        parts = []
        for value in node.values:
            level = min(level + 1, P_ATOM)
            parts.append(unparse_expr(value, level))
        return _paren((" or " if own == P_OR else " and ").join(parts), prec > own)
    if isinstance(node, ast.Compare):
        text = unparse_expr(node.left, P_CMP + 1)
        for op, comparator in zip(node.ops, node.comparators):
            text += " " + _lookup(_CMPOPS, op)[1] + " " + unparse_expr(comparator, P_CMP + 1)
        return _paren(text, prec > P_CMP)
    if isinstance(node, ast.IfExp):
        text = (unparse_expr(node.body, P_TEST + 1) + " if " + unparse_expr(node.test, P_TEST + 1)
                + " else " + unparse_expr(node.orelse, P_TEST))
        return _paren(text, prec > P_TEST)
    fallback = getattr(ast, "unparse", None)
    if fallback is None:
        raise NotImplementedError("annotation expression %s under 'from __future__ import annotations'" % node.__class__.__name__)
    return fallback(node)


class _State:
    def __init__(self, kind: str, prefix: str, class_name: str | None) -> None:
        self.kind = kind              # module | class | function
        self.prefix = prefix          # qualified-name prefix of nested definitions
        self.class_name = class_name  # for private-name mangling (class scopes)
        self.depth = 0                # nesting depth of conditional blocks
        self.entries: list[tuple[str, ast.expr, int]] = []   # (key, annotation, conditional index or -1)
        self.cond_count = 0
        self.names: set[str] = set()  # names this scope binds (module and function scopes; empty for classes)


def _name(identifier: str) -> ast.Name:
    return ast.Name(id=identifier, ctx=ast.Load())


def _call(identifier: str, args: list[ast.expr]) -> ast.Call:
    return ast.Call(func=_name(identifier), args=args, keywords=[])


def _format_arguments() -> ast.arguments:
    return ast.arguments(posonlyargs=[ast.arg(arg=FORMAT)], args=[], vararg=None, kwonlyargs=[], kw_defaults=[],
                         kwarg=None, defaults=[])


def _format_check() -> ast.expr:
    return ast.Compare(left=_name(FORMAT), ops=[ast.Gt()], comparators=[ast.Constant(value=2)])


def _entries_dict(entries: list[tuple[str, ast.expr, int]], cond_name: str) -> ast.expr:
    """``{key: value, ...}`` where entries with a conditional index appear only if the index was recorded."""
    parts: list[ast.expr] = []
    keys: list[ast.expr] = []
    values: list[ast.expr] = []
    tracked = False
    for key, value, index in entries:
        if index < 0:
            keys.append(ast.Constant(value=key))
            values.append(value)
            continue
        tracked = True
        if keys:
            parts.append(ast.Dict(keys=keys, values=values))
            keys = []
            values = []
        one = ast.Dict(keys=[ast.Constant(value=key)], values=[value])
        parts.append(ast.IfExp(
            test=ast.Compare(left=ast.Constant(value=index), ops=[ast.In()], comparators=[_name(cond_name)]),
            body=one, orelse=ast.Dict(keys=[], values=[])))
    if not tracked:
        return ast.Dict(keys=keys, values=values)
    if keys:
        parts.append(ast.Dict(keys=keys, values=values))
    return ast.Dict(keys=[None] * len(parts), values=parts)


def _guarded(body: ast.expr) -> ast.expr:
    return ast.IfExp(test=_format_check(), body=_call("__pyjvm_not_implemented__", []), orelse=body)


class _Lowering(ast.NodeTransformer):
    def __init__(self, future: bool, deferred: bool, dynamic: bool) -> None:
        self.dynamic = dynamic
        self.future = future
        self.deferred = deferred
        self.state = _State("module", "", None)
        self.saved: list[_State] = []

    # ---- helpers -------------------------------------------------------------------------------
    def _push(self, state: _State) -> None:
        self.saved.append(self.state)
        self.state = state

    def _pop(self) -> None:
        self.state = self.saved.pop()

    def _stmts(self, body: list[ast.stmt]) -> list[ast.stmt]:
        out: list[ast.stmt] = []
        for stmt in body:
            result = self.visit(stmt)
            if result is None:
                continue
            out.extend(result if isinstance(result, list) else [result])
        return out

    def _visible(self) -> set[str] | None:
        """Names a lazily evaluated annotation can reach statically: module and enclosing function bindings.
        None when module names live in an exec() namespace and are resolved by name at run time."""
        if self.dynamic:
            return None
        names: set[str] = set()
        for state in self.saved + [self.state]:
            if state.kind != "class":
                names.update(state.names)
        return names

    def _eager(self, expr: ast.expr) -> ast.expr:
        """An annotation evaluated right where it is written: a name nothing binds raises NameError at run time."""
        if self.state.kind == "class":
            return expr
        return _Names(False, self._visible()).visit(expr)

    def _tracked_annotations(self) -> bool:
        return self.deferred and not self.future

    # ---- scopes --------------------------------------------------------------------------------
    def visit_Module(self, node: ast.Module):
        simple = _scope_has_simple_annotation(node.body)
        scope_bindings(node.body, self.state.names)
        for inner in ast.walk(node):
            if isinstance(inner, ast.Global):
                self.state.names.update(inner.names)
        body = self._stmts(node.body)
        st = self.state
        if simple and not self._tracked_annotations():
            init = ast.Assign(targets=[ast.Name(id="__annotations__", ctx=ast.Store())], value=ast.Dict(keys=[], values=[]))
            body.insert(0, ast.copy_location(init, node.body[0]))
        elif st.entries:
            at = self._prologue_index(body)
            body = body[:at] + self._module_prologue(st, node.body[0]) + body[at:]
        node.body = body or [ast.Pass()]
        return node

    @staticmethod
    def _prologue_index(body: list[ast.stmt]) -> int:
        index = 0
        if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) and isinstance(body[0].value.value, str):
            index = 1
        while index < len(body) and isinstance(body[index], ast.ImportFrom) and body[index].module == "__future__":
            index += 1
        return index

    def _module_prologue(self, st: _State, anchor: ast.stmt) -> list[ast.stmt]:
        visible = self._visible()
        entries = [(key, _Names(False, visible).visit(value), index) for key, value, index in st.entries]
        check = ast.If(test=_format_check(), body=[ast.Raise(exc=_name("NotImplementedError"), cause=None)], orelse=[])
        function = ast.FunctionDef(
            name="__annotate__", args=_format_arguments(),
            body=[check, ast.Return(value=_entries_dict(entries, MODULE_COND))],
            decorator_list=[], returns=None, type_comment=None, type_params=[])
        init = ast.Assign(targets=[ast.Name(id=MODULE_COND, ctx=ast.Store())], value=_call("set", []))
        return [ast.copy_location(function, anchor), ast.copy_location(init, anchor)]

    def visit_ClassDef(self, node: ast.ClassDef):
        qualified = self.state.prefix + node.name
        simple = _scope_has_simple_annotation(node.body)
        st = _State("class", qualified + ".", node.name)
        self._push(st)
        try:
            body = self._stmts(node.body)
        finally:
            self._pop()
        if simple and not self._tracked_annotations():
            init = ast.Assign(targets=[ast.Name(id="__annotations__", ctx=ast.Store())], value=ast.Dict(keys=[], values=[]))
            body.insert(0, ast.copy_location(init, node.body[0]))
        elif st.entries:
            self._class_epilogue(body, st, qualified, node.body[0])
        node.body = body or [ast.Pass()]
        return node

    def _class_epilogue(self, body: list[ast.stmt], st: _State, qualified: str, anchor: ast.stmt) -> None:
        tracked = st.cond_count > 0
        entries = [(key, _Names(True, self._visible()).visit(value), index) for key, value, index in st.entries]
        params = [ast.arg(arg=CLASSDICT)]
        arguments: list[ast.expr] = [_call(CLASSDICT_INTRINSIC, [])]
        if tracked:
            params.append(ast.arg(arg=CLASS_COND))
            arguments.append(_name(CLASS_COND))
            body.insert(0, ast.copy_location(ast.Assign(targets=[ast.Name(id=CLASS_COND, ctx=ast.Store())], value=_call("set", [])), anchor))
        inner = ast.Lambda(args=_format_arguments(), body=_guarded(_entries_dict(entries, CLASS_COND)))
        outer = ast.Lambda(args=ast.arguments(posonlyargs=[], args=params, vararg=None, kwonlyargs=[], kw_defaults=[], kwarg=None, defaults=[]),
                           body=inner)
        function = ast.Call(func=outer, args=arguments, keywords=[])
        store = ast.Assign(targets=[ast.Name(id="__annotate_func__", ctx=ast.Store())],
                           value=_call("__pyjvm_class_annotate__", [function, ast.Constant(value=qualified)]))
        body.append(ast.copy_location(store, anchor))
        if tracked:
            body.append(ast.copy_location(ast.Delete(targets=[ast.Name(id=CLASS_COND, ctx=ast.Del())]), anchor))

    def visit_FunctionDef(self, node):
        qualified = self.state.prefix + node.name
        items = self._function_items(node)
        inner = _State("function", qualified + ".<locals>.", None)
        function_bindings(node, inner.names)
        self._push(inner)
        try:
            node.body = self._stmts(node.body) or [ast.Pass()]
        finally:
            self._pop()
        if items:
            node.decorator_list = list(node.decorator_list) + [self._function_marker(items)]
        return node

    visit_AsyncFunctionDef = visit_FunctionDef

    # ---- function annotations ------------------------------------------------------------------
    def _function_items(self, node) -> list[tuple[str, ast.expr]]:
        a = node.args
        owner = self.state.class_name if self.state.kind == "class" else None
        found: list[tuple[str, ast.arg | None]] = []
        for arg in a.posonlyargs + a.args:
            found.append((arg.arg, arg))
        if a.vararg is not None:
            found.append((a.vararg.arg, a.vararg))
        for arg in a.kwonlyargs:
            found.append((arg.arg, arg))
        if a.kwarg is not None:
            found.append((a.kwarg.arg, a.kwarg))
        items: list[tuple[str, ast.expr]] = []
        for name, arg in found:
            if arg is not None and arg.annotation is not None:
                items.append((mangle(owner, name), arg.annotation))
                arg.annotation = None
        if node.returns is not None:
            items.append(("return", node.returns))
            node.returns = None
        for key, value in items:
            if self.deferred or self.future:
                check_annotation(value)
        return items

    def _function_marker(self, items: list[tuple[str, ast.expr]]) -> ast.expr:
        if self.future:
            values = [ast.Constant(value=unparse_expr(value)) for key, value in items]
        elif self.state.kind == "class" and self.deferred:
            values = [_Names(True, self._visible()).visit(value) for key, value in items]
        elif self.deferred:
            values = [_Names(False, self._visible()).visit(value) for key, value in items]
        else:
            values = [self._eager(value) for key, value in items]
        display = ast.Dict(keys=[ast.Constant(value=key) for key, value in items], values=values)
        if not self.deferred:
            return _call(MARK_ANNOTATIONS, [display])
        annotate: ast.expr = ast.Lambda(args=_format_arguments(), body=_guarded(display))
        if self.state.kind == "class" and not self.future:
            outer = ast.Lambda(args=ast.arguments(posonlyargs=[], args=[ast.arg(arg=CLASSDICT)], vararg=None, kwonlyargs=[],
                                                  kw_defaults=[], kwarg=None, defaults=[]), body=annotate)
            annotate = ast.Call(func=outer, args=[_call(CLASSDICT_INTRINSIC, [])], keywords=[])
        return _call(MARK_ANNOTATE, [annotate])

    # ---- annotated assignments -----------------------------------------------------------------
    def _conditional(self, node):
        self.state.depth += 1
        try:
            return self.generic_visit(node)
        finally:
            self.state.depth -= 1

    visit_If = visit_While = visit_For = visit_AsyncFor = _conditional
    visit_Try = visit_TryStar = visit_With = visit_AsyncWith = _conditional

    def visit_Lambda(self, node):
        return node

    def visit_AnnAssign(self, node: ast.AnnAssign):
        st = self.state
        out: list[ast.stmt] = []
        target = node.target
        simple = isinstance(target, ast.Name) and node.simple
        if self.deferred or self.future:
            check_annotation(node.annotation)
        if node.value is not None:
            out.append(ast.copy_location(ast.Assign(targets=[target], value=node.value), node))
        elif isinstance(target, ast.Attribute):
            out.append(ast.copy_location(ast.Expr(value=target.value), node))
        elif isinstance(target, ast.Subscript):
            out.append(ast.copy_location(ast.Expr(value=target.value), node))
            out.append(ast.copy_location(ast.Expr(value=target.slice), node))
        if simple and st.kind != "function":
            self._record(node, out)
        elif not self.deferred and not self.future and st.kind != "function":
            out.append(ast.copy_location(ast.Expr(value=self._eager(node.annotation)), node))   # evaluated, not stored
        return out or [ast.copy_location(ast.Pass(), node)]

    def _record(self, node: ast.AnnAssign, out: list[ast.stmt]) -> None:
        st = self.state
        name = mangle(st.class_name, node.target.id)
        if not self._tracked_annotations():
            annotation: ast.expr = ast.Constant(value=unparse_expr(node.annotation)) if self.future else self._eager(node.annotation)
            store = ast.Assign(
                targets=[ast.Subscript(value=ast.Name(id="__annotations__", ctx=ast.Load()),
                                       slice=ast.Constant(value=name), ctx=ast.Store())],
                value=annotation)
            out.append(ast.copy_location(store, node))
            return
        if st.kind == "module":
            index = len(st.entries)
            cond_name = MODULE_COND
        elif st.depth > 0:
            index = st.cond_count
            st.cond_count += 1
            cond_name = CLASS_COND
        else:
            index = -1
            cond_name = ""
        st.entries.append((name, node.annotation, index))
        if index >= 0:
            out.append(ast.copy_location(ast.Expr(value=_call("__pyjvm_cond_add__", [_name(cond_name), ast.Constant(value=index)])), node))


def _needs_lowering(tree: ast.Module) -> bool:
    for n in ast.walk(tree):
        if isinstance(n, ast.AnnAssign):
            return True
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if n.returns is not None:
                return True
            for arg in n.args.posonlyargs + n.args.args + n.args.kwonlyargs + [n.args.vararg, n.args.kwarg]:
                if arg is not None and arg.annotation is not None:
                    return True
    return False


def lower_annotations(tree: ast.Module, deferred: bool = False, dynamic: bool = False) -> ast.Module:
    if not _needs_lowering(tree):
        return tree
    tree = _Lowering(has_future_annotations(tree), deferred, dynamic).visit(tree)
    ast.fix_missing_locations(tree)
    return tree
