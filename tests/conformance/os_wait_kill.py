import os

# os.waitpid/wait/waitid/wait3/wait4, the W* macros, os.kill/killpg, process groups, pidfd_open (POSIX only).
# SAFETY: this file never signals pid -1, pgid 0 or pgid 1 with a real signal (os.killpg(1, s) is kill(-1, s): every
# process of the user), and only signals children it created.
print(os.name)

if os.name == "posix":
    import signal
    import sys
    import time

    env = dict(os.environ)
    me = os.getpid()

    def spawn(*argv, **kw):
        return os.posix_spawn(argv[0], list(argv), env, **kw)

    def show(label, fn):
        try:
            print(label, fn())
        except Exception as e:
            print(label, type(e).__name__, getattr(e, "errno", None), e.strerror if isinstance(e, OSError) else e)

    print([(n, getattr(os, n, None)) for n in ("WNOHANG", "WUNTRACED", "WCONTINUED", "WEXITED", "WSTOPPED", "WNOWAIT", "P_PID", "P_PGID", "P_ALL", "P_PIDFD",
                                               "CLD_EXITED", "CLD_KILLED", "CLD_DUMPED", "CLD_TRAPPED", "CLD_STOPPED", "CLD_CONTINUED", "EX_OK", "EX_USAGE", "EX_CONFIG", "EX_NOTFOUND")])
    for st in (0, 0x100, 0xff00, 9, 15, 0x8b, 0x7f, 0x137f, 0xffff, 0x10000, 0x1ff00, -1, 2**31, 0x80):
        show(hex(st), lambda: (os.WIFEXITED(st), os.WEXITSTATUS(st), os.WIFSIGNALED(st), os.WTERMSIG(st), os.WIFSTOPPED(st), os.WSTOPSIG(st), os.WIFCONTINUED(st), os.WCOREDUMP(st)))
        show("exitcode", lambda: os.waitstatus_to_exitcode(st))
    show("x", lambda: os.waitstatus_to_exitcode("x"))
    show("1.0", lambda: os.waitstatus_to_exitcode(1.0))
    show("kw", lambda: (os.waitstatus_to_exitcode(status=0), os.WEXITSTATUS(status=256)))
    show("WIFEXITED x", lambda: os.WIFEXITED("x"))

    # no children yet
    show("waitpid-1", lambda: os.waitpid(-1, 0))
    show("wait", lambda: os.wait())
    show("waitpid me", lambda: os.waitpid(me, 0))
    show("waitpid 1", lambda: os.waitpid(1, os.WNOHANG))
    show("waitpid 0", lambda: os.waitpid(0, os.WNOHANG))
    show("waitpid badopt", lambda: os.waitpid(-1, 12345678))
    show("waitpid str", lambda: os.waitpid("x", 0))
    show("waitpid float", lambda: os.waitpid(1.5, 0))
    show("waitpid kw", lambda: os.waitpid(pid=1, options=0))
    show("wait arg", lambda: os.wait(1))
    show("waitid", lambda: os.waitid(os.P_ALL, 0, os.WEXITED))
    show("waitid me", lambda: os.waitid(os.P_PID, me, os.WEXITED))
    show("waitid 0", lambda: os.waitid(os.P_ALL, 0, 0))
    show("waitid 99", lambda: os.waitid(99, 0, os.WEXITED))
    show("waitid kw", lambda: os.waitid(idtype=os.P_ALL, id=0, options=os.WEXITED))
    if sys.version_info >= (3, 14):                                            # 3.13 words these differently
        show("waitpid big", lambda: os.waitpid(2**40, 0))
        show("waitpid 1arg", lambda: os.waitpid(1))
        show("kill big", lambda: os.kill(2**40, 0))
        show("kill 1arg", lambda: os.kill(me))

    p = spawn("/bin/sh", "-c", "exit 3")
    r = os.waitpid(p, 0)
    print(r[0] == p, r[1], type(r).__name__, os.waitstatus_to_exitcode(r[1]))
    show("again", lambda: os.waitpid(p, 0))
    p = spawn("/bin/sleep", "30")
    print(os.waitpid(p, os.WNOHANG), os.waitid(os.P_PID, p, os.WEXITED | os.WNOHANG))
    os.kill(p, signal.SIGSTOP)
    r = os.waitpid(p, os.WUNTRACED)
    print(r[0] == p, hex(r[1]), os.WIFSTOPPED(r[1]), os.WSTOPSIG(r[1]))
    show("stopped", lambda: os.waitstatus_to_exitcode(r[1]))
    os.kill(p, signal.SIGCONT)
    r = os.waitpid(p, os.WCONTINUED)
    print(r[0] == p, hex(r[1]), os.WIFCONTINUED(r[1]))
    os.kill(p, signal.SIGTERM)
    r = os.waitpid(p, 0)
    print(r[0] == p, r[1], os.WIFSIGNALED(r[1]), os.WTERMSIG(r[1]), os.waitstatus_to_exitcode(r[1]))
    p = spawn("/bin/sh", "-c", "exit 7")
    r = os.wait()
    print(r[0] == p, r[1], type(r).__name__)
    # a blocking wait returns when the child exits (not a poll interval later), from the main thread and from a thread
    t0 = time.monotonic()
    p = spawn("/bin/sleep", "0.3")
    r = os.waitpid(p, 0)
    dt = time.monotonic() - t0
    print(r[0] == p, r[1], 0.25 < dt < 2.0)
    import threading
    p = spawn("/bin/sleep", "0.3")
    res = []
    th = threading.Thread(target=lambda: res.append(os.waitpid(p, 0)[1]))
    th.start()
    th.join()
    print(res)
    # waitid
    p = spawn("/bin/sh", "-c", "exit 4")
    r = os.waitid(os.P_PID, p, os.WEXITED | os.WNOWAIT)
    print(type(r).__name__, len(r), r.si_pid == p, r.si_uid == os.getuid(), r.si_signo, r.si_status, r.si_code, r[0] == p, tuple(r)[2:],
          repr(r).replace(str(p), "PID").replace(str(os.getuid()), "UID"))
    r = os.waitid(os.P_PID, p, os.WEXITED)
    print(r.si_status, r.si_code)
    p = spawn("/bin/sleep", "30")
    os.kill(p, 9)
    r = os.waitid(os.P_ALL, 0, os.WEXITED)
    print(r.si_pid == p, r.si_signo, r.si_status, r.si_code)

    # kill, killpg, process groups
    show("kill me 0", lambda: os.kill(me, 0))
    show("kill nopid", lambda: os.kill(999999999, 0))
    show("kill init", lambda: os.kill(1, 0) if os.getuid() else (_ for _ in ()).throw(PermissionError(1, "Operation not permitted")))
    show("kill 99", lambda: os.kill(me, 99))
    show("kill -1sig", lambda: os.kill(me, -1))
    show("kill 65", lambda: os.kill(me, 65))
    show("kill str", lambda: os.kill("x", 0))
    show("kill float", lambda: os.kill(1.5, 0))
    show("kill kw", lambda: os.kill(pid=me, signal=0))
    show("kill enum", lambda: os.kill(me, signal.SIGCONT))
    show("killpg nopg", lambda: os.killpg(999999999, 0))
    show("killpg own 0", lambda: os.killpg(os.getpgrp(), 0))
    show("killpg 99", lambda: os.killpg(os.getpgrp(), 99))
    show("killpg str", lambda: os.killpg("x", 0))
    show("killpg kw", lambda: os.killpg(pgid=999999999, signal=0))
    show("killpg neg", lambda: os.killpg(-5, 0))
    show("getpgid", lambda: (os.getpgid(0) == os.getpgrp(), os.getpgid(me) == os.getpgrp(), os.getpgid(pid=0) == os.getpgrp(), type(os.getpgrp()).__name__))
    show("getpgid nopid", lambda: os.getpgid(999999999))
    show("getpgid str", lambda: os.getpgid("x"))
    show("getsid", lambda: (os.getsid(0) == os.getsid(me), type(os.getsid(0)).__name__))
    show("getsid nopid", lambda: os.getsid(999999999))
    show("getsid kw", lambda: os.getsid(pid=0))
    show("getpgrp arg", lambda: os.getpgrp(1))
    show("setpgid nopid", lambda: os.setpgid(999999999, 0))
    p = spawn("/bin/sleep", "30")
    time.sleep(0.2)
    show("setpgid after exec", lambda: os.setpgid(p, p))
    os.kill(p, 9)
    os.waitpid(p, 0)
    p = spawn("/bin/sleep", "30", setpgroup=0)
    print(os.getpgid(p) == p, os.getsid(p) == os.getsid(0))
    show("killpg child", lambda: os.killpg(p, signal.SIGTERM))
    print(os.waitstatus_to_exitcode(os.waitpid(p, 0)[1]))
    show("killpg dead", lambda: os.killpg(p, 0))
    p = spawn("/bin/sh", "-c", "exit 0")
    time.sleep(0.3)
    show("kill zombie", lambda: (os.kill(p, 0), os.kill(p, 15)))
    os.waitpid(p, 0)
    show("kill reaped", lambda: os.kill(p, 0))
    got = []
    signal.signal(signal.SIGUSR1, lambda s, f: got.append(s))
    os.kill(me, signal.SIGUSR1)
    print(got)
    signal.signal(signal.SIGUSR1, signal.SIG_DFL)

    show("left", lambda: os.waitpid(-1, os.WNOHANG))
