import os
import subprocess
import sys

# subprocess on Windows (_winapi.CreateProcess, pipes, handle inheritance). Windows only: the POSIX runtime has no
# fork_exec yet, so on POSIX both interpreters print just the first line.
print(os.name, hasattr(subprocess, "Popen"))

if os.name == "nt":
    import tempfile

    comspec = os.environ["ComSpec"]
    print(hasattr(subprocess, "STARTUPINFO"), subprocess.STARTF_USESTDHANDLES, subprocess.SW_HIDE, subprocess.CREATE_NEW_PROCESS_GROUP)

    r = subprocess.run([comspec, "/c", "echo", "hi"], capture_output=True)
    print(r.returncode, r.stdout, r.stderr, r.args == [comspec, "/c", "echo", "hi"])
    print(subprocess.check_output([comspec, "/c", "echo checked"]))
    print(subprocess.run([comspec, "/c", "exit 3"]).returncode)
    try:
        subprocess.run([comspec, "/c", "exit 5"], check=True)
    except subprocess.CalledProcessError as e:
        print("CalledProcessError", e.returncode, e.cmd == [comspec, "/c", "exit 5"])
    print(subprocess.call([comspec, "/c", "exit 0"]), subprocess.check_call([comspec, "/c", "exit 0"]))

    # stdin through a pipe, stdout and stderr merged
    r = subprocess.run(["sort"], input=b"pear\r\napple\r\nfig\r\n", capture_output=True)
    print(r.returncode, r.stdout, r.stderr)
    r = subprocess.run([comspec, "/c", "echo out&echo err>&2"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(r.stdout.splitlines())
    r = subprocess.run([comspec, "/c", "echo e1>&2&exit 2"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    print(r.returncode, r.stdout, r.stderr)
    r = subprocess.run("echo from shell", shell=True, capture_output=True, text=True, encoding="ascii")
    print(r.returncode, repr(r.stdout))
    print(subprocess.getstatusoutput("echo gso&exit 4"), subprocess.getoutput("echo go"))

    # environment and working directory
    env = dict(os.environ, PYJVM_SUBPROC_VAR="forty two")
    print(subprocess.check_output([comspec, "/c", "echo %PYJVM_SUBPROC_VAR%"], env=env).strip())
    root = os.environ["SystemRoot"]
    print(subprocess.check_output([comspec, "/c", "cd"], cwd=root).strip().lower() == root.lower().encode())
    os.environ["PYJVM_SUBPROC_PUT"] = "inherited"
    print(subprocess.check_output([comspec, "/c", "echo %PYJVM_SUBPROC_PUT%"]).strip())
    del os.environ["PYJVM_SUBPROC_PUT"]
    print(subprocess.check_output([comspec, "/c", "echo %PYJVM_SUBPROC_PUT%"]).strip())

    # redirecting to and from files
    d = tempfile.mkdtemp()
    try:
        out = os.path.join(d, "out.txt")
        with open(out, "wb") as f:
            subprocess.run([comspec, "/c", "echo to file"], stdout=f)
        with open(out, "rb") as f:
            print(f.read())
        inp = os.path.join(d, "in.txt")
        with open(inp, "wb") as f:
            f.write(b"zebra\r\nant\r\n")
        with open(inp, "rb") as f:
            print(subprocess.run(["sort"], stdin=f, capture_output=True).stdout)
    finally:
        for n in os.listdir(d):
            os.remove(os.path.join(d, n))
        os.rmdir(d)

    # Popen: communicate, poll, wait with a timeout, kill
    p = subprocess.Popen([comspec, "/c", "echo popen&exit 7"], stdout=subprocess.PIPE)
    print(type(p.pid).__name__, p.stdout is not None, p.stdin, p.communicate(), p.returncode, p.poll())
    with subprocess.Popen([comspec, "/c", "exit 9"]) as p:
        print(p.wait(), p.returncode)
    p = subprocess.Popen(["ping", "-n", "30", "127.0.0.1"], stdout=subprocess.DEVNULL)
    print(p.poll())
    try:
        p.wait(timeout=0.3)
    except subprocess.TimeoutExpired as e:
        print("TimeoutExpired", e.timeout)
    p.kill()
    print(p.wait(), p.poll())
    p.kill()                       # an already finished process is fine
    p = subprocess.Popen(["ping", "-n", "30", "127.0.0.1"], stdout=subprocess.PIPE)
    try:
        p.communicate(timeout=0.3)
    except subprocess.TimeoutExpired:
        print("communicate timeout")
    p.terminate()
    print(p.communicate()[1], p.returncode)

    # errors
    try:
        subprocess.run(["pyjvm-no-such-program-xyz"])
    except OSError as e:
        print(type(e).__name__, e.errno, e.winerror)
    try:
        subprocess.run([comspec, "/c", "echo"], cwd=os.path.join(root, "pyjvm-no-such-dir"))
    except OSError as e:
        print(type(e).__name__, e.winerror)
    print(subprocess.list2cmdline(["a b", 'c"d', "e\\", ""]))
