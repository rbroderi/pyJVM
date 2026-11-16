# SPDX-License-Identifier: GPL-3.0-or-later
import os

# Overlapped.ConnectNamedPipe and _overlapped.ConnectPipe (Windows only).
print(os.name)

if os.name == "nt":
    import _overlapped as o
    import _winapi as w

    pid = os.getpid()

    def server(tag):
        name = r"\\.\pipe\pyjvm-conf-connect-%d-%s" % (pid, tag)
        return name, w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 2, 4096, 4096, 0, w.NULL)

    # a connect that is pending until a client arrives
    name, srv = server("a")
    ov = o.Overlapped(o.NULL)
    print(ov.ConnectNamedPipe(srv), ov.pending)
    try:
        ov.getresult()
    except OSError as e:
        print(type(e).__name__, e.winerror)
    cli = o.ConnectPipe(name)
    print(isinstance(cli, int) and cli != 0)
    print(ov.getresult(True), ov.pending, ov.error)

    # a second instance of the same pipe name
    srv2 = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 2, 4096, 4096, 0, w.NULL)
    ov2 = o.Overlapped(o.NULL)
    print(ov2.ConnectNamedPipe(srv2))
    cli2 = o.ConnectPipe(name)
    print(ov2.getresult(True))
    # both instances are taken: no more clients
    try:
        o.ConnectPipe(name)
    except OSError as e:
        print(type(e).__name__, e.winerror, e.winerror == o.ERROR_PIPE_BUSY)

    # a client that is already connected when ConnectNamedPipe starts
    name3, srv3 = server("b")
    cli3 = o.ConnectPipe(name3)
    ov3 = o.Overlapped(o.NULL)
    print(ov3.ConnectNamedPipe(srv3), ov3.pending)
    print(ov3.getresult(), ov3.pending, ov3.error)
    try:
        ov3.ConnectNamedPipe(srv3)
    except ValueError as e:
        print("ValueError", e)

    # the pipe is usable once connected
    wr = o.Overlapped(o.NULL)
    wr.WriteFile(srv3, b"ping")
    wr.getresult(True)
    rd = o.Overlapped(o.NULL)
    rd.ReadFile(cli3, 8)
    print(rd.getresult(True))

    # a missing pipe, and a name that is not a pipe at all
    for target in (r"\\.\pipe\pyjvm-conf-connect-missing-%d" % pid, r"C:\no\such\dir\pipe"):
        try:
            o.ConnectPipe(target)
        except OSError as e:
            print(type(e).__name__, e.winerror)

    # ConnectNamedPipe on something that is not a server pipe
    ov4 = o.Overlapped(o.NULL)
    try:
        ov4.ConnectNamedPipe(cli3)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    try:
        ov4.getresult()
    except ValueError as e:
        print("ValueError", e)
    ov5 = o.Overlapped(o.NULL)
    try:
        ov5.ConnectNamedPipe(0)
    except OSError as e:
        print(type(e).__name__, e.winerror)

    for h in (cli3, srv3, cli2, cli, srv2, srv):
        w.CloseHandle(h)
