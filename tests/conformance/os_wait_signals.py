import os

# Blocking waits and Python signal handlers on the main thread (PEP 475), and os.pidfd_open (POSIX only).
# SAFETY: only signals children it created; never pid -1, pgid 0 or pgid 1.
print(os.name, hasattr(os, "pidfd_open"))

if os.name == "posix":
    import signal
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

    # a blocking wait returns when the child exits, not a poll interval later (20 short children, mean latency)
    t0 = time.monotonic()
    for i in range(20):
        os.waitpid(spawn("/bin/true"), 0)
    print((time.monotonic() - t0) / 20 < 0.04)
    # a Python signal handler runs while the main thread is blocked in waitpid, and the wait continues (PEP 475)
    got = []
    signal.signal(signal.SIGALRM, lambda s, f: got.append(s))
    p = spawn("/bin/sleep", "0.5")
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    r = os.waitpid(p, 0)
    print(r[0] == p, r[1], got)
    def boom(s, f):
        raise TimeoutError("alarm")
    signal.signal(signal.SIGALRM, boom)
    p = spawn("/bin/sleep", "30")
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    show("handler raises", lambda: os.waitpid(p, 0))
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    os.kill(p, signal.SIGKILL)
    print(os.waitstatus_to_exitcode(os.waitpid(p, 0)[1]))

    # the same for wait() and waitid(), and a handler that returns does not end a WNOHANG-free waitid
    got = []
    signal.signal(signal.SIGALRM, lambda s, f: got.append(s))
    p = spawn("/bin/sleep", "0.5")
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    r = os.wait()
    print(r[0] == p, r[1], got)
    got.clear()
    p = spawn("/bin/sleep", "0.5")
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    r = os.waitid(os.P_PID, p, os.WEXITED)
    print(r.si_pid == p, r.si_status, got)
    got.clear()
    p = spawn("/bin/sleep", "30")
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    r = os.waitpid(p, os.WUNTRACED) if os.kill(p, signal.SIGSTOP) is None else None
    print(r[0] == p, os.WIFSTOPPED(r[1]))
    signal.setitimer(signal.ITIMER_REAL, 0)
    os.kill(p, signal.SIGKILL)
    print(os.waitstatus_to_exitcode(os.waitpid(p, 0)[1]))
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    # pidfd_open
    p = spawn("/bin/sleep", "30")
    fd = os.pidfd_open(p)
    print(fd > 2, os.get_inheritable(fd), os.get_blocking(fd))
    import select
    po = select.poll()
    po.register(fd, select.POLLIN)
    print(po.poll(0))
    os.kill(p, 15)
    print([(f == fd, ev) for f, ev in po.poll(5000)])
    r = os.waitid(os.P_PIDFD, fd, os.WEXITED)
    print(r.si_pid == p, r.si_status, r.si_code)
    os.close(fd)
    show("pidfd reaped", lambda: os.pidfd_open(p))
    show("pidfd -1", lambda: os.pidfd_open(-1))
    show("pidfd flags", lambda: os.pidfd_open(me, 12345))
    show("pidfd kw", lambda: os.close(os.pidfd_open(pid=me, flags=0)))
    show("left", lambda: os.waitpid(-1, os.WNOHANG))
