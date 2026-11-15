import sys


def tb_info(exc):
    tb = exc.__traceback__
    out = []
    while tb is not None:
        out.append((tb.tb_frame.f_code.co_name, tb.tb_lineno - tb.tb_frame.f_code.co_firstlineno))
        tb = tb.tb_next
    return out


try:
    1 / 0
except ZeroDivisionError as e:
    print("module-level", e.__traceback__ is not None, tb_info(e))


def f():
    return 1 / 0


try:
    f()
except ZeroDivisionError as e:
    print("in function", tb_info(e))


def g():
    return [][3]


def h():
    return g()


try:
    h()
except IndexError as e:
    print("nested", tb_info(e))

try:
    {}["k"]
except KeyError as e:
    print("keyerror", tb_info(e))

try:
    int("x")
except ValueError as e:
    print("builtin raise", tb_info(e))

try:
    undefined_name
except NameError as e:
    print("nameerror", tb_info(e))

try:
    None.attr
except AttributeError as e:
    print("attrerror", tb_info(e))

try:
    "a" + 1
except TypeError as e:
    print("typeerror", tb_info(e))

try:
    import no_such_module_xyz
except ImportError as e:
    print("import", e.__traceback__ is not None)

e2 = None
try:
    1 // 0
except Exception as e:
    e2 = e
print("kept", e2.__traceback__ is not None, e2.__traceback__.tb_frame.f_code.co_name)

try:
    try:
        1 / 0
    except ZeroDivisionError:
        raise
except ZeroDivisionError as e:
    print("reraise", tb_info(e))

try:
    try:
        1 / 0
    except ZeroDivisionError as inner:
        raise ValueError("outer")
except ValueError as e:
    print("context", tb_info(e), tb_info(e.__context__))

try:
    1 / 0
except ZeroDivisionError as e:
    tb = e.__traceback__
    print(sys.exc_info()[2] is tb)
    e.with_traceback(None)
    print(e.__traceback__ is None)


def gen():
    yield 1 / 0


try:
    next(gen())
except ZeroDivisionError as e:
    print("generator", tb_info(e))


class C:
    def __getitem__(self, i):
        return 1 / 0


try:
    C()[0]
except ZeroDivisionError as e:
    print("dunder", tb_info(e))


def fr():
    return sys._getframe()


fo = fr()
print("f_globals", fo.f_globals is globals(), fo.f_globals is sys.modules[__name__].__dict__)
mf = sys._getframe()
print("module frame", mf.f_globals is globals(), mf.f_locals is globals())


class K:
    fk = sys._getframe()


print("class frame", K.fk.f_globals is globals(), K.fk.f_locals is not globals())
g2 = {"__name__": "ns"}
exec("import sys\nfr = sys._getframe()", g2)
print("exec frame", g2["fr"].f_globals is g2)
print("function globals", f.__globals__ is globals())
