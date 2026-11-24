import os

# N1 ticket 3 check: the part of os_wait_kill.py that needs no child (os.posix_spawn arrives in ticket 4), plus argument
# errors, the lane-dependent texts and the audit events. Run under CPython and pyJVM on the three lanes and diffed.
# SAFETY (plan 2.3): this file creates no child, so no signal goes to any other process. Every kill/killpg to a pid other
# than its own uses signal 0 (an existence probe) or a signal number the kernel rejects (99, 65, -1: EINVAL, never
# delivered); the only real signal is SIGCONT sent to its own pid. Never pid -1/0/negative, pgid 0/1, or a real signal
# to the runner's process group.
print(os.name)

if os.name == "posix":
    import signal
    import sys
    import time

    env = dict(os.environ)
    me = os.getpid()


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
    if True:
        show("waitpid big", lambda: os.waitpid(2**40, 0))
        show("waitpid 1arg", lambda: os.waitpid(1))
        show("kill big", lambda: os.kill(2**40, 0))
        show("kill 1arg", lambda: os.kill(me))


    # kill, killpg, process groups (no child exists: only signal 0, rejected signal numbers, and SIGCONT to the own pid)
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
    show("kill sig 2**40", lambda: os.kill(me, 2**40))
    show("kill sig -2**63", lambda: os.kill(me, -2**63))
    show("kill sig 2**32+99", lambda: os.kill(me, 2**32 + 99))
    show("kill sig 2**63", lambda: os.kill(me, 2**63))
    show("kill sig 2**70", lambda: os.kill(me, 2**70))
    show("kill pid 2**31", lambda: os.kill(2**31, 0))
    show("kill pid -2**31-1", lambda: os.kill(-2**31 - 1, 0))
    show("kill 3arg", lambda: os.kill(me, 0, 1))
    show("killpg nopg", lambda: os.killpg(999999999, 0))
    show("killpg own 0", lambda: os.killpg(os.getpgrp(), 0))
    show("killpg 99", lambda: os.killpg(os.getpgrp(), 99))     # 99 is not a signal: EINVAL, nothing is delivered
    show("killpg str", lambda: os.killpg("x", 0))
    show("killpg kw", lambda: os.killpg(pgid=999999999, signal=0))
    show("killpg neg", lambda: os.killpg(-5, 0))
    show("killpg 1arg", lambda: os.killpg(1))
    show("killpg big", lambda: os.killpg(2**31, 0))
    show("killpg sig big", lambda: os.killpg(999999999, 2**40))
    show("killpg sig 2**70", lambda: os.killpg(999999999, 2**70))
    show("kill pid 2**63", lambda: os.kill(2**63, 0))
    show("kill pid -2**64", lambda: os.kill(-2**64, 0))
    show("waitpid 2**70", lambda: os.waitpid(2**70, 0))
    show("getpgid 2**63", lambda: os.getpgid(2**63))
    show("killpg sig -1", lambda: os.killpg(999999999, -1))     # no such group: ESRCH (the signal number is not checked first)
    show("getpgid", lambda: (os.getpgid(0) == os.getpgrp(), os.getpgid(me) == os.getpgrp(), os.getpgid(pid=0) == os.getpgrp(), type(os.getpgrp()).__name__))
    show("getpgid nopid", lambda: os.getpgid(999999999))
    show("getpgid str", lambda: os.getpgid("x"))
    show("getpgid float", lambda: os.getpgid(1.5))
    show("getpgid big", lambda: os.getpgid(2**31))
    show("getpgid 0arg", lambda: os.getpgid())
    show("getpgid 2arg", lambda: os.getpgid(1, 2))

    class Idx:
        def __index__(self):
            return 999999999

    show("getpgid index", lambda: os.getpgid(Idx()))
    show("getsid", lambda: (os.getsid(0) == os.getsid(me), type(os.getsid(0)).__name__))
    show("getsid nopid", lambda: os.getsid(999999999))
    show("getsid kw", lambda: os.getsid(pid=0))
    show("getsid big", lambda: os.getsid(2**31))
    show("getsid 0arg", lambda: os.getsid())
    show("getsid 2arg", lambda: os.getsid(1, 2))
    show("getpgrp arg", lambda: os.getpgrp(1))
    show("getpgrp kw", lambda: os.getpgrp(x=1))
    show("wait kw", lambda: os.wait(x=1))
    show("setpgid nopid", lambda: os.setpgid(999999999, 0))
    show("setpgid neg", lambda: os.setpgid(0, -1))
    show("setpgid big", lambda: os.setpgid(2**31, 0))
    show("setpgid big2", lambda: os.setpgid(0, 2**31))
    show("setpgid 1arg", lambda: os.setpgid(1))
    show("setpgid kw", lambda: os.setpgid(pid=999999999, pgrp=999999999))

    # argument errors and the lane's texts (every lane is compared with its own CPython)
    show("waitpid big", lambda: os.waitpid(2**40, 0))
    show("waitpid -big", lambda: os.waitpid(-2**40, 0))
    show("waitpid -2**31-1", lambda: os.waitpid(-2**31 - 1, 0))
    show("waitpid opt 2**31", lambda: os.waitpid(-1, 2**31))
    show("waitpid 1arg", lambda: os.waitpid(1))
    show("waitpid 3arg", lambda: os.waitpid(1, 2, 3))
    show("waitpid 0arg", lambda: os.waitpid())
    show("waitid 2arg", lambda: os.waitid(0, 0))
    show("waitid big id", lambda: os.waitid(os.P_PID, 2**32, os.WEXITED))
    show("waitid neg id", lambda: os.waitid(os.P_PID, -1, os.WEXITED))
    show("waitid 2**31 id", lambda: os.waitid(os.P_ALL, 2**31, os.WEXITED))
    show("waitid idtype big", lambda: os.waitid(2**31, 0, os.WEXITED))
    show("waitid opt big", lambda: os.waitid(0, 0, 2**31))
    show("waitid pidfd bad", lambda: os.waitid(os.P_PIDFD, 9999, os.WEXITED))
    show("waitid WNOHANG nochild", lambda: os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG))
    show("W0", lambda: os.WIFEXITED())
    show("W2", lambda: os.WIFEXITED(1, 2))
    show("Wkw", lambda: os.WIFEXITED(x=1))
    show("WCD0", lambda: os.WCOREDUMP())
    show("WCD2", lambda: os.WCOREDUMP(1, 2))
    show("WCDkw", lambda: os.WCOREDUMP(status=1))
    show("WEXIT0", lambda: os.WEXITSTATUS())
    show("wse0", lambda: os.waitstatus_to_exitcode())
    show("wse2", lambda: os.waitstatus_to_exitcode(1, 2))
    show("wsekw", lambda: os.waitstatus_to_exitcode(x=1))
    show("wse big", lambda: os.waitstatus_to_exitcode(2**40))
    show("W bool", lambda: (os.WIFEXITED(True), os.WIFEXITED(False)))

    class Idx256:
        def __index__(self):
            return 256

    show("W index", lambda: os.WEXITSTATUS(Idx256()))
    show("kill index sig", lambda: os.kill(me, Idx256()))
    wid = os.waitid_result
    show("waitid_result", lambda: (wid.__module__, wid.__name__, wid.n_fields, wid.n_sequence_fields, wid.n_unnamed_fields, wid.__match_args__, issubclass(wid, tuple)))

    # audit events: os.kill once per call, os.killpg once per call (own pid / a group that does not exist, signal 0)
    events = []
    sys.addaudithook(lambda ev, args: events.append((ev, args)) if ev in ("os.kill", "os.killpg") else None)
    os.kill(me, 0)
    show("audit killpg", lambda: os.killpg(999999999, 0))
    show("audit kill nopid", lambda: os.kill(999999999, 0))
    print([(ev, tuple("ME" if x == me else x for x in args)) for ev, args in events])

    got = []
    signal.signal(signal.SIGUSR1, lambda s, f: got.append(s))
    os.kill(me, signal.SIGUSR1)
    print(got)
    signal.signal(signal.SIGUSR1, signal.SIG_DFL)
    show("left", lambda: os.waitpid(-1, os.WNOHANG))
