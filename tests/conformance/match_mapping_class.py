def mp(x):
    match x:
        case {"a": 1, "b": b}: return "ab %r" % (b,)
        case {"a": a, **rest}: return "a %r rest %r" % (a, rest)
        case {1: _}: return "key1"
        case {}: return "any-map"
        case _: return "not-map"

for v in [{"a": 1, "b": 2}, {"a": 5, "c": 6, "d": 7}, {1: 0}, {}, {"z": 1}, [1], "a"]:
    print(repr(v), mp(v))


def keep_extra(x):
    match x:
        case {"k": v}: return v
    return None

d = {"k": 1, "other": 2}
print(keep_extra(d), d)

class Point:
    __match_args__ = ("x", "y")
    def __init__(self, x, y):
        self.x = x
        self.y = y

class Point3(Point):
    __match_args__ = ("x", "y", "z")
    def __init__(self, x, y, z):
        Point.__init__(self, x, y)
        self.z = z

def cls(p):
    match p:
        case Point3(0, 0, z): return "origin-column z=%r" % (z,)
        case Point(0, 0): return "origin"
        case Point(x, y=0): return "on-x %r" % (x,)
        case Point(x=1, y=yy): return "x1 y=%r" % (yy,)
        case Point(): return "point"
        case int(n): return "int %r" % (n,)
        case str(s): return "str %r" % (s,)
        case bool(): return "never"
        case float() | complex(): return "number"
        case _: return "other"

for v in [Point3(0, 0, 4), Point(0, 0), Point(5, 0), Point(1, 9), Point(2, 3), 7, "hi", 2.5, None]:
    print(cls(v))

def too_many(p):
    match p:
        case Point(1, 2, 3):
            return "x"

try:
    too_many(Point(1, 2))
except TypeError as e:
    print("TypeError", e)

