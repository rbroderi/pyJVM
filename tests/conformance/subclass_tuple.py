class T(tuple):
    pass

t = T((3, 1, 2))
print(isinstance(t, tuple), isinstance(t, T), issubclass(T, tuple), type(t) is T, type(t).__name__)
print(t, repr(t), len(t), t[0], t[1:], t[-1], 3 in t, list(t), t == (3, 1, 2), (3, 1, 2) == t, bool(t), bool(T()))
print(t + (4,), (0,) + t, t * 2, type(t + (4,)).__name__, type(t[1:]).__name__, t.index(1), t.count(2))
print(sorted(t), min(t), max(t), sum(t), hash(t) == hash((3, 1, 2)), {t: 1}[(3, 1, 2)], tuple(t), list(reversed(t)))
a, b, c = t
print(a, b, c, T("ab"), T(), T(range(3)), [*t], (lambda *x: x)(*t))
print(tuple.__len__(t), tuple.__getitem__(t, 1), tuple.__add__(t, (9,)), tuple.__repr__(t), tuple.__eq__(t, (3, 1, 2)))
t2 = T((1, 2))
t2.tag = "x"
print(t2.tag, t2.__dict__)

class P(tuple):
    def __new__(cls, x, y):
        return super().__new__(cls, (x, y))
    @property
    def x(self):
        return self[0]
    @property
    def y(self):
        return self[1]
    def __repr__(self):
        return "P(%r, %r)" % (self[0], self[1])
    def norm(self):
        return abs(self.x) + abs(self.y)

p = P(3, -4)
print(p, p.x, p.y, p.norm(), len(p), p == (3, -4), tuple(p), p + (1,), isinstance(p, tuple))
x, y = p
print(x, y, str(p), "%s" % (p,), hash(p) == hash((3, -4)))

class Q(tuple):
    def __getitem__(self, i):
        return "item%s" % (i,)
    def __len__(self):
        return 10
    def __iter__(self):
        return iter("xyz")
    def __contains__(self, v):
        return v == "q"
    def __eq__(self, other):
        return "eq"
    def __hash__(self):
        return 5
    def __add__(self, other):
        return "added"
    def __bool__(self):
        return False
q = Q((1, 2))
print(q[0], len(q), list(q), "q" in q, 1 in q, bool(q == 1), hash(q), q + (1,), bool(q), tuple.__len__(q), tuple.__getitem__(q, 0), sorted(q))

class S(tuple):
    __slots__ = ()
s = S((1,))
print(s)
try:
    s.x = 1
except AttributeError:
    print("no dict")
print(T.__mro__ == (T, tuple, object), T.__base__ is tuple)
print(f"{T((1,))}", "%s" % (T((1,)),), "{}".format(T((1, 2))), T((1,)) < T((2,)), T((1, 2)) < (1, 3))
