import os
import subprocess

# subprocess on POSIX: failures to start (missing program, bad cwd, not executable) and what they leave behind.
# POSIX only: on Windows both interpreters print just the first line.
print(os.name)

if os.name == "posix":
    import tempfile

    SH = "/bin/sh"
    d = os.path.realpath(tempfile.mkdtemp())
    try:
        os.mkdir(os.path.join(d, "sub"))
        with open(os.path.join(d, "sub", "prog"), "w") as f:
            f.write("#!/bin/sh\necho prog ran $1\n")
        os.chmod(os.path.join(d, "sub", "prog"), 0o755)
        with open(os.path.join(d, "noexec"), "w") as f:
            f.write("#!/bin/sh\n")
        os.chmod(os.path.join(d, "noexec"), 0o644)
        with open(os.path.join(d, "noshebang"), "w") as f:
            f.write("echo no shebang\n")
        os.chmod(os.path.join(d, "noshebang"), 0o755)

        # errors
        def err(*a, **k):
            try:
                subprocess.run(*a, **k)
                print("no error")
            except OSError as e:
                fn = e.filename
                if isinstance(fn, str):
                    fn = fn.replace(d, "<tmp>")
                print(type(e).__name__, e.errno, e.strerror, repr(fn), e.filename2)
            except subprocess.SubprocessError as e:
                print(type(e).__name__, e)
        err(["pyjvm-no-such-program-xyz"])
        err(["/no/such/dir/prog"])
        err(["./relative-missing"])
        err([os.path.join(d, "noexec")])
        err([d])
        err([os.path.join(d, "noexec", "x")])
        err([os.path.join(d, "noshebang")])
        err([SH, "-c", "true"], cwd=os.path.join(d, "no-such-dir"))
        err([SH, "-c", "true"], cwd=os.path.join(d, "noexec"))
        err(["pyjvm-no-such-program-xyz"], cwd=os.path.join(d, "no-such-dir"))
        err(["pyjvm-no-such-program-xyz"], env={"PATH": d})
        err(["prog", "3"], env={"PATH": os.path.join(d, "sub")}, stdout=subprocess.DEVNULL)   # found through env's PATH: no error
        err([SH, "-c", "true"], stdin=9999)
        err("exit 0", shell=True, executable="/no/such/shell")
        try:
            subprocess.Popen([])
        except (IndexError, OSError, ValueError) as e:
            print(type(e).__name__)
        # the failed Popen object: a pid, exit status 255 collected, nothing left to wait for
        class P(subprocess.Popen):
            last = None

            def __init__(self, *a, **k):
                P.last = self
                super().__init__(*a, **k)
        for a, k in ((["/no/such/prog"], {}), ([SH, "-c", "true"], {"cwd": os.path.join(d, "no-such-dir")}), (["/no/such/prog"], {"close_fds": False}),
                     (["/no/such/prog"], {"stdin": subprocess.PIPE, "stdout": subprocess.PIPE, "stderr": subprocess.PIPE})):
            before = len(os.listdir("/proc/self/fd"))
            try:
                P(a, **k)
            except OSError as e:
                print(type(e).__name__, P.last.returncode, P.last._child_created, len(os.listdir("/proc/self/fd")) == before)
        try:
            os.waitpid(-1, os.WNOHANG)
        except ChildProcessError as e:
            print("no children", e.errno)
    finally:
        for root, dirs, files in os.walk(d, topdown=False):
            for n in files:
                os.remove(os.path.join(root, n))
            for n in dirs:
                os.rmdir(os.path.join(root, n))
        os.rmdir(d)
