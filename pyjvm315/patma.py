# SPDX-License-Identifier: GPL-3.0-or-later
"""Lower structural pattern matching (PEP 634) to plain statements.

``match`` statements are rewritten right after parsing into an ``if/elif`` chain whose
tests are expressions: captures are walrus assignments, sub-subjects are bound to
``$pm<n>`` temporaries, and the checks that need runtime knowledge go through
``__pm_*`` helper calls that the compiler maps onto ``pyjvm315.runtime.PatternMatch``.
Compile-time binding errors CPython reports (inconsistent alternatives, unreachable
irrefutable patterns, duplicate keys/attributes/captures) are raised here as SyntaxError.
"""
from __future__ import annotations

import ast

HELPERS = {
    "isSeq": 1, "isMap": 1, "get": 2, "ok": 1, "rest": 2, "isClass": 2, "pos": 4, "attr": 2, "check": 3, "keys": 1,
}
PREFIX = "__pm_"


def _load(name: str) -> ast.Name:
    return ast.Name(id=name, ctx=ast.Load())


def _store(name: str) -> ast.Name:
    return ast.Name(id=name, ctx=ast.Store())


def _call(helper: str, *args: ast.expr) -> ast.Call:
    assert HELPERS[helper] == len(args), helper
    return ast.Call(func=_load(PREFIX + helper), args=list(args), keywords=[])


def _walrus(name: str, value: ast.expr) -> ast.NamedExpr:
    return ast.NamedExpr(target=_store(name), value=value)


def _then(first: ast.expr, second: ast.expr) -> ast.expr:
    """Evaluate ``first`` (for its side effect), then yield ``second``."""
    return ast.Subscript(value=ast.Tuple(elts=[first, second], ctx=ast.Load()),
                         slice=ast.Constant(value=1), ctx=ast.Load())


def _and(parts: list[ast.expr]) -> ast.expr:
    return parts[0] if len(parts) == 1 else ast.BoolOp(op=ast.And(), values=parts)


def _is_wildcard(p: ast.pattern) -> bool:
    return isinstance(p, ast.MatchAs) and p.pattern is None and p.name is None


def _irrefutable(p: ast.pattern) -> bool:
    if isinstance(p, ast.MatchAs):
        return p.pattern is None or _irrefutable(p.pattern)
    if isinstance(p, ast.MatchOr):
        return any(_irrefutable(a) for a in p.patterns)
    return False


def _unreachable_message(p: ast.pattern) -> str:
    while isinstance(p, ast.MatchOr):
        p = next(a for a in p.patterns if _irrefutable(a))
    while isinstance(p, ast.MatchAs) and p.pattern is not None:
        p = p.pattern
    if isinstance(p, ast.MatchAs) and p.name:
        return f"name capture {p.name!r} makes remaining patterns unreachable"
    return "wildcard makes remaining patterns unreachable"


def _error(message: str, node: ast.AST) -> SyntaxError:
    err = SyntaxError(message)
    err.lineno = getattr(node, "lineno", None)
    return err


def bound_names(p: ast.pattern) -> list[str]:
    """Names a pattern binds, validating binding rules along the way."""
    names: list[str] = []
    if isinstance(p, ast.MatchAs):
        if p.pattern is not None:
            names += bound_names(p.pattern)
        if p.name:
            names.append(p.name)
    elif isinstance(p, ast.MatchStar):
        if p.name:
            names.append(p.name)
    elif isinstance(p, ast.MatchSequence):
        for sub in p.patterns:
            names += bound_names(sub)
    elif isinstance(p, ast.MatchMapping):
        seen: list[object] = []
        for key in p.keys:
            if isinstance(key, ast.Constant):
                if key.value in seen and any(type(key.value) is type(k) for k in seen if k == key.value):
                    raise _error(f"mapping pattern checks duplicate key ({key.value!r})", p)
                seen.append(key.value)
        for sub in p.patterns:
            names += bound_names(sub)
        if p.rest:
            names.append(p.rest)
    elif isinstance(p, ast.MatchClass):
        if len(set(p.kwd_attrs)) != len(p.kwd_attrs):
            dup = next(a for a in p.kwd_attrs if p.kwd_attrs.count(a) > 1)
            raise _error(f"attribute name repeated in class pattern: {dup}", p)
        for sub in [*p.patterns, *p.kwd_patterns]:
            names += bound_names(sub)
    elif isinstance(p, ast.MatchOr):
        first: list[str] | None = None
        for i, alt in enumerate(p.patterns):
            alt_names = bound_names(alt)
            if first is None:
                first = alt_names
            elif set(alt_names) != set(first):
                raise _error("alternative patterns bind different names", p)
            if i < len(p.patterns) - 1 and _irrefutable(alt):
                raise _error(_unreachable_message(alt), alt)
        names += first or []
    for name in names:
        if names.count(name) > 1:
            raise _error(f"multiple assignments to name {name!r} in pattern", p)
    return names


class _Lowering(ast.NodeTransformer):
    def __init__(self) -> None:
        self.counter = 0
        # name -> staging temp for the case being lowered. CPython only binds captures once the
        # whole pattern has matched (a failed match leaves earlier captures unbound), so every
        # capture writes its temp and one commit step copies them to the real names.
        self.staged: dict[str, str] = {}

    def capture(self, name: str) -> str:
        if name not in self.staged:
            self.staged[name] = self.fresh()
        return self.staged[name]

    def fresh(self) -> str:
        self.counter += 1
        return f"$pm{self.counter}"

    def _bind(self, name: str, subject: str) -> ast.expr:
        return _then(_walrus(self.capture(name), _load(subject)), ast.Constant(value=True))

    def _sub(self, pattern: ast.pattern, value: ast.expr, *, require_present: bool = False) -> ast.expr | None:
        """Bind ``value`` to a temp and match ``pattern`` against it (None when nothing to test)."""
        wildcard = _is_wildcard(pattern)
        if wildcard and not require_present:
            return None
        tmp = self.fresh()
        tests: list[ast.expr] = []
        if require_present:
            tests.append(_call("ok", _load(tmp)))
        if not wildcard:
            tests.append(self.gen(pattern, tmp))
        return _then(_walrus(tmp, value), _and(tests))

    def gen(self, p: ast.pattern, s: str) -> ast.expr:
        subj = lambda: _load(s)
        if isinstance(p, ast.MatchValue):
            return ast.Compare(left=subj(), ops=[ast.Eq()], comparators=[p.value])
        if isinstance(p, ast.MatchSingleton):
            return ast.Compare(left=subj(), ops=[ast.Is()], comparators=[ast.Constant(value=p.value)])
        if isinstance(p, ast.MatchAs):
            parts: list[ast.expr] = []
            if p.pattern is not None:
                parts.append(self.gen(p.pattern, s))
            if p.name:
                parts.append(self._bind(p.name, s))
            return _and(parts) if parts else ast.Constant(value=True)
        if isinstance(p, ast.MatchOr):
            return ast.BoolOp(op=ast.Or(), values=[self.gen(a, s) for a in p.patterns])
        if isinstance(p, ast.MatchSequence):
            return self._sequence(p, s)
        if isinstance(p, ast.MatchMapping):
            parts = [_call("isMap", subj())]
            if len(p.keys) > 1 and not all(isinstance(k, ast.Constant) for k in p.keys):
                parts.append(_call("keys", ast.List(elts=list(p.keys), ctx=ast.Load())))
            for key, sub in zip(p.keys, p.patterns):
                parts.append(self._sub(sub, _call("get", subj(), key), require_present=True))
            if p.rest:
                parts.append(_then(_walrus(self.capture(p.rest), _call("rest", subj(), ast.List(elts=list(p.keys), ctx=ast.Load()))),
                                   ast.Constant(value=True)))
            return _and(parts)
        if isinstance(p, ast.MatchClass):
            cls = self.fresh()
            parts = [_call("isClass", subj(), _load(cls))]
            n = len(p.patterns)
            if n and (n > 1 or p.kwd_attrs):
                names = ast.List(elts=[ast.Constant(value=a) for a in p.kwd_attrs], ctx=ast.Load())
                parts.append(_call("check", _load(cls), ast.Constant(value=n), names))
            for i, sub in enumerate(p.patterns):
                parts.append(self._sub(sub, _call("pos", subj(), _load(cls), ast.Constant(value=i), ast.Constant(value=n)),
                                       require_present=True))
            for attr, sub in zip(p.kwd_attrs, p.kwd_patterns):
                parts.append(self._sub(sub, _call("attr", subj(), ast.Constant(value=attr)), require_present=True))
            return _then(_walrus(cls, p.cls), _and(parts))
        raise _error(f"unsupported pattern {type(p).__name__}", p)

    def _sequence(self, p: ast.MatchSequence, s: str) -> ast.expr:
        n = len(p.patterns)
        star = next((i for i, q in enumerate(p.patterns) if isinstance(q, ast.MatchStar)), None)
        length = self.fresh()
        measured = _walrus(length, ast.Call(func=_load("len"), args=[_load(s)], keywords=[]))
        parts: list[ast.expr] = [_call("isSeq", _load(s)),
                                 ast.Compare(left=measured, ops=[ast.Eq() if star is None else ast.GtE()],
                                             comparators=[ast.Constant(value=n if star is None else n - 1)])]
        for i, sub in enumerate(p.patterns):
            if isinstance(sub, ast.MatchStar):
                if sub.name:
                    after = n - 1 - i
                    upper = None if after == 0 else ast.BinOp(left=_load(length), op=ast.Sub(),
                                                              right=ast.Constant(value=after))
                    chunk = ast.Subscript(value=_load(s), ctx=ast.Load(),
                                          slice=ast.Slice(lower=ast.Constant(value=i), upper=upper))
                    parts.append(_then(_walrus(self.capture(sub.name), ast.Call(func=_load("list"), args=[chunk], keywords=[])),
                                       ast.Constant(value=True)))
                continue
            if star is not None and i > star:
                index: ast.expr = ast.BinOp(left=_load(length), op=ast.Sub(), right=ast.Constant(value=n - i))
            else:
                index = ast.Constant(value=i)
            test = self._sub(sub, ast.Subscript(value=_load(s), slice=index, ctx=ast.Load()))
            if test is not None:
                parts.append(test)
        return _and(parts)

    def visit_Match(self, node: ast.Match):
        node = self.generic_visit(node)  # lower matches nested in case bodies first
        for i, case in enumerate(node.cases):
            bound_names(case.pattern)
            if i < len(node.cases) - 1 and case.guard is None and _irrefutable(case.pattern):
                raise _error(_unreachable_message(case.pattern), case.pattern)
        subject = self.fresh()
        chain: list[ast.stmt] = []
        for case in reversed(node.cases):
            self.staged = {}
            test = self.gen(case.pattern, subject)
            if self.staged:
                commit = ast.Tuple(elts=[_walrus(name, _load(tmp)) for name, tmp in self.staged.items()],
                                   ctx=ast.Load())
                test = _and([test, _then(commit, ast.Constant(value=True))])
            if case.guard is not None:
                test = _and([test, case.guard])
            chain = [ast.If(test=test, body=case.body, orelse=chain)]
        out: list[ast.stmt] = [ast.Assign(targets=[_store(subject)], value=node.subject), *chain]
        for stmt in out:
            ast.copy_location(stmt, node)
        return out


def lower_match(tree: ast.Module) -> ast.Module:
    if not any(isinstance(n, ast.Match) for n in ast.walk(tree)):
        return tree
    tree = _Lowering().visit(tree)
    ast.fix_missing_locations(tree)
    return tree
