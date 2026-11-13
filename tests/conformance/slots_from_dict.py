# __slots__ may be any iterable of names, including a dict (the values are docstrings).
class P:
    __slots__ = dict(a="first", b="second")


p = P()
p.a = 1
p.b = 2
print(p.a, p.b, sorted(P.__slots__))
try:
    p.c = 3
except AttributeError as exc:
    print("AttributeError")


class Q:
    __slots__ = {"x", }


q = Q()
q.x = 5
print(q.x)
try:
    class R:
        __slots__ = 5
except TypeError as exc:
    print(exc)
