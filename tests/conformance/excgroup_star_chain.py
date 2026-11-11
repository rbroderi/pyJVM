# try/except*: context, cause and traceback bookkeeping of matched and re-raised groups
import sys
def f():
    try:
        try:
            raise KeyError(0)
        except KeyError as k:
            raise ValueError(1) from k
    except* ValueError as e:
        print(type(e).__name__, repr(e.__context__), repr(e.__cause__), e.__suppress_context__)
        ex = e.exceptions[0]
        print(repr(ex.__context__), repr(ex.__cause__))
        print(e.__traceback__ is ex.__traceback__, e.__traceback__.tb_lineno, ex.__traceback__.tb_lineno)
        tb = e.__traceback__
        while tb: print(tb.tb_lineno, tb.tb_frame.f_code.co_name); tb = tb.tb_next
        print(e.__notes__ if hasattr(e,'__notes__') else 'nonotes')
f()
# raise e (not bare)
try:
    try:
        raise ValueError(1)
    except* ValueError as e:
        raise e
except BaseException as r:
    print(type(r).__name__, r.exceptions, r.__context__)
# raise group member
eg = ExceptionGroup("x", [ValueError(1), TypeError(2)])
try:
    try:
        raise eg
    except* ValueError as e:
        raise
except BaseException as r:
    print(repr(r), r is eg, repr(r.__context__), r.__traceback__ is eg.__traceback__)
    print(r.exceptions[0] is eg.exceptions[1])
try:
    try:
        raise eg
    except* ValueError as e:
        raise e
except BaseException as r:
    print(repr(r), r is eg, repr(r.__context__))
try:
    try:
        raise eg
    except* ValueError as e:
        raise OSError(5)
except BaseException as r:
    print(repr(r), repr(r.exceptions[0].__context__), repr(r.exceptions[1].__context__))
    print(repr(r.__context__))
    print([repr(x) for x in r.exceptions])

# a matched group that gained notes in its handler still counts as re-raised (the notes are dropped)
eg = ExceptionGroup("g", [ValueError("a"), TypeError("b")])
try:
    try:
        raise eg
    except* ValueError as e:
        e.add_note("n")
        raise
except BaseException as r:
    print(repr(r), r is eg, getattr(r, "__notes__", None), getattr(eg, "__notes__", None))
    print([repr(x) for x in r.exceptions])
