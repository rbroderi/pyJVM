# Type parameter objects created by PEP 695 generic functions and classes.
def f[T, *Ts, **P](x: T, *args: int, **kw: int) -> T:
    return x


T, Ts, P = f.__type_params__
print(type(T).__name__, type(Ts).__name__, type(P).__name__)
print(repr(T), repr(Ts), repr(P), str(T))
print(T.__name__, Ts.__name__, P.__name__)
print(T.__covariant__, T.__contravariant__, T.__infer_variance__)
print(T.__bound__, T.__constraints__, T.has_default(), Ts.has_default(), P.has_default())
print(f.__annotations__["x"] is T, f.__annotations__["return"] is T)
print(f(3), f("s"))
print(f.__name__, f.__qualname__, f.__module__)
print(T == T, T != Ts, hash(T) == hash(T), bool(T))
print(isinstance(T.__module__, str))


class Box[V, W = int]:
    def __init__(self, item: V):
        self.item = item

    def get(self) -> V:
        return self.item


V, W = Box.__type_params__
print(V.__name__, W.__name__, W.has_default(), V.has_default())
print(Box(5).get(), Box.__name__, Box.__qualname__)
print(Box.get.__annotations__["return"] is V)
print([c.__name__ for c in Box.__mro__])


def plain():
    pass


class Plain:
    pass


print(plain.__type_params__, Plain.__type_params__)
print(T.__bound__)


def g[A: int, B: (int, str), C = float, D: str = "x"]():
    pass


A, B, C, D = g.__type_params__
print(A.__bound__.__name__, A.__constraints__, B.__bound__, [t.__name__ for t in B.__constraints__])
print(C.__default__.__name__, C.has_default(), D.__bound__.__name__, D.__default__, D.has_default())
print(repr(A), repr(B), repr(C), repr(D))


async def coro[T](x: T) -> T:
    return x


print(coro.__type_params__[0].__name__, coro.__qualname__)
lam = lambda: 1
print(lam.__type_params__)
