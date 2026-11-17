import os
import subprocess

# Child processes see os.putenv / os.unsetenv / os.environ writes (the reason those functions exist). Windows only for
# now: the POSIX runtime has no process creation yet, so on POSIX both interpreters print just the first line.
print(os.name)

if os.name == "nt":
    comspec = os.environ["ComSpec"]
    pid = os.getpid()
    A = "PYJVM_PEC_%d_A" % pid
    B = "PYJVM_PEC_%d_B" % pid
    C = "PYJVM_PEC_%d_C" % pid
    D = "PYJVM_PEC_%d_D" % pid
    E = "PYJVM_PEC_%d_E" % pid
    F = "PYJVM_PEC_%d_F" % pid
    G = "PYJVM_PEC_%d_G" % pid

    def child(name, env=None):
        out = subprocess.run([comspec, "/d", "/c", "echo [%" + name + "%]"], capture_output=True, env=env, timeout=60).stdout.strip()
        return b"<unset>" if out == b"[%" + name.encode() + b"%]" else out

    try:
        print(1, child(A))
        os.putenv(A, "raw")
        print(2, child(A), os.getenv(A), A in os.environ)
        print(3, child(A, env=dict(os.environ)))
        os.putenv(A, "raw2")
        print(4, child(A))
        os.unsetenv(A)
        print(5, child(A))

        os.environ[B] = "map"
        print(6, child(B), os.getenv(B))
        os.environ[B] = "map2"
        print(6, child(B))
        del os.environ[B]
        print(6, child(B), B in os.environ)

        os.environ[C] = "one"
        os.putenv(C, "two")
        print(7, os.environ[C], child(C), child(C, env=dict(os.environ)))

        os.environ[E] = "pop"
        print(8, child(E))
        print(8, os.environ.pop(E), child(E))
        print(8, os.environ.setdefault(E, "sd"), child(E))
        os.environ.update({E: "up", F: "up2"})
        print(8, child(E), child(F))

        os.putenv(D, "x")
        print(9, child(D))
        os.putenv(D, "")
        print(9, child(D))

        os.putenv(G.lower(), "ci")
        print(11, child(G))
        os.putenv(G, "v" * 2000)
        print(11, len(child(G)) - 2)

        os.putenv(A, "again")
        print(12, child(A), child(A))
    finally:
        for n in (A, B, C, D, E, F, G):
            os.unsetenv(n)
