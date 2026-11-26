import os
import subprocess
import sys

# subprocess on POSIX (_posixsubprocess.fork_exec on posix_spawn, kernel pipes, waitpid). POSIX only: on Windows both
# interpreters print just the first line (subprocess_windows covers nt). Children are system tools, never sys.executable.
print(os.name, hasattr(subprocess, "Popen"))

if os.name == "posix":
    import signal
    import tempfile
    import time

    SH = "/bin/sh"
    print(hasattr(subprocess, "STARTUPINFO"), subprocess._USE_POSIX_SPAWN, subprocess._HAVE_POSIX_SPAWN_CLOSEFROM)

    r = subprocess.run([SH, "-c", "echo hi"], capture_output=True)
    print(r.returncode, r.stdout, r.stderr, r.args == [SH, "-c", "echo hi"])
    print(subprocess.check_output([SH, "-c", "echo checked"]))
    print(subprocess.check_output(["echo", "bare", "name"]))                 # PATH search (executable_list)
    print(subprocess.run([SH, "-c", "exit 3"]).returncode)
    try:
        subprocess.run([SH, "-c", "exit 5"], check=True)
    except subprocess.CalledProcessError as e:
        print("CalledProcessError", e.returncode, e.cmd == [SH, "-c", "exit 5"])
    print(subprocess.call([SH, "-c", "exit 0"]), subprocess.check_call([SH, "-c", "exit 0"]))
    print(subprocess.run([SH, "-c", "kill -TERM $$"]).returncode, subprocess.run([SH, "-c", "kill -KILL $$"]).returncode)
    print(int(subprocess.check_output([SH, "-c", "echo $PPID"])) == os.getpid())

    # stdin through a pipe, stdout and stderr merged, DEVNULL, shell, text mode
    r = subprocess.run(["sort"], input=b"pear\napple\nfig\n", capture_output=True)
    print(r.returncode, r.stdout, r.stderr)
    r = subprocess.run([SH, "-c", "echo out; echo err >&2"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(sorted(r.stdout.splitlines()))
    r = subprocess.run([SH, "-c", "echo e1 >&2; exit 2"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    print(r.returncode, r.stdout, r.stderr)
    r = subprocess.run([SH, "-c", "cat; echo x >&2"], stdin=subprocess.DEVNULL, capture_output=True)
    print(r.returncode, r.stdout, r.stderr)
    r = subprocess.run("echo from shell; echo $0", shell=True, capture_output=True, text=True, encoding="ascii")
    print(r.returncode, repr(r.stdout))
    r = subprocess.run("echo $0", shell=True, executable="/bin/sh", capture_output=True, text=True)
    print(repr(r.stdout))
    print(subprocess.getstatusoutput("echo gso; exit 4"), subprocess.getoutput("echo go"))
    r = subprocess.run(["cat"], input="café\n", capture_output=True, text=True, encoding="utf-8")
    print(ascii(r.stdout))
    big = b"0123456789abcdef" * 20000                                           # 320 KB each way: both pipes must be pumped
    r = subprocess.run(["cat"], input=big, capture_output=True)
    print(len(r.stdout), r.stdout == big)
    print(subprocess.run([SH, "-c", 'echo "$0|$1|$2"', "a b", "c'd", ""], capture_output=True).stdout)
    print(subprocess.run([b"/bin/echo", b"bytes", "mixed"], capture_output=True).stdout)
    print(subprocess.run(["/bin/echo", "x"], executable="/bin/sh", capture_output=True).returncode != 0)   # sh runs with argv[0] "/bin/echo"

    # environment and working directory
    env = dict(os.environ, PYJVM_SUBPROC_VAR="forty two")
    print(subprocess.check_output([SH, "-c", 'echo "$PYJVM_SUBPROC_VAR"'], env=env))
    print(subprocess.check_output([SH, "-c", 'echo "[$HOME][$A]"'], env={"A": "only"}))
    print(subprocess.check_output([SH, "-c", 'echo "[$A]"'], env={b"A": b"bytes"}))
    try:
        subprocess.run([SH, "-c", "true"], env={"A=B": "1"})
    except ValueError as e:
        print("ValueError", e)
    os.environ["PYJVM_SUBPROC_PUT"] = "inherited"
    print(subprocess.check_output([SH, "-c", 'echo "[$PYJVM_SUBPROC_PUT]"']))
    del os.environ["PYJVM_SUBPROC_PUT"]
    print(subprocess.check_output([SH, "-c", 'echo "[${PYJVM_SUBPROC_PUT-unset}]"']))
    print(subprocess.check_output(["pwd"], cwd="/usr"), subprocess.check_output(["/bin/pwd"], cwd=b"/usr"))
    d = os.path.realpath(tempfile.mkdtemp())
    old = os.getcwd()
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

        # the emulated working directory reaches children: os.chdir, relative programs, relative cwd=
        os.chdir(d)
        print(subprocess.check_output(["pwd"]).decode().strip() == d, subprocess.check_output(["/bin/pwd"]).decode().strip() == d)
        print(subprocess.check_output(["sub/prog", "1"]), subprocess.check_output(["./prog", "2"], cwd="sub"))
        print(subprocess.check_output(["pwd"], cwd="sub").decode().strip() == os.path.join(d, "sub"))
        print(subprocess.check_output("pwd", shell=True, cwd="sub").decode().strip() == os.path.join(d, "sub"))
        os.chdir(old)

        # redirecting to and from files: the child shares the parent's file offset
        out = os.path.join(d, "out.txt")
        with open(out, "wb") as f:
            f.write(b"before\n")
            f.flush()
            subprocess.run([SH, "-c", "echo to file"], stdout=f)
            f.write(b"after\n")
        with open(out, "rb") as f:
            print(f.read())
        with open(out, "ab") as f:
            subprocess.run([SH, "-c", "echo appended; echo err >&2"], stdout=f, stderr=subprocess.STDOUT)
        with open(out, "rb") as f:
            print(f.read())
        inp = os.path.join(d, "in.txt")
        with open(inp, "wb") as f:
            f.write(b"zebra\nant\n")
        with open(inp, "rb") as f:
            print(subprocess.run(["sort"], stdin=f, capture_output=True).stdout)
        with open(inp, "rb") as f:
            f.readline()                                                    # buffered: the child sees the kernel offset (EOF)
            print(subprocess.run(["cat"], stdin=f, capture_output=True).stdout)
        fd = os.open(inp, os.O_RDONLY)
        os.read(fd, 6)
        print(subprocess.run(["cat"], stdin=fd, capture_output=True).stdout, os.read(fd, 10))
        os.close(fd)

        # descriptors: close_fds, pass_fds, inheritable flag
        r1, w1 = os.pipe()
        r2, w2 = os.pipe()
        os.set_inheritable(r2, True)
        probe = "for f in %d %d %d %d; do test -e /proc/self/fd/$f && echo -n o || echo -n c; done" % (r1, w1, r2, w2)
        print(subprocess.check_output([SH, "-c", probe]),
              subprocess.check_output([SH, "-c", probe], pass_fds=(w1,)),
              subprocess.check_output([SH, "-c", probe], close_fds=False),
              subprocess.check_output([SH, "-c", probe], close_fds=False, pass_fds=[r1, w2]))
        print(os.get_inheritable(w1), os.get_inheritable(r2))                # pass_fds does not change the parent's flags
        subprocess.run([SH, "-c", "echo via pass_fds >&%d" % w1], pass_fds=(w1,))
        print(os.read(r1, 100))
        for f in (r1, w1, r2, w2):
            os.close(f)
        # a child that keeps no copy of the pipe: EOF arrives when it exits
        p = subprocess.Popen(["cat"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        p.stdin.write(b"one\n")
        p.stdin.close()
        print(p.stdout.read(), p.wait())
        p.stdout.close()

        # stdio descriptor shuffles: a child stream given as descriptor 0, 1 or 2 of the parent
        print(subprocess.run([SH, "-c", "echo to-stderr-of-child >&2"], stderr=subprocess.PIPE, stdout=2).stderr)
        r3, w3 = os.pipe()
        p = subprocess.Popen([SH, "-c", "echo swapped-out; echo swapped-err >&2"], stdout=w3, stderr=w3)
        os.close(w3)
        print(p.wait(), sorted(os.read(r3, 200).splitlines()))
        os.close(r3)

        # sessions and process groups
        ids = "echo $(( $(cut -d' ' -f6 /proc/self/stat) == $$ )) $(( $(cut -d' ' -f5 /proc/self/stat) == $$ ))"
        print(subprocess.check_output([SH, "-c", ids]), subprocess.check_output([SH, "-c", ids], start_new_session=True),
              subprocess.check_output([SH, "-c", ids], process_group=0))
        p = subprocess.Popen(["sleep", "30"], process_group=0)
        print(os.getpgid(p.pid) == p.pid, os.getsid(p.pid) == os.getsid(0))
        os.killpg(p.pid, signal.SIGTERM)
        print(p.wait())
        # signals in the child: restore_signals, and the Python-level mask is inherited
        sig = "grep -E 'Sig(Blk|Ign)' /proc/$$/status | tr '\\n\\t' '  '"
        print(subprocess.check_output([SH, "-c", sig]))
        signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1])
        print(subprocess.check_output([SH, "-c", sig]))
        signal.pthread_sigmask(signal.SIG_UNBLOCK, [signal.SIGUSR1])

    finally:
        os.chdir(old)
        for root, dirs, files in os.walk(d, topdown=False):
            for n in files:
                os.remove(os.path.join(root, n))
            for n in dirs:
                os.rmdir(os.path.join(root, n))
        os.rmdir(d)

    # Popen: communicate, poll, wait with a timeout, kill, terminate, send_signal
    p = subprocess.Popen([SH, "-c", "echo popen; exit 7"], stdout=subprocess.PIPE)
    print(type(p.pid).__name__, p.pid > 0, p.stdout is not None, p.stdin, p.communicate(), p.returncode, p.poll())
    with subprocess.Popen([SH, "-c", "exit 9"]) as p:
        print(p.wait(), p.returncode)
    p = subprocess.Popen(["sleep", "30"], stdout=subprocess.DEVNULL)
    print(p.poll())
    try:
        p.wait(timeout=0.3)
    except subprocess.TimeoutExpired as e:
        print("TimeoutExpired", e.timeout)
    p.kill()
    print(p.wait(), p.poll(), p.returncode)
    p.kill()                       # an already finished process is fine
    p.send_signal(signal.SIGTERM)
    p = subprocess.Popen(["sleep", "30"], stdout=subprocess.PIPE)
    try:
        p.communicate(timeout=0.3)
    except subprocess.TimeoutExpired:
        print("communicate timeout")
    p.terminate()
    print(p.communicate(), p.returncode)
    p = subprocess.Popen(["sleep", "30"])
    p.send_signal(signal.SIGSTOP)
    time.sleep(0.1)
    print(p.poll())
    p.send_signal(signal.SIGCONT)
    p.send_signal(signal.SIGINT)
    print(p.wait(timeout=10))
    t0 = time.monotonic()
    print(subprocess.run(["sleep", "0.2"]).returncode, 0.15 < time.monotonic() - t0 < 5)
    try:
        subprocess.run(["sleep", "30"], timeout=0.3)
    except subprocess.TimeoutExpired as e:
        print("run timeout", e.cmd)
    # many short children in a row: every one is reaped, no descriptor is leaked
    before = len(os.listdir("/proc/self/fd"))
    print(sum(subprocess.run([SH, "-c", "exit %d" % (i % 3)], stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode for i in range(30)))
    print(len(os.listdir("/proc/self/fd")) == before)
    # waiting from another thread while the main thread also polls
    import threading
    p = subprocess.Popen(["sleep", "0.3"])
    res = []
    t = threading.Thread(target=lambda: res.append(p.wait()))
    t.start()
    print(p.wait(), (t.join(), res)[1])
    # unsupported without fork (pyJVM-specific texts stay out of this case; see tests/test_posix_process.py)
    print(subprocess.list2cmdline(["a b", 'c"d']))
    # os.system on the spawn core (never libc system(3)): the raw wait status
    print(os.system("exit 3"))
    print(os.system("kill -TERM $$"))
    print(os.waitstatus_to_exitcode(os.system("true")))
    print(os.system("-n 2>/dev/null; exit 7"))   # 1792: a leading "-" is a command (sh -c --), not shell options
