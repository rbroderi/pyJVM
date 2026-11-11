# pyjvm: requires-python >= 3.14
# Classes: __annotate_func__ / __annotations_cache__ and lazy evaluation.
class X:
    a: undefined

try:
    X.__annotations__
except NameError:
    print("NameError")
undefined = 1
print(X.__annotations__)
print("__annotations_cache__" in X.__dict__, "__annotate_func__" in X.__dict__)
print(X.__annotate__.__name__, X.__annotate__.__qualname__)
print(X.__annotate__(1), X.__annotate__(2))
try:
    X.__annotate__(3)
except NotImplementedError:
    print("NotImplementedError")

class C:
    a: int = 3
    b: str = 4

print(C.__annotations__, C.a, C.b)
print("__annotations_cache__" in C.__dict__)
del C.__annotations__
print("__annotations_cache__" in C.__dict__)
try:
    del C.__annotations__
except AttributeError:
    print("AttributeError on second delete")

class D(C): pass
print(D.__annotations__, D.__annotate__)

class E: pass
print(E.__annotations__, E.__annotate__)

foo = type("Foo", (), {})
for i in range(3):
    print("__annotations_cache__" in foo.__dict__)
    d = foo.__annotations__
    print("__annotations_cache__" in foo.__dict__, foo.__dict__["__annotations_cache__"] == d)
    del foo.__annotations__

for i in range(2):
    d = {"a": int}
    foo.__annotations__ = d
    print("__annotations_cache__" in foo.__dict__, foo.__annotations__ == d, foo.__dict__["__annotations_cache__"] == d)
    del foo.__annotations__

# builtin types have no annotations
try:
    print(float.__annotations__)
except AttributeError:
    print("float AttributeError")
try:
    float.__annotations__ = {}
except TypeError:
    print("float set TypeError")
try:
    del float.__annotations__
except TypeError:
    print("float del TypeError")

class Explicit:
    __annotations__ = {"what": int}
print(Explicit.__annotations__)

class ExplicitAnnotate:
    __annotate__ = lambda format: {"what": int}
print(ExplicitAnnotate.__annotations__)
print(ExplicitAnnotate.__annotate__(1))

class User:
    a: int
    def __annotate__(format):
        return {"a": str}
print(User.__annotate__(1), User.__annotations__)

called = False
class Deleted:
    def __annotate__(format):
        global called
        called = True
        return {"a": int}
print(Deleted.__annotations__, called)
del Deleted.__annotations__
called = False
print(Deleted.__annotations__, called, Deleted.__annotate__)

class Setter: pass
Setter.__annotate__ = lambda format: {"k": 1}
print(Setter.__annotations__)
Setter.__annotations__ = {"v": 2}
print(Setter.__annotate__, Setter.__annotations__)
try:
    Setter.__annotate__ = 5
except TypeError as exc:
    print("TypeError", exc)

class NonSimple:
    (y): int
    int.real: int
    [].append: int
print(NonSimple.__annotations__)

class Dunder:
    __classdict__: int
print(Dunder.__annotations__)

class Scoping:
    attr_type = str
    value: attr_type
    other: later
    later = int
print(Scoping.__annotations__)

class Outer:
    class Inner: pass
    field: Inner
print(Outer.__annotations__["field"] is Outer.Inner)
