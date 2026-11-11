import sys

BASE = __file__.rpartition("/")[0] + "/libs"
sys.path.insert(0, BASE)

# sys.path order decides which shadow module wins; path entries are searched again after sys.modules is cleared
sys.path.insert(0, BASE + "/two")
sys.path.insert(0, BASE + "/one")
import shadow
print(shadow.WHICH, shadow.__file__.replace(BASE, "<libs>"))
del sys.modules["shadow"]
sys.path.remove(BASE + "/one")
import shadow
print(shadow.WHICH)

# a module can be placed in sys.modules by hand, and removing it makes the next import execute the file again
import counter_mod
counter_mod.STATE.append(1)
import counter_mod as again
print(again is counter_mod, again.STATE)
del sys.modules["counter_mod"]
import counter_mod as fresh
print(fresh is counter_mod, fresh.STATE)

fake = type(sys)("handmade")
fake.ANSWER = 42
sys.modules["handmade"] = fake
import handmade
print(handmade is fake, handmade.ANSWER, repr(handmade), handmade.__spec__, handmade.__loader__)
from handmade import ANSWER
print(ANSWER)

# PEP 562 module __getattr__
import lazy_attrs
print(lazy_attrs.REAL, lazy_attrs.dyn_thing)
from lazy_attrs import dyn_other
print(dyn_other)
try:
    lazy_attrs.missing
except AttributeError as e:
    print(e)

# star imports into an exec namespace honour __all__
ns = {}
exec("from star_mod import *", ns)
print(sorted(k for k in ns if not k.startswith("__")))

# namespace package: a directory without __init__.py
import nspkg.inner
print(nspkg.inner.NAME, nspkg.__spec__.origin, nspkg.__file__ if hasattr(nspkg, "__file__") else "no __file__",
      [p.replace(BASE, "<libs>") for p in nspkg.__path__], type(nspkg.__loader__).__name__)
print(type(nspkg.__path__).__name__)

# custom finder on sys.meta_path
_boot = sys.modules["_frozen_importlib"]


class Loader:
    @staticmethod
    def create_module(spec):
        return None

    @staticmethod
    def exec_module(module):
        module.GENERATED = "from " + module.__name__


class Finder:
    calls = []

    @classmethod
    def find_spec(cls, name, path=None, target=None):
        cls.calls.append(name)
        if name == "generated_mod":
            return _boot.ModuleSpec(name, Loader)
        return None


sys.meta_path.insert(0, Finder)
import generated_mod
print(generated_mod.GENERATED, generated_mod.__spec__.name, generated_mod.__spec__.loader is Loader)
try:
    import not_generated
except ImportError as e:
    print(type(e).__name__, e)
print(Finder.calls)
sys.meta_path.remove(Finder)
print([f.__name__ for f in sys.meta_path if getattr(f, "__name__", "") in ("BuiltinImporter", "FrozenImporter", "PathFinder")])
print(sys.path_importer_cache.get(BASE) is not None, type(sys.path_importer_cache[BASE]).__name__)
print(callable(sys.path_hooks[-1]), sys.builtin_module_names == tuple(sorted(sys.builtin_module_names)), "sys" in sys.builtin_module_names)
print("sys" in sys.modules, "builtins" in sys.modules, sys.modules["sys"] is sys)
print(sys.implementation.cache_tag is None or isinstance(sys.implementation.cache_tag, str), sys.dont_write_bytecode in (True, False))
