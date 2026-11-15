import errno
import io
import os
import stat

# os.pipe / os.dup2 / msvcrt / _winapi handle bridge. Windows only: the POSIX runtime has no pipe/dup2 yet, so on
# POSIX both interpreters print just the first line.
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
