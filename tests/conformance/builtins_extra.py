import sys

print(divmod(7, 2), divmod(-7, 2), divmod(7, -2), divmod(-7, -2))
print(divmod(2**70, 3), divmod(-(2**70), 7))
print(divmod(7.5, 2), divmod(-7.5, 2), divmod(7, 2.5), divmod(True, 2))
print(divmod(0, 5), divmod(0.0, -3))
for a, b in [(1, 0), (1.0, 0), (1, 0.0), (1.5, 0.0)]:
    try:
        divmod(a, b)
    except ZeroDivisionError as e:
        print("ZeroDivisionError")
for a, b in [("a", 1), (1, "a"), (None, None), ([], 2)]:
    try:
        divmod(a, b)
    except TypeError as e:
        print("TypeError", e)


class D:
    def __divmod__(self, other):
        return ("d", other)

    def __rdivmod__(self, other):
        return ("r", other)


print(divmod(D(), 5))
print(divmod(5, D()))
print(divmod(D(), D())[0])

print(ascii("abc"), ascii("caf\xe9"), ascii("€\U0001f600"), ascii("\n\t'"))
print(ascii('"'), ascii("'\""), ascii(12), ascii(None), ascii([1, "\xe9"]))
print(ascii(b"ab"), ascii(("x", 1.5)), ascii({"\xe9": 1}), ascii(True))


class R:
    def __repr__(self):
        return "R\xe9"


print(ascii(R()))
print(ascii("\x00\x7f\x80\xffĀ"))

calls = []


def hook(*args, **kwargs):
    calls.append((args, kwargs))
    return "hooked"


saved = sys.breakpointhook
sys.breakpointhook = hook
print(breakpoint())
print(breakpoint(1, 2, a=3))
print(calls)
sys.breakpointhook = saved


def bad_hook():
    raise ValueError("from hook")


sys.breakpointhook = bad_hook
try:
    breakpoint()
except ValueError as e:
    print("ValueError", e)
sys.breakpointhook = saved
del sys.breakpointhook
try:
    breakpoint()
except RuntimeError as e:
    print("RuntimeError", e)
sys.breakpointhook = hook
print(breakpoint())

print(callable(input), callable(ascii), callable(divmod), callable(breakpoint))
print(__debug__)
print(isinstance(SystemError("x"), Exception), SystemError.__name__)
print(issubclass(EncodingWarning, Warning))
