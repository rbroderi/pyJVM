import os

# os.wait3 / os.wait4 and resource.struct_rusage (POSIX only).
print(os.name)

if os.name == "posix":
    import resource

    env = dict(os.environ)

    def show(label, fn):
        try:
            print(label, fn())
        except Exception as e:
            print(label, type(e).__name__, getattr(e, "errno", None), e.strerror if isinstance(e, OSError) else e)

    show("wait3 none", lambda: os.wait3(0))
    show("wait4 none", lambda: os.wait4(-1, 0))
    show("wait4 kw none", lambda: os.wait4(pid=-1, options=0))
    show("wait3 kw none", lambda: os.wait3(options=0))
    show("wait3 badopt", lambda: os.wait3(12345678))
    show("wait3 str", lambda: os.wait3("x"))
    show("wait4 str", lambda: os.wait4("x", 0))
    show("wait3 noarg", lambda: os.wait3())
    p = os.posix_spawn("/bin/sh", ["sh", "-c", "exit 5"], env)
    r = os.wait3(0)
    u = r[2]
    print(type(r).__name__, len(r), r[0] == p, r[1], type(u) is resource.struct_rusage, type(u).__module__, type(u).__name__, len(u))
    print([type(x).__name__ for x in u], u.ru_maxrss > 0, u.ru_utime >= 0.0, u.ru_utime == u[0], u.ru_nivcsw == u[15])
    print([n for n in dir(u) if n.startswith("ru_")], type(u).n_fields, type(u).n_sequence_fields, type(u).n_unnamed_fields)
    print(repr(u).startswith("resource.struct_rusage(ru_utime="), repr(u).count("="))
    p = os.posix_spawn("/bin/sh", ["sh", "-c", "kill -TERM $$"], env)
    r = os.wait4(p, 0)
    print(len(r), r[0] == p, r[1], type(r[2]).__name__)
    p = os.posix_spawn("/bin/sleep", ["sleep", "30"], env)
    r = os.wait3(os.WNOHANG)
    print(r[:2], all(x == 0 for x in r[2]), os.wait4(p, os.WNOHANG)[:2])
    os.kill(p, 9)
    print(os.wait4(p, 0)[1])
    show("wait4 reaped", lambda: os.wait4(p, 0))
