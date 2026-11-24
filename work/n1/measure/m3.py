# N1 measurement, part 3: audit events, Popen bookkeeping, misc texts. CPython only, Linux.
import os, sys, signal, subprocess, warnings, gc
ev = []
def hook(name, args):
    if name.startswith(("os.", "subprocess.", "_posixsubprocess.", "signal.")) or "spawn" in name or "fork" in name:
        ev.append((name, tuple(a if isinstance(a, (int, str, bytes, type(None))) else type(a).__name__ for a in args)))
sys.addaudithook(hook)
env = {"A": "1"}
def take(label):
    print(label, [e for e in ev if e[0] not in ("os.listdir", "os.scandir")]); ev.clear()
p = os.posix_spawn("/bin/true", ["true"], env); take("posix_spawn")
os.waitpid(p, 0); take("waitpid")
p = os.posix_spawnp("true", ["true"], env); take("posix_spawnp")
os.wait(); take("wait")
os.kill(os.getpid(), 0); take("kill")
os.killpg(os.getpgrp(), 0); take("killpg")
os.getpgid(0); os.getsid(0); os.sysconf("SC_PAGESIZE"); os.confstr("CS_PATH"); take("getpgid/getsid/sysconf/confstr")
fd = os.pidfd_open(os.getpid()); os.close(fd); take("pidfd_open")
try: os.posix_spawn("/no/such", ["x"], env)
except OSError: pass
take("posix_spawn failing")
subprocess.run(["true"]); take("subprocess.run bare (fork_exec path)")
subprocess.run(["/bin/true"], close_fds=False); take("subprocess.run close_fds=False abs (posix_spawn path)")
subprocess.run(["/bin/true"], cwd="/"); take("subprocess.run cwd")
try: subprocess.run(["/no/such/prog"])
except OSError: pass
take("subprocess.run missing")
p = subprocess.Popen(["sleep", "30"]); ev.clear(); p.send_signal(15); take("send_signal"); p.wait()

# Popen bookkeeping after a failed start, ResourceWarning text, __del__ of a running child
warnings.simplefilter("always")
class P(subprocess.Popen):
    last = None
    def __init__(self, *a, **k):
        P.last = self
        super().__init__(*a, **k)
try: P(["/no/such/prog"])
except OSError as e: print("failed start:", type(e).__name__, "pid set:", isinstance(P.last.pid, int), "returncode:", P.last.returncode, "_child_created:", P.last._child_created)
P.last = None; gc.collect()
try: P(["true"], cwd="/no/such/dir")
except OSError as e: print("failed cwd:", type(e).__name__, "returncode:", P.last.returncode, "_child_created:", P.last._child_created)
try: P(["/no/such/prog"], close_fds=False)
except OSError as e: print("failed posix_spawn path:", type(e).__name__, "has pid:", hasattr(P.last, "pid"), "returncode:", P.last.returncode, "_child_created:", P.last._child_created)
P.last = None
p = subprocess.Popen(["sleep", "30"]); pid = p.pid
del p; gc.collect()
print("_active after dropping a running child:", [x.pid == pid for x in subprocess._active])
os.kill(pid, 9)
import time; time.sleep(0.2)
subprocess._cleanup(); print("_active after cleanup:", len(subprocess._active))
# preexec / user / group / umask texts on CPython, for reference
for kw in ({"preexec_fn": lambda: None}, {"umask": 0o77}, {"user": os.getuid()}, {"group": os.getgid()}, {"extra_groups": []}, {"user": "nosuchuser_xyz"}, {"user": -5}, {"umask": -5}, {"process_group": -5}, {"pass_fds": (-1,)}, {"pass_fds": ("x",)}):
    try:
        r = subprocess.run(["true"], **kw); print(sorted(kw), "->", r.returncode)
    except Exception as e: print(sorted(kw), "-> EXC", type(e).__name__, e)
show = lambda l, f: print(l, f())
import _posixsubprocess
try: os.system
except AttributeError: pass
print("os.system('exit 3') ->", os.system("exit 3"), "| os.popen read:", os.popen("echo hi").read())
