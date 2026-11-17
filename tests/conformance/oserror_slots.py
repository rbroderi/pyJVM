# OSError slots on builtin values and constructor protocol of user subclasses (__new__/__init__ variants).
import sys, builtins

def show(label, e):
    print(label, type(e).__name__, e.args, e.errno, e.strerror, e.filename, e.filename2, getattr(e, "winerror", "n/a"), str(e), repr(e))

class MyNew(OSError):
    def __new__(cls, *a):
        return OSError.__new__(cls, *a)

class MyInit(OSError):
    def __init__(self, *a):
        pass

class MyInit2(OSError):
    def __init__(self, *a):
        super().__init__(*a)

class MyBoth(OSError):
    def __new__(cls, *a):
        return OSError.__new__(cls, *a)
    def __init__(self, *a):
        pass

class MyBoth2(OSError):
    def __new__(cls, *a):
        return OSError.__new__(cls, *a)
    def __init__(self, *a):
        super().__init__(*a)

for cls in (MyNew, MyInit, MyInit2, MyBoth, MyBoth2):
    for row in [(1, "x"), (1, "x", "f"), (1, "x", None, 5), (1, "x", "f", 5, "g"), (1,), ()]:
        try:
            show(cls.__name__ + repr(row), cls(*row))
        except Exception as ex:
            print(cls.__name__, row, "EXC", type(ex).__name__, ex)

# attribute set/del on builtin
for mk in (lambda: OSError(1, "x", "f", 5, "g"), lambda: OSError(1, "x"), lambda: OSError(), lambda: PermissionError(1, "x", "f"), lambda: OSError("a", "b")):
    e = mk()
    for name, val in [("winerror", 7), ("winerror", None), ("errno", 99), ("strerror", "S"), ("filename", "F"), ("filename2", "G"), ("characters_written", 3)]:
        e = mk()
        try:
            setattr(e, name, val)
            print("set", name, val, "->", getattr(e, name), str(e), e.args, repr(e))
        except Exception as ex:
            print("set", name, "EXC", type(ex).__name__, ex)
        try:
            delattr(e, name)
            try:
                v = getattr(e, name)
            except Exception as ex:
                v = "EXC " + type(ex).__name__ + " " + str(ex)
            print("del", name, "->", v, str(e), e.args)
        except Exception as ex:
            print("del", name, "EXC", type(ex).__name__, ex)
        try:
            delattr(e, name)
        except Exception as ex:
            print("del2", name, "EXC", type(ex).__name__, ex)
    # BlockingIOError characters_written
e = BlockingIOError(11, "x", 5)
print(e.characters_written, e.args, str(e))
e.characters_written = 9
print(e.characters_written)
try:
    del e.characters_written
except Exception as ex:
    print(type(ex).__name__, ex)
try:
    print(e.characters_written)
except Exception as ex:
    print(type(ex).__name__, ex)
try:
    e.characters_written = "z"
except Exception as ex:
    print(type(ex).__name__, ex)
e = OSError(1, "x")
try:
    print(e.characters_written)
except Exception as ex:
    print(type(ex).__name__, ex)

