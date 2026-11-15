import os
import sys
import time

events = []
active = [False]


def short(a):
    if type(a).__name__ == "frame":
        return "<frame>"
    if isinstance(a, str):
        name = os.path.basename(a) if (os.sep in a or "/" in a) else a
        return "TMP" if name == os.path.basename(d) else name
    if isinstance(a, (bytes, bytearray)):
        return bytes(a)
    if isinstance(a, (list, tuple)):
        return type(a).__name__
    if hasattr(a, "co_name"):
        return "<code>"
    return a


def hook(event, args):
    if active[0]:
        events.append((event, tuple(short(a) for a in args)))


d = os.path.join(os.getcwd(), "audit_events_%d" % os.getpid())
os.mkdir(d)
sys.addaudithook(hook)
# the encodings and io machinery a first open() touches are loaded before the probes start
open(__file__).read()
open(os.path.join(d, "warm.txt"), "w").close()


def show(label, fn):
    events.clear()
    active[0] = True
    try:
        fn()
    except Exception as e:
        print(label, "raised", type(e).__name__, str(e)[:80])
    finally:
        active[0] = False
    print(label, events)


p = os.path.join(d, "f.txt")
flags_ok = []


def opens(mode):
    open(p, mode).close()


for mode in ("w", "r", "rb", "a+", "x"):
    if mode == "x":
        os.remove(p)
    events.clear()
    active[0] = True
    opens(mode)
    active[0] = False
    ((name, (path, m, flags)),) = events
    # the flag bits are platform specific (O_BINARY / O_CLOEXEC): the access mode bits are not
    print("open", mode, name, path, m, flags & 3, bool(flags & (os.O_CREAT)), bool(flags & os.O_TRUNC), bool(flags & os.O_APPEND), bool(flags & os.O_EXCL))

show("os.open", lambda: os.close(os.open(p, os.O_RDONLY)))
show("compile str", lambda: compile("1+1", "<s>", "eval"))
show("compile bytes", lambda: compile(b"1+1", "<s>", "eval"))
show("exec str", lambda: exec("x = 1", {}))
show("exec code", lambda: exec(compile("y = 1", "<c>", "exec"), {}))
show("eval", lambda: eval("1 + 1"))
events.clear()
active[0] = True
try:
    compile("def", "<bad>", "exec")
except SyntaxError:
    pass
active[0] = False
print("syntax error", events[:1])
show("listdir", lambda: os.listdir(d))
show("listdir cwd", lambda: os.listdir())
show("scandir", lambda: list(os.scandir(d)))
show("mkdir", lambda: os.mkdir(os.path.join(d, "sub")))
show("rmdir", lambda: os.rmdir(os.path.join(d, "sub")))
show("rename", lambda: os.rename(p, p + "2"))
show("replace", lambda: os.replace(p + "2", p))
show("remove", lambda: os.remove(p))
show("unlink", lambda: (open(p, "w").close(), os.unlink(p)))
here = os.getcwd()
show("chdir", lambda: os.chdir(d))
os.chdir(here)
show("chmod", lambda: (open(p, "w").close(), os.chmod(p, 0o600)))
show("utime", lambda: os.utime(p, (1, 1)))
show("putenv", lambda: os.putenv("PYJVM_AUDIT_ENV", "v"))
show("unsetenv", lambda: os.unsetenv("PYJVM_AUDIT_ENV"))
show("putenv invalid", lambda: os.putenv("PYJVM=AUDIT", "v"))   # posix validates before the event, nt after it


def fchmod_probe():
    fd = os.open(p, os.O_RDONLY)
    try:
        events.clear()
        os.fchmod(fd, 0o600)   # on Windows a read-only descriptor fails with PermissionError (WinError 5)
    finally:
        # the descriptor number differs between interpreters
        events[:] = [(e, tuple("FD" if x == fd else x for x in a)) for e, a in events]
        os.close(fd)


show("fchmod", fchmod_probe)
show("walk", lambda: list(os.walk(d)))
show("sys._getframe", lambda: sys._getframe() and None)
show("sys.settrace", lambda: sys.settrace(None))
show("sys.setprofile", lambda: sys.setprofile(None))
show("sys._current_frames", lambda: sys._current_frames() and None)
show("sys.audit", lambda: sys.audit("my.event", 1, "x", None))
show("sys.addaudithook", lambda: sys.addaudithook(lambda e, a: None))
show("time.sleep", lambda: time.sleep(0))

# imports: a module not in sys.modules yet raises "import" (name, None, sys.path, sys.meta_path, sys.path_hooks)
imports = []


def import_hook(event, args):
    if event == "import" and active[0]:
        imports.append((args[0], args[1], args[2] is sys.path, args[3] is sys.meta_path, args[4] is sys.path_hooks))


sys.addaudithook(import_hook)
sys.modules.pop("colorsys", None)
active[0] = True
import colorsys
import colorsys as again
try:
    import no_such_module_zz
except ImportError:
    pass
active[0] = False
print("import", imports)

# a hook can veto an event by raising
vetoed = []


def veto(event, args):
    if event == "os.mkdir":
        raise PermissionError("vetoed " + os.path.basename(args[0]))


sys.addaudithook(veto)
try:
    os.mkdir(os.path.join(d, "blocked"))
except PermissionError as e:
    print("veto", e)
print("blocked dir exists", os.path.exists(os.path.join(d, "blocked")))

# argument validation of sys.audit / sys.addaudithook
for bad in (lambda: sys.audit(), lambda: sys.audit(1), lambda: sys.addaudithook()):
    try:
        bad()
    except TypeError as e:
        print("TypeError")

# bytecode caching: cache_tag is None or a str, importlib agrees with it
import importlib.util

tag = sys.implementation.cache_tag
print(tag is None or isinstance(tag, str))
try:
    importlib.util.cache_from_source("x.py")
    cached = True
except NotImplementedError:
    cached = False
print("cache_from_source consistent with cache_tag", cached == (tag is not None))

for name in os.listdir(d):
    os.remove(os.path.join(d, name))
os.rmdir(d)
