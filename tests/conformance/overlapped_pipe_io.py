# SPDX-License-Identifier: GPL-3.0-or-later
import os

# Overlapped.ReadFile / ReadFileInto / WriteFile on named pipes (Windows only).
print(os.name)

if os.name == "nt":
    import itertools
    import _overlapped as o
    import _winapi as w

    pid = os.getpid()
    counter = itertools.count()

    def pair(message=False):
        name = r"\\.\pipe\pyjvm-conf-pipeio-%d-%d" % (pid, next(counter))
        mode = (w.PIPE_TYPE_MESSAGE | w.PIPE_READMODE_MESSAGE if message else 0) | w.PIPE_WAIT
        srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, mode, 1, 4096, 4096, 0, w.NULL)
        ov = o.Overlapped(o.NULL)
        ov.ConnectNamedPipe(srv)
        cli = o.ConnectPipe(name)
        print("connect", ov.getresult(True))
        return srv, cli

    def waited(h):
        return w.WaitForSingleObject(h, 5000) == w.WAIT_OBJECT_0

    # a read that is pending until the other end writes
    srv, cli = pair()
    rd = o.Overlapped(o.NULL)
    print(rd.ReadFile(srv, 16), rd.pending)
    try:
        rd.getresult()
    except OSError as e:
        print(type(e).__name__, e.winerror)
    wr = o.Overlapped(o.NULL)
    print(wr.WriteFile(cli, b"hello"))
    print(wr.getresult(True), wr.pending, wr.error)
    print(rd.getresult(True), rd.pending, rd.error)
    print(rd.getresult(True))
    try:
        rd.ReadFile(srv, 4)
    except ValueError as e:
        print("ValueError", e)
    try:
        wr.WriteFile(cli, b"x")
    except ValueError as e:
        print("ValueError", e)

    # the default event is signalled on completion
    rd = o.Overlapped()
    print(w.WaitForSingleObject(rd.event, 0) == w.WAIT_TIMEOUT)
    rd.ReadFile(srv, 4)
    wr = o.Overlapped()
    wr.WriteFile(cli, b"abcdefgh")
    print(waited(wr.event), waited(rd.event))
    print(rd.getresult(), rd.getresult(True))

    # ReadFileInto fills the caller's buffer (visible after getresult)
    buf = bytearray(8)
    rd = o.Overlapped(o.NULL)
    print(rd.ReadFileInto(srv, buf))
    wr = o.Overlapped(o.NULL)
    wr.WriteFile(cli, b"xyz")
    print(rd.getresult(True), bytes(buf))
    mv = memoryview(bytearray(6))
    rd = o.Overlapped(o.NULL)
    rd.ReadFileInto(srv, mv)
    wr = o.Overlapped(o.NULL)
    wr.WriteFile(cli, bytearray(b"uvw"))
    print(rd.getresult(True), bytes(mv))

    # write counts, large and from a memoryview
    wr = o.Overlapped(o.NULL)
    wr.WriteFile(cli, memoryview(b"0123456789" * 100))
    print(wr.getresult(True))
    rd = o.Overlapped(o.NULL)
    rd.ReadFile(srv, 4096)
    print(len(rd.getresult(True)))

    # the peer closes while a read is pending: the read completes with no data and error 109
    rd = o.Overlapped(o.NULL)
    rd.ReadFile(srv, 4)
    w.CloseHandle(cli)
    print(rd.getresult(True), rd.error)
    # and a read that starts after the close fails at once
    rd = o.Overlapped(o.NULL)
    try:
        rd.ReadFile(srv, 4)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    print(rd.error)
    w.CloseHandle(srv)

    # message mode: a short buffer gets ERROR_MORE_DATA and the rest comes with the next read
    srv, cli = pair(message=True)
    wr = o.Overlapped(o.NULL)
    wr.WriteFile(cli, b"0123456789")
    wr.getresult(True)
    rd = o.Overlapped(o.NULL)
    print(rd.ReadFile(srv, 4))
    print(rd.getresult(True), rd.error)
    rd = o.Overlapped(o.NULL)
    print(rd.ReadFile(srv, 16))
    print(rd.getresult(True), rd.error)
    w.CloseHandle(cli)
    w.CloseHandle(srv)

    # type errors
    srv, cli = pair()
    for f, args in ((o.Overlapped(o.NULL).WriteFile, (cli, "text")), (o.Overlapped(o.NULL).ReadFileInto, (srv, 5)),
                    (o.Overlapped(o.NULL).ReadFile, (srv, "4")), (o.Overlapped(o.NULL).ReadFile, (srv, -1))):
        try:
            f(*args)
        except Exception as e:
            print(type(e).__name__, e)
    w.CloseHandle(cli)
    w.CloseHandle(srv)
