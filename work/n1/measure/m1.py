# N1 measurement, part 1: surfaces, constants, signatures, wait/kill/sysconf behaviour. CPython only, Linux.
import os, sys, signal, errno, time, subprocess, tempfile, json

def show(label, fn):
    try:
        r = fn()
        print(label, "->", repr(r))
    except BaseException as e:
        extra = ""
        if isinstance(e, OSError):
            extra = " errno=%r filename=%r filename2=%r" % (e.errno, e.filename, e.filename2)
        print(label, "-> EXC", type(e).__name__ + ":", e, extra)

print("version", sys.version_info[:2])
names = """posix_spawn posix_spawnp POSIX_SPAWN_OPEN POSIX_SPAWN_CLOSE POSIX_SPAWN_DUP2 POSIX_SPAWN_CLOSEFROM
waitpid wait wait3 wait4 waitid waitid_result waitstatus_to_exitcode WIFEXITED WEXITSTATUS WIFSIGNALED WTERMSIG WIFSTOPPED WSTOPSIG WIFCONTINUED WCOREDUMP
WNOHANG WUNTRACED WCONTINUED WEXITED WSTOPPED WNOWAIT P_PID P_PGID P_ALL P_PIDFD CLD_EXITED CLD_KILLED CLD_DUMPED CLD_TRAPPED CLD_STOPPED CLD_CONTINUED
kill killpg sysconf sysconf_names confstr confstr_names pidfd_open PIDFD_NONBLOCK getpgid getpgrp setpgid getsid setsid setpgrp tcgetpgrp tcsetpgrp
system popen fork forkpty execv execve spawnv spawnve spawnl spawnlp spawnvp spawnvpe P_WAIT P_NOWAIT P_NOWAITO _exit abort
EX_OK EX_USAGE EX_DATAERR EX_NOINPUT EX_NOUSER EX_NOHOST EX_UNAVAILABLE EX_SOFTWARE EX_OSERR EX_OSFILE EX_CANTCREAT EX_IOERR EX_TEMPFAIL EX_PROTOCOL EX_NOPERM EX_CONFIG EX_NOTFOUND
getpid getppid times nice getpriority setpriority cpu_count process_cpu_count sched_getaffinity sched_setaffinity sched_param sched_yield getlogin uname ctermid getgroups
getresuid getresgid setuid setgid seteuid setegid setreuid setregid setgroups initgroups getloadavg register_at_fork unshare setns
SCHED_OTHER SCHED_FIFO SCHED_RR SCHED_BATCH SCHED_IDLE SCHED_RESET_ON_FORK environ environb getenv getenvb putenv unsetenv get_exec_path""".split()
for n in names:
    v = getattr(os, n, None)
    if v is None and not hasattr(os, n):
        print("os." + n, "ABSENT")
    elif isinstance(v, int):
        print("os." + n, "=", v)
    else:
        print("os." + n, "present", type(v).__name__, getattr(v, "__text_signature__", None), "| defined in os.py" if getattr(v, "__module__", None) == "os" else "")
import posix
print("posix-only names missing from above (process-ish):", sorted(n for n in dir(posix) if n.startswith(("W", "P_", "CLD_", "POSIX_SPAWN", "PIDFD", "EX_")) and n not in names))
import _posixsubprocess
print("_posixsubprocess dir", sorted(n for n in dir(_posixsubprocess) if not n.startswith("__")))
print("fork_exec sig", _posixsubprocess.fork_exec.__text_signature__)
print("fork_exec doc", repr(_posixsubprocess.fork_exec.__doc__))
print("subprocess flags", subprocess._USE_POSIX_SPAWN, getattr(subprocess, "_USE_VFORK", "n/a"), subprocess._HAVE_POSIX_SPAWN_CLOSEFROM, getattr(subprocess, "_CAN_USE_PIDFD_OPEN", "n/a"))

# ---- sysconf / confstr tables
print("sysconf_names count", len(os.sysconf_names), "confstr_names count", len(os.confstr_names))
print("SYSCONF_TABLE", json.dumps(sorted(os.sysconf_names.items())))
print("CONFSTR_TABLE", json.dumps(sorted(os.confstr_names.items())))
for n in ("SC_PAGESIZE", "SC_PAGE_SIZE", "SC_CLK_TCK", "SC_OPEN_MAX", "SC_ARG_MAX", "SC_NPROCESSORS_ONLN", "SC_NPROCESSORS_CONF", "SC_GETPW_R_SIZE_MAX", "SC_GETGR_R_SIZE_MAX", "SC_SEM_NSEMS_MAX", "SC_MINSIGSTKSZ", "SC_NGROUPS_MAX", "SC_HOST_NAME_MAX", "SC_LINE_MAX", "SC_PHYS_PAGES", "SC_IOV_MAX"):
    show("sysconf(%r)" % n, lambda n=n: os.sysconf(n) if n in os.sysconf_names else "NAME ABSENT")
show("sysconf(30)", lambda: os.sysconf(30))
show("sysconf('NOPE')", lambda: os.sysconf("NOPE"))
show("sysconf(99999)", lambda: os.sysconf(99999))
show("sysconf(-5)", lambda: os.sysconf(-5))
show("sysconf(1.5)", lambda: os.sysconf(1.5))
show("sysconf(None)", lambda: os.sysconf(None))
show("sysconf(2**40)", lambda: os.sysconf(2**40))
show("sysconf(b'SC_PAGESIZE')", lambda: os.sysconf(b"SC_PAGESIZE"))
show("sysconf()", lambda: os.sysconf())
show("sysconf(name=30)", lambda: os.sysconf(name=30))
show("sysconf SC_ unsupported -> -1?", lambda: [(n, os.sysconf(n)) for n in sorted(os.sysconf_names) if os.sysconf(n) == -1][:60])
def all_sysconf():
    bad = []
    for n, v in sorted(os.sysconf_names.items()):
        try: os.sysconf(n)
        except OSError as e: bad.append((n, v, e.errno))
    return bad
show("sysconf names raising", all_sysconf)
for n in ("CS_PATH", "CS_GNU_LIBC_VERSION", "CS_GNU_LIBPTHREAD_VERSION"):
    show("confstr(%r)" % n, lambda n=n: os.confstr(n))
show("confstr(2)", lambda: os.confstr(2))
show("confstr('NOPE')", lambda: os.confstr("NOPE"))
show("confstr(99999)", lambda: os.confstr(99999))
show("confstr(-1)", lambda: os.confstr(-1))
show("confstr(1.5)", lambda: os.confstr(1.5))
show("confstr(2**40)", lambda: os.confstr(2**40))
def all_confstr():
    out = []
    for n, v in sorted(os.confstr_names.items()):
        try: r = os.confstr(n); out.append((n, v, None if r is None else ("''" if r == "" else "str")))
        except OSError as e: out.append((n, v, "errno %d" % e.errno))
    return out
show("confstr all kinds", all_confstr)
show("sysconf_names type", lambda: (type(os.sysconf_names).__name__, type(os.confstr_names).__name__))

# ---- W* macros and waitstatus_to_exitcode
for st in (0, 0x100, 0xff00, 9, 15, 0x8b, 0x7f, 0x137f, 0xffff, 0x10000, 0x1ff00, -1, 2**31, 0x80):
    show("W*(%#x)" % st, lambda st=st: (os.WIFEXITED(st), os.WEXITSTATUS(st), os.WIFSIGNALED(st), os.WTERMSIG(st), os.WIFSTOPPED(st), os.WSTOPSIG(st), os.WIFCONTINUED(st), os.WCOREDUMP(st)))
    show("waitstatus_to_exitcode(%#x)" % st, lambda st=st: os.waitstatus_to_exitcode(st))
show("waitstatus_to_exitcode('x')", lambda: os.waitstatus_to_exitcode("x"))
show("waitstatus_to_exitcode(1.0)", lambda: os.waitstatus_to_exitcode(1.0))
show("waitstatus_to_exitcode(2**40)", lambda: os.waitstatus_to_exitcode(2**40))
show("waitstatus_to_exitcode(status=0)", lambda: os.waitstatus_to_exitcode(status=0))
show("WIFEXITED(2**40)", lambda: os.WIFEXITED(2**40))
show("WIFEXITED('x')", lambda: os.WIFEXITED("x"))
show("WEXITSTATUS(status=256)", lambda: os.WEXITSTATUS(status=256))

# ---- wait family
me = os.getpid()
show("waitpid no children", lambda: os.waitpid(-1, 0))
show("wait no children", lambda: os.wait())
show("waitpid(own pid)", lambda: os.waitpid(me, 0))
show("waitpid(1, WNOHANG)", lambda: os.waitpid(1, os.WNOHANG))
show("waitpid(0, WNOHANG) no children", lambda: os.waitpid(0, os.WNOHANG))
show("waitpid(-1, 12345678)", lambda: os.waitpid(-1, 12345678))
show("waitpid('x', 0)", lambda: os.waitpid("x", 0))
show("waitpid(1.5, 0)", lambda: os.waitpid(1.5, 0))
show("waitpid(2**40, 0)", lambda: os.waitpid(2**40, 0))
show("waitpid(1, 2**40)", lambda: os.waitpid(1, 2**40))
show("waitpid(1)", lambda: os.waitpid(1))
show("waitpid(pid=1, options=0)", lambda: os.waitpid(pid=1, options=0))
show("wait(1)", lambda: os.wait(1))
show("wait3(0) no children", lambda: os.wait3(0))
show("wait4(-1, 0) no children", lambda: os.wait4(-1, 0))
show("wait4(pid=-1, options=0)", lambda: os.wait4(pid=-1, options=0))
show("wait3(options=0)", lambda: os.wait3(options=0))
show("waitid(P_ALL, 0, WEXITED) no children", lambda: os.waitid(os.P_ALL, 0, os.WEXITED))
show("waitid(P_PID, me, WEXITED)", lambda: os.waitid(os.P_PID, me, os.WEXITED))
show("waitid(P_ALL, 0, 0)", lambda: os.waitid(os.P_ALL, 0, 0))
show("waitid(99, 0, WEXITED)", lambda: os.waitid(99, 0, os.WEXITED))
show("waitid(idtype=P_ALL, id=0, options=WEXITED)", lambda: os.waitid(idtype=os.P_ALL, id=0, options=os.WEXITED))

def spawn(*argv, **kw):
    return os.posix_spawn(argv[0], list(argv), os.environ, **kw)

p = spawn("/bin/sh", "-c", "exit 3")
r = os.waitpid(p, 0)
print("waitpid exit3", r[0] == p, r[1], type(r).__name__, os.waitstatus_to_exitcode(r[1]))
show("waitpid again (reaped)", lambda: os.waitpid(p, 0))
p = spawn("/bin/sleep", "30")
print("waitpid WNOHANG running", os.waitpid(p, os.WNOHANG))
show("waitid WNOHANG running", lambda: os.waitid(os.P_PID, p, os.WEXITED | os.WNOHANG))
os.kill(p, signal.SIGSTOP)
r = os.waitpid(p, os.WUNTRACED)
print("stopped", r[0] == p, hex(r[1]), os.WIFSTOPPED(r[1]), os.WSTOPSIG(r[1]))
show("waitstatus_to_exitcode(stopped)", lambda: os.waitstatus_to_exitcode(r[1]))
os.kill(p, signal.SIGCONT)
r = os.waitpid(p, os.WCONTINUED)
print("continued", r[0] == p, hex(r[1]), os.WIFCONTINUED(r[1]))
os.kill(p, signal.SIGTERM)
r = os.waitpid(p, 0)
print("killed TERM", r[0] == p, r[1], os.WIFSIGNALED(r[1]), os.WTERMSIG(r[1]), os.waitstatus_to_exitcode(r[1]))
p = spawn("/bin/sh", "-c", "kill -SEGV $$")
r = os.waitpid(p, 0)
print("killed SEGV status&0x7f, WCOREDUMP is bool", r[1] & 0x7f, type(os.WCOREDUMP(r[1])).__name__)
p = spawn("/bin/sh", "-c", "exit 7")
r = os.wait()
print("wait()", r[0] == p, r[1], type(r).__name__)
p = spawn("/bin/sh", "-c", "exit 5")
r = os.wait3(0)
print("wait3", len(r), r[0] == p, r[1], type(r[2]).__module__, type(r[2]).__name__, len(r[2]), type(r[2].ru_utime).__name__, type(r[2].ru_maxrss).__name__, r[2].ru_maxrss > 0, type(r).__name__)
p = spawn("/bin/sh", "-c", "exit 6")
r = os.wait4(p, 0)
print("wait4", len(r), r[0] == p, r[1], type(r[2]).__name__, [type(x).__name__ for x in r[2]])
p = spawn("/bin/sleep", "30")
print("wait3 WNOHANG running", os.wait3(os.WNOHANG)[:2], "rusage all zero:", all(x == 0 for x in os.wait3(os.WNOHANG)[2]))
print("wait4 WNOHANG running", os.wait4(p, os.WNOHANG)[:2])
os.kill(p, 9); os.waitpid(p, 0)
p = spawn("/bin/sh", "-c", "exit 4")
r = os.waitid(os.P_PID, p, os.WEXITED | os.WNOWAIT)
print("waitid NOWAIT", type(r).__module__, type(r).__name__, len(r), r.si_pid == p, r.si_uid == os.getuid(), r.si_signo, r.si_status, r.si_code, repr(r).replace(str(p), "PID").replace(str(os.getuid()), "UID"))
print("waitid fields", [n for n in dir(r) if n.startswith("si_")], type(r).n_fields, type(r).n_sequence_fields)
r = os.waitid(os.P_PID, p, os.WEXITED)
print("waitid reap", r.si_status, r.si_code)
p = spawn("/bin/sleep", "30"); os.kill(p, 9)
r = os.waitid(os.P_ALL, 0, os.WEXITED)
print("waitid killed", r.si_pid == p, r.si_signo, r.si_status, r.si_code)
p = spawn("/bin/sleep", "30")
fd = os.pidfd_open(p)
print("pidfd_open", fd > 2, os.get_inheritable(fd), os.get_blocking(fd))
os.kill(p, 15)
r = os.waitid(os.P_PIDFD, fd, os.WEXITED)
print("waitid P_PIDFD", r.si_pid == p, r.si_status, r.si_code)
os.close(fd)
show("pidfd_open(reaped pid)", lambda: os.pidfd_open(p))
show("pidfd_open(-1)", lambda: os.pidfd_open(-1))
show("pidfd_open(me, 12345)", lambda: os.pidfd_open(me, 12345))
show("pidfd_open sig", lambda: os.pidfd_open.__text_signature__)
show("pidfd_open(pid=me, flags=0) works", lambda: os.close(os.pidfd_open(pid=me, flags=0)))

# ---- kill / killpg / pgid
show("kill(me, 0)", lambda: os.kill(me, 0))
show("kill(999999999, 0)", lambda: os.kill(999999999, 0))
show("kill(1, 0)", lambda: os.kill(1, 0))
show("kill(me, 99)", lambda: os.kill(me, 99))
show("kill(me, -1)", lambda: os.kill(me, -1))
show("kill(me, 65)", lambda: os.kill(me, 65))
show("kill(me, 64) blocked?", lambda: "skipped")
show("kill('x', 0)", lambda: os.kill("x", 0))
show("kill(1.5, 0)", lambda: os.kill(1.5, 0))
show("kill(2**40, 0)", lambda: os.kill(2**40, 0))
show("kill(me, 2**40)", lambda: os.kill(me, 2**40))
show("kill(me)", lambda: os.kill(me))
show("kill(pid=me, signal=0)", lambda: os.kill(pid=me, signal=0))
show("kill(me, signal.SIGCONT) enum", lambda: os.kill(me, signal.SIGCONT))
show("killpg(999999999, 0)", lambda: os.killpg(999999999, 0))
show("killpg(os.getpgrp(), 0)", lambda: os.killpg(os.getpgrp(), 0))
show("killpg(os.getpgrp(), 99)", lambda: os.killpg(os.getpgrp(), 99))
show("killpg('x', 0)", lambda: os.killpg("x", 0))
show("killpg(pgid=999999999, signal=0)", lambda: os.killpg(pgid=999999999, signal=0))
show("killpg(-5, 0)", lambda: os.killpg(-5, 0))
show("killpg(0, 0)", lambda: os.killpg(0, 0))
show("getpgid(0)==getpgrp()", lambda: os.getpgid(0) == os.getpgrp())
show("getpgid(me)==getpgrp()", lambda: os.getpgid(me) == os.getpgrp())
show("getpgid(999999999)", lambda: os.getpgid(999999999))
show("getpgid(pid=0) kw", lambda: os.getpgid(pid=0) == os.getpgrp())
show("getpgid('x')", lambda: os.getpgid("x"))
show("getsid(0)==getsid(me)", lambda: os.getsid(0) == os.getsid(me))
show("getsid(999999999)", lambda: os.getsid(999999999))
show("getsid(pid=0) kw", lambda: os.getsid(pid=0))
show("getpgrp(1)", lambda: os.getpgrp(1))
show("setpgid(999999999, 0)", lambda: os.setpgid(999999999, 0))
p = spawn("/bin/sleep", "30")
show("setpgid(child, child) after exec", lambda: os.setpgid(p, p))
os.kill(p, 9); os.waitpid(p, 0)
p = spawn("/bin/sleep", "30", setpgroup=0)
print("child own group", os.getpgid(p) == p, os.getsid(p) == os.getsid(0))
show("killpg(child group, TERM)", lambda: os.killpg(p, signal.SIGTERM))
print("after killpg", os.waitstatus_to_exitcode(os.waitpid(p, 0)[1]))
show("killpg(dead group, 0)", lambda: os.killpg(p, 0))
# kill of a zombie (exited, unreaped) and of a reaped pid
p = spawn("/bin/sh", "-c", "exit 0"); time.sleep(0.3)
show("kill(zombie, 0)", lambda: os.kill(p, 0))
show("kill(zombie, 15)", lambda: os.kill(p, 15))
os.waitpid(p, 0)
show("kill(reaped, 0)", lambda: os.kill(p, 0))
# signal to self through os.kill with a Python handler: synchronous?
got = []
signal.signal(signal.SIGUSR1, lambda s, f: got.append(s))
os.kill(me, signal.SIGUSR1)
print("handler ran synchronously after os.kill(me)", got)
show("os.times type", lambda: (type(os.times()).__name__, len(os.times())))
