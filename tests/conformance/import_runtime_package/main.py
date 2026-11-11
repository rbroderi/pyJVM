import sys

LIBS = __file__.rpartition("/")[0] + "/libs"
sys.path.insert(0, LIBS)


def rel(path):
    return path.replace(LIBS, "<libs>")


import rtpkg
import rtpkg.sub.leaf
import rtpkg.alpha as alpha
from rtpkg import beta, TOP, BV
from rtpkg.sub import leaf as leaf2
from rtpkg.beta import describe as d

print(rtpkg.TOP, TOP, BV, alpha.ALPHA)
print(beta.describe(), d())
print(leaf2 is rtpkg.sub.leaf, leaf2 is sys.modules["rtpkg.sub.leaf"])
print(rtpkg.sub.leaf.helper())
print(type(rtpkg).__name__, type(rtpkg) is type(sys))
for m in (rtpkg, rtpkg.alpha, rtpkg.sub, rtpkg.sub.leaf):
    print(m.__name__, repr(m.__package__), rel(m.__file__), m.__spec__.name, repr(m.__spec__.parent),
          rel(m.__spec__.origin), m.__spec__.has_location, hasattr(m, "__path__"))
print([rel(p) for p in rtpkg.__path__], [rel(p) for p in rtpkg.sub.__path__])
print(rel(repr(rtpkg)))
print(rel(repr(rtpkg.beta)))
print(type(rtpkg.__loader__).__name__, type(rtpkg.beta.__loader__).__name__)
print(rtpkg.__spec__.submodule_search_locations is not None, rtpkg.beta.__spec__.submodule_search_locations)
print(sorted(k for k in sys.modules if k.startswith("rtpkg")))
print(__import__("rtpkg") is rtpkg, __import__("rtpkg.sub.leaf") is rtpkg)
print(__import__("rtpkg.sub.leaf", fromlist=["x"]) is rtpkg.sub.leaf)
print(__import__("sub.leaf", {"__package__": "rtpkg", "__name__": "rtpkg.zzz"}, None, ["LEAF"], 1) is rtpkg.sub.leaf)
print(__import__("beta", {"__package__": "rtpkg"}, None, (), 1) is rtpkg)
print(rtpkg.__dict__["TOP"], "__builtins__" in vars(rtpkg))
import importlib
print(importlib.import_module("rtpkg.beta") is beta)
print(importlib.import_module(".beta", "rtpkg") is beta)
print(importlib.import_module("..beta", "rtpkg.sub") is beta)
print(importlib.import_module("rtpkg.sub").__name__)
