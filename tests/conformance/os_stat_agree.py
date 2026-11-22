import os
print(os.name)
if os.name == "posix":
    import warnings
    import stat
    import tempfile

    def show(label, fn):
        try:
            r = fn()
        except BaseException as e:
            if isinstance(e, OSError):
                fn_ = e.filename.replace(d, "<d>") if isinstance(e.filename, str) else e.filename
                print(label, type(e).__name__, e.errno, repr(fn_[:40]) if isinstance(fn_, str) else fn_)
            else:
                print(label, type(e).__name__, e)
        else:
            print(label, r)

    d = tempfile.mkdtemp(prefix="pyjvm_stat_agree_")
    d = os.path.realpath(d)
    old_cwd = os.getcwd()
    opened = []
    try:
        p = os.path.join(d, "f")
        fd = os.open(p, os.O_RDWR | os.O_CREAT, 0o644)
        opened.append(fd)
        os.write(fd, b"hello world")

        # a path and a descriptor of the same file agree on every field
        print("stat == fstat", os.stat(p) == os.fstat(fd))
        print("fields", os.stat(p).st_blocks == os.fstat(fd).st_blocks, os.stat(p).st_blksize == os.fstat(fd).st_blksize,
              os.stat(p).st_rdev == os.fstat(fd).st_rdev, os.stat(p).st_mtime_ns == os.fstat(fd).st_mtime_ns,
              os.stat(p).st_ctime_ns == os.fstat(fd).st_ctime_ns, os.stat(p).st_atime_ns == os.fstat(fd).st_atime_ns)
        print("size", os.stat(p).st_size, stat.S_ISREG(os.stat(p).st_mode))
        print("stat(fd) == fstat(fd)", os.stat(fd) == os.fstat(fd))
        warnings.simplefilter("ignore")
        print("stat(True) is fd 1", os.stat(True) == os.fstat(1))
        print("samefile", os.path.samefile(p, p), os.path.samestat(os.stat(p), os.fstat(fd)))
        show("stat(9999)", lambda: os.stat(9999))
        show("lstat(fd)", lambda: os.lstat(fd))
        show("lstat(3)", lambda: os.lstat(3))
        show("stat(1.5)", lambda: os.stat(1.5))
        show("stat(None)", lambda: os.stat(None))
        show("stat(fd, follow_symlinks=False)", lambda: os.stat(fd, follow_symlinks=False))

        # a sparse file: blocks and blksize come from the kernel for both
        os.ftruncate(fd, 1 << 20)
        a, b = os.stat(p), os.fstat(fd)
        print("sparse", a.st_size, a.st_blocks == b.st_blocks, a.st_blksize == b.st_blksize, a == b)
        print("sparse blocks", a.st_blocks < 2048)

        # missing path, dangling and looping symlinks, non-directory in the path
        missing = os.path.join(d, "missing")
        show("stat missing", lambda: os.stat(missing) and None)
        dangling = os.path.join(d, "dangling")
        os.symlink("nowhere", dangling)
        show("stat dangling", lambda: os.stat(dangling))
        ls = os.lstat(dangling)
        print("lstat dangling", stat.S_ISLNK(ls.st_mode), ls.st_size)
        print("stat nofollow", stat.S_ISLNK(os.stat(dangling, follow_symlinks=False).st_mode))
        link = os.path.join(d, "link")
        os.symlink(p, link)
        print("stat link", stat.S_ISREG(os.stat(link).st_mode), stat.S_ISLNK(os.lstat(link).st_mode), os.stat(link) == os.stat(p))
        show("stat through file", lambda: os.stat(os.path.join(p, "x")))
        show("stat empty", lambda: os.stat(""))
        show("lstat empty", lambda: os.lstat(""))
        show("stat long name", lambda: os.stat(os.path.join(d, "x" * 300)))
        loop = os.path.join(d, "loop")
        os.symlink("loop", loop)
        show("stat loop", lambda: os.stat(loop))
        print("lstat loop", stat.S_ISLNK(os.lstat(loop).st_mode))
        show("stat NUL", lambda: os.stat("a\0b"))
        show("lstat NUL", lambda: os.lstat("a\0b"))

        # devices
        dn = os.stat("/dev/null")
        print("dev null", stat.S_ISCHR(dn.st_mode), dn.st_rdev == (1 << 8 | 3))

        # a relative path resolves against the emulated working directory
        os.chdir(d)
        print("relative", os.stat("f") == os.stat(p), os.lstat("link") == os.lstat(link), os.stat("./f").st_size)
        show("relative missing", lambda: os.stat("missing"))
        show("relative through file", lambda: os.stat("f/x"))
        os.mkdir("sub")
        print("dotdot", os.stat("sub/..") == os.stat(d))
        os.chdir(old_cwd)

        # bytes and PathLike paths
        print("bytes", os.stat(os.fsencode(p)).st_size, os.lstat(os.fsencode(p)).st_size)

        class P:
            def __fspath__(self):
                return p
        print("pathlike", os.stat(P()).st_size, os.lstat(P()).st_size)

        # DirEntry
        names = sorted(e.name for e in os.scandir(d))
        print("entries", names)
        for e in sorted(os.scandir(d), key=lambda e: e.name):
            if e.name == "dangling":
                print("de dangling", e.inode() == os.lstat(e.path).st_ino, e.stat(follow_symlinks=False) == os.lstat(e.path))
                show("de dangling stat", lambda: e.stat())
            elif e.name == "loop":
                show("de loop stat", lambda: e.stat())
                print("de loop lstat", e.stat(follow_symlinks=False) == os.lstat(e.path), e.inode() == os.lstat(e.path).st_ino)
            else:
                print("de", e.name, e.inode() == os.lstat(e.path).st_ino, e.stat() == os.stat(e.path),
                      e.stat(follow_symlinks=False) == os.lstat(e.path))
    finally:
        os.chdir(old_cwd)
        for x in opened:
            os.close(x)
        for n in os.listdir(d):
            q = os.path.join(d, n)
            if os.path.isdir(q) and not os.path.islink(q):
                os.rmdir(q)
            else:
                os.unlink(q)
        os.rmdir(d)
