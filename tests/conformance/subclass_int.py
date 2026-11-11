class C(int):
    pass

c = C(5)
print(isinstance(c, int), isinstance(c, C), issubclass(C, int), type(c) is C, type(c).__name__)
print(c, repr(c), str(c), c + 1, 1 + c, c * 2, c - 1, c // 2, c % 3, c ** 2, -c, +c, abs(c), ~c)
print(type(c + 1) is int, type(-c) is int, type(c * c) is int)
print(c == 5, 5 == c, c < 6, c > 4, c <= 5, c != 5, hash(c) == hash(5), bool(C(0)), bool(c))
print(int.__add__(c, 1), int.__mul__(c, 3), int.__repr__(c), int.__str__(c))
print(c.bit_length(), c.real, c.numerator, c.denominator, c.conjugate(), c.to_bytes(2, "big"))
print(int(c), type(int(c)) is int, float(c), complex(c), list(range(c)), [0, 1, 2, 3, 4, 5, 6][c])
print({c: 1}[5], sorted([C(3), C(1), C(2)]), sum([C(1), C(2)]), max(C(1), C(9)), divmod(c, 2), pow(c, 2, 7))
print("%d %s %r" % (c, c, c), "{} {:>4}".format(c, c), f"{c} {c:03d}")
c.extra = "x"
print(c.extra, c.__dict__)

class D(int):
    def __new__(cls, value, label="d"):
        self = super().__new__(cls, value * 2)
        self.label = label
        return self
    def __repr__(self):
        return "D(%d,%s)" % (int(self), self.label)
    def __add__(self, other):
        return D(int(self) + int(other))
    def __bool__(self):
        return False

d = D(4, "q")
print(int(d), d.label, repr(d), str(d), d + 1, bool(d), isinstance(d, int))
print([10, 20, 30, 40][d - 5], "%s" % d, len([1] * (d - 6)))
print(sum([D(1), D(2)]))

class E(int):
    def __init__(self, v):
        self.v = v
        super().__init__()
e = E(7)
print(e, e.v, e + e)

class F(int):
    __slots__ = ()
f = F(3)
print(f + 1, f)
try:
    f.x = 1
except AttributeError:
    print("no dict")

class B(int):
    pass
print(B(True), B("12", 16), B(3.9))
print(int.__eq__(c, 5), int.__lt__(c, 7), int.__hash__(c) == 5)
print(C.__mro__ == (C, int, object), C.__bases__ == (int,), C.__base__ is int)
big = C(10 ** 30)
print(big + 1, big * big, isinstance(big, int))
