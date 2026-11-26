# SPDX-License-Identifier: GPL-3.0-or-later
"""N2 ticket 1: how CPython's struct sequences (os.waitid_result, resource.struct_rusage, pwd.struct_passwd) are
constructed, pickled, copied, compared and sliced. Output is compared across the three reference lanes and is the
reference for PosixStructSeq's constructor. No terminals, limits, locks or signals; read-only.
Run: python structseq.py   (Linux CPython)"""
import copy
import os
import pickle
import pwd
import resource


def t(label, f):
    try:
        r = f()
        print(label, "->", repr(r))
    except BaseException as e:
        print(label, "!!", type(e).__name__, e.args)


def gen(n):
    return (i for i in range(n))


for cls in (os.waitid_result, resource.struct_rusage, pwd.struct_passwd):
    n = cls.n_fields
    print("==", cls.__module__, cls.__name__, n, cls.n_sequence_fields, cls.n_unnamed_fields)
    t("list", lambda: cls(list(range(n))))
    t("tuple", lambda: cls(tuple(range(n))))
    t("generator", lambda: cls(gen(n)))
    t("range", lambda: cls(range(n)))
    t("str-of-right-length", lambda: cls("a" * n))
    t("too short", lambda: cls(range(n - 1)))
    t("too long", lambda: cls(range(n + 1)))
    t("empty", lambda: cls(()))
    t("no args", lambda: cls())
    t("two args", lambda: cls(range(n), range(n)))
    t("dict arg", lambda: cls(range(n), {}))
    t("dict arg 2", lambda: cls(range(n), {"x": 1}))
    t("dict arg non-dict", lambda: cls(range(n), 5))
    t("keyword sequence", lambda: cls(sequence=range(n)))
    t("keyword dict", lambda: cls(range(n), dict={}))
    t("non-iterable", lambda: cls(5))
    t("None", lambda: cls(None))
    t("bytes", lambda: cls(bytes(range(n))))
    r = cls(range(n))
    print(type(r).__name__, r[0], r[-1], r[:2], type(r[:2]).__name__, len(r), tuple(r) == tuple(range(n)))
    print(r == tuple(range(n)), r != tuple(range(n)), hash(r) == hash(tuple(range(n))), r == cls(range(n)), r < cls(range(1, n + 1)))
    print(repr(r))
    t("reduce", lambda: r.__reduce__())
    t("pickle", lambda: pickle.loads(pickle.dumps(r)) == r)
    t("pickle type", lambda: type(pickle.loads(pickle.dumps(r))) is cls)
    for proto in (0, 1, 2, 3, 4, 5):
        t("pickle proto %d" % proto, lambda: pickle.loads(pickle.dumps(r, proto)) == r)
    t("copy", lambda: (copy.copy(r) == r, type(copy.copy(r)) is cls, copy.copy(r) is r))
    t("deepcopy", lambda: (copy.deepcopy(r) == r, type(copy.deepcopy(r)) is cls))
    t("attr after copy", lambda: getattr(copy.copy(r), cls.__match_args__[0]))
    t("attr after pickle", lambda: getattr(pickle.loads(pickle.dumps(r)), cls.__match_args__[-1]))
    t("setattr", lambda: setattr(r, cls.__match_args__[0], 1))
    t("match_args", lambda: cls.__match_args__)
    t("is tuple subclass", lambda: (issubclass(cls, tuple), cls.__mro__))
    t("add", lambda: r + (1,))
    t("replace", lambda: type(r.__replace__(**{cls.__match_args__[0]: 99})).__name__ if hasattr(r, "__replace__") else "absent")
    t("subclass", lambda: type("S", (cls,), {}))
    # review round: attribute rules, argument order of checks, errors raised during iteration
    f0 = cls.__match_args__[0]
    t("setattr field", lambda: setattr(r, f0, 1))
    t("setattr n_fields", lambda: setattr(r, "n_fields", 1))
    t("setattr foo", lambda: setattr(r, "foo", 1))
    t("delattr field", lambda: delattr(r, f0))
    t("delattr n_fields", lambda: delattr(r, "n_fields"))
    t("delattr foo", lambda: delattr(r, "foo"))
    t("class setattr", lambda: (setattr(cls, "x_probe", 1), cls.x_probe, delattr(cls, "x_probe")))
    t("5, 5", lambda: cls(5, 5))
    t("5, None", lambda: cls(5, None))
    t("range, None", lambda: cls(range(n), None))
    t("pos+name sequence", lambda: cls(range(n), sequence=range(n)))
    t("pos+name dict", lambda: cls(range(n), {}, dict={}))
    t("unknown keyword", lambda: cls(range(n), foo=1))
    t("three args", lambda: cls(range(n), {}, 1))
    def bad_gen(exc):
        yield 1
        raise exc
    t("iteration TypeError", lambda: cls(bad_gen(TypeError("inner"))))
    t("iteration ZeroDivisionError", lambda: cls(bad_gen(ZeroDivisionError("zd"))))

    class BadIter:
        def __iter__(self):
            raise TypeError("iter failed")
    t("__iter__ raises TypeError", lambda: cls(BadIter()))
