class C(complex):
    pass

c = C(1, 2)
print(isinstance(c, complex), isinstance(c, C), issubclass(C, complex), type(c) is C, type(c).__name__)
print(c, repr(c), c + 1, 1 + c, c * 2, -c, abs(C(3, 4)), c.real, c.imag, c.conjugate(), c == 1 + 2j, hash(c) == hash(1 + 2j), bool(c), bool(C()))
print(type(c + 1).__name__, type(c.conjugate()).__name__, complex(c), C("1+2j"), complex.__add__(c, 1), complex.__repr__(c))
c.unit = "V"
print(c.unit, c.__dict__)

class P(complex):
    def __new__(cls, re, im, label):
        self = super().__new__(cls, re, im)
        self.label = label
        return self
    def __repr__(self):
        return "P<%s>" % self.label
print(repr(P(1, 2, "p")), P(1, 2, "p") + 1, P(1, 2, "p").label)


class O(object):
    pass

class Base(object):
    def __init__(self, a):
        self.a = a

class Derived(Base):
    def __init__(self, a, b):
        super().__init__(a)
        self.b = b

d = Derived(1, 2)
print(d.a, d.b, d.__dict__, isinstance(d, Base), isinstance(d, object), type(d) is Derived)

class Slotted(object):
    __slots__ = ("x", "y")
    def __init__(self):
        self.x = 1
s = Slotted()
print(s.x, hasattr(s, "y"), hasattr(s, "__dict__"))

class Tracked(object):
    count = 0
    def __new__(cls, *args):
        inst = super().__new__(cls)
        Tracked.count += 1
        inst.args = args
        return inst
    def __init__(self, *args):
        self.n = len(args)
t = Tracked(1, 2, 3)
print(t.args, t.n, Tracked.count)

class MixA:
    def hello(self):
        return "A"

class MixI(MixA, int):
    def __new__(cls, v):
        return super().__new__(cls, v)
    def hello(self):
        return "I" + super().hello()
mi = MixI(5)
print(mi + 1, mi.hello(), isinstance(mi, MixA), isinstance(mi, int), MixI.__mro__ == (MixI, MixA, int, object))

class L2(MixA, list):
    pass
l = L2([1, 2])
print(l, len(l), l.hello(), l + [3], MixA.hello(l))

class DictMixin:
    def total(self):
        return sum(self.values())
class Reg(DictMixin, dict):
    pass
r = Reg(a=1, b=2)
print(r.total(), r, len(r), Reg.__mro__ == (Reg, DictMixin, dict, object))

class Hooked(str):
    registry = []
    def __init_subclass__(cls, **kw):
        Hooked.registry.append(cls.__name__)
class H2(Hooked):
    pass
class H3(H2):
    pass
print(Hooked.registry, H3("z"), isinstance(H3("z"), Hooked), issubclass(H3, str))

class Meta(type):
    def __call__(cls, *a, **k):
        obj = super().__call__(*a, **k)
        obj.made_by = "Meta"
        return obj
class WithMeta(int, metaclass=Meta):
    pass
w = WithMeta(4)
print(w, w.made_by, type(WithMeta) is Meta, w + 1)

class Plain:
    pass
x = Plain()
x.k = 1
print(vars(x), x.__class__ is Plain)
