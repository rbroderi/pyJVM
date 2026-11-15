import os
import stat

base = os.path.join(os.environ.get("TEMP") or os.environ.get("TMPDIR") or "/tmp", "pyjvm_os_fchmod_%d" % os.getpid())
os.mkdir(base)
try:
    f = os.path.join(base, "data.txt")
    fd = os.open(f, os.O_RDWR | os.O_CREAT, 0o666)
    try:
        os.fchmod(fd, stat.S_IREAD)
        mode = stat.S_IMODE(os.stat(f).st_mode)
        print(bool(mode & stat.S_IWRITE), bool(mode & stat.S_IREAD), os.access(f, os.W_OK))
        if os.name != "nt":
            os.fchmod(fd, 0o640)
            print(oct(stat.S_IMODE(os.fstat(fd).st_mode)), oct(stat.S_IMODE(os.stat(f).st_mode)))
        os.fchmod(fd, stat.S_IREAD | stat.S_IWRITE)
        print(bool(stat.S_IMODE(os.stat(f).st_mode) & stat.S_IWRITE))
        for bad in (lambda: os.fchmod(9999, 0o600), lambda: os.fchmod("x", 0o600), lambda: os.fchmod(fd)):
            try:
                bad()
            except BaseException as e:
                print(type(e).__name__, e.args[0] if isinstance(e, OSError) else "")
    finally:
        os.close(fd)
finally:
    os.chmod(os.path.join(base, "data.txt"), stat.S_IREAD | stat.S_IWRITE)
    os.remove(os.path.join(base, "data.txt"))
    os.rmdir(base)
