# SPDX-License-Identifier: GPL-3.0-or-later
# pyjvm: requires-python >= 3.15
import os
import sys
import types

sys.dont_write_bytecode = True

root = os.path.join('/tmp', 'pyjvm_lazy_%d' % os.getpid())
files = {
    'lzpkg/__init__.py': 'print("lzpkg init")\n',
    'lzpkg/leaf.py': 'print("leaf loaded")\nV = 7\n',
    'lzpkg/user.py': (
        'import sys\n'
        'lazy from . import leaf\n'
        'lazy from .leaf import V as VV\n'
        'lazy import lzpkg.leaf as ll\n'
        'g = globals()\n'
        'def probe():\n'
        '    return VV\n'
    ),
    'lzpkg/compat.py': (
        "__lazy_modules__ = ['lzpkg.leaf2', 'lzpkg.other']\n"
        'import lzpkg.leaf2\n'
        'from lzpkg.other import W\n'
        'import lzpkg.leaf3\n'
        'try:\n'
        '    import lzpkg.leaf4\n'
        'except ImportError:\n'
        '    pass\n'
        'def f():\n'
        '    import lzpkg.leaf5\n'
        '    return lzpkg.leaf5\n'
    ),
    'lzpkg/leaf2.py': 'print("leaf2 loaded")\nV = 2\n',
    'lzpkg/leaf3.py': 'print("leaf3 loaded")\n',
    'lzpkg/leaf4.py': 'print("leaf4 loaded")\n',
    'lzpkg/leaf5.py': 'print("leaf5 loaded")\n',
    'lzpkg/other.py': 'print("other loaded")\nW = 3\n',
    'lzpkg/broken.py': 'raise RuntimeError("boom")\n',
    'lzpkg/dyn.py': 'print("dyn loaded")\nX = 11\n',
    'lzpkg/allmode.py': (
        'import sys\n'
        'sys.set_lazy_imports("all")\n'
        'import lzpkg.am1\n'
        'from lzpkg.am2 import Y\n'
        'import lzpkg.am3 as am3\n'
        'sys.set_lazy_imports("normal")\n'
        'import lzpkg.am4\n'
        'state = [type(globals()[n]).__name__ for n in ("lzpkg", "Y", "am3")]\n'
    ),
    'lzpkg/am1.py': 'print("am1 loaded")\n',
    'lzpkg/am2.py': 'print("am2 loaded")\nY = 5\n',
    'lzpkg/am3.py': 'print("am3 loaded")\n',
    'lzpkg/am4.py': 'print("am4 loaded")\n',
}
os.makedirs(os.path.join(root, 'lzpkg'))
for rel, text in files.items():
    with open(os.path.join(root, rel), 'w') as f:
        f.write(text)
sys.path.insert(0, root)


def kind(value):
    return type(value).__name__


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, str(e).split(' (')[0])


def present(*names):
    return [(n, n in sys.modules, n in sys.lazy_modules) for n in names]


g = globals()
lazy import lzpkg.dyn as dyn
lazy from lzpkg.dyn import X, nosuch
lazy import lzpkg.dyn
print(kind(g['dyn']), repr(g['dyn']), repr(g['X']), repr(g['lzpkg']))
print(isinstance(g['dyn'], types.LazyImportType), types.LazyImportType.__name__, types.LazyImportType is type(g['dyn']))
print(present('lzpkg.dyn', 'lzpkg'))
print(sorted(n for n in sys.lazy_modules if n.startswith('lzpkg')))
print(dyn.X)
print(present('lzpkg.dyn'), kind(g['dyn']), kind(g['X']), sorted(n for n in sys.lazy_modules if n.startswith('lzpkg')))
print(X, kind(g['X']))
t(lambda: nosuch)
print(kind(g['nosuch']))

# a lazy object reached through a dict is not loaded; loading the name does it
print(kind(g['lzpkg']))
print(kind(lzpkg), kind(g['lzpkg']))

# resolve() returns the object but leaves the binding alone
lazy import lzpkg.am1 as am1
print(kind(g['am1']), g['am1'].resolve() is sys.modules['lzpkg.am1'], kind(g['am1']))
print(kind(am1), kind(g['am1']))
t(types.LazyImportType)
t(types.LazyImportType.resolve, 5)

# imported modules: the lazy bindings of a module run through its own namespace
import lzpkg.user as user
print(present('lzpkg.leaf'))
print(kind(user.g['leaf']), kind(user.g['VV']), kind(user.g['ll']))
print(repr(user.g['leaf']), repr(user.g['VV']), repr(user.g['ll']))
print(user.probe())
print(present('lzpkg.leaf'), kind(user.g['VV']), kind(user.g['leaf']))
print(user.leaf.V, kind(user.g['leaf']), kind(user.g['ll']))
print(user.ll.V, kind(user.g['ll']))
from lzpkg.user import leaf as leaf_again
print(leaf_again is user.leaf)

# __lazy_modules__ makes plain module-level imports lazy
import lzpkg.compat as compat
print(present('lzpkg.leaf2', 'lzpkg.other', 'lzpkg.leaf3', 'lzpkg.leaf4'))
print(kind(compat.__dict__['lzpkg']), kind(compat.__dict__['W']))
print(compat.W, present('lzpkg.other'))
print(compat.f().__name__, present('lzpkg.leaf5'))

# "all" mode and sys.set_lazy_imports
t(sys.set_lazy_imports, 'bad')
t(sys.set_lazy_imports, 1)
t(sys.set_lazy_imports)
t(sys.set_lazy_imports, 'none')
print(sys.get_lazy_imports())
import lzpkg.allmode as am
print(am.state, present('lzpkg.am1', 'lzpkg.am2', 'lzpkg.am3', 'lzpkg.am4'))
print(sys.get_lazy_imports())
print(am.Y, present('lzpkg.am2'))

# the filter
calls = []


def flt(importer, name, fromlist):
    calls.append((importer, name, fromlist))
    return name != 'lzpkg.leaf3'


print(sys.get_lazy_imports_filter())
t(sys.set_lazy_imports_filter, 5)
t(sys.set_lazy_imports_filter)
sys.set_lazy_imports_filter(flt)
print(sys.get_lazy_imports_filter() is flt)
lazy import lzpkg.leaf3 as f3
lazy from lzpkg.leaf2 import V as v2
lazy import lzpkg.leaf4 as f4
print(calls)
print(present('lzpkg.leaf3', 'lzpkg.leaf2', 'lzpkg.leaf4'))
print(kind(g['v2']), kind(g['f4']), kind(g['f3']))
sys.set_lazy_imports_filter(None)
print(sys.get_lazy_imports_filter())

# errors on first use keep the binding lazy and chain an ImportError
lazy import nosuchtopmodule as mm
lazy from lzpkg.dyn import absent_name
lazy import lzpkg.broken as br
lazy import lzpkg.missing_module as mm2
for name in ('mm', 'absent_name', 'br', 'mm2'):
    for attempt in (1, 2):
        try:
            eval(name)
        except BaseException as e:
            c = e.__cause__
            print(name, attempt, type(e).__name__, str(e).split(' (')[0], '|', type(c).__name__, str(c).split(' (')[0], '|', kind(g[name]))

# relative imports resolve against the importing module and fail when the statement runs
code = compile('lazy from . import x\n', 'rel.py', 'exec')
try:
    exec(code, {'__name__': 'rel', '__package__': ''})
except BaseException as e:
    print(type(e).__name__, e)

# scope rules
for src in ('def f():\n    lazy import os\n', 'class C:\n    lazy import os\n',
            'try:\n    lazy import os\nexcept ImportError:\n    pass\n', 'try:\n    pass\nfinally:\n    lazy import os\n',
            'async def f():\n    lazy from os import path\n', 'lazy from os import *\n',
            'lazy from __future__ import annotations\n', 'lazy import\n', 'if 1:\n    lazy import os\n',
            'with open:\n    lazy import os, sys\n', 'def f():\n    class C:\n        lazy import os\n'):
    try:
        compile(src, '<s>', 'exec')
        print('ok', repr(src))
    except SyntaxError as e:
        print(repr(src), '|', e.msg, e.lineno, e.offset)

# cleanup
for rel in sorted(files, reverse=True):
    os.remove(os.path.join(root, rel))
os.rmdir(os.path.join(root, 'lzpkg'))
os.rmdir(root)
