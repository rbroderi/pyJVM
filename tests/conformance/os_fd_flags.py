import os
print(os.name)
if os.name == "posix":
    import stat
    import tempfile

    def show(label, fn):
        try:
            r = fn()
        except BaseException as e:
            print(label, type(e).__name__, e.args[0] if isinstance(e, OSError) and e.args else str(e))
        else:
            print(label, r)

    d = tempfile.mkdtemp(prefix="pyjvm_fd_flags_")
    old_umask = os.umask(0o22)
    os.umask(old_umask)
    opened = []
    try:
        p = os.path.join(d, "f")
        fd = os.open(p, os.O_RDWR | os.O_CREAT, 0o644)
        opened.append(fd)
        print("open inheritable", os.get_inheritable(fd))
        fd2 = os.dup(fd)
        opened.append(fd2)
        print("dup inheritable", os.get_inheritable(fd2))
        os.set_inheritable(fd, True)
        print("set True", os.get_inheritable(fd), os.get_inheritable(fd2))
        os.set_inheritable(fd, True)
        os.set_inheritable(fd, False)
        os.set_inheritable(fd, False)
        print("set False", os.get_inheritable(fd))

        print("blocking", os.get_blocking(fd))
        os.set_blocking(fd, False)
        print("set non-blocking", os.get_blocking(fd), os.get_blocking(fd2))
        os.set_blocking(fd, False)
        print("write still works", os.write(fd, b"hello"))
        os.set_blocking(fd, True)
        os.set_blocking(fd, True)
        print("set blocking", os.get_blocking(fd))

        # fstat of a file: the size is the kernel's, visible through a second descriptor without any flush
        fd3 = os.open(p, os.O_RDONLY)
        opened.append(fd3)
        st = os.fstat(fd3)
        print("size", st.st_size, st[6], len(st))
        print("regular", stat.S_ISREG(st.st_mode), stat.S_ISDIR(st.st_mode), st.st_nlink)
        ps = os.stat(p)
        print("same file", st.st_ino == ps.st_ino, st.st_dev == ps.st_dev, st[1] == st.st_ino, st[2] == st.st_dev)
        print("times", type(st.st_mtime).__name__, isinstance(st.st_mtime_ns, int), isinstance(st.st_atime_ns, int),
              isinstance(st.st_ctime_ns, int), st.st_mtime_ns // 10**9 == int(st.st_mtime))
        print("blocks", isinstance(st.st_blocks, int), isinstance(st.st_blksize, int), st.st_blksize > 0, isinstance(st.st_rdev, int))
        print("owner", os.geteuid() == st.st_uid, st.st_uid >= 0, st.st_gid >= 0)
        print("ids", type(os.getuid()).__name__, type(os.geteuid()).__name__, type(os.getgid()).__name__, type(os.getegid()).__name__,
              os.getppid() > 0)

        # a directory descriptor
        dfd = os.open(d, os.O_RDONLY)
        opened.append(dfd)
        dst = os.fstat(dfd)
        print("dir", stat.S_ISDIR(dst.st_mode), stat.S_ISREG(dst.st_mode), dst.st_ino == os.stat(d).st_ino, dst.st_dev == os.stat(d).st_dev)

        # ftruncate, fsync, fdatasync
        print("ftruncate", os.ftruncate(fd, 3), os.fstat(fd3).st_size)
        print("fsync", os.fsync(fd), os.fdatasync(fd))

        # fchmod acts on the open file: after a rename, and after the last name is gone
        os.fchmod(fd, 0o600)
        print("fchmod", oct(stat.S_IMODE(os.fstat(fd).st_mode)), oct(stat.S_IMODE(os.stat(p).st_mode)))
        q = os.path.join(d, "g")
        os.rename(p, q)
        os.fchmod(fd, 0o640)
        print("fchmod renamed", oct(stat.S_IMODE(os.fstat(fd).st_mode)), oct(stat.S_IMODE(os.stat(q).st_mode)))
        os.unlink(q)
        os.fchmod(fd, 0o644)
        print("fchmod unlinked", oct(stat.S_IMODE(os.fstat(fd).st_mode)), os.fstat(fd).st_nlink)

        # errors
        show("fstat bad", lambda: os.fstat(9999))
        show("fstat float", lambda: os.fstat(1.5))
        show("get_inheritable bad", lambda: os.get_inheritable(9999))
        show("set_inheritable bad", lambda: os.set_inheritable(9999, True))
        show("get_blocking bad", lambda: os.get_blocking(9999))
        show("set_blocking bad", lambda: os.set_blocking(9999, True))
        show("dup bad", lambda: os.dup(9999))
        show("fchmod bad", lambda: os.fchmod(9999, 0o600))
        show("fsync bad", lambda: os.fsync(9999))
        show("fdatasync bad", lambda: os.fdatasync(9999))
        show("ftruncate bad", lambda: os.ftruncate(9999, 0))
        show("close bad", lambda: os.close(9999))
        show("close -1", lambda: os.close(-1))
        show("close huge", lambda: os.close(2**40))
        show("fstat huge", lambda: os.fstat(2**40))
        show("isatty bad", lambda: os.isatty(9999))
        show("isatty file", lambda: os.isatty(fd))

        # umask
        old = os.umask(0o22)
        print("umask", oct(os.umask(0o7777777)), oct(os.umask(0o27)), oct(os.umask(0o22)))
        os.umask(old)
        fd4 = os.open(os.path.join(d, "m"), os.O_WRONLY | os.O_CREAT, 0o666)
        os.close(fd4)
        os.umask(0o22)
        fd4 = os.open(os.path.join(d, "n"), os.O_WRONLY | os.O_CREAT, 0o666)
        os.close(fd4)
        print("created", oct(stat.S_IMODE(os.stat(os.path.join(d, "n")).st_mode)))
        os.umask(old)

        # FileIO over a directory descriptor fails with EISDIR and leaves the caller's descriptor open (CPython closes
        # only what it opened itself; test_fileio testOpenDirFD)
        import io
        dfd = os.open(d, os.O_RDONLY)
        opened.append(dfd)
        show("FileIO(dirfd)", lambda: io.FileIO(dfd, "r"))
        show("dirfd still open", lambda: stat.S_ISDIR(os.fstat(dfd).st_mode))

        # terminal size of something that is not a terminal
        show("terminal size", lambda: os.get_terminal_size(fd))
        show("terminal size bad", lambda: os.get_terminal_size(9999))
    finally:
        os.umask(old_umask)
        for n in opened:
            try:
                os.close(n)
            except OSError:
                pass
        for n in os.listdir(d):
            os.unlink(os.path.join(d, n))
        os.rmdir(d)
