import atexit
import sys


def hello(*args, **kwargs):
    print("hello", args, kwargs)


def bye():
    print("bye")


def bad():
    raise ValueError("ignored at exit")


print(atexit._ncallbacks())
r = atexit.register(hello, 1, 2, k=3)
print(r is hello)
atexit.register(bye)
atexit.register(bad)


@atexit.register
def decorated():
    print("decorated")


atexit.register(hello, "twice")
atexit.register(hello, "twice")
print(atexit._ncallbacks())
atexit.unregister(hello)
atexit.register(hello, "kept")
try:
    atexit.register(1)
except TypeError as e:
    print("TypeError", e)
try:
    atexit.register()
except TypeError as e:
    print("TypeError")

# _run_exitfuncs runs last-registered first and leaves the registry empty
atexit._run_exitfuncs()
print(atexit._ncallbacks())
atexit.register(print, "cleared")
atexit._clear()
print(atexit._ncallbacks())

# callbacks registered while running at exit are run too, last in first out
atexit.register(print, "first registered")


def spawner():
    print("spawner")
    atexit.register(print, "registered during exit")


atexit.register(spawner)
print("end of program", file=sys.stdout)
