class FS(frozenset):
    def __new__(cls, items=()):
        self = super().__new__(cls, items)
        self.tag = "fs"
        return self

f = FS([3, 1, 2])
print(len(f), 2 in f, sorted(f), f | {9} == frozenset({1, 2, 3, 9}), type(f | {9}).__name__, f.tag, isinstance(f, frozenset))
print(hash(f) == hash(frozenset([1, 2, 3])), {f: 1}[frozenset([1, 2, 3])], f == frozenset([1, 2, 3]), f.issubset({1, 2, 3, 4}), type(f.union([7])).__name__)

class LS(list):
    __slots__ = ("note",)

ls = LS([1, 2])
ls.note = "n"
ls.append(3)
print(ls, ls.note, len(ls), hasattr(ls, "__dict__"))

class DS(dict):
    __slots__ = ()
    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.setdefault("made", True)

ds = DS({"a": 1}, b=2)
print(ds, DS.fromkeys("xy", 0), type(DS.fromkeys("xy", 0)).__name__, sorted(DS(zip("ab", "cd"))))

class SS(str):
    __slots__ = ("extra",)
    def __new__(cls, text, extra=None):
        self = super().__new__(cls, text)
        self.extra = extra
        return self

ss = SS("txt", 5)
print(ss, ss.extra, ss.upper(), len(ss), ss + "!")

D = type("D", (int,), {"double": lambda self: self * 2})
d = D(21)
print(d.double(), d, isinstance(d, int), type(d).__name__)

class Cnt(int):
    made = 0
    def __new__(cls, v=0):
        Cnt.made += 1
        return super().__new__(cls, v)
    def __init__(self, v=0):
        self.original = v

c = Cnt(7)
print(c, c.original, Cnt.made, c + c, Cnt.made)

class L(list):
    def __iadd__(self, other):
        return L(list.__add__(self, other) + ["!"])

l = L([1])
l += [2]
print(l, type(l).__name__)

class M(list):
    pass

m = M([3, 1, 2])
m += [0]
m *= 2
m.sort()
m.extend(M([9]))
m.insert(0, -1)
print(m, type(m).__name__, m.pop(), m.index(3), m.count(0), type(m[:2]).__name__, type(m + m).__name__, type(m.copy()).__name__)
m2 = M([1, 2, 3])
del m2[0]
m2[0:1] = [7, 8]
print(m2, m2[::-1], [x * 2 for x in m2], min(m2), max(m2), sum(m2), list(reversed(m2)), any(m2), all(m2))

class Hk(dict):
    def __missing__(self, key):
        self[key] = key * 2
        return self[key]

h = Hk()
print(h[2], h, h.get(5), 2 in h, h.pop(2), len(h))

class Eq(dict):
    def __eq__(self, other):
        return True
    __hash__ = None

e = Eq(a=1)
print(e == {"x": 2}, bool(e != 3), isinstance(e, dict))
try:
    hash(e)
except TypeError:
    print("unhashable")

class Meta(type):
    pass

class WM(dict, metaclass=Meta):
    pass

w = WM(a=1)
print(type(WM).__name__, w, isinstance(w, dict), type(w).__name__)

class A1(int):
    pass

class A2(A1):
    def __repr__(self):
        return "A2<%d>" % int(self)

a2 = A2(4)
print(a2, repr(a2), a2 + 1, A2.__mro__ == (A2, A1, int, object), isinstance(a2, A1), issubclass(A2, int), [a2], {a2: a2})
try:
    class Bad(int, str):
        pass
except TypeError:
    print("layout conflict")
try:
    int.__add__("x", 1)
except TypeError:
    print("descriptor type error")
try:
    list.append(5, 1)
except TypeError:
    print("descriptor type error 2")
print(int("ff", 16), int("0b11", 0), str(b"hi", "ascii"), float("1e2"), bytes("é", "latin-1"), bytearray(2), complex("1+2j"))
