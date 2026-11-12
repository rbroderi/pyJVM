import collections


def f(a, b, c):
    return (a, b, c)


print(f.__defaults__, f.__kwdefaults__)
f.__defaults__ = (5, 6)
print(f(1), f(1, 2), f(1, 2, 3), f.__defaults__)
f.__defaults__ = None
print(f.__defaults__)
try:
    f(1)
except TypeError:
    print("TypeError")


def g(a, b=2, *, k=1, j):
    return (a, b, k, j)


print(g.__defaults__, g.__kwdefaults__)
g.__kwdefaults__ = {"j": 9}
print(g.__kwdefaults__, g(1, k=0), g(1, k=3, j=4))
try:
    g.__defaults__ = [1]
except TypeError:
    print("TypeError")

h = lambda x, y=1: x + y
h.__defaults__ = (10,)
print(h(1), (lambda: 0).__defaults__)

P = collections.namedtuple("P", ["x", "y", "z"], defaults=["~", "^"])
print(P._field_defaults)
print(P(1, 2, 3), P(1), P(1, z=0))
try:
    P()
except TypeError:
    print("TypeError")

print(P(1, 2, 3), "%s-%s-%r" % P(1, 2), repr(P(0)))
