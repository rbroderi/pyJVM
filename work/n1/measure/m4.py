import os, signal, sys
env = dict(os.environ)
def out(**kw):
    r, w = os.pipe()
    pid = os.posix_spawn("/bin/sh", ["sh", "-c", "grep -E 'SigBlk|SigIgn' /proc/$$/status | tr '\n\t' '  '"], env, file_actions=[(os.POSIX_SPAWN_DUP2, w, 1)], **kw)
    os.close(w); d = os.read(r, 200); os.close(r); os.waitpid(pid, 0); return d
signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1])
print("default", out())
print("setsigmask=()", out(setsigmask=()))
print("setsigmask=[]", out(setsigmask=[]))
print("setsigmask=[SIGUSR2]", out(setsigmask=[signal.SIGUSR2]))
print("setsigdef=()", out(setsigdef=()))
signal.pthread_sigmask(signal.SIG_UNBLOCK, [signal.SIGUSR1])
for label, fn in (("env 5", lambda: os.posix_spawn("/bin/true", ["true"], 5)), ("env str", lambda: os.posix_spawn("/bin/true", ["true"], "A=1")),
                  ("env key =A", lambda: os.waitpid(os.posix_spawn("/bin/true", ["true"], {"=A": "1"}), 0)[1]),
                  ("path int + argv bad", lambda: os.posix_spawn(5, 5, 5)), ("argv bad + env bad", lambda: os.posix_spawn("/bin/true", 5, 5)),
                  ("argv[0] empty + env bad", lambda: os.posix_spawn("/bin/true", [""], 5)), ("env bad + fa bad", lambda: os.posix_spawn("/bin/true", ["t"], 5, file_actions=5)),
                  ("fa bad + setsigmask bad", lambda: os.posix_spawn("/bin/true", ["t"], env, file_actions=5, setsigmask=5)),
                  ("setpgroup bad + setsigmask bad", lambda: os.posix_spawn("/bin/true", ["t"], env, setpgroup="x", setsigmask=5)),
                  ("setsid + sched bad", lambda: os.posix_spawn("/bin/true", ["t"], env, setsid=True, scheduler=5)),
                  ("setsid + setsigmask bad", lambda: os.posix_spawn("/bin/true", ["t"], env, setsid=True, setsigmask=5)),
                  ("resetids='x' truthy", lambda: os.waitpid(os.posix_spawn("/bin/true", ["t"], env, resetids="x"), 0)[1]),
                  ("killpg big", lambda: os.killpg(2**40, 0)), ("getpgid big", lambda: os.getpgid(2**40)), ("getsid big", lambda: os.getsid(2**40)),
                  ("setpgid big", lambda: os.setpgid(2**40, 0)), ("waitid big", lambda: os.waitid(os.P_PID, 2**40, os.WEXITED)), ("pidfd big", lambda: os.pidfd_open(2**40)),
                  ("wait4 big", lambda: os.wait4(2**40, 0)), ("wait3 big", lambda: os.wait3(2**40)), ("killpg 1arg", lambda: os.killpg(1)), ("waitid 2args", lambda: os.waitid(1, 2)),
                  ("setpgid 1arg", lambda: os.setpgid(1)), ("getsid noarg", lambda: os.getsid()), ("kill True pid?", lambda: os.kill(os.getpid(), False)),
                  ("spawnp name", lambda: os.posix_spawnp(5, ["t"], env)), ("spawnp argv", lambda: os.posix_spawnp("true", [], env)), ("spawnp few", lambda: os.posix_spawnp("true", ["t"])),
                  ("confstr noarg", lambda: os.confstr()), ("confstr kw", lambda: os.confstr(name=0)), ("sysconf True", lambda: os.sysconf(True) == os.sysconf(1))):
    try: print(label, "->", fn())
    except Exception as e: print(label, "-> EXC", type(e).__name__, e)
