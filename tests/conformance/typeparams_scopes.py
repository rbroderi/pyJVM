# Scoping of type parameters: visible to annotations, bases and the body, never leaked, class-scope interaction.
def trace(label, value):
    print("eval", label)
    return value


def deco(label):
    print("deco expr", label)

    def apply(fn):
        print("deco apply", label)
        return fn
    return apply


@deco("a")
@deco("b")
def ordered[T](x: T = trace("default", 1), *, k: int = trace("kwdefault", 2)) -> T:
    return x


print(ordered(), ordered(5, k=6))
print(ordered.__type_params__[0].__name__)

try:
    T
except NameError:
    print("T does not leak")

T = "outer"


def uses_outer_default[T](x=T):
    return x


print(uses_outer_default())


def body_sees[T]():
    return T.__name__


print(body_sees())


def nested[A]():
    def inner[B](a: A, b: B):
        return A.__name__ + B.__name__
    return inner


print(nested()(1, 2), nested().__qualname__, nested().__type_params__[0].__name__)


class Outer[T]:
    X = int

    def method[U: X](self, a: T, b: U) -> tuple[T, U]:
        return (T.__name__, U.__name__)

    class Inner[V]:
        def get(self) -> V:
            return V

    attr = T.__name__
    params = __type_params__


o = Outer()
print(o.method(1, 2), Outer.attr, Outer.params[0].__name__)
print(Outer.method.__type_params__[0].__bound__.__name__)
print(Outer.method.__annotations__["a"] is Outer.__type_params__[0])
print(Outer.Inner.__qualname__, Outer.method.__qualname__, Outer.Inner.__type_params__[0].__name__)
print(Outer.Inner().get().__name__)


class Shadow:
    T = "class attr"

    def m[T](self, x: T):
        return T

    def n(self, x=T):
        return x

    def q[U: T](self):
        return U


print(Shadow().m(1).__name__, Shadow().n(), Shadow.q.__type_params__[0].__bound__)
print(Shadow.m.__annotations__["x"])


class Base:
    def hello(self):
        return "base"


class Derived[T](Base):
    def hello(self):
        return "derived+" + super().hello()


print(Derived().hello(), Derived.__bases__[0].__name__, Base in Derived.__mro__)


def make():
    counter = 0

    def bump[T](x: T) -> T:
        nonlocal counter
        counter += 1
        return x

    bump(1)
    bump(2)
    return counter


print(make())


def recursive[T](n: int) -> int:
    return 1 if n <= 1 else n * recursive(n - 1)


print(recursive(5))


def rebinds[T]():
    return rebinds


original = rebinds
rebinds = "replaced"
print(original())


class WithDecorator:
    @staticmethod
    def static[T](x: T):
        return x

    @classmethod
    def cls[T](cls, x: T):
        return (cls.__name__, x)

    @property
    def prop(self):
        return 1

    def gen[T](self, n):
        for i in range(n):
            yield i


print(WithDecorator.static(3), WithDecorator.cls(4), list(WithDecorator().gen(3)))


def outer_fn():
    class Local[T]:
        def f(self, x: T) -> T:
            return x
    return Local


print(outer_fn()().f(9), outer_fn().__qualname__)
