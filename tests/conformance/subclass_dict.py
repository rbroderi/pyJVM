class D(dict):
    pass

d = D(a=1, b=2)
print(isinstance(d, dict), isinstance(d, D), issubclass(D, dict), type(d) is D, type(d).__name__)
print(d, repr(d), len(d), d["a"], d.get("b"), d.get("z", 9), "a" in d, list(d), list(d.items()), list(d.keys()), list(d.values()))
d["c"] = 3
del d["a"]
print(d, d == {"b": 2, "c": 3}, {"b": 2, "c": 3} == d, d != {}, bool(d), bool(D()))
print(dict(d), {**d, "z": 0}, type(dict(d)) is dict, type(d.copy()) is dict, sorted(d), max(d), len(D(x=1)))
print(d.pop("b"), d.setdefault("q", 5), d.popitem(), d)
d.update({"u": 1}, v=2)
print(d, D([(1, 2)]), D({1: 2}), D.fromkeys("ab", 0), type(D.fromkeys("ab")).__name__)
print(dict.__len__(d), dict.__getitem__(d, "u"), dict.keys(d), dict.get(d, "v"))
for k in d:
    print("k", k)
d.attr = 4
print(d.attr, d.__dict__)
try:
    hash(d)
except TypeError:
    print("unhashable")

class L(dict):
    def __init__(self, *args, **kw):
        self.log = []
        super().__init__(*args, **kw)
    def __setitem__(self, key, value):
        self.log.append(("set", key))
        super().__setitem__(key, value)
    def __getitem__(self, key):
        self.log.append(("get", key))
        return super().__getitem__(key)
    def __delitem__(self, key):
        self.log.append(("del", key))
        super().__delitem__(key)
    def __contains__(self, key):
        self.log.append(("in", key))
        return super().__contains__(key)
    def __len__(self):
        return super().__len__() + 100
    def __iter__(self):
        return iter(sorted(super().__iter__()))
    def __repr__(self):
        return "L" + dict.__repr__(self)
    def __missing__(self, key):
        return "missing-" + str(key)

l = L(x=1)
print(l.log)
l["y"] = 2
print(l["x"], l["nope"], "x" in l, len(l), list(l), repr(l), str(l))
del l["x"]
l.update({"w": 3})
print(l.log, dict.__len__(l), list(dict.keys(l)))
print(l.get("zzz"), "zzz" in l, dict(l), sorted(l))

class M(dict):
    def __missing__(self, key):
        self[key] = len(self)
        return self[key]
m = M()
print(m["a"], m["b"], m["a"], m)

class Counter2(dict):
    def __init__(self):
        super().__init__()
    def add(self, k):
        self[k] = self.get(k, 0) + 1
c = Counter2()
for ch in "abca":
    c.add(ch)
print(c, c["a"])

class E(dict):
    __slots__ = ()
e = E(a=1)
print(e)
try:
    e.x = 1
except AttributeError:
    print("no dict")

class OD(dict):
    def __eq__(self, other):
        return True
    __hash__ = None
print(OD(a=1) == {"z": 9}, OD() == 5, OD() != 5)
print(D.__mro__ == (D, dict, object), D.__base__ is dict, D.__bases__ == (dict,))
print(f"{d}", "%s" % (D(a=1),), "{}".format(D(a=1)))
a = D(a=1)
a |= {"b": 2}
print(a, a | {"c": 3}, {"c": 3} | a, type(a | {}).__name__)
print(sum(1 for _ in a), any(a), all(a), list(reversed(a)), dict(zip(a, a.values())))
