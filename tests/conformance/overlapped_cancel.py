# SPDX-License-Identifier: GPL-3.0-or-later
import os

# Overlapped.cancel and dropping a pending Overlapped (Windows only).
print(os.name)

if os.name == "nt":
    import gc
    import _overlapped as o
    import _winapi as w

    pid = os.getpid()
    port = o.CreateIoCompletionPort(o.INVALID_HANDLE_VALUE, o.NULL, 0, 0)

    name = r"\\.\pipe\pyjvm-conf-cancel-%d" % pid
    srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 1, 4096, 4096, 0, w.NULL)
    o.CreateIoCompletionPort(srv, port, 0, 0)
    cli = o.ConnectPipe(name)
    conn = o.Overlapped(o.NULL)
    print(conn.ConnectNamedPipe(srv))

    # cancelling an object that never started, and one that failed to start
    fresh = o.Overlapped(o.NULL)
    print(fresh.cancel(), fresh.pending)
    bad = o.Overlapped(o.NULL)
    try:
        bad.ReadFile(0, 4)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    print(bad.cancel(), bad.pending)

    # a pending read that is cancelled
    rd = o.Overlapped(o.NULL)
    print(rd.ReadFile(srv, 16), rd.pending)
    print(rd.cancel())
    st = o.GetQueuedCompletionStatus(port, 5000)
    print(st[0], st[1], st[3] == rd.address)
    print(rd.pending)
    try:
        rd.getresult()
    except OSError as e:
        print(type(e).__name__, e.winerror)
    print(rd.error)
    print(rd.cancel())              # already finished

    # a pending write-less read that completes before cancel is called
    rd = o.Overlapped(o.NULL)
    rd.ReadFile(srv, 16)
    wr = o.Overlapped(o.NULL)
    wr.WriteFile(cli, b"data")
    st = o.GetQueuedCompletionStatus(port, 5000)
    print(st[0], st[1], st[3] == rd.address)
    print(rd.cancel(), rd.pending)
    print(rd.getresult())
    wr.getresult(True)

    # cancelling twice is harmless
    rd = o.Overlapped(o.NULL)
    rd.ReadFile(srv, 16)
    rd.cancel()
    rd.cancel()
    st = o.GetQueuedCompletionStatus(port, 5000)
    print(st[0], st[3] == rd.address)

    # a pending Overlapped that is dropped without cancel, on a pipe that is not registered with the port (CPython
    # waits for the cancelled operation when the object dies, which never ends for a registered handle)
    name2 = name + "-b"
    srv2 = w.CreateNamedPipe(name2, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 1, 4096, 4096, 0, w.NULL)
    cli2 = o.ConnectPipe(name2)
    c2 = o.Overlapped(o.NULL)
    c2.ConnectNamedPipe(srv2)
    dropped = o.Overlapped(o.NULL)
    dropped.ReadFile(srv2, 16)
    print(dropped.pending)
    dropped = None
    for _ in range(3):
        gc.collect()
    w.CloseHandle(cli2)
    w.CloseHandle(srv2)
    print("dropped")

    # the pipe still works afterwards
    rd = o.Overlapped(o.NULL)
    rd.ReadFile(srv, 16)
    wr = o.Overlapped(o.NULL)
    wr.WriteFile(cli, b"after")
    o.GetQueuedCompletionStatus(port, 5000)
    print(rd.getresult(), wr.getresult(True))

    w.CloseHandle(cli)
    w.CloseHandle(srv)
    w.CloseHandle(port)
