import builtins
import io
import os
import sys

print(IOError is OSError, EnvironmentError is OSError)
print(IOError.__name__, repr(EnvironmentError))
print(builtins.IOError is OSError, builtins.EnvironmentError is OSError)
print("IOError" in dir(builtins), "EnvironmentError" in dir(builtins))
print(hasattr(builtins, "WindowsError") == (sys.platform == "win32"))

try:
    raise OSError(2, "x")
except IOError:
    print("caught IOError")

try:
    raise IOError(2, "x")
except FileNotFoundError as e:
    print(type(e).__name__, e.errno)

try:
    raise OSError(13, "x")
except EnvironmentError as e:
    print("caught env", type(e).__name__)

try:
    raise OSError(13, "x")
except (IOError, ValueError) as e:
    print("caught tuple", type(e).__name__)

try:
    raise OSError(13, "x")
except (ValueError, IOError):
    print("caught tuple2")


class A(IOError):
    pass


print(A.__bases__ == (OSError,), [c.__name__ for c in A.__mro__])
print(isinstance(A(), OSError))

try:
    raise ExceptionGroup("g", [IOError(2, "x")])
except* IOError as eg:
    print("star", [type(x).__name__ for x in eg.exceptions])

errs = (IOError, KeyError)
print([c.__name__ for c in errs])
print(issubclass(FileNotFoundError, IOError))
print(isinstance(PermissionError(), EnvironmentError))


def local_rebind():
    IOError = ValueError
    try:
        raise ValueError("v")
    except IOError:
        print("local rebind caught")


local_rebind()
print(os.error is OSError, io.BlockingIOError is BlockingIOError)

# star import: `except IOError` is evaluated at run time (star imports are only supported in exec'd code)
exec(
    "from string import *\n"
    "try:\n"
    "    raise OSError(13, 'x')\n"
    "except IOError as e:\n"
    "    print('star import caught', type(e).__name__)\n"
    "try:\n"
    "    raise OSError(13, 'x')\n"
    "except (EnvironmentError, KeyError) as e:\n"
    "    print('star import tuple caught', type(e).__name__)\n"
)

if sys.platform == "win32":
    print(WindowsError is OSError)
    try:
        raise OSError(5, "x")
    except WindowsError as e:
        print("caught WindowsError", type(e).__name__)
else:
    try:
        WindowsError
    except NameError as e:
        print(e)
    try:
        try:
            raise OSError(1, "x")
        except WindowsError:
            print("wrong")
    except NameError as e:
        print(e)
