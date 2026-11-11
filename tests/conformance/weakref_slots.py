import weakref
class A:
    __slots__ = ("x",)
class B:
    __slots__ = ("x", "__weakref__")
for K in (A, B):
    try:
        weakref.ref(K())
        print(K.__name__, "ok")
    except TypeError as e:
        print(K.__name__, e)
for v in ([], {}, 1, "s", (1,), None):
    try:
        weakref.ref(v)
    except TypeError as e:
        print(e)
