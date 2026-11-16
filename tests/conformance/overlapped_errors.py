# SPDX-License-Identifier: GPL-3.0-or-later
import os

# Error paths of _overlapped.Overlapped (Windows only).
print(os.name)

if os.name == "nt":
    import _overlapped as o
    import _winapi as w

    def show(f, *a):
        try:
            print("OK", f(*a))
        except OSError as e:
            print(type(e).__name__, e.errno, e.winerror, e.strerror)
        except Exception as e:
            print(type(e).__name__, e)

    # use of an object that never ran an operation
    ov = o.Overlapped(o.NULL)
    show(ov.getresult)
    show(ov.getresult, True)
    print(ov.pending, ov.error)

    # an operation on an invalid handle fails to start; the object then reports that
    show(ov.ReadFile, 0, 4)
    show(ov.getresult)
    show(ov.ReadFile, 0, 4)
    print(ov.pending, ov.error)

    for start in (lambda x: x.ReadFile(0, 4), lambda x: x.ReadFileInto(0, bytearray(4)), lambda x: x.WriteFile(0, b"abc"),
                  lambda x: x.ConnectNamedPipe(0)):
        x = o.Overlapped(o.NULL)
        show(start, x)
        show(x.getresult)
        print(x.error, x.pending)

    # a second operation on a started object
    ev = o.CreateEvent(None, True, False, None)
    name = r"\\.\pipe\pyjvm-conf-errors-%d" % os.getpid()
    srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 1, 4096, 4096, 0, w.NULL)
    cli = o.ConnectPipe(name)
    c = o.Overlapped(o.NULL)
    c.ConnectNamedPipe(srv)
    c.getresult(True)
    x = o.Overlapped(o.NULL)
    x.WriteFile(cli, b"zz")
    x.getresult(True)
    for again in (lambda: x.ReadFile(srv, 1), lambda: x.ReadFileInto(srv, bytearray(1)), lambda: x.WriteFile(cli, b"q"),
                  lambda: x.ConnectNamedPipe(srv)):
        show(again)
    print(x.getresult(), x.error)

    # a wrong handle kind: a read on a handle that is not overlapped-capable for that call
    r = o.Overlapped(o.NULL)
    show(r.ReadFile, ev, 4)
    show(r.getresult)

    # a write to a closed pipe
    w.CloseHandle(cli)
    y = o.Overlapped(o.NULL)
    show(y.WriteFile, srv, b"gone")
    show(y.getresult)
    w.CloseHandle(srv)

    # FormatMessage
    print(o.FormatMessage(5))
    print(o.FormatMessage(1234))
    print(o.FormatMessage(0xFFFFFFF0))
    w.CloseHandle(ev)

    # the Windows error to exception class mapping used by the module
    for code in (2, 5, 6, 87, 109, 995, 997, 1225, 1236):
        try:
            raise OSError(None, "", None, code)
        except OSError as e:
            print(code, type(e).__name__)
