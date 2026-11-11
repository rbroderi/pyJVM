# posix file-system functions, stat_result and print(file=...).
import _io
import posix
import sys

D = "/tmp/pyjvm_io_posix_conf"


def show(label, fn):
    try:
        r = fn()
    except BaseException as e:
        print(label, type(e).__name__, str(e).replace(D, "<D>"))
    else:
        print(label, repr(r).replace(D, "<D>"))


try:
    posix.mkdir(D)
except FileExistsError:
    pass
show("consts", lambda: (posix.O_RDONLY, posix.O_WRONLY, posix.O_RDWR, posix.O_APPEND, posix.O_CREAT, posix.O_EXCL, posix.O_TRUNC,
                        posix.SEEK_SET if hasattr(posix, "SEEK_SET") else 0))
show("getcwd", lambda: isinstance(posix.getcwd(), str))
fd = posix.open(D + "/a.txt", posix.O_WRONLY | posix.O_CREAT | posix.O_TRUNC, 0o644)
show("write", lambda: (posix.write(fd, b"hello world\n"), posix.lseek(fd, 0, 1), posix.fsync(fd), posix.ftruncate(fd, 5)))
posix.close(fd)
show("close again", lambda: posix.close(fd))
fd = posix.open(D + "/a.txt", posix.O_RDONLY)
show("read", lambda: (posix.read(fd, 3), posix.lseek(fd, 0, 2), posix.read(fd, 10), posix.lseek(fd, 1, 0), posix.read(fd, 100)))
st = posix.fstat(fd)
show("fstat", lambda: (st.st_size, st[6], len(st), st.st_mode & 0o170000 == 0o100000, type(st.st_mtime).__name__, isinstance(st.st_mtime_ns, int), st.st_nlink))
show("isatty", lambda: posix.isatty(fd))
fd2 = posix.dup(fd)
show("dup", lambda: (fd2 != fd, posix.read(fd2, 2)))
posix.close(fd2)
posix.close(fd)
show("stat", lambda: (posix.stat(D + "/a.txt").st_size, posix.stat(D).st_mode & 0o170000 == 0o040000, posix.lstat(D + "/a.txt").st_size))
show("stat missing", lambda: posix.stat(D + "/missing"))
show("stat bytes", lambda: posix.stat((D + "/a.txt").encode()).st_size)
show("stat repr", lambda: repr(posix.stat(D + "/a.txt")).startswith("os.stat_result(st_mode="))
show("rename", lambda: (posix.rename(D + "/a.txt", D + "/b.txt"), sorted(posix.listdir(D))))
show("rename missing", lambda: posix.rename(D + "/a.txt", D + "/c.txt"))
show("mkdir", lambda: (posix.mkdir(D + "/sub"), posix.mkdir(D + "/sub")))
show("mkdir nested", lambda: posix.mkdir(D + "/x/y"))
show("listdir", lambda: sorted(posix.listdir(D)))
show("listdir bytes", lambda: sorted(posix.listdir(D.encode())))
show("listdir file", lambda: posix.listdir(D + "/b.txt"))
show("listdir missing", lambda: posix.listdir(D + "/missing"))
show("rmdir", lambda: (posix.rmdir(D + "/sub"), posix.rmdir(D + "/sub")))
show("rmdir nonempty", lambda: posix.rmdir(D))
show("unlink dir", lambda: posix.unlink(D))
show("remove", lambda: (posix.remove(D + "/b.txt"), posix.remove(D + "/b.txt")))
show("chdir", lambda: (posix.chdir(D), posix.getcwd() == D, posix.chdir("/")))
show("chdir missing", lambda: posix.chdir(D + "/missing"))
show("open excl", lambda: (posix.close(posix.open(D + "/e", posix.O_CREAT | posix.O_EXCL | posix.O_WRONLY)), posix.open(D + "/e", posix.O_CREAT | posix.O_EXCL | posix.O_WRONLY)))
show("utime", lambda: (posix.utime(D + "/e", (1000000000, 1000000001)), posix.stat(D + "/e").st_mtime, posix.stat(D + "/e").st_atime))
show("utime ns", lambda: (posix.utime(D + "/e", ns=(5 * 10**9, 7 * 10**9)), posix.stat(D + "/e").st_mtime_ns))
show("fspath", lambda: (posix.fspath("a"), posix.fspath(b"b")))
show("fspath bad", lambda: posix.fspath(1))
show("read bad fd", lambda: posix.read(9999, 1))
show("write bad fd", lambda: posix.write(9999, b"x"))
show("lseek bad fd", lambda: posix.lseek(9999, 0, 0))
show("read neg", lambda: posix.read(0, -1))
show("environ", lambda: isinstance(posix.environ, dict) and all(type(k) is bytes for k in posix.environ))
show("devnull", lambda: open("/dev/null", "wb").write(b"abc"))
show("scandir", lambda: sorted((e.name, e.is_file(), e.is_dir()) for e in posix.scandir(D)))
show("umask", lambda: isinstance(posix.umask(0o22), int))
show("strerror", lambda: posix.strerror(2))

# ---- print ----
buf = _io.StringIO()
print("a", 1, 2.5, None, file=buf)
print("x", "y", sep="-", end="!", file=buf)
print(file=buf)
print(*[], file=buf)
print("only", end="", file=buf)
show("print file", lambda: buf.getvalue())
show("print bad sep", lambda: print("a", sep=1, file=buf))
show("print bad end", lambda: print("a", end=1, file=buf))
show("print sep None", lambda: (print("a", "b", sep=None, end=None, file=buf), buf.getvalue()[-5:]))
show("print bad kw", lambda: print("a", foo=1))
show("print bad file", lambda: print("a", file=object()))


class W:
    def __init__(self):
        self.parts = []
        self.flushed = 0

    def write(self, s):
        self.parts.append(s)

    def flush(self):
        self.flushed += 1


w = W()
print("p", "q", file=w, flush=True)
show("print custom", lambda: (w.parts, w.flushed))
show("print None file", lambda: print("goes to stdout", file=None))
old = sys.stdout
sys.stdout = buf2 = _io.StringIO()
print("redirected")
sys.stdout = old
show("print redirect", lambda: buf2.getvalue())
sys.stdout = None
show("print stdout None", lambda: print("silent"))
sys.stdout = old
show("std streams", lambda: (sys.stdout is sys.__stdout__, sys.stderr is sys.__stderr__, sys.stdin is sys.__stdin__,
                             sys.stdout.encoding, sys.stdout.errors, sys.stdin.encoding, sys.stdout.fileno(), sys.stderr.fileno(), sys.stdin.fileno()))
show("std types", lambda: type(sys.stdout).__name__)
show("std name", lambda: (sys.stdout.name, sys.stderr.name, sys.stdin.name, sys.stdout.mode, sys.stdin.mode))
sys.stderr.write("to stderr\n")
sys.stdout.write("direct write\n")
sys.stdout.flush()
print("after flush")

# cleanup
for n in posix.listdir(D):
    posix.unlink(D + "/" + n)
posix.rmdir(D)
