import sys

LIBS = __file__.rpartition("/")[0] + "/libs"
sys.path.insert(0, LIBS)


def show(e):
    text = str(e).replace(LIBS, "<libs>")
    path = getattr(e, "path", None)
    print(type(e).__name__ + ":", text, "| name=%r" % getattr(e, "name", None),
          "path=%r" % (path.replace(LIBS, "<libs>") if isinstance(path, str) else path),
          "name_from=%r" % getattr(e, "name_from", None))


try:
    import nosuch_module_xyz
except ImportError as e:
    show(e)
    print(isinstance(e, ModuleNotFoundError), e.args)

try:
    import errpkg.nosuch
except ImportError as e:
    show(e)

try:
    import errpkg.mod.deeper
except ImportError as e:
    show(e)

try:
    from errpkg import nosuch_name
except ImportError as e:
    show(e)

try:
    from errpkg.mod import nosuch_name
except ImportError as e:
    show(e)

try:
    from . import something
except ImportError as e:
    show(e)

try:
    import errpkg.relbad
except ImportError as e:
    show(e)

for attempt in (1, 2):
    try:
        import errpkg.boom
    except ValueError as e:
        print("attempt", attempt, type(e).__name__, e, "errpkg.boom" in sys.modules)

try:
    import errpkg.syn
except SyntaxError as e:
    print(type(e).__name__, "errpkg.syn" in sys.modules)

sys.modules["blocked_mod"] = None
try:
    import blocked_mod
except ImportError as e:
    show(e)

for call in (lambda: __import__(""), lambda: __import__("x", level=-1), lambda: __import__(1),
             lambda: __import__("errpkg", fromlist=1), lambda: __import__(), lambda: __import__("x", bogus=1)):
    try:
        call()
    except (ValueError, TypeError, ImportError) as e:
        print(type(e).__name__ + ":", e)

print(sorted(k for k in sys.modules if k.startswith("errpkg")))
import errpkg.mod
print(errpkg.mod.VALUE, errpkg.EXPORTED)
