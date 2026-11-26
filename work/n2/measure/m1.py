# N2 measurement 1: module surfaces. CPython only. SAFE: read-only, no terminal or rlimit change.
import sys, os
print("VERSION", sys.version_info[:3])
for name in ("fcntl", "termios", "resource", "pwd", "grp"):
    m = __import__(name)
    ints = {}; other = []
    for n in sorted(dir(m)):
        if n.startswith("__"): continue
        v = getattr(m, n)
        if isinstance(v, int) and not isinstance(v, bool): ints[n] = v
        else:
            sig = getattr(v, "__text_signature__", None)
            other.append((n, type(v).__name__, sig))
    print("MODULE", name, "ints", len(ints), "other", len(other))
    for n, t, s in other: print("  NAME", name, n, t, s)
    for n, v in ints.items(): print("  INT", name, n, v)
    print("  DOCNONE", name, m.__doc__ is None, getattr(m, "__file__", None) is None)
import termios, resource, pwd, grp, fcntl
print("termios.error", termios.error, termios.error.__mro__, termios.error.__module__, termios.error.__qualname__)
print("resource.error", resource.error is OSError)
for c in (pwd.struct_passwd, grp.struct_group, resource.struct_rusage):
    print("SEQ", c.__module__, c.__qualname__, c.n_fields, c.n_sequence_fields, c.n_unnamed_fields, getattr(c, "__match_args__", None))
for n in ("openpty", "ttyname", "login_tty", "ctermid", "tcgetpgrp", "tcsetpgrp", "forkpty", "posix_openpt", "grantpt", "unlockpt", "ptsname", "getlogin", "getgroups", "getgrouplist", "setsid", "initgroups", "get_terminal_size", "isatty", "fork"):
    f = getattr(os, n, None)
    print("OS", n, f is not None, getattr(f, "__text_signature__", None))
