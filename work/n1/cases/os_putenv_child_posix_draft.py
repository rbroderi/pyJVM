import os
import subprocess

print(os.name)

if os.name == "posix":
    pid = os.getpid()
    A, B, C, D, E, F, G = ("PYJVM_PEC_%d_%s" % (pid, x) for x in "ABCDEFG")

    def child(name, env=None):
        if isinstance(name, bytes):
            name = name.decode()
        return subprocess.run(["/bin/sh", "-c", 'printf "[%s]" "${' + name + '-<unset>}"'], capture_output=True, env=env, timeout=60).stdout

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
        os.putenv(os.fsencode(G), b"bytes api")
        print(10, child(G))
        os.putenv(G, "café €")
        print(10, child(G))
        os.putenv(G, "a=b c  d")
        print(10, child(G))
        os.environ[G] = "mapped"
        os.unsetenv(G)
        print(10, child(G), os.environ[G], child(G, env=dict(os.environ)))
        os.putenv(A, "again")
        print(12, child(A), child(A))
        # the same through os.posix_spawn with env=None is covered by os_posix_spawn
    finally:
        for n in (A, B, C, D, E, F, G):
            os.unsetenv(n)
