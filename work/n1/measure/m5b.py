# dash clears the inherited signal mask itself: only a direct grep child shows SigBlk (see ticket 10 notes)
import os, signal, subprocess
signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1, signal.SIGUSR2])
G = "grep SigBlk /proc/self/status"
for lab, f in (("system sh -c grep (exec'd by dash?)", lambda: os.system(G)),
               ("system sh -c grep; true", lambda: os.system(G + "; true")),
               ("system: sh sees", lambda: os.system("grep SigBlk /proc/$$/status")),
               ("run shell=True", lambda: subprocess.run(G, shell=True)),
               ("run sh -c", lambda: subprocess.run(["sh", "-c", G + "; true"])),
               ("posix_spawn sh -c", lambda: os.waitpid(os.posix_spawn("/bin/sh", ["sh", "-c", G + "; true"], os.environ), 0)),
               ("run grep", lambda: subprocess.run(["grep", "SigBlk", "/proc/self/status"]))):
    print("--", lab, flush=True); f()
