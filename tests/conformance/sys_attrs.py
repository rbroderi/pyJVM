import sys

for n in ("prefix", "exec_prefix", "base_prefix", "base_exec_prefix", "platlibdir", "executable", "platform", "version", "abiflags", "float_repr_style", "copyright", "api_version", "hexversion"):
    print(n, type(getattr(sys, n)).__name__)
print(sys.maxsize == 2**63 - 1, sys.maxunicode, sys.byteorder)
print(isinstance(sys.builtin_module_names, tuple), "sys" in sys.builtin_module_names, "builtins" in sys.builtin_module_names)
print(isinstance(sys.stdlib_module_names, frozenset), "os" in sys.stdlib_module_names, "sys" in sys.stdlib_module_names, "unittest" in sys.stdlib_module_names)
print(type(sys.orig_argv).__name__, type(sys.argv).__name__, type(sys.warnoptions).__name__, type(sys._xoptions).__name__)
print(type(sys.dont_write_bytecode).__name__, hasattr(sys, "pycache_prefix"))
print(sys.modules["sys"] is sys, sys.modules["builtins"].__name__)
print(type(sys.meta_path).__name__, type(sys.path_hooks).__name__, type(sys.path_importer_cache).__name__, type(sys.path).__name__)

vi = sys.version_info
print(len(vi), vi.major == vi[0], vi.minor == vi[1], vi.micro == vi[2], vi.releaselevel == vi[3], vi.serial == vi[4])
print(type(vi).__name__, vi >= (3, 13), vi[:2] == (vi.major, vi.minor), vi.n_fields, vi.n_sequence_fields, vi.n_unnamed_fields)
print(vi._fields if hasattr(vi, "_fields") else None)
print(sys.hexversion >> 24 == vi.major, (sys.hexversion >> 16) & 0xff == vi.minor)
print(sys.version.startswith("%d.%d" % (vi.major, vi.minor)))
print(repr(vi).startswith("sys.version_info(major="), vi.__reduce__()[0].__name__ if False else "")

fl = sys.flags
for f in ("debug", "inspect", "interactive", "optimize", "dont_write_bytecode", "no_user_site", "no_site", "ignore_environment", "verbose", "bytes_warning", "quiet", "hash_randomization", "isolated", "dev_mode", "utf8_mode", "warn_default_encoding", "safe_path", "int_max_str_digits"):
    print("flags." + f, type(getattr(fl, f)).__name__)
print(fl[0] == fl.debug, len(fl), type(fl).__name__)

hi = sys.hash_info
print(hi.width, hi.modulus, hi.inf, hi.nan, hi.imag, type(hi.algorithm).__name__, hi.hash_bits, hi.seed_bits, hi.cutoff)
print(hash(1.5) == hash(1.5), hash(2**61 - 1) == 0, hash(float("inf")) == hi.inf)

fi = sys.float_info
print(fi.max, fi.min, fi.epsilon, fi.dig, fi.mant_dig, fi.max_exp, fi.min_exp, fi.max_10_exp, fi.min_10_exp, fi.radix, fi.rounds)
print(len(fi), fi[0] == fi.max)

ii = sys.int_info
print(ii.bits_per_digit, ii.sizeof_digit, type(ii.default_max_str_digits).__name__, type(ii.str_digits_check_threshold).__name__)
print(sys.get_int_max_str_digits() >= 0)

ti = sys.thread_info
print(type(ti.name).__name__, type(ti.lock).__name__, type(ti.version).__name__)

im = sys.implementation
print(type(im.name).__name__, im.name == im.name.lower(), im.cache_tag is None or isinstance(im.cache_tag, str), tuple(im.version)[:2] == tuple(vi)[:2], im.hexversion == sys.hexversion)
print(type(getattr(im, "supports_isolated_interpreters", False)).__name__)
print(type(im).__name__)

print(sys.getdefaultencoding(), sys.getfilesystemencoding().lower().replace("-", ""), sys.getfilesystemencodeerrors())
print(sys.stdout is sys.__stdout__, sys.stderr is sys.__stderr__, sys.stdin is sys.__stdin__)
print(callable(sys.displayhook), sys.displayhook is sys.__displayhook__, callable(sys.excepthook), sys.excepthook is sys.__excepthook__)
print(callable(sys.unraisablehook), sys.unraisablehook is sys.__unraisablehook__, callable(sys.breakpointhook), sys.breakpointhook is sys.__breakpointhook__)
print(callable(sys.getrefcount), sys.getrefcount(object()) > 0, sys.is_finalizing())
print(type(sys.getsizeof(1)).__name__, sys.getsizeof(1) > 0, sys.getsizeof([]) > 0)
try:
    sys.getsizeof(object(), 1, 2)
except TypeError:
    print("getsizeof TypeError")


class S:
    def __sizeof__(self):
        return 5


print(sys.getsizeof(S()) >= 5)
print(sys.getsizeof(S(), 99) >= 5, sys.getsizeof(object(), 99) if False else "")

a = sys.intern("hello world intern")
b = sys.intern("hello " + "world intern")
print(a is b)
try:
    sys.intern(b"x")
except TypeError:
    print("intern TypeError")


class SS(str):
    pass


try:
    sys.intern(SS("x"))
except TypeError:
    print("intern subclass TypeError")

print(type(sys.getswitchinterval()).__name__)
old = sys.getswitchinterval()
sys.setswitchinterval(0.01)
print(sys.getswitchinterval())
sys.setswitchinterval(old)
try:
    sys.setswitchinterval(0)
except ValueError:
    print("switchinterval ValueError")
print(hasattr(sys, "monitoring"), hasattr(sys.monitoring, "use_tool_id"), hasattr(sys.monitoring, "events"), hasattr(sys.monitoring, "DEBUGGER_ID"))
for n in ("activate_stack_trampoline", "deactivate_stack_trampoline", "is_stack_trampoline_active", "getallocatedblocks", "getunicodeinternedsize", "call_tracing", "exception", "exc_info", "last_exc", "get_asyncgen_hooks", "set_asyncgen_hooks", "get_coroutine_origin_tracking_depth", "set_coroutine_origin_tracking_depth", "getdlopenflags", "setdlopenflags", "getrecursionlimit", "setrecursionlimit", "audit", "addaudithook", "_is_interned", "_git", "_getframemodulename", "_clear_type_cache", "_clear_internal_caches", "getwindowsversion", "set_int_max_str_digits", "exit", "gettrace", "getprofile", "is_remote_debug_enabled", "_is_gil_enabled", "_stdlib_dir", "last_type", "last_value", "last_traceback", "last_exc", "ps1"):
    print(n, hasattr(sys, n))
import types
ns = types.SimpleNamespace(a=1)
print(ns.a, type(sys.implementation) is types.SimpleNamespace)
