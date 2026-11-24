# SPDX-License-Identifier: GPL-3.0-or-later
# N1 measurement script: m1.py..m4.py of work/n1/measure/ in one file (N1 plan, D4). CPython only, Linux only.
# SAFETY: never signal pid -1, pgid 1 or pgid 0 with a real signal: os.killpg(1, sig) is kill(-1, sig) = every process of the user.
# (N1 plan 2.3: only signal children this script created, and its own pid; signal 0 is the only signal sent to other pids.)
"""Print CPython's POSIX process facts (spawn, wait, kill, sysconf/confstr, _posixsubprocess), one fact per line.

usage: python posix_measure_n1.py m1|m2|m3|m4 [...]      (no argument: all four, in order)

Part m1: surfaces, constants, signatures, wait/kill/sysconf behaviour.  Part m2: os.posix_spawn[p],
_posixsubprocess.fork_exec, child state.  Part m3: audit events, Popen bookkeeping.  Part m4: check order, lane texts.
Each part below the "#@@PART" marker is the unedited text of the same-named script, executed in a fresh
namespace; the output of ``posix_measure_n1.py mN`` equals the committed ``work/n1/measure/mN-31x.txt`` except for
pids and pid-derived numbers.  For that comparison run one part per process: run together, the parts
share process state (m1's SIGUSR1 handler, m3's audit hook and warnings filter).  Run it under each reference interpreter (3.13, 3.14, 3.15) with a clean cwd
(for example ``cd /tmp``) and stdin from /dev/null, with PYTHONUNBUFFERED=1, stdout and stderr to the same file.
"""
import sys

PARTS = ("m1", "m2", "m3", "m4")


def _sections():
    with open(__file__, encoding="utf-8") as f:
        text = f.read()
    out = {}
    for chunk in text.split("\n#@@PART ")[1:]:
        name, _, body = chunk.partition("\n")
        out[name.strip()] = body
    return out


def main(argv):
    if not sys.platform.startswith("linux"):
        print("posix_measure_n1.py: run under Linux CPython", file=sys.stderr)
        return 2
    wanted = argv or list(PARTS)
    sections = _sections()
    for name in wanted:
        if name not in sections:
            print("unknown part", name, file=sys.stderr)
            return 2
    for name in wanted:
        code = compile(sections[name], "posix_measure_n1.py:" + name, "exec")
        exec(code, {"__name__": "__main__", "__file__": name + ".py"})
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

#@@PART m1
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

#@@PART m2
# N1 measurement, part 2: os.posix_spawn[p], _posixsubprocess.fork_exec, child state. CPython only, Linux.
# SAFETY: never signal pid -1, pgid 1 or pgid 0 with a real signal: os.killpg(1, sig) is kill(-1, sig) = every process of the user.
import os, sys, signal, errno, time, subprocess, tempfile, shutil, stat, pathlib, _posixsubprocess

def show(label, fn):
    try:
        r = fn()
        print(label, "->", repr(r))
    except BaseException as e:
        extra = ""
        if isinstance(e, OSError):
            extra = " errno=%r filename=%r filename2=%r" % (e.errno, e.filename, e.filename2)
        print(label, "-> EXC", type(e).__name__ + ":", str(e).replace(D, "<tmp>"), extra.replace(D, "<tmp>"))

D = os.path.realpath(tempfile.mkdtemp(prefix="n1m2"))
os.mkdir(D + "/sub"); os.mkdir(D + "/noperm")
def mk(name, text, mode):
    with open(D + "/" + name, "w") as f: f.write(text)
    os.chmod(D + "/" + name, mode)
mk("noexec", "#!/bin/sh\necho hi\n", 0o644)
mk("noshebang", "echo noshebang ran\n", 0o755)
mk("badinterp", "#!/no/such/interp\n", 0o755)
mk("sub/prog", "#!/bin/sh\necho prog ran in $(pwd)\n", 0o755)
mk("prog2", "#!/bin/sh\necho prog2 \"$@\"\n", 0o755)
os.chmod(D + "/noperm", 0)
env = dict(os.environ)

def wait(pid):
    return os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1])
def run_out(spawn, path, argv, e=None, **kw):
    r, w = os.pipe()
    try:
        fa = list(kw.pop("file_actions", [])) + [(os.POSIX_SPAWN_DUP2, w, 1)]
        pid = spawn(path, argv, env if e is None else e, file_actions=fa, **kw)
    finally:
        os.close(w)
    data = b""
    while True:
        b = os.read(r, 65536)
        if not b: break
        data += b
    os.close(r)
    return wait(pid), data.replace(D.encode(), b"<tmp>")

print("== os.posix_spawn basics")
show("spawn true", lambda: wait(os.posix_spawn("/bin/true", ["true"], env)))
show("spawn sh exit 3", lambda: wait(os.posix_spawn("/bin/sh", ["sh", "-c", "exit 3"], env)))
show("returns int pid > 0", lambda: (lambda p: (type(p).__name__, p > 0, wait(p)))(os.posix_spawn("/bin/true", ["true"], env)))
show("stdout via DUP2", lambda: run_out(os.posix_spawn, "/bin/echo", ["echo", "hello"]))
show("argv tuple", lambda: run_out(os.posix_spawn, "/bin/echo", ("echo", "t")))
show("argv bytes/pathlike mix", lambda: run_out(os.posix_spawn, b"/bin/echo", [b"echo", pathlib.PurePosixPath("/x/y"), "z"]))
show("path PathLike", lambda: run_out(os.posix_spawn, pathlib.PurePosixPath("/bin/echo"), ["echo", "p"]))
show("env passed", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", "echo $N1V-$HOME"], {"N1V": "v1"}))
show("env bytes keys", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", "echo $N1V"], {b"N1V": b"vb"}))
show("env empty dict", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", "echo [$HOME]"], {}))
show("env os.environ object", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", "echo ok"], os.environ))
show("argv[0] is free text", lambda: run_out(os.posix_spawn, "/bin/sh", ["zzz", "-c", "echo $0"]))
print("== os.posix_spawn errors")
show("missing", lambda: os.posix_spawn("/no/such/prog", ["x"], env))
show("missing bytes path", lambda: os.posix_spawn(b"/no/such/prog", ["x"], env))
show("missing PathLike", lambda: os.posix_spawn(pathlib.PurePosixPath("/no/such/prog"), ["x"], env))
show("noexec", lambda: os.posix_spawn(D + "/noexec", ["x"], env))
show("isdir", lambda: os.posix_spawn(D, ["x"], env))
show("notdir", lambda: os.posix_spawn(D + "/noexec/x", ["x"], env))
show("noshebang", lambda: os.posix_spawn(D + "/noshebang", ["x"], env))
show("badinterp", lambda: os.posix_spawn(D + "/badinterp", ["x"], env))
show("empty path", lambda: os.posix_spawn("", ["x"], env))
show("relative bare name (no PATH search)", lambda: os.posix_spawn("true", ["true"], env))
show("argv empty list", lambda: os.posix_spawn("/bin/true", [], env))
show("argv empty tuple", lambda: os.posix_spawn("/bin/true", (), env))
show("argv[0] empty str", lambda: wait(os.posix_spawn("/bin/true", [""], env)))
show("argv not sequence", lambda: os.posix_spawn("/bin/true", 5, env))
show("argv str", lambda: os.posix_spawn("/bin/true", "true", env))
show("argv item int", lambda: os.posix_spawn("/bin/true", ["true", 5], env))
show("argv item NUL", lambda: os.posix_spawn("/bin/true", ["true", "a\0b"], env))
show("path NUL", lambda: os.posix_spawn("/bin/tr\0ue", ["true"], env))
show("path int", lambda: os.posix_spawn(5, ["true"], env))
show("env None", lambda: os.posix_spawn("/bin/true", ["true"], None))
show("env list", lambda: os.posix_spawn("/bin/true", ["true"], ["A=1"]))
show("env key with =", lambda: os.posix_spawn("/bin/true", ["true"], {"A=B": "1"}))
show("env key empty", lambda: os.posix_spawn("/bin/true", ["true"], {"": "1"}))
show("env value int", lambda: os.posix_spawn("/bin/true", ["true"], {"A": 1}))
show("env key NUL", lambda: os.posix_spawn("/bin/true", ["true"], {"A\0": "1"}))
show("env value with = ok", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", "echo $A"], {"A": "x=y z"}))
show("too few args", lambda: os.posix_spawn("/bin/true", ["true"]))
show("env as keyword", lambda: os.posix_spawn("/bin/true", ["true"], env=env))
show("unknown keyword", lambda: os.posix_spawn("/bin/true", ["true"], env, bogus=1))
show("file_actions positional", lambda: os.posix_spawn("/bin/true", ["true"], env, []))
print("== file_actions")
show("fa None", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=None)))
show("fa () ", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=())))
show("fa int", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=5))
show("fa [5]", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[5]))
show("fa [()]", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[()]))
show("fa [[CLOSE, 9]] list item", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[[os.POSIX_SPAWN_CLOSE, 9]]))
show("fa unknown id", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(99, 1)]))
show("fa id str", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[("x", 1)]))
show("fa CLOSE wrong arity", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_CLOSE,)]))
show("fa CLOSE extra", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_CLOSE, 1, 2)]))
show("fa DUP2 wrong arity", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_DUP2, 1)]))
show("fa OPEN wrong arity", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_OPEN, 1, "/dev/null")]))
show("fa CLOSE fd str", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_CLOSE, "x")]))
show("fa CLOSE not-open fd ok", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_CLOSE, 9999)])))
show("fa CLOSE -1", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_CLOSE, -1)]))
show("fa DUP2 bad fd", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_DUP2, 9999, 1)]))
show("fa OPEN missing dir", lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_OPEN, 1, D + "/nodir/f", os.O_WRONLY | os.O_CREAT, 0o644)]))
def fa_open():
    pid = os.posix_spawn("/bin/echo", ["echo", "to file"], env, file_actions=[(os.POSIX_SPAWN_OPEN, 1, D + "/out.txt", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)])
    rc = wait(pid)
    return rc, open(D + "/out.txt").read(), oct(os.stat(D + "/out.txt").st_mode & 0o777)
show("fa OPEN creates file", fa_open)
show("fa OPEN path bytes/PathLike", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=[(os.POSIX_SPAWN_OPEN, 1, os.fsencode(D + "/o2"), os.O_WRONLY | os.O_CREAT, 0o600), (os.POSIX_SPAWN_OPEN, 2, pathlib.PurePosixPath(D + "/o3"), os.O_WRONLY | os.O_CREAT, 0o600)])))
show("fa order: OPEN 5, DUP2 5->1, CLOSE 5", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", "ls /proc/self/fd | tr '\\n' ' '"], file_actions=[(os.POSIX_SPAWN_OPEN, 5, "/dev/null", os.O_RDONLY, 0), (os.POSIX_SPAWN_CLOSE, 5)]))
show("has CLOSEFROM", lambda: hasattr(os, "POSIX_SPAWN_CLOSEFROM"))
print("== attributes")
IDS = "echo sid_eq_pid=$(( $(cut -d' ' -f6 /proc/self/stat) == $$ )) pgrp_eq_pid=$(( $(cut -d' ' -f5 /proc/self/stat) == $$ )); grep -E 'Sig(Blk|Ign)' /proc/$$/status | tr '\\n\\t' '  '"
show("child default (inherits SIGPIPE ignore? mask?)", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS]))
show("setsid=True", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setsid=True))
show("setsid=1", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setsid=1))
show("setpgroup=0", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setpgroup=0))
show("setpgroup=None", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setpgroup=None))
show("setpgroup='x'", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setpgroup="x"))
show("setpgroup=1 (EPERM)", lambda: os.posix_spawn("/bin/true", ["true"], env, setpgroup=1))
show("resetids=True", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, resetids=True)))
show("setsigdef [SIGPIPE]", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setsigdef=[signal.SIGPIPE]))
show("setsigdef (SIGPIPE, SIGXFSZ) tuple", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setsigdef=(signal.SIGPIPE, signal.SIGXFSZ)))
show("setsigmask [SIGUSR1, SIGUSR2]", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setsigmask=[signal.SIGUSR1, signal.SIGUSR2]))
show("setsigmask set()", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS], setsigmask=set()))
show("setsigmask [0]", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=[0]))
show("setsigmask [65]", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=[65]))
show("setsigmask ['x']", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=["x"]))
show("setsigmask 5", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=5))
show("setsigmask [SIGKILL] accepted", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, setsigmask=[9])))
show("setsigdef [65]", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigdef=[65]))
show("setsigdef [9] SIGKILL", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigdef=[9]))
show("scheduler=None", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, scheduler=None)))
show("scheduler=(None, sched_param(0))", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, scheduler=(None, os.sched_param(0)))))
show("scheduler=(SCHED_OTHER, sched_param(0))", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, scheduler=(os.SCHED_OTHER, os.sched_param(0)))))
show("scheduler=5", lambda: os.posix_spawn("/bin/true", ["true"], env, scheduler=5))
signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1])
show("parent blocked USR1 -> child", lambda: run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", IDS]))
show("parent blocked USR1, subprocess.run child", lambda: subprocess.run(["/bin/sh", "-c", IDS], capture_output=True).stdout)
signal.pthread_sigmask(signal.SIG_UNBLOCK, [signal.SIGUSR1])
print("== posix_spawnp")
show("spawnp true", lambda: wait(os.posix_spawnp("true", ["true"], env)))
show("spawnp missing", lambda: os.posix_spawnp("pyjvm-no-such-prog", ["x"], env))
show("spawnp with slash missing", lambda: os.posix_spawnp("/no/such/prog", ["x"], env))
show("spawnp uses parent's PATH, not env's", lambda: wait(os.posix_spawnp("true", ["true"], {"PATH": "/nonexistent"})))
def spawnp_parent_path():
    old = os.environ["PATH"]
    os.environ["PATH"] = D
    try: return run_out(os.posix_spawnp, "prog2", ["prog2", "a"], env)
    finally: os.environ["PATH"] = old
show("spawnp sees os.environ['PATH'] change (putenv)", spawnp_parent_path)
show("spawnp noshebang via PATH-less", lambda: os.posix_spawnp(D + "/noshebang", ["x"], env))
print("== inheritance through posix_spawn")
def inh():
    r, w = os.pipe(); r2, w2 = os.pipe(); os.set_inheritable(w2, True)
    try: return run_out(os.posix_spawn, "/bin/sh", ["sh", "-c", "for f in %d %d %d %d; do test -e /proc/self/fd/$f && echo open || echo closed; done | tr '\\n' ' '" % (r, w, r2, w2)])
    finally:
        for f in (r, w, r2, w2): os.close(f)
show("cloexec closed, inheritable open", inh)
print("== cwd / chdir")
os.chdir(D + "/sub")
show("after os.chdir: child pwd", lambda: run_out(os.posix_spawn, "/bin/pwd", ["pwd"]))
show("after os.chdir: relative exe ./prog", lambda: run_out(os.posix_spawn, "./prog", ["prog"]))
show("after os.chdir: relative exe missing -> filename", lambda: os.posix_spawn("./nope", ["nope"], env))
show("after os.chdir: subprocess relative exe", lambda: subprocess.run(["./prog"], capture_output=True).stdout.replace(D.encode(), b"<tmp>"))
show("subprocess cwd relative '..' + relative exe", lambda: subprocess.run(["./prog2", "x"], cwd="..", capture_output=True).stdout)
show("subprocess cwd abs + exe relative to new cwd", lambda: subprocess.run(["./prog"], cwd=D + "/sub", capture_output=True).stdout.replace(D.encode(), b"<tmp>"))
os.chdir("/")

print("== fork_exec direct")
NARGS = 23 if sys.version_info[:2] == (3, 13) else 22
def fe(args, exes, cwd=None, env_list=None, close_fds=True, keep=(), restore=True, setsid=False, pgid=-1, gid=None, groups=None, uid=None, umask=-1, preexec=None, stdio=(-1, -1, -1, -1, -1, -1)):
    er, ew = os.pipe()
    try:
        a = [args, exes, close_fds, tuple(sorted(set(keep) | {ew})), cwd, env_list, *stdio, er, ew, restore, setsid, pgid, gid, groups, uid, umask, preexec]
        if NARGS == 23: a.append(True)
        pid = _posixsubprocess.fork_exec(*a)
    finally:
        os.close(ew)
    data = b""
    while True:
        b = os.read(er, 50000)
        if not b: break
        data += b
    os.close(er)
    try: st = os.waitpid(pid, 0)[1]
    except ChildProcessError: st = "ECHILD"
    return type(pid).__name__, pid > 0, data, st
show("fe true", lambda: fe([b"true"], (b"/bin/true",)))
show("fe exit 3", lambda: fe(["sh", "-c", "exit 3"], (b"/bin/sh",)))
show("fe missing", lambda: fe([b"x"], (b"/no/such/prog",)))
show("fe noexec", lambda: fe([b"x"], (os.fsencode(D + "/noexec"),)))
show("fe isdir", lambda: fe([b"x"], (os.fsencode(D),)))
show("fe notdir", lambda: fe([b"x"], (os.fsencode(D + "/noexec/x"),)))
show("fe noshebang", lambda: fe([b"x"], (os.fsencode(D + "/noshebang"),)))
show("fe badinterp", lambda: fe([b"x"], (os.fsencode(D + "/badinterp"),)))
show("fe list: missing, noexec, missing -> first non-ENOENT", lambda: fe([b"x"], (b"/no/such/a", os.fsencode(D + "/noexec"), b"/no/such/b")))
show("fe list: noexec, noshebang -> first", lambda: fe([b"x"], (os.fsencode(D + "/noexec"), os.fsencode(D + "/noshebang"))))
show("fe list: notdir, missing -> last errno", lambda: fe([b"x"], (os.fsencode(D + "/noexec/x"), b"/no/such/b")))
show("fe list: missing, notdir -> last errno", lambda: fe([b"x"], (b"/no/such/b", os.fsencode(D + "/noexec/x"))))
show("fe list: missing then true runs", lambda: fe([b"true"], (b"/no/such/a", b"/bin/true")))
show("fe empty exe list", lambda: fe([b"x"], ()))
show("fe cwd missing", lambda: fe([b"true"], (b"/bin/true",), cwd="/no/such/dir"))
show("fe cwd notdir", lambda: fe([b"true"], (b"/bin/true",), cwd=D + "/noexec"))
show("fe cwd noperm", lambda: fe([b"true"], (b"/bin/true",), cwd=D + "/noperm"))
show("fe cwd empty str", lambda: fe([b"true"], (b"/bin/true",), cwd=""))
show("fe cwd bytes", lambda: fe([b"true"], (b"/bin/true",), cwd=os.fsencode(D)))
show("fe cwd bad AND exe missing -> chdir first", lambda: fe([b"x"], (b"/no/such/prog",), cwd="/no/such/dir"))
show("fe bad stdio fd (dup2 fails)", lambda: fe([b"true"], (b"/bin/true",), stdio=(9999, -1, -1, -1, -1, -1)))
show("fe setpgid EPERM (pgid=1)", lambda: fe([b"true"], (b"/bin/true",), pgid=1))
show("fe args None", lambda: fe(None, (b"/bin/true",)))
show("fe args empty", lambda: fe([], (b"/bin/true",)))
show("fe args str items", lambda: fe(["true"], (b"/bin/true",)))
show("fe args int item", lambda: fe([5], (b"/bin/true",)))
show("fe exe list of str", lambda: fe([b"true"], ("/bin/true",)))
show("fe exe not tuple (list)", lambda: fe([b"true"], [b"/bin/true"]))
show("fe env_list", lambda: fe(["sh", "-c", "test \"$A\" = 1 && test -z \"$HOME\""], (b"/bin/sh",), env_list=[b"A=1"]))
show("fe env_list str items", lambda: fe(["true"], (b"/bin/true",), env_list=["A=1"]))
show("fe pass_fds not tuple", lambda: _posixsubprocess.fork_exec(*([[b"true"], (b"/bin/true",), True, [5], None, None, -1, -1, -1, -1, -1, -1, 3, 4, True, False, -1, None, None, None, -1, None] + ([True] if NARGS == 23 else []))))
show("fe pass_fds negative", lambda: _posixsubprocess.fork_exec(*([[b"true"], (b"/bin/true",), True, (-5,), None, None, -1, -1, -1, -1, -1, -1, 3, 4, True, False, -1, None, None, None, -1, None] + ([True] if NARGS == 23 else []))))
show("fe pass_fds unsorted", lambda: _posixsubprocess.fork_exec(*([[b"true"], (b"/bin/true",), True, (9, 5), None, None, -1, -1, -1, -1, -1, -1, 3, 4, True, False, -1, None, None, None, -1, None] + ([True] if NARGS == 23 else []))))
show("fe close_fds with errpipe_write < 3", lambda: _posixsubprocess.fork_exec(*([[b"true"], (b"/bin/true",), True, (), None, None, -1, -1, -1, -1, -1, -1, 3, 2, True, False, -1, None, None, None, -1, None] + ([True] if NARGS == 23 else []))))
show("fe wrong arg count", lambda: _posixsubprocess.fork_exec(1, 2, 3))
show("fe preexec_fn not callable", lambda: fe([b"true"], (b"/bin/true",), preexec="x"))
show("fe uid wrong type", lambda: fe([b"true"], (b"/bin/true",), uid="x"))
show("fe gid wrong type", lambda: fe([b"true"], (b"/bin/true",), gid="x"))
show("fe extra_groups wrong type", lambda: fe([b"true"], (b"/bin/true",), groups=5))
show("fe umask 0o077 child", lambda: fe(["sh", "-c", "test $(umask) = 0077"], (b"/bin/sh",), umask=0o077))
show("fe uid=own", lambda: fe([b"true"], (b"/bin/true",), uid=os.getuid()))
show("fe gid=own", lambda: fe([b"true"], (b"/bin/true",), gid=os.getgid()))
show("fe groups=[] (EPERM as non-root)", lambda: fe([b"true"], (b"/bin/true",), groups=()))
show("fe preexec_fn runs", lambda: fe([b"true"], (b"/bin/true",), preexec=lambda: None))
def fe_keep():
    r, w = os.pipe(); r2, w2 = os.pipe()
    try: return fe(["sh", "-c", "for f in %d %d %d %d; do test -e /proc/self/fd/$f || exit 9; done; exit 0" % (r, w, r2, w2)], (b"/bin/sh",), keep=(r, w, r2, w2))
    finally:
        for f in (r, w, r2, w2): os.close(f)
show("fe keep 4 cloexec fds", fe_keep)
def fe_fdlist(close_fds, keep_some):
    r, w = os.pipe(); r2, w2 = os.pipe(); os.set_inheritable(r2, True)
    o, ow = os.pipe()
    try:
        res = fe(["sh", "-c", "for f in %d %d %d %d; do test -e /proc/self/fd/$f && echo -n o || echo -n c; done" % (r, w, r2, w2)], (b"/bin/sh",), close_fds=close_fds, keep=(w,) if keep_some else (), stdio=(-1, -1, -1, ow, -1, -1))
    finally:
        os.close(ow)
    out = os.read(o, 100); os.close(o)
    for f in (r, w, r2, w2): os.close(f)
    return res[3], out
show("fds r(cloexec) w(kept) r2(inheritable) w2(cloexec): close_fds=True", lambda: fe_fdlist(True, True))
show("same, close_fds=False", lambda: fe_fdlist(False, True))
show("close_fds=False no keep", lambda: fe_fdlist(False, False))

os.chmod(D + "/noperm", 0o755)
shutil.rmtree(D)

#@@PART m3
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

#@@PART m4
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
