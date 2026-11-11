REAL = "real"


def __getattr__(name):
    if name.startswith("dyn_"):
        return "dynamic " + name[4:]
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
