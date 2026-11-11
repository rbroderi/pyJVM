# add_note / __notes__, assignable chaining attributes, identity of caught exceptions
import sys

def attempt(label, fn):
    try:
        print(label, "ok", fn())
    except BaseException as e:
        print(label, type(e).__name__, e)

e = ValueError("v")
print(hasattr(e, "__notes__"), getattr(e, "__notes__", "none"))
e.add_note("first")
e.add_note("second")
print(e.__notes__, type(e.__notes__).__name__)
attempt("non-str note", lambda: e.add_note(5))
attempt("two args", lambda: e.add_note("a", "b"))
attempt("no args", lambda: e.add_note())
e.__notes__ = "not a list"
attempt("notes not list", lambda: e.add_note("x"))
del e.__notes__
print(hasattr(e, "__notes__"))
e.__notes__ = ("a", "b")
attempt("notes tuple", lambda: e.add_note("x"))
e.__notes__ = []
e.add_note("again")
print(e.__notes__)

# notes survive catch and re-raise; the exception object keeps its identity
try:
    try:
        raise KeyError("k")
    except KeyError as k:
        k.add_note("while handling")
        inner = k
        raise
except KeyError as k2:
    print(k2 is inner, k2.__notes__)

try:
    raise inner
except KeyError as k3:
    print(k3 is inner, k3.__notes__)

# user exceptions: notes, attributes, chaining
class MyErr(Exception):
    pass

m = MyErr("mine")
m.add_note("n1")
print(m.__notes__, m.__cause__, m.__context__, m.__traceback__, m.__suppress_context__)
m.extra = 42
print(m.extra, m.args)
try:
    raise m
except MyErr as caught:
    print(caught is m, caught.__traceback__ is not None, caught.extra)

# __cause__ / __context__ / __suppress_context__
c = RuntimeError("c")
v = ValueError("v")
v.__cause__ = c
print(v.__cause__ is c, v.__suppress_context__)
v.__cause__ = None
print(v.__cause__, v.__suppress_context__)
v.__suppress_context__ = False
print(v.__suppress_context__)
v.__context__ = c
print(v.__context__ is c)
attempt("bad cause", lambda: setattr(v, "__cause__", 3))
attempt("bad context", lambda: setattr(v, "__context__", "x"))
attempt("bad tb", lambda: setattr(v, "__traceback__", 3))
print(v.__cause__, v.__context__ is c)

# raise ... from sets cause and suppresses context
try:
    try:
        raise OSError("first")
    except OSError as first:
        raise TypeError("second") from first
except TypeError as second:
    print(type(second.__cause__).__name__, second.__suppress_context__, type(second.__context__).__name__)
    print(second.__cause__ is second.__context__)
try:
    try:
        raise OSError("first")
    except OSError:
        raise TypeError("second") from None
except TypeError as second:
    print(second.__cause__, second.__suppress_context__, type(second.__context__).__name__)

# implicit context
try:
    try:
        raise OSError("first")
    except OSError:
        raise TypeError("second")
except TypeError as second:
    print(second.__cause__, second.__suppress_context__, type(second.__context__).__name__)

# with_traceback
try:
    raise ValueError("tb")
except ValueError as t:
    tb = t.__traceback__
w = ValueError("w")
print(w.with_traceback(tb) is w, w.__traceback__ is tb)
print(w.with_traceback(None) is w, w.__traceback__)
attempt("with_traceback bad", lambda: w.with_traceback(3))

# attributes on builtin exception instances
x = ValueError(1, 2)
x.label = "L"
print(x.label, x.args, x.__dict__)
attempt("missing attr", lambda: x.nope)
del x.label
attempt("deleted attr", lambda: x.label)
x.args = ("replaced",)
print(x.args, str(x), repr(x))
x.args = (1, 2, 3)
print(x.args, str(x))

# sys.exception() / sys.exc_info() inside handlers
print(sys.exception(), sys.exc_info())
try:
    raise ValueError("outer")
except ValueError as outer:
    print(sys.exception() is outer, sys.exc_info()[1] is outer, sys.exc_info()[0] is ValueError, sys.exc_info()[2] is outer.__traceback__)
    try:
        raise KeyError("inner")
    except KeyError as inner_exc:
        print(sys.exception() is inner_exc)
    print(sys.exception() is outer)
print(sys.exception())

def helper():
    return sys.exception()

try:
    raise IndexError("deep")
except IndexError as ie:
    print(helper() is ie)

def gen():
    try:
        raise ArithmeticError("g")
    except ArithmeticError as ae:
        yield sys.exception() is ae
    yield sys.exception()

print(list(gen()))
