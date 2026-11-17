# SPDX-License-Identifier: GPL-3.0-or-later
# The builtin _operator module: surface, docs/signatures, argument errors, getters, reprs, pickling.
import _operator as o
import array
import copy
import operator
import pickle
import sys


def t(f, *a, **k):
    try:
        r = f(*a, **k)
        print('ok', repr(r))
    except BaseException as e:
        print(type(e).__name__, e)


def te(f, *a, **k):
    """Like t, but only the exception type: these messages come from the generic runtime, not from _operator."""
    try:
        print('ok', repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__)


# ---- surface
print(o.__name__, o.__doc__)
names = sorted(n for n in dir(o) if not n.startswith('__'))
print(names)
print(o.__doc__ == operator.__doc__)
for n in names:
    f = getattr(o, n)
    if isinstance(f, type):
        print(n, f.__module__, f.__name__, f.__qualname__, f.__text_signature__, repr(f.__doc__))
        continue
    print(n, type(f).__name__, f.__module__, f.__name__, f.__qualname__, f.__text_signature__, f.__self__ is o, repr(f.__doc__))
    print('  ', repr(f))
    print('  ', getattr(operator, n, None) is f)
print(sys.modules['_operator'] is o, '_operator' in sys.builtin_module_names)
print([n for n in operator.__all__ if not hasattr(operator, n)])
for n in operator.__all__:
    if n in names:
        assert getattr(operator, n) is getattr(o, n), n
print(operator.__lt__ is o.lt, operator.__iadd__ is o.iadd, operator.__getitem__ is o.getitem)
for T in (o.itemgetter, o.attrgetter, o.methodcaller):
    print(T.__mro__, callable(T))

# ---- argument counts
t(o.add); t(o.add, 1); t(o.add, 1, 2, 3); t(o.add, a=1, b=2); t(o.neg); t(o.neg, 1, 2); t(o.neg, a=1)
t(o.setitem, 1, 2); t(o.setitem, 1, 2, 3, 4)
t(o.length_hint); t(o.length_hint, 1, 2, 3); t(o.length_hint, 1, default=2)
t(o.call); t(o.call, 1); t(o.call, len, [1]); t(o.call, divmod, 7, 2)
t(o.call, dict, a=1); t(o.call, lambda *a, **k: (a, k), 1, 2, x=3)
t(o.truth); t(o.truth, 1, 2)
t(o.contains); t(o.countOf, 1); t(o.indexOf); t(o.getitem, 1); t(o.delitem); t(o.index); t(o.not_); t(o.is_, 1)

# ---- _compare_digest
t(o._compare_digest); t(o._compare_digest, 'a'); t(o._compare_digest, a='a', b='a')
t(o._compare_digest, 'a', 'a'); t(o._compare_digest, b'a', b'a'); t(o._compare_digest, 'a', b'a')
t(o._compare_digest, '\xe9', '\xe9'); t(o._compare_digest, '\xe9', 'a'); t(o._compare_digest, 'a', '\xe9'); t(o._compare_digest, 1, 1)
t(o._compare_digest, bytearray(b'ab'), b'ab'); t(o._compare_digest, memoryview(b'ab'), b'ab')
t(o._compare_digest, b'ab', b'abc'); t(o._compare_digest, b'abc', b'ab'); t(o._compare_digest, b'ab', b'ac')
t(o._compare_digest, [1], [1]); t(o._compare_digest, b'a', 'a'); t(o._compare_digest, 'a', 1)
t(o._compare_digest, 1, 'a'); t(o._compare_digest, None, b'a'); t(o._compare_digest, b'a', None); t(o._compare_digest, None, None)
t(o._compare_digest, memoryview(bytes(range(6))).cast('B', (2, 3)), b'a')
t(o._compare_digest, memoryview(b'ab')[::2], b'a')
t(o._compare_digest, memoryview(b'abcd')[::2], b'ac')
t(o._compare_digest, array.array('i', [1]), array.array('i', [1]))
t(o._compare_digest, array.array('i', [1]), b'\x01\x00\x00\x00')
t(o._compare_digest, '', ''); t(o._compare_digest, b'', b'')
t(o._compare_digest, b'x' * 1000, b'x' * 1000); t(o._compare_digest, b'x' * 1000, b'x' * 999 + b'y')


class SS(str):
    pass


t(o._compare_digest, SS('a'), 'a')

# ---- operations
te(o.add, 1, 'a'); te(o.iadd, 1, 'a'); te(o.neg, 'a'); te(o.abs, 'a'); te(o.inv, 1.5); te(o.pos, 'a')
t(o.index, 1.5); t(o.index, True); t(o.index, 'a'); t(o.index, 10 ** 30); t(o.index, -3)
t(o.concat, 1, 2); t(o.concat, [1], [2]); t(o.concat, 'a', 'b'); te(o.concat, [1], (2,)); t(o.concat, {}, {}); t(o.concat, (1,), (2,))
t(o.iconcat, 1, 2); t(o.iconcat, [1], (2,)); t(o.iconcat, (1,), (2,)); t(o.iconcat, 'a', 'b'); t(o.iconcat, {}, {})
te(o.contains, 1, 1); t(o.contains, [1], 1); t(o.contains, 'abc', 'b'); t(o.contains, {1: 2}, 1)
t(o.countOf, [1, 1, 2], 1); t(o.countOf, 1, 1); t(o.countOf, 'abca', 'a'); t(o.countOf, iter([1, 1]), 1)
t(o.indexOf, [1, 2], 2); t(o.indexOf, [1, 2], 3); t(o.indexOf, 1, 1); t(o.indexOf, (5, 6, 7), 7)
nan = float('nan')
t(o.countOf, [nan, nan], nan); t(o.indexOf, [1, nan], nan); t(o.countOf, [1, 1.0, True], 1)
te(o.getitem, [1], 5); t(o.getitem, 1, 1); t(o.getitem, {}, 'k'); t(o.getitem, 'abc', 1); t(o.getitem, [1, 2, 3], slice(1, None))
te(o.setitem, [1], 5, 1); te(o.setitem, 1, 1, 1); te(o.delitem, [1], 5); te(o.delitem, 1, 1); te(o.delitem, (1,), 0)
t(o.setitem, [1, 2], slice(0, 1), [5]); t(o.delitem, [1, 2, 3], slice(0, 2))
d = {}; print(o.setitem(d, 'k', 1), d, o.delitem(d, 'k'), d)
t(o.length_hint, [1, 2]); t(o.length_hint, 5); t(o.length_hint, 5, 3)
t(o.length_hint, 5, 'x'); t(o.length_hint, object(), 7); t(o.length_hint, object()); t(o.length_hint, 5, 2.5); t(o.length_hint, 5, None)
t(o.length_hint, 5, True); t(o.length_hint, 5, 2 ** 70); t(o.length_hint, {1: 2, 3: 4}); t(o.length_hint, range(7)); t(o.length_hint, 'abc')
t(o.length_hint, (x for x in ()), 9)


class LH:
    def __length_hint__(self): return NotImplemented


class LH2:
    def __length_hint__(self): return -1


class LH3:
    def __length_hint__(self): return 'a'


class LH4:
    def __length_hint__(self): raise ValueError('boo')


class LH5:
    def __length_hint__(self): return 3
    def __len__(self): return 9


class LH6:
    def __len__(self): return -1


class LH7:
    def __len__(self): raise TypeError('x')


class LH8:
    def __length_hint__(self): return 2 ** 70


class LH9:
    def __length_hint__(self): return True


for cls in (LH, LH2, LH3, LH4, LH5, LH6, LH7, LH8, LH9):
    t(o.length_hint, cls(), 4)
t(o.truth, []); t(o.not_, []); t(o.is_, 1, 1); t(o.is_not, 1, 1); t(o.truth, 'x'); t(o.not_, 0)
x = object(); t(o.is_, x, x); t(o.is_not, x, x); t(o.is_, None, None); t(o.is_not, None, 1)
if hasattr(o, 'is_none'):
    t(o.is_none, None); t(o.is_none, 0); t(o.is_not_none, None); t(o.is_not_none, 0)
else:
    print('no is_none')
t(o.pow, 2, 3); t(o.pow, 2, 3, 5); te(o.mod, 1, 0); te(o.truediv, 1, 0); te(o.floordiv, 1, 0); t(o.matmul, 1, 2); t(o.imatmul, 1, 2)
t(o.eq, 1, 1.0); t(o.lt, 1, 'a'); t(o.lt, 1, 2); t(o.ge, 'a', 'a'); t(o.ne, 1, 2); t(o.le, 2, 1); t(o.gt, 2, 1)
t(o.add, 1, 2); t(o.sub, 5, 3); t(o.mul, 'ab', 2); t(o.truediv, 7, 2); t(o.floordiv, 7, 2); t(o.mod, 7, 2); t(o.lshift, 1, 4)
t(o.rshift, 256, 4); t(o.and_, 6, 3); t(o.or_, 6, 3); t(o.xor, 6, 3); t(o.neg, 5); t(o.pos, -5); t(o.abs, -5); t(o.inv, 5); t(o.invert, 5)
t(o.and_, {1}, {1, 2}); t(o.or_, {1}, {2}); t(o.xor, {1, 2}, {2, 3}); t(o.sub, {1, 2}, {2}); t(o.or_, {1: 1}, {2: 2})
te(o.sub, 'a', 'b'); te(o.mul, 'a', 'b'); te(o.truediv, 'a', 1); te(o.floordiv, 'a', 1); te(o.mod, 1, 'a'); te(o.lshift, 'a', 1)
te(o.rshift, 1, 'a'); te(o.and_, 1, 'a'); te(o.or_, 1, 'a'); te(o.xor, 1, 'a'); te(o.pow, 'a', 1)

# ---- in-place
a = [1, 2]
print(o.iadd(a, [3]) is a, o.imul(a, 2) is a, o.iconcat(a, [4]) is a, a)
s = {1}
print(o.ior(s, {2}) is s, o.iand(s, {1}) is s, o.isub(s, {1}) is s, o.ixor(s, {5}) is s, s)
d = {1: 1}
print(o.ior(d, {2: 2}) is d, d)
x = 5
print(o.ifloordiv(x, 2), o.itruediv(x, 2), o.imod(x, 2), o.ilshift(x, 2), o.irshift(x, 1), o.ipow(x, 2), o.imul(x, 2), o.isub(x, 1),
      o.iand(x, 3), o.ior(x, 2), o.ixor(x, 1), o.iadd(x, 1))
te(o.iadd, 1, 'a'); te(o.isub, 1, 'a'); te(o.imul, 1.5, 'a'); te(o.itruediv, 1, 'a'); te(o.ifloordiv, 1, 'a'); te(o.imod, 1, 'a')
te(o.ipow, 1, 'a'); te(o.ilshift, 1, 'a'); te(o.irshift, 1, 'a'); te(o.iand, 1, 'a'); te(o.ior, 1, 'a'); te(o.ixor, 1, 'a'); te(o.imatmul, 1, 'a')


class IA:
    def __ifloordiv__(self, other): return 'ifloor'
    def __imod__(self, other): return 'imod'
    def __itruediv__(self, other): return 'itd'
    def __ilshift__(self, other): return 'ils'
    def __irshift__(self, other): return 'irs'
    def __isub__(self, other): return 'isub'
    def __imatmul__(self, other): return 'imm'
    def __ipow__(self, other): return 'ipow'
    def __iadd__(self, other): return 'iadd'
    def __imul__(self, other): return 'imul'
    def __iand__(self, other): return 'iand'
    def __ior__(self, other): return 'ior'
    def __ixor__(self, other): return 'ixor'


ia = IA()
for f in (o.ifloordiv, o.imod, o.itruediv, o.ilshift, o.irshift, o.isub, o.imatmul, o.ipow, o.iadd, o.imul, o.iand, o.ior, o.ixor):
    t(f, ia, 1)


class Num:
    def __init__(self, v): self.v = v
    def __add__(self, o): return Num(self.v + o.v)
    def __radd__(self, o): return 'radd'
    def __sub__(self, o): return NotImplemented
    def __eq__(self, o): return 'eq'
    def __lt__(self, o): return 'lt'
    def __repr__(self): return 'Num(%r)' % self.v
    __hash__ = None


t(o.add, Num(1), Num(2)); t(o.iadd, Num(1), Num(2)); t(o.add, 1, Num(2)); te(o.sub, Num(1), Num(2)); te(o.isub, Num(1), Num(2))
t(o.eq, Num(1), 1); t(o.lt, Num(1), 1); t(o.gt, 1, Num(1))


class Idx:
    def __index__(self): return 7


t(o.index, Idx()); t(o.length_hint, 3, Idx())


class Seq:
    def __getitem__(self, i): return i


class Map(dict):
    pass


t(o.concat, Seq(), Seq()); t(o.concat, Map(), {}); t(o.getitem, Seq(), 3); t(o.contains, Seq(), 1) if False else None


class Cont:
    def __contains__(self, x): return x == 3


t(o.contains, Cont(), 3); t(o.contains, Cont(), 4)

# ---- getters
ig, ag, mc = o.itemgetter, o.attrgetter, o.methodcaller
t(ig); t(ig, 1); t(ig, 1, 2); t(ig, a=1); t(ig, [1]); t(ag); t(ag, 1); t(ag, 'a', 1); t(ag, 'a.b'); t(ag, a=1); t(ag, 'a', b=1)
t(mc); t(mc, 1); t(mc, 'a'); t(mc, 'a', 1, x=2); t(mc, name='a'); t(mc, 'a', name=2)
t(ig(0), [5]); t(ig(0, 1), [5, 6]); t(ig('a'), {'a': 1}); t(ig(0), 1); te(ig(0), []); t(ig(0), [1], 2); t(ig(0)); t(ig(0), x=1)
t(ig(slice(0, 1)), [1, 2]); te(ig([1]), {}); te(ig(0, 1), [1]); t(ig(0), 'abc'); t(ig(-1), 'abc'); t(ig(1.5), [1, 2])
t(ig(True), [1, 2]); t(ig(None), {None: 3}); te(ig({}), {}); t(ig(0, 0, 0), [9])


class A:
    def __init__(self, **k): self.__dict__.update(k)


a = A(x=1, y=A(z=2, w=A(q=3)))
t(ag('x'), a); t(ag('y.z'), a); t(ag('x', 'y.z'), a); t(ag('y.w.q'), a); t(ag('nope'), a); t(ag('y.nope'), a)
t(ag('x.'), a); t(ag('.x'), a); t(ag('x..y'), a); t(ag(''), a); t(ag('__class__'), a); t(ag('real'), 5); t(ag('real.imag'), 5)
t(ag('x'), a, 1); t(ag('x')); t(ag('x'), obj=a); t(ag('x', 'y.z', 'x'), a); t(ag(*['x'] * 3), a)
t(ag(SS('x')), a); t(repr, ag(SS('x')))


class B:
    def m(self, *a, **k): return ('m', a, k)
    def __getattr__(self, n): return 7


t(mc('m'), B()); t(mc('m', 1, 2, k=3), B()); t(mc('nope'), A()); t(mc('x'), A(x=1)); t(mc('upper'), 'abc'); t(mc('m'), B(), 1)
t(mc('m')); t(mc('m'), obj=B()); t(mc('split', ','), 'a,b'); t(mc('split', sep=','), 'a,b'); t(mc('a.b'), A(**{'a.b': lambda: 4}))
t(mc, 1); t(mc, b'x'); t(mc, SS('upper')); t(ag, 'a', None); print(repr(mc(SS('upper'))))
t(mc('append', 5), [1]); t(mc('get', 'k', 0), {'k': 9})
lst = [3, 1, 2]
print(sorted(lst, key=ig(0) if False else o.neg), sorted(['bb', 'a', 'ccc'], key=mc('count', 'b')), sorted([A(x=2), A(x=1)], key=ag('x'))[0].x)
for g in (ig(1), ig(1, 'a'), ag('a'), ag('a.b', 'c'), mc('m'), mc('m', 1, k=2), mc('m', 'x', 25), mc('m', k=[1]), mc('m', 1, 2, a=3, b=4)):
    print(repr(g), type(g).__name__)
    print(g.__reduce__())
    t(copy.copy, g); t(copy.deepcopy, g)
    for p in range(0, 6):
        try:
            print(p, repr(pickle.loads(pickle.dumps(g, p))))
        except BaseException as e:
            print(p, type(e).__name__, e)
t(repr, ig(1, [2], 'x')); t(repr, ag('a', 'b.c')); t(repr, mc('f', 1, k='v'))


class R:
    def __repr__(self): raise ValueError('rr')


t(repr, ig(R())); t(repr, mc('f', R()))
r1 = ig(1)
print(r1 == ig(1), hash(r1) == hash(ig(1)), r1 == r1, r1 != ig(1))
print(hasattr(ig(1), '__call__'), callable(ag('a')), type(ig(1)) is ig, isinstance(mc('f'), mc), isinstance(mc('f'), ag))
print(ag('a.b.c').__reduce__()); print(ag('a', 'b').__reduce__()); print(ag('a.b.c', 'd').__reduce__())
print(mc('f', 1, b=1, a=2).__reduce__()); print(repr(mc('f', 1, b=1, a=2)))
t(ag('a..b'), a)
print(ig(2)((1, 2, 3)), ig(*range(3))('abcdef'))
