# SPDX-License-Identifier: GPL-3.0-or-later
import os

# Overlapped.WSARecv / WSARecvInto / WSASend over a loopback socket pair and an I/O completion port (Windows only).
print(os.name)

if os.name == "nt":
    import socket
    import _overlapped as o
    import _winapi

    print([n for n in ("WSARecv", "WSARecvInto", "WSASend", "ReadFile", "WriteFile") if hasattr(o.Overlapped, n)])

    a, b = socket.socketpair()
    c, d = socket.socketpair()
    port = o.CreateIoCompletionPort(o.INVALID_HANDLE_VALUE, o.NULL, 0, 0)
    keys = {a: 11, b: 22, c: 33, d: 44}
    print([o.CreateIoCompletionPort(s.fileno(), port, keys[s], 0) == port for s in (a, b, c, d)])

    def drain(count):
        """The next `count` completion packets as sorted (error, bytes, key) triples; the packets of overlapped
        operations on a completion port are what finish them (pending stays true until one is dequeued)."""
        out = []
        for _ in range(count):
            st = o.GetQueuedCompletionStatus(port, 5000)
            out.append(None if st is None else st[:3])
        return sorted(out, key=repr)

    # pending receive, then a send from the other end
    rd = o.Overlapped(o.NULL)
    print(rd.WSARecv(a.fileno(), 16), rd.pending)
    try:
        rd.getresult()
    except OSError as e:
        print(type(e).__name__, e.winerror)
    wr = o.Overlapped(o.NULL)
    print(wr.WSASend(b.fileno(), b"hello", 0))
    print(drain(2))
    print(wr.getresult(True), wr.pending, wr.error)
    print(rd.getresult(True), rd.pending, rd.error)

    # one operation per object
    for name, args in (("WSARecv", (a.fileno(), 4)), ("WSASend", (a.fileno(), b"x", 0)), ("WSARecvInto", (a.fileno(), bytearray(2), 0))):
        try:
            getattr(rd, name)(*args)
        except ValueError as e:
            print(name, "ValueError", e)

    # WSARecvInto: the caller's buffer is filled once getresult has run
    buf = bytearray(8)
    rd = o.Overlapped(o.NULL)
    print(rd.WSARecvInto(b.fileno(), buf, 0))
    wr = o.Overlapped(o.NULL)
    wr.WSASend(a.fileno(), b"abc", 0)
    print(drain(2))
    print(wr.getresult(True), rd.getresult(True), bytes(buf[:3]))

    # a receive larger than the data: the result is bytes of the transferred length
    rd = o.Overlapped(o.NULL)
    rd.WSARecv(a.fileno(), 100, 0)
    wr = o.Overlapped(o.NULL)
    wr.WSASend(b.fileno(), b"x" * 10, 0)
    print(drain(2))
    print(rd.getresult(True), wr.getresult(True))

    # data that is already there: the receive completes at once and still queues a packet
    wr = o.Overlapped(o.NULL)
    wr.WSASend(b.fileno(), b"ready", 0)
    print(drain(1))
    import time
    time.sleep(0.2)
    rd = o.Overlapped(o.NULL)
    rd.WSARecv(a.fileno(), 10, 0)
    print(drain(1))
    print(rd.getresult(True), wr.getresult(True))

    # argument errors
    try:
        o.Overlapped(o.NULL).WSARecv(a.fileno())
    except TypeError as e:
        print("TypeError", e)
    try:
        o.Overlapped(o.NULL).WSARecvInto(a.fileno(), bytearray(2))
    except TypeError as e:
        print("TypeError", e)
    try:
        o.Overlapped(o.NULL).WSASend(a.fileno(), "text", 0)
    except TypeError as e:
        print("TypeError", e)

    # peer close: the receive completes with 0 bytes
    rd = o.Overlapped(o.NULL)
    rd.WSARecv(a.fileno(), 10, 0)
    b.close()
    print(drain(1))
    print(rd.getresult(True))

    # cancel a pending receive
    rd = o.Overlapped(o.NULL)
    rd.WSARecv(c.fileno(), 10, 0)
    rd.cancel()
    print(drain(1))
    try:
        rd.getresult(True)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    print(rd.pending)

    # an invalid socket handle: the start fails and the object is unusable
    bad = o.Overlapped(o.NULL)
    try:
        bad.WSARecv(0x7FFF0, 4, 0)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    try:
        bad.getresult()
    except ValueError as e:
        print("ValueError", e)

    for s in (a, c, d):
        s.close()
    _winapi.CloseHandle(port)
