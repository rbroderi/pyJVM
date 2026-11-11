# try/except*: matching, splitting, ordering, re-raise, else/finally, naked exceptions
def show(e):
    if isinstance(e, BaseExceptionGroup):
        return f"{type(e).__name__}({e.message!r}, [{', '.join(show(x) for x in e.exceptions)}])"
    return f"{type(e).__name__}({', '.join(map(repr, e.args))})"

# basic split
try:
    raise ExceptionGroup("g", [ValueError(1), TypeError(2), KeyError(3)])
except* ValueError as e:
    print("V", show(e))
except* TypeError as e:
    print("T", show(e))
except* KeyError as e:
    print("K", show(e))

# unhandled remainder propagates
try:
    try:
        raise ExceptionGroup("g", [ValueError(1), TypeError(2), OSError(3)])
    except* ValueError as e:
        print("V", show(e))
    except* TypeError as e:
        print("T", show(e))
except BaseException as rest:
    print("rest", show(rest))

# naked exception is wrapped
try:
    raise ValueError("naked")
except* ValueError as e:
    print(show(e), type(e).__name__, e.message == "")

# naked exception not matching propagates unchanged
try:
    try:
        raise OSError("o")
    except* ValueError:
        print("no")
except OSError as o:
    print("propagated", show(o))

# tuple of types, ordering: first clause that matches gets it
try:
    raise ExceptionGroup("g", [ValueError(1), TypeError(2)])
except* (ValueError, TypeError) as e:
    print("both", show(e))
except* ValueError:
    print("never")

# nested groups
try:
    raise ExceptionGroup("outer", [ValueError(1), ExceptionGroup("inner", [TypeError(2), ValueError(3)])])
except* ValueError as e:
    print("V", show(e))
except* TypeError as e:
    print("T", show(e))

# bare raise re-raises the matched part, remainder is combined
try:
    try:
        raise ExceptionGroup("g", [ValueError(1), TypeError(2)])
    except* ValueError:
        raise
except BaseException as r:
    print("reraise", show(r))

# raising a new exception inside the handler
try:
    try:
        raise ExceptionGroup("g", [ValueError(1), TypeError(2)])
    except* ValueError as e:
        raise RuntimeError("new")
except BaseException as r:
    print("new", show(r))
    for x in r.exceptions:
        print(" ", type(x).__name__, type(x.__context__).__name__)

# raise e
try:
    try:
        raise ExceptionGroup("g", [ValueError(1), TypeError(2)])
    except* ValueError as e:
        raise e
except BaseException as r:
    print("raise e", show(r))

# else and finally
def run(exc):
    out = []
    try:
        try:
            if exc is not None:
                raise exc
            out.append("body")
        except* ValueError:
            out.append("handler")
        else:
            out.append("else")
        finally:
            out.append("finally")
    except BaseException as r:
        out.append("escaped " + type(r).__name__)
    return out

print(run(None))
print(run(ValueError(1)))
print(run(TypeError(1)))
print(run(ExceptionGroup("g", [ValueError(1), TypeError(2)])))

# handler variable is deleted after the clause
try:
    raise ValueError(1)
except* ValueError as e:
    pass
print("e" in dir())
print([n for n in globals() if "eg_" in n])

# nested try/except* inside a handler
try:
    raise ExceptionGroup("g", [ValueError(1), TypeError(2)])
except* ValueError as e:
    try:
        raise ExceptionGroup("h", [KeyError(1), IndexError(2)])
    except* KeyError as k:
        print("inner", show(k))
    except* IndexError as i:
        print("inner", show(i))
except* TypeError as t:
    print("outer", show(t))

# BaseExceptionGroup with a BaseException
try:
    raise BaseExceptionGroup("b", [KeyboardInterrupt(), ValueError(1)])
except* KeyboardInterrupt as e:
    print("ki", show(e))
except* ValueError as e:
    print("val", show(e))

# invalid except* types
for bad in (int, ExceptionGroup, BaseExceptionGroup):
    try:
        try:
            raise ValueError(1)
        except* bad:
            pass
    except TypeError as t:
        print("TypeError", t)

# the clause types are evaluated even when nothing is left to match
def kind(name):
    print("eval", name)
    return {"V": ValueError, "T": TypeError}[name]

try:
    raise ValueError(1)
except* kind("V"):
    print("handled")
except* kind("T"):
    print("never")

# loops inside the body are fine
for i in range(3):
    try:
        raise ValueError(i)
    except* ValueError as e:
        for j in range(2):
            if j == 1:
                break
        print(i, j)
