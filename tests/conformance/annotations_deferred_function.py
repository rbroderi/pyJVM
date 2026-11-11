# pyjvm: requires-python >= 3.14
# PEP 649/749: function annotations are evaluated lazily through __annotate__.
def func(x: undefined, /, y: undefined, *args: undefined, z: undefined, **kwargs: undefined) -> undefined:
    pass

try:
    func.__annotations__
except NameError as exc:
    print("NameError", exc)

undefined = 1
print(list(func.__annotations__.items()))
print(func.__annotations__ is func.__annotations__)

def plain(a, b):
    pass

print(plain.__annotations__, plain.__annotate__)
print(func.__annotate__.__name__, func.__annotate__.__qualname__)
print(func.__annotate__(1) == func.__annotations__, func.__annotate__(2) == func.__annotations__)

for fmt in (3, 4, 5):
    try:
        func.__annotate__(fmt)
    except NotImplementedError:
        print("NotImplementedError", fmt)
try:
    func.__annotate__(None)
except TypeError:
    print("TypeError for None")

# mixed parameter kinds keep declaration order; defaults are not annotations
def order(a: int, b: str = "s", *rest: float, kw: bool = False, **extra: bytes) -> list:
    pass

print(list(order.__annotations__.items()))

# annotations run after the function exists and see later bindings
def early(p: Late) -> Late:
    pass

try:
    early.__annotations__
except NameError:
    print("NameError before Late")
class Late: pass
print(early.__annotations__["p"] is Late, early.__annotations__["return"] is Late)

# decorators see the annotate function and wrappers keep it
def deco(fn):
    print("decorating", fn.__annotate__ is not None)
    return fn

@deco
def decorated(v: int) -> int:
    return v

print(decorated.__annotations__["v"] is int)

# nested functions: qualified name and closure access
def outer(tp):
    def inner(v: tp) -> tp:
        pass
    return inner

inner = outer(str)
print(inner.__annotations__["v"] is str, inner.__annotate__.__qualname__)

class Holder:
    def meth(self, x: Nested): ...
    x: Nested
    class Nested: ...

print(Holder.meth.__annotations__["x"] is Holder.Nested, Holder.__annotations__["x"] is Holder.Nested)
print(Holder.meth.__annotate__.__qualname__, Holder.__annotate__.__qualname__)

# the name "format" is an ordinary annotation name, not the annotate parameter
def uses_format(x: format): pass
print(uses_format.__annotations__["x"] is format)

def closure_format(format):
    def inner(x: format): pass
    return inner
print(closure_format("closure var").__annotations__)

# a comprehension inside an annotation
def comp(x: [y for y in range(3)]): pass
print(comp.__annotations__)
