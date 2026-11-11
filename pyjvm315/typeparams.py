# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
"""Lower PEP 695/696 type parameters and the ``type`` statement to plain statements (roadmap P1-12).

CPython evaluates a generic definition inside an implicit *annotation scope* (a function) that creates the
type-parameter objects and then defines the function/class/alias. This pass builds exactly that function:

    @deco
    def f[T: int = str, *Ts, **P](a=d, *, k=e) -> T: body

becomes

    def __pyjvm_tp_0__(__pyjvm_d0__, __pyjvm_d1__):          # defaults are evaluated by the caller, outside the scope
        global f                                              # the body's own name resolves to the enclosing scope
        T = __pyjvm_tp_typevar__("T", lambda: int, None, lambda: str)       # bound/constraints/default are lazy
        Ts = __pyjvm_tp_typevartuple__("Ts", None)
        P = __pyjvm_tp_paramspec__("P", None)
        def f(a=__pyjvm_d0__, *, k=__pyjvm_d1__) -> T: body
        f.__type_params__ = (T, Ts, P)
        return f
    f = deco(__pyjvm_tp_0__(d, e))                            # decorators wrap the call, so their order is unchanged

A generic class additionally gets ``Generic[T, ...]`` appended to its bases (``__orig_bases__`` keeps the
subscripted form) and binds ``__type_params__`` in its namespace before the body runs. ``type X[T] = v`` becomes
``X = __pyjvm_tp_alias__("X", (T,), lambda: v)`` (inside such a function when there are parameters).
Inside a class body the scope can see the class namespace: annotations, bounds, defaults and alias values look a
name up in ``__pyjvm_cd__`` (the class dict) before anything else, exactly like CPython's LOAD_FROM_DICT_OR_GLOBALS.

Run before annotation lowering (annot.py), which then treats the wrapper as an ordinary function. The compiler
recognises wrapper names (``TP_PREFIX``) to keep ``__qualname__`` free of the implicit scope.
Generated names start with __pyjvm_ and end with __, so they are never private-mangled.
"""
from __future__ import annotations

import ast

from .annot import CLASSDICT, CLASSDICT_INTRINSIC, _Names, has_future_annotations, scope_bindings, function_bindings

TP_PREFIX = "__pyjvm_tp_"
H_TYPEVAR = "__pyjvm_tp_typevar__"
H_TYPEVARTUPLE = "__pyjvm_tp_typevartuple__"
H_PARAMSPEC = "__pyjvm_tp_paramspec__"
H_ALIAS = "__pyjvm_tp_alias__"
H_GENERIC = "__pyjvm_tp_generic__"
H_BASES = "__pyjvm_tp_bases__"
H_UNPACK = "__pyjvm_tp_unpack__"
HELPERS = (H_TYPEVAR, H_TYPEVARTUPLE, H_PARAMSPEC, H_ALIAS, H_GENERIC, H_BASES, H_UNPACK)
ORIG_BASES = "__pyjvm_ob__"
SENTINEL_MODULE = "__pyjvm_module__"


def _name(identifier: str, ctx=None) -> ast.Name:
    return ast.Name(id=identifier, ctx=ctx if ctx is not None else ast.Load())


def _call(identifier: str, args: list[ast.expr]) -> ast.Call:
    return ast.Call(func=_name(identifier), args=args, keywords=[])


def _no_args() -> ast.arguments:
    return ast.arguments(posonlyargs=[], args=[], vararg=None, kwonlyargs=[], kw_defaults=[], kwarg=None, defaults=[])


def _params(names: list[str]) -> ast.arguments:
    return ast.arguments(posonlyargs=[], args=[ast.arg(arg=n) for n in names], vararg=None, kwonlyargs=[],
                         kw_defaults=[], kwarg=None, defaults=[])


def _thunk(expr: ast.expr) -> ast.expr:
    return ast.Lambda(args=_no_args(), body=expr)


def _tuple(items: list[ast.expr]) -> ast.Tuple:
    return ast.Tuple(elts=items, ctx=ast.Load())


def _is_sentinel_call(n: ast.AST) -> bool:
    return isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "sentinel"


def needs_lowering(tree: ast.Module) -> bool:
    for n in ast.walk(tree):
        if _is_sentinel_call(n):
            return True
        if isinstance(n, ast.TypeAlias):
            return True
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and getattr(n, "type_params", None):
            return True
    return False


def _alias_names(body: list[ast.stmt], out: set[str]) -> None:
    """Names bound by ``type X = ...`` statements directly in a scope (not inside nested def/class bodies)."""
    for stmt in body:
        if isinstance(stmt, ast.TypeAlias):
            out.add(stmt.name.id)
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        for field in ("body", "orelse", "finalbody"):
            inner = getattr(stmt, field, None)
            if isinstance(inner, list):
                _alias_names(inner, out)
        for handler in getattr(stmt, "handlers", []) or []:
            _alias_names(handler.body, out)
        for case in getattr(stmt, "cases", []) or []:
            _alias_names(case.body, out)


def _declared(body: list[ast.stmt], kind: type, out: set[str]) -> None:
    """Names declared ``global`` / ``nonlocal`` directly in a scope."""
    for stmt in body:
        if isinstance(stmt, kind):
            for name in stmt.names:
                out.add(name)
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        for field in ("body", "orelse", "finalbody"):
            inner = getattr(stmt, field, None)
            if isinstance(inner, list):
                _declared(inner, kind, out)
        for handler in getattr(stmt, "handlers", []) or []:
            _declared(handler.body, kind, out)
        for case in getattr(stmt, "cases", []) or []:
            _declared(case.body, kind, out)


class _Scope:
    def __init__(self, kind: str, names: set[str]) -> None:
        self.kind = kind          # module | function | class
        self.names = names        # names visible from nested lazily evaluated expressions (empty for classes)
        self.globals: set[str] = set()
        self.nonlocals: set[str] = set()


class _Lowering(ast.NodeTransformer):
    def __init__(self, future: bool, dynamic: bool, module_name: str) -> None:
        self.future = future
        self.dynamic = dynamic
        self.module_name = module_name
        self.scopes: list[_Scope] = []
        self.counter = 0

    # ---- scope bookkeeping ---------------------------------------------------------------------
    @property
    def scope(self) -> _Scope:
        return self.scopes[-1]

    def _visible(self) -> set[str] | None:
        if self.dynamic:
            return None
        names: set[str] = set()
        for scope in self.scopes:
            if scope.kind != "class":
                names.update(scope.names)
        return names

    def _rewrite(self, expr: ast.expr, extra: set[str], classdict: bool) -> ast.expr:
        visible = self._visible()
        if visible is not None:
            visible = visible | extra
        return _Names(classdict, visible).visit(expr)

    def _stmts(self, body: list[ast.stmt]) -> list[ast.stmt]:
        out: list[ast.stmt] = []
        for stmt in body:
            result = self.visit(stmt)
            if result is None:
                continue
            if isinstance(result, list):
                out.extend(result)
            else:
                out.append(result)
        return out

    def visit_Module(self, node: ast.Module):
        scope = _Scope("module", set())
        scope_bindings(node.body, scope.names)
        _alias_names(node.body, scope.names)
        for inner in ast.walk(node):
            if isinstance(inner, ast.Global):
                scope.names.update(inner.names)
        self.scopes.append(scope)
        node.body = self._stmts(node.body) or [ast.Pass()]
        self.scopes.pop()
        return node

    # ---- non-generic scopes: only track them ---------------------------------------------------
    def _enter_function(self, node) -> _Scope:
        scope = _Scope("function", set())
        function_bindings(node, scope.names)
        _alias_names(node.body, scope.names)
        _declared(node.body, ast.Global, scope.globals)
        _declared(node.body, ast.Nonlocal, scope.nonlocals)
        return scope

    def _plain_function(self, node):
        node.decorator_list = [self.visit(d) for d in node.decorator_list]
        self.scopes.append(self._enter_function(node))
        node.body = self._stmts(node.body) or [ast.Pass()]
        self.scopes.pop()
        return node

    def _plain_class(self, node: ast.ClassDef):
        scope = _Scope("class", set())
        _declared(node.body, ast.Global, scope.globals)
        _declared(node.body, ast.Nonlocal, scope.nonlocals)
        self.scopes.append(scope)
        node.body = self._stmts(node.body) or [ast.Pass()]
        self.scopes.pop()
        return node

    def visit_Lambda(self, node):
        return node

    def visit_Call(self, node: ast.Call):
        self.generic_visit(node)
        if _is_sentinel_call(node) and not any(k.arg == SENTINEL_MODULE for k in node.keywords):
            if not any("sentinel" in scope.names for scope in self.scopes if scope.kind != "class"):
                # the builtin has no caller frame to ask for its module: say which module the call site is in
                module: ast.expr = _name("__name__") if self.dynamic else ast.Constant(value=self.module_name)
                node.keywords = list(node.keywords) + [ast.keyword(arg=SENTINEL_MODULE, value=module)]
        return node

    # ---- helpers for generic definitions -------------------------------------------------------
    def _fresh(self) -> str:
        name = TP_PREFIX + str(self.counter) + "__"
        self.counter += 1
        return name

    def _declaration(self, name: str) -> list[ast.stmt]:
        """Keep the definition's own name bound in the enclosing scope (CPython resolves it there)."""
        scope = self.scope
        if scope.kind == "class":
            return []
        if scope.kind == "module" or name in scope.globals:
            return [ast.Global(names=[name])]
        return [ast.Nonlocal(names=[name])]

    def _type_param_stmts(self, params: list, extra: set[str], classdict: bool) -> list[ast.stmt]:
        out: list[ast.stmt] = []
        for p in params:
            target = [_name(p.name, ast.Store())]
            default: ast.expr = ast.Constant(value=None)
            if getattr(p, "default_value", None) is not None:
                expr = p.default_value
                if isinstance(expr, ast.Starred):
                    expr = _call(H_UNPACK, [expr.value])
                default = _thunk(self._rewrite(expr, extra, classdict))
            if isinstance(p, ast.TypeVar):
                bound: ast.expr = ast.Constant(value=None)
                constraints: ast.expr = ast.Constant(value=None)
                if p.bound is not None:
                    if isinstance(p.bound, ast.Tuple):
                        constraints = _thunk(self._rewrite(p.bound, extra, classdict))
                    else:
                        bound = _thunk(self._rewrite(p.bound, extra, classdict))
                value = _call(H_TYPEVAR, [ast.Constant(value=p.name), bound, constraints, default])
            elif isinstance(p, ast.TypeVarTuple):
                value = _call(H_TYPEVARTUPLE, [ast.Constant(value=p.name), default])
            else:
                value = _call(H_PARAMSPEC, [ast.Constant(value=p.name), default])
            out.append(ast.copy_location(ast.Assign(targets=target, value=value), p))
        return out

    def _wrapper_scope(self, param_names: list[str], arg_names: list[str]) -> _Scope:
        scope = _Scope("function", set(param_names) | set(arg_names) | {ORIG_BASES})
        return scope

    # ---- generic functions ---------------------------------------------------------------------
    def _generic_function(self, node):
        in_class = self.scope.kind == "class"
        wrapper = self._fresh()
        names = [p.name for p in node.type_params]
        arg_names: list[str] = []
        call_args: list[ast.expr] = []
        if in_class:
            arg_names.append(CLASSDICT)
            call_args.append(_call(CLASSDICT_INTRINSIC, []))
        a = node.args
        for i in range(len(a.defaults)):
            arg_names.append("__pyjvm_d" + str(len(call_args)) + "__")
            call_args.append(a.defaults[i])
            a.defaults[i] = _name(arg_names[-1])
        for i in range(len(a.kw_defaults)):
            if a.kw_defaults[i] is not None:
                arg_names.append("__pyjvm_d" + str(len(call_args)) + "__")
                call_args.append(a.kw_defaults[i])
                a.kw_defaults[i] = _name(arg_names[-1])
        decorators = [self.visit(d) for d in node.decorator_list]
        node.decorator_list = []
        extra = set(names) | set(arg_names)
        body: list[ast.stmt] = list(self._declaration(node.name))
        body.extend(self._type_param_stmts(node.type_params, extra, in_class))
        if in_class and not self.future:
            self._classdict_annotations(node, extra)
        node.type_params = []
        wrapper_scope = self._wrapper_scope(names, arg_names)
        self.scopes.append(wrapper_scope)
        self._plain_function(node)
        self.scopes.pop()
        body.append(node)
        record = ast.Assign(targets=[ast.Attribute(value=_name(node.name), attr="__type_params__", ctx=ast.Store())],
                            value=_tuple([_name(n) for n in names]))
        body.append(ast.copy_location(record, node))
        body.append(ast.copy_location(ast.Return(value=_name(node.name)), node))
        function = ast.FunctionDef(name=wrapper, args=_params(arg_names), body=body, decorator_list=[], returns=None,
                                   type_comment=None, type_params=[])
        value: ast.expr = _call(wrapper, call_args)
        for decorator in reversed(decorators):
            value = ast.Call(func=decorator, args=[value], keywords=[])
        store = ast.Assign(targets=[_name(node.name, ast.Store())], value=value)
        return [ast.copy_location(function, node), ast.copy_location(store, node)]

    def _classdict_annotations(self, node, extra: set[str]) -> None:
        """Annotations of a generic method are looked up in the class namespace first (see module docstring)."""
        a = node.args
        for arg in a.posonlyargs + a.args + a.kwonlyargs + [a.vararg, a.kwarg]:
            if arg is not None and arg.annotation is not None:
                arg.annotation = _Names(True, None).visit(arg.annotation)
        if node.returns is not None:
            node.returns = _Names(True, None).visit(node.returns)

    def visit_FunctionDef(self, node):
        if getattr(node, "type_params", None):
            return self._generic_function(node)
        return self._plain_function(node)

    visit_AsyncFunctionDef = visit_FunctionDef

    # ---- generic classes -----------------------------------------------------------------------
    def _generic_class(self, node: ast.ClassDef):
        in_class = self.scope.kind == "class"
        wrapper = self._fresh()
        names = [p.name for p in node.type_params]
        arg_names: list[str] = []
        call_args: list[ast.expr] = []
        if in_class:
            arg_names.append(CLASSDICT)
            call_args.append(_call(CLASSDICT_INTRINSIC, []))
        decorators = [self.visit(d) for d in node.decorator_list]
        node.decorator_list = []
        extra = set(names) | set(arg_names)
        body: list[ast.stmt] = list(self._declaration(node.name))
        body.extend(self._type_param_stmts(node.type_params, extra, in_class))
        generic = _call(H_GENERIC, [_tuple([_name(n) for n in names])])
        bases = [self._rewrite_base(b, extra, in_class) for b in node.bases] + [generic]
        body.append(ast.copy_location(ast.Assign(targets=[_name(ORIG_BASES, ast.Store())], value=_tuple(bases)), node))
        node.bases = [ast.Starred(value=_call(H_BASES, [_name(ORIG_BASES)]), ctx=ast.Load())]
        node.keywords = [ast.keyword(arg=k.arg, value=self._rewrite_base(k.value, extra, in_class)) for k in node.keywords]
        params = ast.Tuple(elts=[_name(n) for n in names], ctx=ast.Load())
        prologue = [
            ast.Assign(targets=[_name("__type_params__", ast.Store())], value=params),
            ast.Assign(targets=[_name("__orig_bases__", ast.Store())], value=_name(ORIG_BASES)),
            ast.Assign(targets=[_name("__parameters__", ast.Store())], value=_tuple([_name(n) for n in names])),
        ]
        node.type_params = []
        self.scopes.append(self._wrapper_scope(names, arg_names))
        self._plain_class(node)
        self.scopes.pop()
        doc = 0
        first = node.body[0]
        if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
            doc = 1
        node.body = node.body[:doc] + [ast.copy_location(p, node) for p in prologue] + node.body[doc:]
        body.append(node)
        body.append(ast.copy_location(ast.Return(value=_name(node.name)), node))
        function = ast.FunctionDef(name=wrapper, args=_params(arg_names), body=body, decorator_list=[], returns=None,
                                   type_comment=None, type_params=[])
        value: ast.expr = _call(wrapper, call_args)
        for decorator in reversed(decorators):
            value = ast.Call(func=decorator, args=[value], keywords=[])
        store = ast.Assign(targets=[_name(node.name, ast.Store())], value=value)
        return [ast.copy_location(function, node), ast.copy_location(store, node)]

    def _rewrite_base(self, expr: ast.expr, extra: set[str], classdict: bool) -> ast.expr:
        # evaluated directly in the type-parameter scope: the parameters themselves are locals there, so only
        # other names consult the class namespace
        if not classdict:
            return expr
        walker = _Names(True, None)
        walker.bound.append(set(extra))
        return walker.visit(expr)

    def visit_ClassDef(self, node: ast.ClassDef):
        if getattr(node, "type_params", None):
            return self._generic_class(node)
        node.decorator_list = [self.visit(d) for d in node.decorator_list]
        return self._plain_class(node)

    # ---- type aliases --------------------------------------------------------------------------
    def visit_TypeAlias(self, node: ast.TypeAlias):
        in_class = self.scope.kind == "class"
        target = node.name.id
        names = [p.name for p in node.type_params]
        if not names and not in_class:
            value = _call(H_ALIAS, [ast.Constant(value=target), _tuple([]), _thunk(self._rewrite(node.value, set(), False))])
            return ast.copy_location(ast.Assign(targets=[_name(target, ast.Store())], value=value), node)
        wrapper = self._fresh()
        arg_names: list[str] = []
        call_args: list[ast.expr] = []
        if in_class:
            arg_names.append(CLASSDICT)
            call_args.append(_call(CLASSDICT_INTRINSIC, []))
        extra = set(names) | set(arg_names)
        body = list(self._type_param_stmts(node.type_params, extra, in_class))
        lazy = _thunk(self._rewrite(node.value, extra, in_class))
        alias = _call(H_ALIAS, [ast.Constant(value=target), _tuple([_name(n) for n in names]), lazy])
        body.append(ast.copy_location(ast.Return(value=alias), node))
        function = ast.FunctionDef(name=wrapper, args=_params(arg_names), body=body, decorator_list=[], returns=None,
                                   type_comment=None, type_params=[])
        store = ast.Assign(targets=[_name(target, ast.Store())], value=_call(wrapper, call_args))
        return [ast.copy_location(function, node), ast.copy_location(store, node)]


def lower_type_params(tree: ast.Module, dynamic: bool = False, module_name: str = "__main__") -> ast.Module:
    if not needs_lowering(tree):
        return tree
    tree = _Lowering(has_future_annotations(tree), dynamic, module_name).visit(tree)
    ast.fix_missing_locations(tree)
    return tree
