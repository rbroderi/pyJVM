# The `type` statement creates a TypeAliasType with a lazily evaluated value.
def trace(label, value):
    print("eval", label)
    return value


type Plain = trace("plain", int)
print("defined")
print(type(Plain).__name__, repr(Plain), str(Plain), Plain.__name__)
print(Plain.__value__.__name__)
print(Plain.__value__.__name__)
print(Plain.__type_params__, Plain.__parameters__, Plain.__module__)

type Gen[T, *Ts, **P] = trace("gen", list)
print([p.__name__ for p in Gen.__type_params__])
print(Gen.__value__.__name__)
type Pair[K, V] = trace("pair", dict)
print(Pair.__parameters__ == Pair.__type_params__, Pair.__parameters__[1].__name__)
print(Pair[int, str].__parameters__, Pair[int, str].__args__)

type Recursive = list[Recursive] | int if False else Recursive2
type Recursive2 = int

type Forward = Later
try:
    Forward.__value__
except NameError:
    print("NameError")
Later = str
print(Forward.__value__.__name__)

type Default[T = int] = T
print(Default.__type_params__[0].has_default())
print(Default.__value__ is Default.__type_params__[0])

try:
    T
except NameError:
    print("T does not leak")

def in_function():
    x = 5
    type Local = trace("local", x)
    type LocalGen[A: int] = A
    return Local, LocalGen


Local, LocalGen = in_function()
print(Local.__value__)
print(LocalGen.__value__.__bound__.__name__, LocalGen.__name__)


class Holder:
    attr = 7
    type InClass = trace("in class", attr)
    type InClassGen[T] = list if attr else None


print(Holder.InClass.__value__, Holder.InClassGen.__type_params__[0].__name__)
print(Holder.InClassGen.__value__.__name__)

try:
    Plain()
except TypeError:
    print("not callable")
try:
    Plain.foo = 1
except AttributeError:
    print("immutable")
try:
    Plain.__value__ = 1
except AttributeError:
    print("readonly value")
print(Plain == Plain, hash(Plain) == hash(Plain), Plain != Gen)

try:
    Plain[int]
except TypeError as e:
    print("TypeError")
G = Gen[int, str, int]
print(G.__origin__ is Gen, G.__args__)
print(G == Gen[int, str, int], G == Gen[int, int, int])

T = "bound after the leak check"
