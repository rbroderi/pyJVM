import gc
import weakref


class C:
    pass


class D(C):
    __slots__ = ()


class E:
    __slots__ = ("a", "__weakref__")


def collect():
    for _ in range(5):
        gc.collect()


c = C()
r = weakref.ref(c)
print(r() is c, type(r).__name__, r.__callback__)
print(weakref.ref(c) is r, weakref.ref(c) is weakref.ref(c))
print(weakref.getweakrefcount(c), weakref.getweakrefs(c) == [r])
print(hash(r) == hash(c), r == weakref.ref(c), r != weakref.ref(c))
print(r == 5, r != 5)

# callbacks prevent sharing; the plain ref stays first
calls = []
r2 = weakref.ref(c, calls.append)
r3 = weakref.ref(c, calls.append)
print(r2 is r, r3 is r2, weakref.getweakrefcount(c))
print(weakref.getweakrefs(c) == [r, r3, r2])
print(r2.__callback__ == calls.append)


# subclassing ref: never shared, carries extra state
class MyRef(weakref.ref):
    def __init__(self, ob, callback=None, **annotations):
        super().__init__(ob, callback)
        self.__counter = 0
        self.extra = annotations


mr = MyRef(c, extra=1)
print(mr() is c, mr.extra, MyRef(c) is mr, weakref.ref(c) is r)
print(isinstance(mr, weakref.ref), isinstance(r, weakref.ReferenceType))


# unhashable referents make the ref unhashable
class U:
    __hash__ = None


u = U()
ur = weakref.ref(u)
try:
    hash(ur)
except TypeError as e:
    print("TypeError", e)

# which types support weak references
for obj in ([], {}, 1, "s", (1, 2), None, 2.5, b"x", object()):
    try:
        weakref.ref(obj)
        print(type(obj).__name__, "ok")
    except TypeError as e:
        print(e)
for obj in (c, C, collect, weakref, set(), frozenset(), len, int, (x for x in ()), E()):
    try:
        print(type(weakref.ref(obj)()).__name__, "ok")
    except TypeError as e:
        print(e)
try:
    weakref.ref(D())
except TypeError as e:
    print(e)


class L(list):
    pass


class N(int):
    pass


lv = L()
print(weakref.ref(lv)() is lv)
try:
    weakref.ref(N(3))
except TypeError as e:
    print(e)

# death: ref clears, callbacks run with the dead ref
del c, r2
seen = []
holder = []


def cb(ref):
    seen.append((ref(), ref is holder[0]))


t = C()
holder.append(weakref.ref(t, cb))
del t
for _ in range(10):
    collect()
    if seen:
        break
print(seen, holder[0]())
try:
    hash(holder[0])
except TypeError as e:
    print("TypeError", e)
print(holder[0] == holder[0], holder[0] == weakref.ref(C()))
print("dead" in repr(holder[0]), repr(holder[0]).startswith("<weakref at 0x"))
print(holder[0].__callback__)


# a callback that raises does not stop the program
def bad(ref):
    raise ValueError("boom")


t = C()
rb = weakref.ref(t, bad)
del t
collect()
print("survived", rb())

# the ref object itself going away silences its callback
fired = []
t = C()
rr = weakref.ref(t, lambda r: fired.append(1))
del rr
collect()
del t
collect()
print("silenced", fired)
