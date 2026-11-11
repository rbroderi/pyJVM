# pyjvm: requires-python >= 3.14
# Setting, deleting and caching rules for function __annotations__ / __annotate__.
def f(a: int, b: "x" = 3) -> str:
    pass

print(f.__annotations__)
f.__annotate__ = None
print(f.__annotations__)           # setting annotate to None keeps the cache
del f.__annotations__
print(f.__annotations__, f.__annotate__)

try:
    f.__annotate__ = 3
except TypeError as exc:
    print("TypeError", exc)

f.__annotate__ = lambda x: {"q": x}
print(f.__annotations__)
f.__annotate__ = lambda x: {"y": x}  # a new callable invalidates the cache
print(f.__annotations__)
f.__annotations__ = {"z": 43}
print(f.__annotate__, f.__annotations__)

try:
    f.__annotations__ = 3
except TypeError as exc:
    print("TypeError", exc)
try:
    del f.__annotate__
except TypeError as exc:
    print("TypeError", exc)

f.__annotate__ = lambda: 42
try:
    f.__annotations__
except TypeError:
    print("TypeError arity")
f.__annotate__ = lambda x: 42
try:
    f.__annotations__
except TypeError as exc:
    print("TypeError", exc)

g = lambda: None
print(g.__annotations__, g.__annotate__)

def with_none(a: None) -> None: pass
with_none.__annotations__ = None
print(with_none.__annotations__, with_none.__annotate__)
