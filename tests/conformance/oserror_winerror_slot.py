# The winerror slot of OSError (Windows only; POSIX prints just this line).
import sys

print("start")

if sys.platform == "win32":

    class My(OSError):
        pass

    def show(label, e):
        print(label, type(e).__name__, e.args, e.errno, e.strerror, e.filename, e.filename2, e.winerror, str(e), repr(e))

    rows = [
        (1, "x", None, None), (1, "x", None, 5), (1, "x", "f", None), (1, "x", "f", 5), (1, "x", None, "w"),
        (1, "x", None, 0), (1, "x", None, True), (1, "x", None, 5.0), (1, "x", None, -1),
        (1, "x", None, None, "g"), (1, "x", "f", None, "g"), (1, "x", None, 5, None), (2, "x"), (2, "x", "f"),
    ]
    for cls in (OSError, My, PermissionError):
        for row in rows:
            show(cls.__name__ + repr(row), cls(*row))

    for cls in (OSError, My):
        try:
            cls(1, "x", None, 2 ** 40)
        except OverflowError as ex:
            print(cls.__name__, "OverflowError", ex)

    print(OSError().winerror, OSError(2, "x").winerror, My(2, "x").winerror, My().winerror)
    class MyNew(OSError):
        def __new__(cls, *a):
            return OSError.__new__(cls, *a)

    class MyInit(OSError):
        def __init__(self, *a):
            pass

    class MyBoth(OSError):
        def __new__(cls, *a):
            return OSError.__new__(cls, *a)

        def __init__(self, *a):
            pass

    for cls in (MyNew, MyInit, MyBoth):
        for row in rows[:5] + [(1,), ()]:
            show(cls.__name__ + repr(row), cls(*row))

    for cls in (OSError, My):
        e = cls(1, "x", None, 5)
        e.winerror = None
        print(str(e), e.errno, e.args)
        e.winerror = 7
        print(str(e), e.errno, e.args)
        del e.winerror
        print(str(e), e.winerror)
        e = cls(1, "x")
        e.winerror = 9
        print(str(e), e.errno, e.args)

    try:
        OSError(1, "x", key=1)
    except TypeError as ex:
        print(ex)
