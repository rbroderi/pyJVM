import gc
import operator
import weakref


class Obj:
    def __init__(self, v=0):
        self.v = v

    def meth(self, x):
        return self.v + x

    def __repr__(self):
        return "Obj(%r)" % self.v

    def __len__(self):
        return 3

    def __getitem__(self, i):
        return ("item", i)

    def __setitem__(self, i, v):
        self.last = (i, v)

    def __delitem__(self, i):
        self.deleted = i

    def __contains__(self, i):
        return i == 2

    def __iter__(self):
        return iter([1, 2, 3])

    def __bool__(self):
        return self.v != 0

    def __int__(self):
        return 7

    def __index__(self):
        return 1

    def __float__(self):
        return 1.5

    def __add__(self, o):
        return ("add", o)

    def __radd__(self, o):
        return ("radd", o)

    def __iadd__(self, o):
        self.v += o
        return self

    def __eq__(self, o):
        return isinstance(o, Obj) and o.v == self.v

    def __lt__(self, o):
        return self.v < o.v

    __hash__ = None

    def __str__(self):
        return "str-obj"


class Callable(Obj):
    def __call__(self, *a):
        return ("called", a)


class Plain:
    def __init__(self):
        self.v = 1


def collect():
    for _ in range(5):
        gc.collect()


o = Obj(5)
p = weakref.proxy(o)
zero = Obj(0)
print(type(p).__name__, type(weakref.proxy(lambda: 1)).__name__)
print(p.v, p.meth(2), repr(p).startswith("<weakproxy at"), str(p))
cp = weakref.proxy(co := Callable())
print(type(cp).__name__, cp(1, 2))
print(len(p), p[4], 2 in p, 3 in p, list(p), bool(p), bool(weakref.proxy(zero)))
p.v = 6
print(o.v, hasattr(p, "v"))
p.extra = 1
print(o.extra)
del p.extra
print(hasattr(o, "extra"))
p[1] = 2
print(o.last)
del p[3]
print(o.deleted)
print(p + 1, 1 + p, int(p), float(p), operator.index(p))
p += 1
print(o.v, type(p).__name__)
print(p == Obj(7), p == o, p != o, p < Obj(10))
print(isinstance(p, Obj), p.__class__ is Obj)
try:
    hash(p)
except TypeError as e:
    print("TypeError", e)
print(weakref.getweakrefcount(o))

# proxy of a plain object: no __call__
pp = weakref.proxy(keep := Plain())
print(pp.v, pp + pp if False else "ok")
try:
    pp()
except TypeError as e:
    print("TypeError", type(e).__name__)

# callbacks and death
seen = []
t = Obj(1)
pt = weakref.proxy(t, seen.append)
print(pt.v)
del t
for _ in range(10):
    collect()
    if seen:
        break
print(len(seen), seen[0] is pt if seen else None)
for fn in (lambda: pt.v, lambda: str(pt), lambda: pt + 1, lambda: len(pt), lambda: bool(pt)):
    try:
        fn()
    except ReferenceError as e:
        print("ReferenceError", e)
try:
    pt.v = 1
except ReferenceError as e:
    print("ReferenceError", e)

# function proxy is callable
def f(x):
    return x * 2


pf = weakref.proxy(f)
print(pf(4), type(pf).__name__)
print(weakref.ProxyType.__name__, weakref.CallableProxyType.__name__)
print(weakref.ref(o) is weakref.ref(o), weakref.getweakrefcount(o))
print(weakref.proxy(o) is weakref.proxy(o))
