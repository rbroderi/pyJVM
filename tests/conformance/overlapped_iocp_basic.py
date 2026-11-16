# SPDX-License-Identifier: GPL-3.0-or-later
import os

# I/O completion ports through _overlapped: create, post, dequeue (Windows only).
print(os.name)

if os.name == "nt":
    import _overlapped as o
    import _winapi

    NULL = 0
    port = o.CreateIoCompletionPort(o.INVALID_HANDLE_VALUE, NULL, 0, 0)
    print(isinstance(port, int) and port != 0)
    print(o.GetQueuedCompletionStatus(port, 0))
    print(o.GetQueuedCompletionStatus(port, 20))

    ov = o.Overlapped(NULL)
    print(o.PostQueuedCompletionStatus(port, 7, 99, ov.address))
    st = o.GetQueuedCompletionStatus(port, 1000)
    print(st[0], st[1], st[2], st[3] == ov.address, len(st))
    print(o.GetQueuedCompletionStatus(port, 0))

    # packets come out in the order they were posted
    ovs = [o.Overlapped(NULL) for _ in range(4)]
    for i, x in enumerate(ovs):
        o.PostQueuedCompletionStatus(port, i, 10 + i, x.address)
    got = [o.GetQueuedCompletionStatus(port, 1000) for _ in ovs]
    print([(g[0], g[1], g[2], g[3] == x.address) for g, x in zip(got, ovs)])

    # a large key and the byte count are passed through unchanged
    o.PostQueuedCompletionStatus(port, 0xFFFFFFFF, 2**64 - 1, ov.address)
    st = o.GetQueuedCompletionStatus(port, 1000)
    print(st[1], st[2], st[3] == ov.address)

    # a port cannot be created over an existing port
    try:
        o.CreateIoCompletionPort(o.INVALID_HANDLE_VALUE, port, 0, 0)
    except OSError as e:
        print(type(e).__name__, e.winerror)

    # a pending operation completes into the port: an overlapped pipe read
    name = r"\\.\pipe\pyjvm-conf-iocp-%d" % os.getpid()
    srv = _winapi.CreateNamedPipe(name, _winapi.PIPE_ACCESS_DUPLEX | _winapi.FILE_FLAG_OVERLAPPED, _winapi.PIPE_WAIT, 1, 4096, 4096, 0, _winapi.NULL)
    cli = _winapi.CreateFile(name, _winapi.GENERIC_READ | _winapi.GENERIC_WRITE, 0, _winapi.NULL, _winapi.OPEN_EXISTING, _winapi.FILE_FLAG_OVERLAPPED, _winapi.NULL)
    print(o.CreateIoCompletionPort(srv, port, 5, 0) == port)
    rd = o.Overlapped(NULL)
    print(rd.ReadFile(srv, 16))
    print(rd.pending)
    wr = o.Overlapped(NULL)
    wr.WriteFile(cli, b"hello")
    st = o.GetQueuedCompletionStatus(port, 2000)      # only the server end is registered with the port
    print(st[:3], st[3] == rd.address)
    print(rd.pending, rd.getresult(), wr.getresult(True))
    print(o.GetQueuedCompletionStatus(port, 0))
    _winapi.CloseHandle(cli)
    _winapi.CloseHandle(srv)

    # a closed or invalid port
    try:
        o.GetQueuedCompletionStatus(port, 0)
        _winapi.CloseHandle(port)
        o.GetQueuedCompletionStatus(port, 0)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    try:
        o.GetQueuedCompletionStatus(0, 0)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    try:
        o.PostQueuedCompletionStatus(0, 0, 0, 0)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    try:
        o.CreateIoCompletionPort(0, 0, 0, 0)
    except OSError as e:
        print(type(e).__name__, e.winerror)
