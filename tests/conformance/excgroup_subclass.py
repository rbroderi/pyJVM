# subclassing, custom derive, __new__ rules
class MyEG(ExceptionGroup):
    def derive(self, excs):
        print("derive", [type(e).__name__ for e in excs])
        return MyEG(self.message + "'", excs)

eg = MyEG("m", [ValueError(1), TypeError(2), ExceptionGroup("n", [KeyError(3), IndexError(4)])])
sub = eg.subgroup(ValueError)
print(type(sub).__name__, sub.message, sub.exceptions)
m, r = eg.split(LookupError)
print(type(m).__name__, m.message, [type(e).__name__ for e in m.exceptions])
print(type(r).__name__, r.message, [type(e).__name__ for e in r.exceptions])
print(type(m.exceptions[0]).__name__)

class Bad(ExceptionGroup):
    def derive(self, excs):
        return 42

try:
    Bad("b", [ValueError()]).subgroup(ValueError)
    Bad("b", [ValueError(), TypeError()]).subgroup(ValueError)
except TypeError as e:
    print("TypeError", e)

class Plain(ExceptionGroup):
    pass

p = Plain("p", [ValueError(1), TypeError(2)])
s = p.subgroup(ValueError)
print(type(s).__name__, type(p.derive([ValueError()])).__name__)
print(type(Plain("p", [ValueError()])).__name__, isinstance(p, ExceptionGroup), isinstance(p, BaseExceptionGroup))

class BEG(BaseExceptionGroup):
    pass

b = BEG("x", [ValueError()])
print(type(b).__name__, b.exceptions)
class Both(BaseExceptionGroup, Exception):
    pass
print(type(Both("z", [ValueError()])).__name__)
try:
    Both("z", [KeyboardInterrupt()])
except TypeError as e:
    print("TypeError", e)

# __new__ / __init__ with extra arguments
class Extra(ExceptionGroup):
    def __new__(cls, message, excs, code):
        self = super().__new__(cls, message, excs)
        self.code = code
        return self
    def derive(self, excs):
        return Extra(self.message, excs, self.code)

x = Extra("e", [ValueError(1), TypeError(2)], 7)
y = x.subgroup(ValueError)
print(type(y).__name__, y.code, y.exceptions, x.args)

# the args tuple
g = ExceptionGroup("hello", [ValueError(1)])
print(g.args, g.message, g.exceptions)
print(str(g), repr(g))
g = ExceptionGroup("hello", (ValueError(1), TypeError(2)))
print(str(g), repr(g))
g = ExceptionGroup("hello", [ValueError(1), TypeError(2)])
print(repr(g))
g = BaseExceptionGroup("b", [KeyboardInterrupt()])
print(str(g), repr(g))
print(ExceptionGroup("", [ValueError()]).message == "", str(ExceptionGroup("", [ValueError()])))

# exceptions attribute is read-only; message too
try:
    g.exceptions = ()
except AttributeError as e:
    print("AttributeError")
try:
    g.message = "x"
except AttributeError as e:
    print("AttributeError")

# generic alias
print(ExceptionGroup[int])
print(BaseExceptionGroup[ValueError])
