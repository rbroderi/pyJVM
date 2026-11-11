# pyjvm: requires-python < 3.14
# Eager (pre-PEP 649) function annotations: evaluated once, when the def statement runs.
log = []
def mark(tag, value):
    log.append(tag)
    return value

def f(a: mark("a", int), b: mark("b", str) = mark("default", 3), *rest: mark("rest", float),
      kw: mark("kw", bool) = False, **extra: mark("extra", bytes)) -> mark("ret", list):
    pass

print(log)
print(list(f.__annotations__.items()))
print(f.__annotations__ is f.__annotations__)
f.__annotations__["new"] = 1
print(sorted(f.__annotations__))
f.__annotations__ = {"z": 1}
print(f.__annotations__)
del f.__annotations__
print(f.__annotations__)
try:
    f.__annotations__ = 3
except TypeError:
    print("TypeError")

def plain(a, b): pass
print(plain.__annotations__, hasattr(plain, "__annotate__"))

try:
    def broken(x: undefined_name): pass
except NameError:
    print("NameError at def time")

class K:
    def m(self, __priv: int, other: "str") -> None: pass
    attr: int = 1
print(sorted(K.m.__annotations__), K.__annotations__)
print(hasattr(K, "__annotate__"), "__annotations__" in K.__dict__)

lam = lambda: 0
print(lam.__annotations__)

async def coro(x: int) -> str: pass
print(coro.__annotations__)

def deco(fn):
    print(fn.__annotations__)
    return fn

@deco
def decorated(q: float) -> int: pass

def outer(tp):
    def inner(v: tp) -> tp: pass
    return inner
print(outer(str).__annotations__)
