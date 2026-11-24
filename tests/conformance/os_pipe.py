import errno
import io
import os
import stat

# os.pipe / os.dup2 and the descriptor layer around them. The Windows branch also covers the msvcrt / _winapi handle
# bridge; the POSIX branch needs kernel descriptors (PYJVM_POSIX_FDS=nio has no pipe and fails by design).
print(os.name, hasattr(os, "dup"))

if os.name == "nt":
    import _winapi
    import msvcrt

    r, w = os.pipe()
    print(type(r).__name__, type(w).__name__, r != w, r > 2, w > 2, os.get_inheritable(r))
    print(os.write(w, b"hello"), os.read(r, 100))
    os.write(w, b"abc")
    os.write(w, b"def")
    print(os.read(r, 4), os.read(r, 100))

    # fstat of a pipe end
    st = os.fstat(r)
    print(stat.S_ISFIFO(st.st_mode), st.st_size, os.isatty(r), os.isatty(w))

    # a pipe stays open until every descriptor for its write end is closed
    w2 = os.dup(w)
    os.close(w)
    os.write(w2, b"via dup")
    print(os.read(r, 100))
    os.close(w2)
    print(os.read(r, 100))            # end of stream: the last writer closed

    # dup2 makes the second descriptor refer to the first one's pipe
    r2, w2 = os.pipe()
    r3, w3 = os.pipe()
    print(os.dup2(w3, w2) == w2)
    os.write(w2, b"redirected")
    print(os.read(r3, 100))
    os.close(w2); os.close(w3)
    print(os.read(r3, 100))
    os.close(r2); os.close(r3)
    print(os.dup2(r, r) == r)
    try:
        os.dup2(9999, 5)
    except OSError as e:
        print("dup2", e.errno == errno.EBADF)

    # io.open on pipe descriptors, text and binary
    r4, w4 = os.pipe()
    with io.open(w4, "w", encoding="ascii", newline="\n") as out:
        out.write("line one\nline two\n")
    with io.open(r4, "r", encoding="ascii") as inp:
        print(inp.read().splitlines())
    r4, w4 = os.pipe()
    fw = io.open(w4, "wb", 0)
    fr = io.open(r4, "rb", 0)
    print(fw.writable(), fr.readable(), fw.fileno() == w4)
    fw.write(b"xyz")
    print(fr.read(3))
    fw.close()
    print(fr.read(), fr.readinto(bytearray(4)))
    fr.close()

    # a closed descriptor is gone
    for fn in (os.close, os.dup):
        try:
            fn(r4)
        except OSError as e:
            print(fn.__name__, e.errno == errno.EBADF)
    try:
        os.read(r4, 1)
    except OSError as e:
        print("read", e.errno == errno.EBADF)

    # writing to a pipe whose read end is gone
    r5, w5 = os.pipe()
    os.close(r5)
    try:
        os.write(w5, b"x")
    except OSError as e:
        print("write", type(e).__name__, e.errno)
    os.close(w5)

    # descriptor <-> HANDLE bridge
    hr, hw = _winapi.CreatePipe(None, 0)
    print(type(hr).__name__, hr != hw, _winapi.GetFileType(hr) == _winapi.FILE_TYPE_PIPE)
    fdw = msvcrt.open_osfhandle(hw, 0)
    fdr = msvcrt.open_osfhandle(hr, 0)
    print(msvcrt.get_osfhandle(fdw) == hw, msvcrt.get_osfhandle(fdr) == hr)
    os.write(fdw, b"over handles")
    os.close(fdw)
    print(os.read(fdr, 100), os.read(fdr, 100))
    os.close(fdr)
    try:
        msvcrt.get_osfhandle(fdr)
    except OSError as e:
        print("get_osfhandle", e.errno == errno.EBADF)
    try:
        msvcrt.open_osfhandle(_winapi.INVALID_HANDLE_VALUE, 0)
    except OSError as e:
        print("open_osfhandle", e.errno == errno.EBADF)

    # DuplicateHandle with inheritance, then both copies close independently
    hr, hw = _winapi.CreatePipe(None, 0)
    cur = _winapi.GetCurrentProcess()
    hw2 = _winapi.DuplicateHandle(cur, hw, cur, 0, 1, _winapi.DUPLICATE_SAME_ACCESS)
    print(hw2 != hw, type(hw2).__name__)
    _winapi.CloseHandle(hw)
    _winapi.CloseHandle(hw2)
    _winapi.CloseHandle(hr)
    try:
        _winapi.CloseHandle(hr)
    except OSError as e:
        print("CloseHandle", e.winerror, e.errno)

    # standard streams
    print([msvcrt.get_osfhandle(i) == _winapi.GetStdHandle(s) for i, s in
           ((0, _winapi.STD_INPUT_HANDLE), (1, _winapi.STD_OUTPUT_HANDLE), (2, _winapi.STD_ERROR_HANDLE))
           if _winapi.GetStdHandle(s) is not None])

    # a disk file's descriptor has a handle too
    path = os.path.join(os.environ["TEMP"], "pyjvm_os_pipe_%d.txt" % os.getpid())
    try:
        with open(path, "wb") as f:
            f.write(b"0123456789")
            f.flush()
            h = msvcrt.get_osfhandle(f.fileno())
            print(_winapi.GetFileType(h) == _winapi.FILE_TYPE_DISK, msvcrt.get_osfhandle(f.fileno()) == h)
    finally:
        os.remove(path)

    print(msvcrt.setmode(r, os.O_BINARY) == os.O_BINARY, msvcrt.setmode(r, os.O_BINARY) == os.O_BINARY)
    os.close(r)
    print(hasattr(msvcrt, "open_osfhandle"), hasattr(_winapi, "CreateProcess"))

elif os.name == "posix":
    import tempfile
    import threading

    def attempt(label, fn, *args, **kwargs):
        try:
            print(label, fn(*args, **kwargs))
        except OSError as e:
            print(label, type(e).__name__, e.errno)
        except (TypeError, ValueError, OverflowError) as e:
            print(label, type(e).__name__, e)

    def flags(fd):
        return os.get_inheritable(fd), os.get_blocking(fd)

    r, w = os.pipe()
    print(type(r).__name__, type(w).__name__, r != w, r > 2, w > 2, flags(r), flags(w))
    print(os.write(w, b"hello"), os.read(r, 100))
    os.write(w, b"abc")
    os.write(w, b"def")
    print(os.read(r, 4), os.read(r, 100))

    # fstat of a pipe end
    st = os.fstat(r)
    print(stat.S_ISFIFO(st.st_mode), st.st_size, os.isatty(r), os.isatty(w))

    # a pipe stays open until every descriptor for its write end is closed
    w2 = os.dup(w)
    os.close(w)
    os.write(w2, b"via dup")
    print(os.read(r, 100))
    os.close(w2)
    print(os.read(r, 100))            # end of stream: the last writer closed

    # dup2 makes the second descriptor refer to the first one's pipe
    r2, w2 = os.pipe()
    r3, w3 = os.pipe()
    print(os.dup2(w3, w2) == w2, flags(w2))
    os.write(w2, b"redirected")
    print(os.read(r3, 100))
    os.close(w2); os.close(w3)
    print(os.read(r3, 100))
    os.close(r2); os.close(r3)
    print(os.dup2(r, r) == r)
    for args, kwargs in (((9999, 5), {}), ((r, r), {"inheritable": False}), ((r, -1), {}), ((-1, 5), {})):
        try:
            os.dup2(*args, **kwargs)
        except OSError as e:
            print("dup2", errno.errorcode[e.errno])
    attempt("dup2", os.dup2, 1)
    attempt("dup2", os.dup2, r, fd=r)
    attempt("dup2", os.dup2, r, r, True, 1)
    attempt("dup2", os.dup2, r, r, bogus=1)
    attempt("dup2", os.dup2, r, r, True, inheritable=1)            # keywords count towards "at most 3"
    attempt("dup2", os.dup2, fd=r, fd2=r, inheritable=1, bogus=1)  # "keyword arguments" when none is positional
    attempt("dup2", os.dup2, r, 2 ** 40)

    # inheritable flags: pipe, dup, dup2 (inheritable by default), pipe2
    a, b = os.pipe()
    c = os.dup(a)
    print(flags(a), flags(c))
    print(os.dup2(a, c) == c, flags(c))
    print(os.dup2(a, c, inheritable=False) == c, flags(c))
    print(os.dup2(fd=a, fd2=c, inheritable=True) == c, flags(c))
    print(os.dup2(a, c, False) == c, flags(c))
    for fd in (a, b, c):
        os.close(fd)
    a, b = os.pipe2(0)
    print(flags(a), flags(b))
    os.close(a); os.close(b)
    a, b = os.pipe2(os.O_NONBLOCK | os.O_CLOEXEC)
    print(flags(a), flags(b))
    attempt("read", os.read, a, 1)
    os.close(a); os.close(b)
    attempt("pipe2", os.pipe2, 12345678)
    attempt("pipe2", os.pipe2)
    attempt("pipe2", os.pipe2, 1, 2)
    attempt("pipe2", os.pipe2, "x")
    attempt("pipe", os.pipe, 1)

    # a non-blocking read end with nothing to read
    os.close(r)
    r, w = os.pipe()
    os.set_blocking(r, False)
    try:
        os.read(r, 1)
    except OSError as e:
        print("nonblocking", type(e).__name__, e.errno == errno.EAGAIN, e.strerror)
    os.set_blocking(r, True)
    os.close(r)
    os.close(w)

    # io.open on pipe descriptors, text and binary
    r4, w4 = os.pipe()
    with io.open(w4, "w", encoding="ascii", newline="\n") as out:
        out.write("line one\nline two\n")
    with io.open(r4, "r", encoding="ascii") as inp:
        print(inp.read().splitlines())
    r4, w4 = os.pipe()
    fw = io.open(w4, "wb", 0)
    fr = io.open(r4, "rb", 0)
    print(fw.writable(), fr.readable(), fw.fileno() == w4)
    fw.write(b"xyz")
    print(fr.read(3))
    fw.close()
    print(fr.read(), fr.readinto(bytearray(4)))
    fr.close()

    # a closed descriptor is gone
    for fn in (os.close, os.dup):
        try:
            fn(r4)
        except OSError as e:
            print(fn.__name__, e.errno == errno.EBADF)
    try:
        os.read(r4, 1)
    except OSError as e:
        print("read", e.errno == errno.EBADF)

    # writing to a pipe whose read end is gone
    r5, w5 = os.pipe()
    os.close(r5)
    try:
        os.write(w5, b"x")
    except OSError as e:
        print("write", type(e).__name__, e.errno)
    os.close(w5)

    # pread / pwrite do not move the offset
    tmp = tempfile.mkdtemp()
    path = os.path.join(tmp, "f")
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_TRUNC, 0o600)
    os.write(fd, b"0123456789")
    print(os.pwrite(fd, b"hello", 3), os.pread(fd, 4, 2), os.lseek(fd, 0, 1))
    print(os.pread(fd, 100, 0), os.pread(fd, 4, 100), os.pread(fd, 0, 0), os.pwrite(fd, b"", 0))
    os.lseek(fd, 2, 0)
    print(os.pwrite(fd, bytearray(b"AB"), 0), os.pwrite(fd, memoryview(b"CD"), 8), os.lseek(fd, 0, 1), os.pread(fd, 100, 0))
    attempt("pread", os.pread, fd, -1, 0)
    attempt("pread", os.pread, fd, 4, -1)
    attempt("pread", os.pread, fd, 4, 2 ** 63)
    attempt("pread", os.pread, fd, 4)
    attempt("pread", os.pread, fd, 4, offset=0)
    attempt("pread", os.pread, 9999, 4, 0)
    attempt("pwrite", os.pwrite, fd, "x", 0)
    attempt("pwrite", os.pwrite, fd, b"x", "0")
    attempt("pwrite", os.pwrite, 9999, b"x", 0)
    r6, w6 = os.pipe()
    attempt("pread", os.pread, r6, 4, 0)
    attempt("pwrite", os.pwrite, w6, b"x", 0)
    os.close(r6); os.close(w6)
    os.close(fd)
    os.remove(path)
    os.rmdir(tmp)

    # closerange closes only what is open inside [low, high), ignores the rest and bad ranges
    r7, w7 = os.pipe()
    base = 900
    for n in range(base, base + 4):
        try:
            os.fstat(n)
        except OSError:
            continue
        raise SystemExit("descriptor %d is already open" % n)
    mine = [os.dup2(r7, base + i) for i in range(4)]
    print(mine == [base, base + 1, base + 2, base + 3])
    os.closerange(base + 2, base)                       # an empty range
    os.closerange(-5, -3)
    print([os.get_inheritable(n) for n in mine])
    os.closerange(base, base + 2)
    gone = []
    for n in mine:
        try:
            os.fstat(n)
            gone.append(False)
        except OSError as e:
            gone.append(e.errno == errno.EBADF)
    print(gone)
    os.closerange(base - 1, base + 100)                 # the open ones and many that are not
    for n in mine:
        try:
            os.fstat(n)
        except OSError as e:
            print("closerange", e.errno == errno.EBADF)
    attempt("closerange", os.closerange, 2 ** 40, 3)
    attempt("closerange", os.closerange, 3, 2 ** 40)
    attempt("closerange", os.closerange, 3)
    attempt("closerange", os.closerange, "a", 3)
    print(os.fstat(r7).st_size, os.fstat(w7).st_size)    # the pipe itself is untouched
    os.close(r7); os.close(w7)

    # readinto (3.14+)
    if hasattr(os, "readinto"):
        r8, w8 = os.pipe()
        os.write(w8, b"0123456789")
        buf = bytearray(4)
        print(os.readinto(r8, buf), buf)
        mv = memoryview(bytearray(b"-------"))
        print(os.readinto(r8, mv[2:5]), bytes(mv))
        print(os.readinto(r8, bytearray(0)))
        os.close(w8)
        print(os.readinto(r8, bytearray(100)), os.readinto(r8, bytearray(100)))
        attempt("readinto", os.readinto, r8, b"abc")
        attempt("readinto", os.readinto, r8, "abc")
        attempt("readinto", os.readinto, r8, memoryview(bytearray(10))[::2])   # writable but not contiguous
        attempt("readinto", os.readinto, r8)
        attempt("readinto", os.readinto, 9999, bytearray(1))
        attempt("readinto", os.readinto, 9999, b"x")
        os.close(r8)

    # a 200000-byte stream from a thread: short reads and partial writes, through os.* and through io.open
    N = 200000
    payload = bytes((i * 7 + i // 251) % 256 for i in range(N))

    def summary(data):
        return len(data), sum(b * (i % 253 + 1) for i, b in enumerate(data)) % 1000003, data == payload

    def stream(label, produce, consume):
        r, w = os.pipe()
        t = threading.Thread(target=produce, args=(w,))
        t.start()
        data = consume(r)
        t.join()
        print(label, summary(data))

    def produce_os(w):
        off = 0
        while off < N:
            off += os.write(w, payload[off:off + 70000])
        os.close(w)

    def consume_os(r):
        chunks = []
        while True:
            c = os.read(r, 30000)
            if not c:
                break
            chunks.append(c)
        os.close(r)
        return b"".join(chunks)

    def produce_raw(w):
        fw = io.open(w, "wb", 0)
        off = 0
        while off < N:
            off += fw.write(payload[off:off + 100000])
        fw.close()

    def consume_raw(r):
        fr = io.open(r, "rb", 0)
        chunks = []
        while True:
            c = fr.read(50000)
            if not c:
                break
            chunks.append(c)
        fr.close()
        return b"".join(chunks)

    def produce_buffered(w):
        with io.open(w, "wb") as fw:
            fw.write(payload)

    def consume_buffered(r):
        with io.open(r, "rb") as fr:
            return fr.read()

    def consume_read1(r):
        chunks = []
        with io.open(r, "rb", 4096) as fr:
            while True:
                c = fr.read1(10000)
                if not c:
                    break
                chunks.append(c)
        return b"".join(chunks)

    stream("os", produce_os, consume_os)
    stream("raw", produce_raw, consume_raw)
    stream("buffered", produce_buffered, consume_buffered)
    stream("read1", produce_os, consume_read1)
