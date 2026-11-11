e = Exception(1, 2, 3)
print(e.args, str(e), repr(e))
e = ValueError("a", "b")
print(e.args, str(e), repr(e))
e = KeyError("a", "b")
print(e.args, str(e))
e = Exception((1, 2))
print(e.args, str(e))
e = Exception()
print(e.args, str(e), repr(e))
e = RuntimeError("one")
print(e.args, str(e), repr(e))
args = (4, 5)
e = TypeError(*args)
print(e.args)
e = TypeError("x", *args)
print(e.args)

try:
    raise OSError("m", "n")
except OSError as err:
    print("caught", err.args)
try:
    raise ValueError("p", 7)
except ValueError as err:
    print("caught", err.args, err)


class Mine(Exception):
    pass


m = Mine(1, 2)
print(m.args, str(m))

ErrCls = ValueError
try:
    raise ValueError("v")
except ErrCls as err:
    print("var class", err)

types = (KeyError, ValueError)
try:
    raise KeyError("k")
except types as err:
    print("var tuple", repr(err))

try:
    raise ValueError("w")
except (TypeError, *types) as err:
    print("starred tuple", err)


def mk():
    return (IndexError, ZeroDivisionError)


try:
    1 / 0
except mk() as err:
    print("call", err)

try:
    raise Mine("custom")
except (ImportError, Mine) as err:
    print("user", err)

Alias = Mine
try:
    raise Mine("alias")
except Alias as err:
    print("alias", err)

nested = (ImportError, KeyError, Mine)
try:
    raise Mine("deep")
except nested as err:
    print("nested", err)

try:
    try:
        raise ValueError("no match")
    except TypeError:
        print("wrong")
except ValueError as err:
    print("passed through", err)

try:
    try:
        raise ValueError("bad")
    except 3:
        print("wrong")
except TypeError as err:
    print("TypeError", err)

try:
    try:
        raise ValueError("bad")
    except (ValueError, "x"):
        print("matched before bad entry")
except TypeError as err:
    print("TypeError", err)

try:
    try:
        raise ValueError("bad")
    except int:
        print("wrong")
except TypeError as err:
    print("TypeError", err)
