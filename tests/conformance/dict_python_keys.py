d = {}
d[1] = "int"
d[1.0] = "float"
d[True] = "bool"
print(d, len(d))
d2 = {0: "zero", False: "false", 0.0: "float zero"}
print(d2)
print(False in {0: 1}, 1.0 in {1: 1}, True in {1: 1}, 2.5 in {2: 1})
x = {(1, 2): "t", (1.0, 2): "t2", "a": 1, None: "none", b"k": "bytes", 3j: "c"}
print(x[(1, 2)], x[None], x[b"k"], x[3j], len(x))

class Key:
    def __init__(self, v, h=None):
        self.v = v
        self.h = v if h is None else h
    def __hash__(self):
        return hash(self.h)
    def __eq__(self, other):
        return isinstance(other, Key) and self.v == other.v
    def __repr__(self):
        return "Key(%r)" % (self.v,)

k = {Key(1): "a", Key(2): "b"}
print(k[Key(1)], k[Key(2)], Key(3) in k)
collide = {Key(1, 7): "x", Key(2, 7): "y", Key(3, 7): "z"}
print(collide[Key(2, 7)], collide[Key(3, 7)], len(collide))
del collide[Key(2, 7)]
print(Key(2, 7) in collide, list(collide.values()))

for bad in ([], {}, {1}, [1, 2]):
    try:
        {bad: 1}
    except TypeError as e:
        print("TypeError", "unhashable" in str(e))

order = {}
for i in range(50):
    order[i * 7 % 13] = i
for key in list(order)[::2]:
    del order[key]
for i in range(100, 130):
    order[i] = i
print(list(order)[:12], len(order), list(order)[-3:])

big = {i: i * i for i in range(2000)}
print(len(big), big[1999], sum(big.values()))
for i in range(0, 2000, 2):
    del big[i]
print(len(big), 1 in big, 2 in big, list(big)[:3])

try:
    for key in d:
        d[key + 100] = 0
except RuntimeError as e:
    print("RuntimeError", e)
e = {1: 1, 2: 2}
try:
    for key in e:
        del e[key]
except RuntimeError as err:
    print("RuntimeError", err)
m = {1: 1, 2: 2}
for key in m:
    m[key] = 5
print(m)

print({1: 2} == {1.0: 2.0}, {1: 2} == {1: 3}, {1: 2} != {2: 2}, {} == {})
u = {"b": 1, "a": 2}
u.update({"c": 3, "a": 9})
print(u, u.pop("b"), u.get("zz", "dflt"), list(u.items()))
print(dict(a=1, **{"b": 2}))
c = u.copy()
c["new"] = 1
print(len(u), len(c))
