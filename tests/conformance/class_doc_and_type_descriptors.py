# class __doc__ defaults to None, type.__dict__ has the __mro__ descriptor, and instances see object.__init__.
import enum


class E:
    pass


class D:
    "doc"


print(E.__doc__, D.__doc__)
get_mro = type.__dict__["__mro__"].__get__
print(get_mro(E)[0] is E, get_mro(E)[-1] is object, get_mro(int)[0] is int)
print(type.__dict__["__bases__"].__get__(D) == (object,))


class P:
    def __new__(cls, *a):
        return object.__new__(cls)


p = P(1)
print(p.__init__() is None)


class Color(enum.Enum):
    RED = 1
    GREEN = 2


print(Color.RED.__init__(1) is None)
