# A module is written, imported, rewritten and imported again; on pyJVM the second import must not be served from a
# stale compile cache entry, and re-importing unchanged source must behave exactly like the first import.
import importlib
import os
import sys
import tempfile

root = tempfile.mkdtemp(prefix="pyjvm-recompile-")
sys.path.insert(0, root)


def write(rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


write("rc_mod.py", "VALUE = 1\n\ndef f(x):\n    return x + VALUE\n")
import rc_mod
print(rc_mod.VALUE, rc_mod.f(10))

# same source again after dropping the module: a cached class must give the same behaviour
del sys.modules["rc_mod"]
import rc_mod as again
print(again is not rc_mod, again.VALUE, again.f(10))

# edited source (different size): the new text wins
write("rc_mod.py", "VALUE = 22222\nEXTRA = 'edited'\n\ndef f(x):\n    return x * VALUE\n")
del sys.modules["rc_mod"]
import rc_mod as edited
print(edited.VALUE, edited.EXTRA, edited.f(2))

# reload picks up another edit
write("rc_mod.py", "VALUE = 3\n")
importlib.reload(edited)
print(edited.VALUE, hasattr(edited, "EXTRA"))

# a package whose submodule is rewritten
write("rc_pkg/__init__.py", "from . import sub\nNAME = 'pkg'\n")
write("rc_pkg/sub.py", "def who():\n    return 'first'\n")
import rc_pkg
print(rc_pkg.NAME, rc_pkg.sub.who())
write("rc_pkg/sub.py", "def who():\n    return 'second, longer'\n")
importlib.reload(rc_pkg.sub)
print(rc_pkg.sub.who())
print(os.path.basename(rc_mod.__file__), os.path.basename(os.path.dirname(rc_pkg.sub.__file__)))
