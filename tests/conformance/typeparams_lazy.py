# Bounds, constraints and defaults are evaluated lazily, once, in the type parameter scope.
def trace(label, value):
    print("eval", label)
    return value


def f[T: trace("bound", int), U: (trace("c1", int), trace("c2", str)), V = trace("default", float)](): pass


print("defined")
T, U, V = f.__type_params__
print("fetched")
print(T.__bound__.__name__)
print(T.__bound__.__name__)
print([c.__name__ for c in U.__constraints__])
print(len(U.__constraints__))
print(U.__bound__)
print(V.has_default())
print(V.__default__.__name__)
print(V.__default__.__name__)
print(T.has_default())

try:
    def g[X: undefined_name](): pass
    print("g defined")
    g.__type_params__[0].__bound__
except NameError as e:
    print("NameError", e)

try:
    def h[X = also_undefined](): pass
    print("h defined")
    X, = h.__type_params__
    print(X.has_default())
    X.__default__
except NameError as e:
    print("NameError", e)

late = None


def k[Y: late_bound](): pass


Y, = k.__type_params__
try:
    Y.__bound__
except NameError:
    print("not yet")
late_bound = int
print(Y.__bound__.__name__)


def refer[A, B: list[A] if False else int](): pass


print(refer.__type_params__[1].__bound__.__name__)

try:
    def bad[Z: (int,)](): pass
    bad.__type_params__[0].__constraints__
except TypeError as e:
    print("TypeError", e)

try:
    def bad2[Z: 3](): pass
    print(bad2.__type_params__[0].__bound__)
except TypeError as e:
    print("TypeError", e)


class K[T: trace("class bound", int) = trace("class default", str)]:
    pass


print("class made")
print(K.__type_params__[0].__bound__.__name__, K.__type_params__[0].__default__.__name__)
