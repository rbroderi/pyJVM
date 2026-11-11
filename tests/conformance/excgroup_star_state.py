# except* bodies: sys.exception() is the matched group, a lone new exception is raised unwrapped, split() overrides
import sys

try:
    raise ExceptionGroup("mmu", [OSError("os"), BlockingIOError("io")])
except* BlockingIOError:
    print(repr(sys.exception()))
except* OSError:
    print(repr(sys.exception()))
print(sys.exception())

# a new exception raised by a handler that consumed everything is raised as it is, with the group as context
orig = ExceptionGroup("eg", [TypeError(1), ValueError(2)])
try:
    try:
        raise orig
    except* (TypeError, ValueError) as e:
        raise SyntaxError(3)
except SyntaxError as caught:
    print(type(caught).__name__, repr(caught.__context__), caught.__context__ is orig)

# the handled exception of an enclosing except clause becomes the context after an except* statement finished
try:
    try:
        raise ValueError(42)
    except:
        try:
            raise TypeError(int)
        except* Exception:
            pass
        1 / 0
except Exception as exc:
    print(type(exc).__name__, repr(exc.__context__), sys.exception())


# split overrides and super()
class BadEG1(ExceptionGroup):
    def split(self, *args):
        return "NOT A 2-TUPLE!"


class BadEG2(ExceptionGroup):
    def split(self, *args):
        return ("NOT A 2-TUPLE!",)


class WeirdEG(ExceptionGroup):
    def split(self, *args):
        return super().split(*args) + ("anything", 123456, None)


for cls in (BadEG1, BadEG2):
    try:
        try:
            raise cls("eg", [OSError(123), ValueError(456)])
        except* ValueError:
            pass
        except* OSError:
            pass
    except TypeError as exc:
        print(exc, repr(exc.__context__))

try:
    raise WeirdEG("eg", [OSError(123), ValueError(456)])
except* OSError as e:
    print(repr(e))
except* ValueError as e:
    print(repr(e))
