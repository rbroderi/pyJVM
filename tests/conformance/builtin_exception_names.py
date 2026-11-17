import builtins
print(sorted(n for n in dir(builtins) if isinstance(getattr(builtins, n), type) and issubclass(getattr(builtins, n), BaseException)))
for n in ("PythonFinalizationError", "ImportCycleError"):
    t = getattr(builtins, n, None)
    print(n, t is not None, t and t.__module__, t and [c.__name__ for c in t.__mro__])
try:
    raise PythonFinalizationError("x")
except RuntimeError as e:
    print(type(e).__name__, e.args, repr(e))
try:
    raise PythonFinalizationError
except PythonFinalizationError:
    print("caught")
