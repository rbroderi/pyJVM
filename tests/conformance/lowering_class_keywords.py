class Meta(type):
    def __new__(mcls, name, bases, ns, **kw):
        print("Meta.__new__", name, sorted(kw.items()))
        return super().__new__(mcls, name, bases, ns)

    def __init__(cls, name, bases, ns, **kw):
        print("Meta.__init__", name, sorted(kw.items()))
        super().__init__(name, bases, ns)


class Base:
    seen = []

    def __init_subclass__(cls, flag=None, **kw):
        Base.seen.append((cls.__name__, flag, sorted(kw.items())))


kw = {"flag": 1, "extra": 2}


class A(Base, **kw):
    pass


class B(Base, flag=3, **{"z": 4}):
    pass


class C(Base, **{}):
    pass


class D(metaclass=Meta, **{"p": 1, "q": 2}):
    pass


class E(Base, metaclass=Meta, flag=5):
    pass


class F(**{"metaclass": Meta, "r": 6}):
    pass


print(Base.seen)
print(type(F).__name__, type(D).__name__)


class G(Base, flag=7):
    pass


class H(G):
    pass


print(Base.seen)
args = (Base,)


class I(*args, flag="star"):
    pass


print(Base.seen[-1])
try:
    class J(**{"a": 1}):
        pass
except TypeError as e:
    print("TypeError")
try:
    class K(Base, **{1: 2}):
        pass
except TypeError as e:
    print("TypeError2")
