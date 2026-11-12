import sys
import io

# displayhook
import builtins
saved_out = sys.stdout
buf = io.StringIO()
sys.stdout = buf
try:
    builtins._ = None
    sys.displayhook(12)
    sys.displayhook(None)
    sys.displayhook("s")
finally:
    sys.stdout = saved_out
print(repr(buf.getvalue()), builtins._)

# excepthook output goes to stderr
saved_err = sys.stderr
sys.stderr = ebuf = io.StringIO()
try:
    try:
        raise ValueError("boom")
    except ValueError as e:
        sys.excepthook(type(e), e, e.__traceback__)
finally:
    sys.stderr = saved_err
out = ebuf.getvalue()
print("Traceback (most recent call last):" in out, out.rstrip().endswith("ValueError: boom"))

# unraisablehook
class U:
    pass


seen = []
def myhook(u):
    seen.append(u.exc_type.__name__)
old = sys.unraisablehook
sys.unraisablehook = myhook
class D:
    def __del__(self):
        raise KeyError("in del")
import gc
D()
gc.collect()
sys.unraisablehook = old
print(seen in ([], ["KeyError"]))

# audit hooks
events = []
def hook(event, args):
    if event.startswith("pyjvm.test"):
        events.append((event, args))
sys.addaudithook(hook)
sys.audit("pyjvm.test.one", 1, "a")
sys.audit("pyjvm.test.two")
print(events)
try:
    sys.audit()
except TypeError:
    print("audit TypeError")
def bad(event, args):
    if event == "pyjvm.test.boom":
        raise RuntimeError("blocked")
sys.addaudithook(bad)
try:
    sys.audit("pyjvm.test.boom")
except RuntimeError as e:
    print("blocked", e)

# breakpointhook honours PYTHONBREAKPOINT=0
import os
os.environ["PYTHONBREAKPOINT"] = "0"
print(sys.breakpointhook())
os.environ["PYTHONBREAKPOINT"] = "builtins.len"
print(sys.breakpointhook([1, 2, 3]))
del os.environ["PYTHONBREAKPOINT"]

# asyncgen hooks, coroutine origin tracking
h = sys.get_asyncgen_hooks()
print(type(h).__name__, h.firstiter, h.finalizer)
sys.set_asyncgen_hooks(firstiter=print)
print(sys.get_asyncgen_hooks().firstiter is print)
sys.set_asyncgen_hooks(firstiter=None)
print(sys.get_coroutine_origin_tracking_depth())
sys.set_coroutine_origin_tracking_depth(3)
print(sys.get_coroutine_origin_tracking_depth())
sys.set_coroutine_origin_tracking_depth(0)

# int max str digits
old = sys.get_int_max_str_digits()
sys.set_int_max_str_digits(5000)
print(sys.get_int_max_str_digits())
try:
    int("1" * 6000)
except ValueError:
    print("int digits ValueError")
sys.set_int_max_str_digits(old)

# exit
try:
    sys.exit(3)
except SystemExit as e:
    print(e.code)
try:
    sys.exit()
except SystemExit as e:
    print(e.code)

# modules consistency
import json
print(sys.modules["json"] is json, "json.decoder" in sys.modules)
sys.modules["fake_mod_x"] = type(sys)("fake_mod_x")
import fake_mod_x
print(fake_mod_x.__name__)
del sys.modules["fake_mod_x"]
try:
    import fake_mod_x
except ImportError:
    print("ImportError")
