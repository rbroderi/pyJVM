# construction rules and error messages for BaseExceptionGroup / ExceptionGroup
def attempt(label, fn):
    try:
        r = fn()
        print(label, "ok", type(r).__name__)
    except BaseException as e:
        print(label, type(e).__name__, e)

attempt("empty list", lambda: ExceptionGroup("m", []))
attempt("empty tuple", lambda: BaseExceptionGroup("m", ()))
attempt("not seq int", lambda: ExceptionGroup("m", 3))
attempt("set", lambda: ExceptionGroup("m", {ValueError()}))
attempt("dict", lambda: ExceptionGroup("m", {ValueError(): 1}))
attempt("gen", lambda: ExceptionGroup("m", (ValueError() for _ in range(2))))
attempt("str seq", lambda: ExceptionGroup("m", "ab"))
attempt("non exc", lambda: ExceptionGroup("m", [ValueError(), 5]))
attempt("class not instance", lambda: ExceptionGroup("m", [ValueError]))
attempt("base in EG", lambda: ExceptionGroup("m", [ValueError(), SystemExit()]))
attempt("msg not str", lambda: ExceptionGroup(5, [ValueError()]))
attempt("msg bytes", lambda: BaseExceptionGroup(b"x", [ValueError()]))
attempt("no args", lambda: ExceptionGroup())
attempt("one arg", lambda: ExceptionGroup("m"))
attempt("three args", lambda: ExceptionGroup("m", [ValueError()], 3))
attempt("kwargs", lambda: ExceptionGroup("m", [ValueError()], x=1))
attempt("kw message", lambda: ExceptionGroup(message="m", exceptions=[ValueError()]))
attempt("auto select", lambda: BaseExceptionGroup("m", [ValueError()]))
attempt("auto select base", lambda: BaseExceptionGroup("m", [GeneratorExit()]))
attempt("nested base", lambda: BaseExceptionGroup("m", [ExceptionGroup("n", [ValueError()]), KeyboardInterrupt()]))
attempt("nested eg in eg", lambda: ExceptionGroup("m", [ExceptionGroup("n", [ValueError()])]))
attempt("beg in eg", lambda: ExceptionGroup("m", [BaseExceptionGroup("n", [KeyboardInterrupt()])]))
attempt("same exc twice", lambda: ExceptionGroup("m", [ValueError(), ValueError()]))

e1 = ValueError("one")
g = ExceptionGroup("m", [e1, e1])
print(len(g.exceptions), g.exceptions[0] is g.exceptions[1] is e1)
lst = [ValueError(1)]
g = ExceptionGroup("m", lst)
lst.append(TypeError(2))
print(len(g.exceptions), type(g.exceptions).__name__, g.args[1] is lst, g.args[1])
tup = (ValueError(1),)
g = ExceptionGroup("m", tup)
print(g.exceptions is tup, g.args[1] is tup)
class Seq:
    def __len__(self): return 2
    def __getitem__(self, i):
        if i >= 2: raise IndexError
        return ValueError(i)
attempt("custom seq", lambda: ExceptionGroup("m", Seq()))
print(ExceptionGroup("m", [ValueError(0)]).exceptions)

print(issubclass(ExceptionGroup, BaseExceptionGroup), issubclass(ExceptionGroup, Exception))
g = ExceptionGroup("m", [ValueError(1)])
print(isinstance(g, BaseExceptionGroup), isinstance(g, Exception), type(g) is ExceptionGroup)
print(g.__class__.__name__, g.__cause__, g.__context__, g.__traceback__, g.__suppress_context__)
print(hasattr(g, "__notes__"), hasattr(g, "split"), hasattr(g, "subgroup"), hasattr(g, "derive"))
g.custom = 5
print(g.custom)

# BaseExceptionGroup.__new__ chooses by contents
for items in ([ValueError()], [KeyboardInterrupt()], [ValueError(), KeyboardInterrupt()], [ExceptionGroup("i", [OSError()])],
              [BaseExceptionGroup("i", [SystemExit()])]):
    print(type(BaseExceptionGroup("x", items)).__name__)

# pickling-free copy semantics: args reflect construction
g = ExceptionGroup("msg", [ValueError("a")])
print(g.args == ("msg", [g.exceptions[0]]), g.args[0], g.args[1][0] is g.exceptions[0])
