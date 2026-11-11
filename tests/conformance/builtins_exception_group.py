eg = ExceptionGroup("msg", [ValueError(1), TypeError("t")])
print(type(eg).__name__, eg.message, len(eg.exceptions))
print(eg.exceptions[0].args, repr(eg.exceptions[1]))
print(str(eg))
print(repr(eg))
print(eg.args[0], len(eg.args[1]))
print(isinstance(eg, Exception), isinstance(eg, BaseException), isinstance(eg, BaseExceptionGroup))
print(issubclass(ExceptionGroup, Exception), issubclass(ExceptionGroup, BaseExceptionGroup))
print(issubclass(BaseExceptionGroup, BaseException), issubclass(BaseExceptionGroup, Exception))

beg = BaseExceptionGroup("base", [KeyboardInterrupt(), ValueError(2)])
print(type(beg).__name__, beg.message, type(beg.exceptions).__name__)
mixed = BaseExceptionGroup("all exc", [ValueError(3)])
print(type(mixed).__name__)
print(isinstance(mixed, ExceptionGroup))

try:
    ExceptionGroup("x", [KeyboardInterrupt()])
except TypeError as e:
    print("TypeError", e)
for bad in (1, "abc", [], [1], None):
    try:
        ExceptionGroup("x", bad)
    except (TypeError, ValueError) as e:
        print(type(e).__name__, e)
try:
    ExceptionGroup("x")
except TypeError as e:
    print("TypeError")
try:
    ExceptionGroup(1, [ValueError()])
except TypeError as e:
    print("TypeError", e)

nested = ExceptionGroup("outer", [ExceptionGroup("inner", [OSError("o")]), ValueError("v")])
print(nested.exceptions[0].message, nested.exceptions[0].exceptions[0].args)

try:
    raise ExceptionGroup("raised", [IndexError("i")])
except ExceptionGroup as caught:
    print("caught", caught.message, caught.exceptions)
except Exception:
    print("other")

try:
    raise ExceptionGroup("raised", [IndexError("i")])
except Exception as caught:
    print("as Exception", type(caught).__name__)

try:
    raise beg
except BaseExceptionGroup as caught:
    print("base caught", caught.message)

errors = (ValueError("a"), TypeError("b"))
eg2 = ExceptionGroup("tuple", errors)
print(type(eg2.exceptions).__name__, eg2.exceptions == errors)
eg3 = ExceptionGroup("list", list(errors))
print(type(eg3.exceptions).__name__)


class MyEG(ExceptionGroup):
    pass


m = MyEG("mine", [ValueError(1)])
print(type(m).__name__, m.message, m.exceptions[0].args, isinstance(m, ExceptionGroup))
