import os

# os.posix_spawn / os.posix_spawnp (POSIX only; no subprocess import, so this case is cheap on a cold cache).
print(os.name)

if os.name == "posix":
    import pathlib
    import signal
    import tempfile

    env = dict(os.environ)
    d = os.path.realpath(tempfile.mkdtemp())

    def wait(pid):
        return os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1])

    def out(spawn, path, argv, e=None, **kw):
        r, w = os.pipe()
        try:
            fa = list(kw.pop("file_actions", [])) + [(os.POSIX_SPAWN_DUP2, w, 1)]
            pid = spawn(path, argv, env if e is None else e, file_actions=fa, **kw)
        finally:
            os.close(w)
        data = b""
        while True:
            b = os.read(r, 65536)
            if not b:
                break
            data += b
        os.close(r)
        return wait(pid), data.replace(d.encode(), b"<tmp>")

    def show(label, fn):
        try:
            print(label, fn())
        except Exception as e:
            fn_ = getattr(e, "filename", None)
            if isinstance(fn_, str):
                fn_ = fn_.replace(d, "<tmp>")
            print(label, type(e).__name__, getattr(e, "errno", None), str(e).replace(d, "<tmp>") if not isinstance(e, OSError) else e.strerror, repr(fn_))

    def mk(name, text, mode):
        with open(os.path.join(d, name), "w") as f:
            f.write(text)
        os.chmod(os.path.join(d, name), mode)

    try:
        os.mkdir(os.path.join(d, "sub"))
        mk("noexec", "#!/bin/sh\n", 0o644)
        mk("noshebang", "echo no shebang\n", 0o755)
        mk("badinterp", "#!/no/such/interp\n", 0o755)
        mk("sub/prog", "#!/bin/sh\necho prog ran in $(pwd)\n", 0o755)
        mk("prog2", '#!/bin/sh\necho prog2 "$@"\n', 0o755)

        print([(n, getattr(os, n, None)) for n in ("POSIX_SPAWN_OPEN", "POSIX_SPAWN_CLOSE", "POSIX_SPAWN_DUP2", "POSIX_SPAWN_CLOSEFROM")])
        p = os.posix_spawn("/bin/true", ["true"], env)
        print(type(p).__name__, p > 0, wait(p))
        show("exit3", lambda: wait(os.posix_spawn("/bin/sh", ["sh", "-c", "exit 3"], env)))
        show("dup2", lambda: out(os.posix_spawn, "/bin/echo", ["echo", "hello"]))
        show("tuple", lambda: out(os.posix_spawn, "/bin/echo", ("echo", "t")))
        show("mixed", lambda: out(os.posix_spawn, b"/bin/echo", [b"echo", pathlib.PurePosixPath("/x/y"), "z"]))
        show("pathlike", lambda: out(os.posix_spawn, pathlib.PurePosixPath("/bin/echo"), ["echo", "p"]))
        show("env", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", 'echo "$N1V-$HOME"'], {"N1V": "v1"}))
        show("envb", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", 'echo "$N1V"'], {b"N1V": b"vb"}))
        show("env{}", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", 'echo "[$HOME]"'], {}))
        show("environ", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", "echo ok"], os.environ))
        show("env=", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", 'echo "$A"'], {"A": "x=y z"}))
        show("argv0", lambda: out(os.posix_spawn, "/bin/sh", ["zzz", "-c", "echo $0"]))
        # env=None: the child gets the C environment, so a raw putenv is visible
        os.putenv("PYJVM_N1_RAW", "raw")
        r, w = os.pipe()
        p = os.posix_spawn("/bin/sh", ["sh", "-c", 'echo "[$PYJVM_N1_RAW]"'], None, file_actions=[(os.POSIX_SPAWN_DUP2, w, 1)])
        os.close(w)
        print("env None", os.read(r, 100), wait(p))
        os.close(r)
        os.unsetenv("PYJVM_N1_RAW")

        for label, path in (("missing", "/no/such/prog"), ("missingb", b"/no/such/prog"), ("missingp", pathlib.PurePosixPath("/no/such/prog")),
                            ("noexec", os.path.join(d, "noexec")), ("isdir", d), ("notdir", os.path.join(d, "noexec", "x")),
                            ("noshebang", os.path.join(d, "noshebang")), ("badinterp", os.path.join(d, "badinterp")), ("empty", ""), ("bare", "true")):
            show(label, lambda: os.posix_spawn(path, ["x"], env))
        show("argv[]", lambda: os.posix_spawn("/bin/true", [], env))
        show("argv()", lambda: os.posix_spawn("/bin/true", (), env))
        show("argv['']", lambda: os.posix_spawn("/bin/true", [""], env))
        show("argv5", lambda: os.posix_spawn("/bin/true", 5, env))
        show("argvstr", lambda: os.posix_spawn("/bin/true", "true", env))
        show("argvint", lambda: os.posix_spawn("/bin/true", ["true", 5], env))
        show("argvnul", lambda: os.posix_spawn("/bin/true", ["true", "a\0b"], env))
        show("pathnul", lambda: os.posix_spawn("/bin/tr\0ue", ["true"], env))
        show("pathint", lambda: os.posix_spawn(5, ["true"], env))
        show("envlist", lambda: os.posix_spawn("/bin/true", ["true"], ["A=1"]))
        show("envkey=", lambda: os.posix_spawn("/bin/true", ["true"], {"A=B": "1"}))
        show("envkey''", lambda: os.posix_spawn("/bin/true", ["true"], {"": "1"}))
        show("envint", lambda: os.posix_spawn("/bin/true", ["true"], {"A": 1}))
        show("envnul", lambda: os.posix_spawn("/bin/true", ["true"], {"A\0": "1"}))
        show("few", lambda: os.posix_spawn("/bin/true", ["true"]))
        show("envkw", lambda: os.posix_spawn("/bin/true", ["true"], env=env))
        show("bogus", lambda: os.posix_spawn("/bin/true", ["true"], env, bogus=1))
        show("fapos", lambda: os.posix_spawn("/bin/true", ["true"], env, []))

        C, D2, O = os.POSIX_SPAWN_CLOSE, os.POSIX_SPAWN_DUP2, os.POSIX_SPAWN_OPEN
        show("faNone", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=None)))
        show("fa()", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=())))
        for label, fa in (("fa5", 5), ("fa[5]", [5]), ("fa[()]", [()]), ("fa[[]]", [[C, 9]]), ("fa99", [(99, 1)]), ("fastr", [("x", 1)]),
                          ("faC1", [(C,)]), ("faC3", [(C, 1, 2)]), ("faD2", [(D2, 1)]), ("faO3", [(O, 1, "/dev/null")]), ("faCstr", [(C, "x")]),
                          ("faC-1", [(C, -1)]), ("faDbad", [(D2, 9999, 1)]),
                          ("faObad", [(O, 1, os.path.join(d, "nodir", "f"), os.O_WRONLY | os.O_CREAT, 0o644)])):
            show(label, lambda: os.posix_spawn("/bin/true", ["true"], env, file_actions=fa))
        show("faCclosed", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=[(C, 9999)])))
        o = os.path.join(d, "out.txt")
        show("faOpen", lambda: (wait(os.posix_spawn("/bin/echo", ["echo", "to file"], env, file_actions=[(O, 1, o, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)])),
                                open(o).read(), oct(os.stat(o).st_mode & 0o777)))
        show("faOpenTypes", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, file_actions=[(O, 1, os.fsencode(os.path.join(d, "o2")), os.O_WRONLY | os.O_CREAT, 0o600),
                                                                                              (O, 2, pathlib.PurePosixPath(d, "o3"), os.O_WRONLY | os.O_CREAT, 0o600)])))
        show("faOrder", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", "test -e /proc/self/fd/45 && echo open || echo closed; test -e /proc/self/fd/46 && echo open || echo closed"],
                                    file_actions=[(O, 45, "/dev/null", os.O_RDONLY, 0), (D2, 45, 46), (C, 45)]))

        ids = "echo $(( $(cut -d' ' -f6 /proc/self/stat) == $$ )) $(( $(cut -d' ' -f5 /proc/self/stat) == $$ )); grep SigBlk /proc/$$/status | tr '\\n\\t' '  '"
        show("ids", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", ids]))
        show("setsid", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", ids], setsid=True))
        show("setpgroup0", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", ids], setpgroup=0))
        show("setpgroupNone", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", ids], setpgroup=None))
        show("setpgroupx", lambda: os.posix_spawn("/bin/true", ["true"], env, setpgroup="x"))
        show("setpgroup1", lambda: os.posix_spawn("/bin/true", ["true"], env, setpgroup=1))
        show("resetids", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, resetids=True)))
        show("sigdef", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, setsigdef=[signal.SIGPIPE, signal.SIGXFSZ])))
        show("sigmask", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", ids], setsigmask=[signal.SIGUSR1, signal.SIGUSR2]))
        show("sigmask{}", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", ids], setsigmask=set()))
        show("sigmask0", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=[0]))
        show("sigmask65", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=[65]))
        show("sigmaskx", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=["x"]))
        show("sigmask5", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigmask=5))
        show("sigdef65", lambda: os.posix_spawn("/bin/true", ["true"], env, setsigdef=[65]))
        show("schedNone", lambda: wait(os.posix_spawn("/bin/true", ["true"], env, scheduler=None)))
        show("sched5", lambda: os.posix_spawn("/bin/true", ["true"], env, scheduler=5))
        signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1])
        show("blocked", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", ids]))
        signal.pthread_sigmask(signal.SIG_UNBLOCK, [signal.SIGUSR1])

        show("p", lambda: wait(os.posix_spawnp("true", ["true"], env)))
        show("pmissing", lambda: os.posix_spawnp("pyjvm-no-such-prog", ["x"], env))
        show("pslash", lambda: os.posix_spawnp("/no/such/prog", ["x"], env))
        show("pparentpath", lambda: wait(os.posix_spawnp("true", ["true"], {"PATH": "/nonexistent"})))
        old = os.environ["PATH"]
        os.environ["PATH"] = d
        show("pputenv", lambda: out(os.posix_spawnp, "prog2", ["prog2", "a"]))
        os.environ["PATH"] = old
        show("pnoshebang", lambda: os.posix_spawnp(os.path.join(d, "noshebang"), ["x"], env))

        r1, w1 = os.pipe()
        r2, w2 = os.pipe()
        os.set_inheritable(w2, True)
        probe = "for f in %d %d %d %d; do test -e /proc/self/fd/$f && echo -n o || echo -n c; done" % (r1, w1, r2, w2)
        show("inherit", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", probe]))
        show("inheritdup", lambda: out(os.posix_spawn, "/bin/sh", ["sh", "-c", probe], file_actions=[(D2, r1, r1), (C, w2)]))
        for f in (r1, w1, r2, w2):
            os.close(f)

        cwd = os.getcwd()
        os.chdir(os.path.join(d, "sub"))
        show("cwd", lambda: out(os.posix_spawn, "/bin/pwd", ["pwd"]))
        show("cwdrel", lambda: out(os.posix_spawn, "./prog", ["prog"]))
        show("cwdrelmissing", lambda: os.posix_spawn("./nope", ["nope"], env))
        show("cwdopen", lambda: (wait(os.posix_spawn("/bin/echo", ["echo", "rel"], env, file_actions=[(O, 1, "rel.txt", os.O_WRONLY | os.O_CREAT, 0o600)])),
                                 open(os.path.join(d, "sub", "rel.txt")).read()))
        os.chdir(d)
        os.environ["PATH"] = "sub" + os.pathsep + old
        show("cwdp", lambda: out(os.posix_spawnp, "prog", ["prog"]))
        os.environ["PATH"] = old
        os.chdir(cwd)
        try:
            os.waitpid(-1, os.WNOHANG)
        except ChildProcessError as e:
            print("no children left", e.errno)
    finally:
        for root, dirs, files in os.walk(d, topdown=False):
            for n in files:
                os.remove(os.path.join(root, n))
            for n in dirs:
                os.rmdir(os.path.join(root, n))
        os.rmdir(d)
