class S(set):
    pass

s = S([3, 1, 2])
print(isinstance(s, set), isinstance(s, S), issubclass(S, set), type(s) is S, type(s).__name__)
print(sorted(s), len(s), 3 in s, 9 in s, bool(s), bool(S()), s == {1, 2, 3}, {1, 2, 3} == s, s != set())
s.add(4)
s.discard(1)
s.update([7])
print(sorted(s), repr(S()), repr(S([1])), str(S([1])))
print(sorted(s | {10}), type(s | {10}).__name__, type(s & {2}).__name__, type(s.copy()).__name__, sorted(s - {2}), sorted(s ^ {2, 100}))
print(s.issubset({1, 2, 3, 4, 7}), s <= {2, 3, 4, 7}, s >= {2}, {2} <= s, s.isdisjoint({99}), sorted(s.union([5])), sorted(s.intersection([2, 3])))
s |= {50}
s -= {3}
print(sorted(s), type(s).__name__)
print(set.__len__(s), set.__contains__(s, 2), sorted(set.union(s, [0])))
print(sorted(set(s)), sorted(frozenset(s)), sorted(sorted(s)), sum(s), max(s), len(S(range(5))), sorted(S("hello")))
for x in S([1]):
    print("x", x)
s.attr = 1
print(s.attr, s.__dict__)
try:
    hash(s)
except TypeError:
    print("unhashable")

class Logged(set):
    def __init__(self, it=()):
        self.log = []
        super().__init__(it)
    def add(self, x):
        self.log.append(x)
        super().add(x)
    def __contains__(self, x):
        return x == "magic"
    def __len__(self):
        return 99
    def __iter__(self):
        return iter(["a", "b"])
    def __repr__(self):
        return "Logged!"
g = Logged([1])
g.add(2)
print(g.log, "magic" in g, 1 in g, len(g), list(g), repr(g), set.__len__(g), sorted(set.copy(g)), sorted(g))

class F(frozenset):
    pass
f = F([3, 1, 2])
print(isinstance(f, frozenset), isinstance(f, F), type(f).__name__, sorted(f), len(f), 2 in f, f == frozenset({1, 2, 3}), hash(f) == hash(frozenset({1, 2, 3})))
print(repr(F()), repr(F([1])), type(f | {5}).__name__, sorted(f | {5}), type(f.copy()).__name__, {f: 1}[frozenset({1, 2, 3})])
print(f.issubset({1, 2, 3, 4}), f <= {1, 2, 3}, sorted(f - {1}), F.__mro__ == (F, frozenset, object))

class G(frozenset):
    def __new__(cls, items):
        self = super().__new__(cls, [i * 2 for i in items])
        self.n = len(self)
        return self
    def __hash__(self):
        return 1
g2 = G([1, 2])
print(sorted(g2), g2.n, hash(g2), {g2}.__len__())
print(S.__mro__ == (S, set, object), S.__base__ is set)
