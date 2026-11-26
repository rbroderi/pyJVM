# N1 ticket 10 measurement: os.system on the reference interpreters (run: bash run.sh m5.py m5)
import os, signal, sys, time
def t(label, fn):
    label = label.replace(chr(0), "<NUL>")
    try: print(label, "->", repr(fn()))
    except BaseException as e: print(label, "-> EXC", type(e).__name__, e)
sys.stdout.flush()
t('system("exit 3")', lambda: os.system("exit 3"))
t('system("kill -TERM $$")', lambda: os.system("kill -TERM $$"))
t('waitstatus_to_exitcode(system("true"))', lambda: os.waitstatus_to_exitcode(os.system("true")))
t('system(command="exit 5")', lambda: os.system(command="exit 5"))
t('system(b"exit 6")', lambda: os.system(b"exit 6"))
class P:
    def __fspath__(self): return "exit 7"
t('system(PathLike)', lambda: os.system(P()))
t('system(exit 8 surrogateescape)', lambda: os.system("exit 8 # \udcff"))
t('system(exit 9 lone surrogate)', lambda: os.system("exit 9 # \ud800x"))
t('system("")', lambda: os.system(""))
t('system("exit 300")', lambda: os.system("exit 300"))
t('system("nonexistent_cmd_xyz 2>/dev/null")', lambda: os.system("nonexistent_cmd_xyz 2>/dev/null"))
t('system("echo $0 $# ")', lambda: os.system("echo $0 $#"))
t('system()', lambda: os.system())
t('system(5)', lambda: os.system(5))
t('system(None)', lambda: os.system(None))
t('system(["a"])', lambda: os.system(["a"]))
t('system("a\0b")', lambda: os.system("a\0b"))
t('system(b"a\0b")', lambda: os.system(b"a\0b"))
t('system("true", 1)', lambda: os.system("true", 1))
t('system("true", command="x")', lambda: os.system("true", command="x"))
t('system(cmd="true")', lambda: os.system(cmd="true"))
t('system(command="true", x=1)', lambda: os.system(command="true", x=1))
t('system(NUL + bad kw)', lambda: os.system("a\0b", x=1))
# audit events
ev = []
sys.addaudithook(lambda e, a: ev.append((e, a)) if e == "os.system" else None)
os.system("true"); os.system(b"true"); os.system(P())
try: os.system("a\0b")
except ValueError: pass
print("audit:", ev)
def hook_raise(e, a):
    if e == "os.system": raise RuntimeError("audit veto")
# (cannot remove hooks; raising hook last)
# environment, cwd, inheritance
os.putenv("M5_VAR", "put"); os.environ["M5_VAR2"] = "env"
sys.stdout.flush()
os.system('echo "env: $M5_VAR $M5_VAR2"')
os.chdir("/tmp"); os.system("pwd"); os.chdir("/")
sys.stdout.flush()
r, w = os.pipe(); os.set_inheritable(w, True)
os.system(f"echo viapipe >&{w}"); os.close(w); print("pipe:", os.read(r, 100)); os.close(r)
r, w = os.pipe()   # non-inheritable
os.system(f"echo notseen >&{w} 2>/dev/null"); os.close(w); print("noninh pipe:", os.read(r, 100)); os.close(r)
# signal mask and dispositions seen by the child
signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1])
os.system("grep -E 'SigBlk' /proc/$$/status")
signal.pthread_sigmask(signal.SIG_UNBLOCK, [signal.SIGUSR1])
# a SIGALRM handler that raises while the child runs (main thread)
def h(s, f): raise TimeoutError("handler raised")
signal.signal(signal.SIGALRM, h)
t0 = time.monotonic(); signal.setitimer(signal.ITIMER_REAL, 0.3)
try:
    r = os.system("sleep 1; echo child-finished")
    print("alarm: returned", r, "after", round(time.monotonic() - t0, 1))
except BaseException as e:
    print("alarm: EXC", type(e).__name__, e, "after", round(time.monotonic() - t0, 1))
signal.setitimer(signal.ITIMER_REAL, 0)
# is the child reaped? (a leftover zombie would make wait return a pid)
try: print("leftover child:", os.waitpid(-1, os.WNOHANG))
except ChildProcessError as e: print("leftover child: ChildProcessError")
# handler that does not raise
seen = []
signal.signal(signal.SIGALRM, lambda s, f: seen.append(s))
t0 = time.monotonic(); signal.setitimer(signal.ITIMER_REAL, 0.3)
r = os.system("sleep 1"); print("alarm noraise:", r, seen, "after", round(time.monotonic() - t0, 1))
# a raising handler where the exception is seen only after return: is the status lost?
# SIGINT delivered to the parent while system() runs (system(3) ignores SIGINT/SIGQUIT in the parent): use a pid-safe self kill from a child
signal.signal(signal.SIGINT, lambda s, f: seen.append("INT"))
os.system("kill -INT $PPID; sleep 0.3")      # $PPID is this interpreter (the shell's parent): our own pid
print("sigint seen after system:", seen)
os.system("kill -USR2 $PPID; sleep 0.2") if False else None
print("end")
