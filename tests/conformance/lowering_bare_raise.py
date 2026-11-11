try:
    raise
except RuntimeError as e:
    print("RuntimeError", e)


def bare():
    raise


try:
    bare()
except RuntimeError as e:
    print("RuntimeError", e)


def nested():
    raise


def reraise():
    try:
        raise TypeError("foo")
    except TypeError:
        nested()


try:
    reraise()
except TypeError as e:
    print("TypeError", e)


def after_handler():
    try:
        raise ValueError("gone")
    except ValueError:
        pass
    raise


try:
    after_handler()
except RuntimeError as e:
    print("RuntimeError", e)
except ValueError as e:
    print("ValueError", e)


def gen():
    try:
        raise KeyError("k")
    except KeyError:
        yield 1
        raise


g = gen()
print(next(g))
try:
    next(g)
except KeyError as e:
    print("KeyError", e)


def in_finally():
    try:
        raise OSError("io")
    finally:
        try:
            raise
        except OSError as e:
            print("finally reraise", e)


try:
    in_finally()
except OSError as e:
    print("OSError", e)

try:
    try:
        raise ValueError(1)
    except ValueError:
        try:
            raise
        except ValueError as inner:
            print("inner", inner)
        raise
except ValueError as outer:
    print("outer", outer)

try:
    try:
        raise ValueError("x")
    except ValueError:
        raise
except ValueError as e:
    print("plain reraise", e)
