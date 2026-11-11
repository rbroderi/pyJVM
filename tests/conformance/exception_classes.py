class AppError(Exception):
    pass

class DetailError(AppError):
    def __init__(self, message, code):
        super().__init__(message)
        self.code = code

class Bad(ValueError):
    pass

class NoArgs(Exception):
    pass

try:
    raise AppError("boom")
except AppError as e:
    print("caught", e, e.args, type(e).__name__, repr(e), isinstance(e, Exception), isinstance(e, AppError), isinstance(e, ValueError))

try:
    raise DetailError("detail", 42)
except AppError as e:
    print(type(e).__name__, e, e.code, e.args, isinstance(e, DetailError))

try:
    raise Bad("v", 2)
except ValueError as e:
    print("as ValueError:", type(e).__name__, e.args, str(e))

try:
    raise NoArgs
except NoArgs as e:
    print("class form:", repr(e), str(e) == "", e.args)

for exc in (AppError("a"), Bad("b"), KeyError("k")):
    try:
        raise exc
    except (AppError, KeyError) as e:
        print("tuple:", type(e).__name__)
    except ValueError as e:
        print("value:", type(e).__name__)

def risky(n):
    if n == 0:
        raise DetailError("zero", 0)
    return n

for n in (1, 0):
    try:
        print(risky(n))
    except DetailError as e:
        print("handled", e.code)
    finally:
        print("done", n)

try:
    try:
        raise AppError("inner")
    except AppError as e:
        raise Bad("outer") from e
except Bad as e:
    print("chained", e, type(e.__cause__).__name__)

class Quiet(Exception):
    def __str__(self):
        return "custom text"
try:
    raise Quiet()
except Exception as e:
    print(str(e), repr(e.args))

try:
    raise AppError("x")
except Exception:
    print("generic Exception handler")

try:
    try:
        raise Bad("re")
    except Bad:
        raise
except Exception as e:
    print("reraised", type(e).__name__)
print(issubclass(DetailError, AppError), issubclass(Bad, Exception), issubclass(AppError, ValueError))
