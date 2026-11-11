# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
"""AST helpers that need nothing but the ast module (usable when the compiler compiles itself)."""
from __future__ import annotations

import ast


def deepcopy_ast(node, keep=None):
    """Copy an AST. Subtrees whose id() is a key of ``keep`` are shared with the original, not copied
    (registration of nested scopes is keyed by node identity)."""
    if keep is not None and id(node) in keep:
        return keep[id(node)]
    if isinstance(node, ast.AST):
        fields = {}
        for name, value in ast.iter_fields(node):
            fields[name] = deepcopy_ast(value, keep)
        new = node.__class__(**fields)
        for attr in node._attributes:
            if hasattr(node, attr):
                setattr(new, attr, getattr(node, attr))
        for key, value in list(node.__dict__.items()):   # compiler-private marks
            if key not in fields and key not in node._attributes:
                setattr(new, key, value)
        return new
    if isinstance(node, list):
        return [deepcopy_ast(item, keep) for item in node]
    return node
