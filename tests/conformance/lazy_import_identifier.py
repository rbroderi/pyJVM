# SPDX-License-Identifier: GPL-3.0-or-later
# pyjvm: requires-python < 3.15
# `lazy` is an ordinary identifier before 3.15; `lazy import` is not yet syntax.
import sys

lazy = 1
print(lazy + 1)


class box:
    x = 5


lazy = box
print(lazy.x)
lazy = len
print(lazy([1, 2, 3]))


def f(lazy, import_=2):
    return lazy * import_


print(f(4))
lazy = [1, 2]
lazy[0] = 9
print(lazy)

for src in ('lazy import os\n', 'lazy from os import path\n'):
    try:
        compile(src, '<s>', 'exec')
        print('compiled', repr(src))
    except SyntaxError as e:
        print(repr(src), 'SyntaxError')

print(hasattr(sys, 'set_lazy_imports'), hasattr(sys, 'lazy_modules'))
import types
print(hasattr(types, 'LazyImportType'))
