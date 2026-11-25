import os
import sys

# _posixsubprocess.fork_exec called directly: the error record written to errpipe_write, the exec-list rule and the
# argument checks (POSIX only). subprocess is not imported.
print(os.name)

if os.name == "posix":
    import tempfile
    import _posixsubprocess

    NARGS = 23 if sys.version_info[:2] == (3, 13) else 22
    d = os.path.realpath(tempfile.mkdtemp())

    def call(args, exes, close_fds, keep, cwd, env_list, stdio, er, ew, restore=True, setsid=False, pgid=-1):
        a = [args, exes, close_fds, keep, cwd, env_list, *stdio, er, ew, restore, setsid, pgid, None, None, None, -1, None]
        if NARGS == 23:
            a.append(True)
        return _posixsubprocess.fork_exec(*a)

    def fe(args, exes, cwd=None, env_list=None, close_fds=True, keep=(), stdio=(-1, -1, -1, -1, -1, -1), **kw):
        er, ew = os.pipe()
        try:
            pid = call(args, exes, close_fds, tuple(sorted(set(keep) | {ew})), cwd, env_list, stdio, er, ew, **kw)
        finally:
            os.close(ew)
        data = b""
        while True:
            b = os.read(er, 50000)
            if not b:
                break
            data += b
        os.close(er)
        return type(pid).__name__, pid > 0, data, os.waitpid(pid, 0)[1]

    def show(label, fn):
        try:
            print(label, fn())
        except Exception as e:
            print(label, type(e).__name__, e)

    def mk(name, text, mode):
        with open(os.path.join(d, name), "w") as f:
            f.write(text)
        os.chmod(os.path.join(d, name), mode)

    try:
        mk("noexec", "#!/bin/sh\n", 0o644)
        mk("noshebang", "echo no shebang\n", 0o755)
        mk("badinterp", "#!/no/such/interp\n", 0o755)
        b = os.fsencode
        show("true", lambda: fe([b"true"], (b"/bin/true",)))
        show("exit3", lambda: fe(["sh", "-c", "exit 3"], (b"/bin/sh",)))
        show("missing", lambda: fe([b"x"], (b"/no/such/prog",)))
        show("noexec", lambda: fe([b"x"], (b(d + "/noexec"),)))
        show("isdir", lambda: fe([b"x"], (b(d),)))
        show("notdir", lambda: fe([b"x"], (b(d + "/noexec/x"),)))
        show("noshebang", lambda: fe([b"x"], (b(d + "/noshebang"),)))
        show("badinterp", lambda: fe([b"x"], (b(d + "/badinterp"),)))
        show("list1", lambda: fe([b"x"], (b"/no/such/a", b(d + "/noexec"), b"/no/such/b")))
        show("list2", lambda: fe([b"x"], (b(d + "/noexec"), b(d + "/noshebang"))))
        show("list3", lambda: fe([b"x"], (b(d + "/noexec/x"), b"/no/such/b")))
        show("list4", lambda: fe([b"x"], (b"/no/such/b", b(d + "/noexec/x"))))
        show("list5", lambda: fe([b"true"], (b"/no/such/a", b"/bin/true")))
        show("list6", lambda: fe([b"true"], [b"/bin/true"]))
        show("cwd missing", lambda: fe([b"true"], (b"/bin/true",), cwd="/no/such/dir"))
        show("cwd notdir", lambda: fe([b"true"], (b"/bin/true",), cwd=d + "/noexec"))
        show("cwd empty", lambda: fe([b"true"], (b"/bin/true",), cwd=""))
        show("cwd bytes", lambda: fe([b"true"], (b"/bin/true",), cwd=b(d)))
        show("cwd+missing", lambda: fe([b"x"], (b"/no/such/prog",), cwd="/no/such/dir"))
        show("bad stdio", lambda: fe([b"true"], (b"/bin/true",), stdio=(9999, -1, -1, -1, -1, -1)))
        show("pgid 1", lambda: fe([b"true"], (b"/bin/true",), pgid=1))
        show("env", lambda: fe(["sh", "-c", 'test "$A" = 1 && test -z "$HOME"'], (b"/bin/sh",), env_list=[b"A=1"]))
        show("env None", lambda: fe(["sh", "-c", 'test -n "$PATH"'], (b"/bin/sh",)))
        show("args str", lambda: fe(["true"], (b"/bin/true",)))
        show("args int", lambda: fe([5], (b"/bin/true",)))
        show("exe str", lambda: fe([b"true"], ("/bin/true",)))
        show("env str", lambda: fe(["true"], (b"/bin/true",), env_list=["A=1"]))
        show("keep list", lambda: call([b"true"], (b"/bin/true",), True, [5], None, None, (-1,) * 6, 3, 4))
        show("keep neg", lambda: call([b"true"], (b"/bin/true",), True, (-5,), None, None, (-1,) * 6, 3, 4))
        show("keep unsorted", lambda: call([b"true"], (b"/bin/true",), True, (9, 5), None, None, (-1,) * 6, 3, 4))
        show("errpipe low", lambda: call([b"true"], (b"/bin/true",), True, (), None, None, (-1,) * 6, 3, 2))
        show("argcount", lambda: _posixsubprocess.fork_exec(1, 2, 3))

        def fds(close_fds, keep_w):
            r, w = os.pipe()
            r2, w2 = os.pipe()
            os.set_inheritable(r2, True)
            o, ow = os.pipe()
            try:
                res = fe(["sh", "-c", "for f in %d %d %d %d; do test -e /proc/self/fd/$f && echo -n o || echo -n c; done" % (r, w, r2, w2)], (b"/bin/sh",),
                         close_fds=close_fds, keep=(w,) if keep_w else (), stdio=(-1, -1, -1, ow, -1, -1))
            finally:
                os.close(ow)
            out = os.read(o, 100)
            for f in (o, r, w, r2, w2):
                os.close(f)
            return res[3], out
        show("fds close keep", lambda: fds(True, True))
        show("fds noclose keep", lambda: fds(False, True))
        show("fds noclose", lambda: fds(False, False))
        show("fds close", lambda: fds(True, False))
        try:
            os.waitpid(-1, os.WNOHANG)
        except ChildProcessError as e:
            print("no children", e.errno)
    finally:
        for n in os.listdir(d):
            os.remove(os.path.join(d, n))
        os.rmdir(d)
