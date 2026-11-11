import sys

# --- globals()/locals()/vars() in a statically compiled module ----------------------------------
mod_value = 11
g = globals()
print(g is globals(), g["mod_value"], "__name__" in g, g["__name__"])
g["injected"] = 5
print(injected)                         # a name written through globals() is visible to module code
mod_value = 12
print(g["mod_value"], locals() is g, vars() is g)
del g["injected"]
print("injected" in globals())


def counter(a, b=2):
    c = a + b
    snap = locals()
    c = 100
    d = 7
    snap["zzz"] = 1                     # a snapshot: writing it never reaches the function
    return sorted(snap.items()), c, snap is locals()


print(counter(1))


def with_free():
    x = 1
    y = 2

    def inner():
        z = 3
        return sorted(locals().items()) + [x]
    return inner()


print(with_free())


def unbound_names():
    first = 1
    del first
    if False:
        later = 2
    return locals()


print(unbound_names())


def globals_in_function():
    return globals() is g, globals()["mod_value"]


print(globals_in_function())


class Body:
    attr = 3
    inside = sorted(k for k in locals() if not k.startswith("__"))
    locals()["added"] = 4
    same = locals() is vars()


print(Body.inside, Body.added, Body.same)

print(vars(Body)["attr"])

# --- __debug__ ---------------------------------------------------------------------------------
print(__debug__)
try:
    exec("__debug__ = False", {})
except SyntaxError as e:
    print("SyntaxError", e.msg)
print(eval("__debug__", {}))
print(compile("assert 0, 'boom'", "<d>", "exec", optimize=1) is not None)
try:
    exec(compile("assert 0, 'boom'", "<d>", "exec"), {})
except AssertionError as e:
    print("AssertionError", e)
exec(compile("assert 0, 'boom'\nprint('assert skipped', __debug__)", "<d>", "exec", optimize=1), {})
exec(compile("assert 0, 'boom'\nprint('assert skipped', __debug__)", "<d>", "exec", optimize=2), {})

# --- __builtins__ in exec'd namespaces -----------------------------------------------------------
ns = {}
exec("r = len([1, 2, 3])", ns)
print(ns["r"], "__builtins__" in ns, type(ns["__builtins__"]).__name__)
print(ns["__builtins__"]["len"]([1]), ns["__builtins__"]["print"] is print)

caught = []
exec("def shout(*a):\n    caught.append(a)\n\nshout(1)", {"caught": caught, "__builtins__": {}})
print(caught)
try:
    exec("len([1])", {"__builtins__": {}})
except NameError as e:
    print("NameError", e)
exec("print('custom', abs(-3))", {"__builtins__": {"print": lambda *a: caught.append(("custom", len(a), a[0], a[1])), "abs": abs}})
print(caught[-1])
fake = {"__builtins__": {"len": lambda x: "fake len"}}
print(eval("len([1, 2])", fake))
exec("def f():\n    return len('abc')\nout = f()", fake)
print(fake["out"])

kept = {"__builtins__": {"abs": abs}}
exec("x = abs(-4)", kept)
print(kept["__builtins__"] is kept["__builtins__"], sorted(kept["__builtins__"]), kept["x"])

import builtins
print(builtins.len is len, builtins.__dict__["len"] is len, builtins.__dict__ is ns["__builtins__"])
print(callable(__builtins__.__dict__["len"] if hasattr(__builtins__, "__dict__") else __builtins__["len"]))

# --- locals() / globals() / vars() inside exec'd code -------------------------------------------
gl = {}
exec("""
import_free = 1
def f(a):
    b = a * 2
    snap = locals()
    b = 0
    return sorted(snap.items())
res = f(3)
res_globals = sorted(k for k in globals() if not k.startswith("__"))
res_locals = locals() is globals()
res_vars = vars() is globals()
def h():
    return globals() is gl_alias, vars is not None
gl_alias = globals()
res_h = h()
class C:
    q = 1
    names = sorted(k for k in locals() if not k.startswith("__"))
    same = vars() is locals()
res_c = (C.names, C.same)
""", gl)
print(gl["res"], gl["res_globals"], gl["res_locals"], gl["res_vars"], gl["res_h"], gl["res_c"])

gl2, lc2 = {"base": 1}, {}
exec("""
a = 2
res_l = locals() is lc2_alias
def f():
    return globals() is gl2_alias
res_f = f()
res_g = globals() is not locals()
""", dict(gl2, lc2_alias=lc2, gl2_alias=None), lc2)
print(sorted(k for k in lc2 if not k.startswith("__")), lc2["res_l"], lc2["res_g"])

# exec() with no explicit namespaces inside a function sees a snapshot of its locals
def local_exec():
    v = 1
    exec("w = v + 1\nprint(w)")
    return locals()


print(local_exec())

print(eval("sorted(locals())", {"p": 1}, {"q": 2}))
print(eval("sorted(globals())", {"p": 1})[-1:])
print(sorted(eval("locals()", {"__builtins__": {"locals": locals}}, {"only": 1}).items()))
