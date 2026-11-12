import sys
import threading


def inner():
    f = sys._getframe()
    print(f.f_code.co_name, f.f_back.f_code.co_name, type(f.f_lineno).__name__)
    print(sys._getframe(1).f_code.co_name)
    print(f.f_globals["__name__"] == __name__, f.f_back.f_back.f_code.co_name)
    return f


def outer():
    x = 42
    f = inner()
    g = sys._getframe(0)
    print(g.f_locals["x"], "f" in g.f_locals, g.f_lineno > 0)
    print(g.f_code.co_filename == __file__, g.f_code.co_name == outer.__code__.co_name)
    return g


g = outer()
print(g.f_back.f_code.co_name)
try:
    sys._getframe(10 ** 6)
except ValueError:
    print("deep ValueError")

d = sys._current_frames()
print(type(d).__name__, threading.get_ident() in d, d[threading.get_ident()].f_code.co_name)

# exc_info / exception
print(sys.exc_info())
try:
    raise KeyError("k")
except KeyError as e:
    ei = sys.exc_info()
    print(ei[0] is KeyError, ei[1] is e, ei[2] is e.__traceback__)
    if hasattr(sys, "exception"):
        print(sys.exception() is e)
    try:
        raise ValueError("v")
    except ValueError:
        print(sys.exc_info()[0].__name__)
    print(sys.exc_info()[0].__name__)
print(sys.exc_info())


def gen():
    try:
        raise OSError("g")
    except OSError:
        yield sys.exc_info()[0].__name__
    yield sys.exc_info()[0]


print(list(gen()))

# recursion limit
lim = sys.getrecursionlimit()
print(type(lim).__name__, lim > 100)
sys.setrecursionlimit(200)
print(sys.getrecursionlimit())


def rec(n):
    return rec(n + 1)


try:
    rec(0)
except RecursionError:
    print("RecursionError")
sys.setrecursionlimit(lim)
try:
    sys.setrecursionlimit(0)
except ValueError:
    print("limit ValueError")
try:
    sys.setrecursionlimit("x")
except TypeError:
    print("limit TypeError")

# settrace / setprofile storage
print(sys.gettrace(), sys.getprofile())


def tr(frame, event, arg):
    return tr


def pr(frame, event, arg):
    pass


sys.settrace(tr)
print(sys.gettrace() is tr)
sys.settrace(None)
print(sys.gettrace())
sys.setprofile(pr)
print(sys.getprofile() is pr)
sys.setprofile(None)
print(sys.getprofile())

# call_tracing
print(sys.call_tracing(lambda a, b: a + b, (1, 2)))

# traceback frames
try:
    inner_tb = None
    try:
        raise ZeroDivisionError('z')
    except ZeroDivisionError as e:
        tb = e.__traceback__
        print(tb.tb_frame.f_code.co_name, type(tb.tb_lineno).__name__, tb.tb_next)
except Exception:
    print("tb fail")
