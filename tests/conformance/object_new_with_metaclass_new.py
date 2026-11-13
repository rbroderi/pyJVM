# `Class.__new__` resolves through the class's own MRO (ending at object.__new__); a metaclass __new__ must not win.
import abc


class Meta(type):
    def __new__(mcls, name, bases, ns):
        return super().__new__(mcls, name, bases, ns)


class Plain(metaclass=Meta):
    pass


class Abstract(abc.ABC):
    def clone(self):
        return self.__class__.__new__(self.__class__)


class Concrete(Abstract):
    x = 1


class Named(str):
    def __new__(cls, name, value):
        return str.__new__(cls, value)


print(type(Plain.__new__(Plain)).__name__)
print(type(Abstract.__new__(Concrete)).__name__)
print(type(Concrete().clone()).__name__)
print(Named("n", "v"))
print(Plain.__new__ == object.__new__)
