# SPDX-License-Identifier: GPL-3.0-or-later
import os

# _overlapped.RegisterWaitWithQueue / UnregisterWait / UnregisterWaitEx (Windows only).
print(os.name)

if os.name == "nt":
    import _overlapped as o
    import _winapi as w

    port = o.CreateIoCompletionPort(o.INVALID_HANDLE_VALUE, o.NULL, 0, 0)

    def unregister(f, *args):
        try:
            f(*args)
            return "ok"
        except OSError as e:
            return "pending" if e.winerror == o.ERROR_IO_PENDING else e.winerror

    # a wait that times out: the packet carries 1
    ev = o.CreateEvent(None, True, False, None)
    ov = o.Overlapped(o.NULL)
    h = o.RegisterWaitWithQueue(ev, port, ov.address, 50)
    print(isinstance(h, int) and h != 0)
    st = o.GetQueuedCompletionStatus(port, 5000)
    print(st[0], st[1], st[2], st[3] == ov.address)
    print(unregister(o.UnregisterWait, h) in ("ok", "pending"))
    print(o.GetQueuedCompletionStatus(port, 50))

    # an event that is already signalled: the packet carries 0
    o.SetEvent(ev)
    ov = o.Overlapped(o.NULL)
    h = o.RegisterWaitWithQueue(ev, port, ov.address, o.INFINITE)
    st = o.GetQueuedCompletionStatus(port, 5000)
    print(st[0], st[1], st[2], st[3] == ov.address)
    print(unregister(o.UnregisterWait, h) in ("ok", "pending"))
    o.ResetEvent(ev)

    # an event that is signalled after the registration
    ov = o.Overlapped(o.NULL)
    h = o.RegisterWaitWithQueue(ev, port, ov.address, o.INFINITE)
    print(o.GetQueuedCompletionStatus(port, 100))
    o.SetEvent(ev)
    st = o.GetQueuedCompletionStatus(port, 5000)
    print(st[0], st[1], st[2], st[3] == ov.address)
    print(unregister(o.UnregisterWait, h) in ("ok", "pending"))
    o.ResetEvent(ev)

    # a wait that is unregistered before it fires never posts
    ov = o.Overlapped(o.NULL)
    h = o.RegisterWaitWithQueue(ev, port, ov.address, o.INFINITE)
    print(unregister(o.UnregisterWait, h))
    o.SetEvent(ev)
    print(o.GetQueuedCompletionStatus(port, 200))
    o.ResetEvent(ev)

    # UnregisterWaitEx with an event of its own and with INVALID_HANDLE_VALUE (waits for callbacks)
    done = o.CreateEvent(None, True, False, None)
    ov = o.Overlapped(o.NULL)
    h = o.RegisterWaitWithQueue(ev, port, ov.address, o.INFINITE)
    print(unregister(o.UnregisterWaitEx, h, done) in ("ok", "pending"))
    print(o.GetQueuedCompletionStatus(port, 50))
    ov = o.Overlapped(o.NULL)
    h = o.RegisterWaitWithQueue(ev, port, ov.address, 20)
    st = o.GetQueuedCompletionStatus(port, 5000)
    print(st[1], unregister(o.UnregisterWaitEx, h, o.INVALID_HANDLE_VALUE))

    # several waits at once, each with its own address
    evs = [o.CreateEvent(None, True, False, None) for _ in range(3)]
    ovs = [o.Overlapped(o.NULL) for _ in range(3)]
    hs = [o.RegisterWaitWithQueue(e, port, x.address, o.INFINITE) for e, x in zip(evs, ovs)]
    for e in reversed(evs):
        o.SetEvent(e)
    got = sorted(o.GetQueuedCompletionStatus(port, 5000)[3] for _ in evs)
    print(got == sorted(x.address for x in ovs))
    print([unregister(o.UnregisterWait, x) in ("ok", "pending") for x in hs])
    print(o.GetQueuedCompletionStatus(port, 50))

    # errors
    for f, args in ((o.UnregisterWait, (0,)), (o.UnregisterWaitEx, (0, 0)), (o.RegisterWaitWithQueue, (0, 0, 0, 0)),
                    (o.RegisterWaitWithQueue, (ev, port, ov.address)), (o.UnregisterWait, ())):
        try:
            f(*args)
            print("no error")
        except OSError as e:
            print(type(e).__name__, e.winerror)
        except TypeError as e:
            print("TypeError", e)

    for h in evs + [ev, done, port]:
        w.CloseHandle(h)
