# SPDX-License-Identifier: GPL-3.0-or-later
import os

# _winapi named pipes, CreateFile, events and the Overlapped object of ConnectNamedPipe(overlapped=True) (Windows only).
print(os.name)

if os.name == "nt":
    import _winapi as w

    pid = os.getpid()
    consts = ["FILE_FLAG_OVERLAPPED", "FILE_FLAG_FIRST_PIPE_INSTANCE", "PIPE_ACCESS_DUPLEX", "PIPE_ACCESS_INBOUND", "PIPE_READMODE_MESSAGE",
              "PIPE_TYPE_MESSAGE", "PIPE_WAIT", "PIPE_UNLIMITED_INSTANCES", "NMPWAIT_WAIT_FOREVER", "OPEN_EXISTING", "ERROR_PIPE_BUSY",
              "ERROR_IO_PENDING", "ERROR_OPERATION_ABORTED", "ERROR_NETNAME_DELETED", "ERROR_SEM_TIMEOUT", "ERROR_PIPE_CONNECTED",
              "ERROR_ALREADY_EXISTS", "FILE_GENERIC_READ", "FILE_GENERIC_WRITE", "GENERIC_READ", "GENERIC_WRITE", "INFINITE", "NULL"]
    print([getattr(w, n) for n in consts])

    def err(f, *a, **k):
        try:
            print("OK", f(*a, **k))
        except Exception as e:
            print(type(e).__name__, e)

    def winerr(f, *a, **k):
        try:
            f(*a, **k)
            print("no error")
        except OSError as e:
            print(type(e).__name__, e.winerror)

    def waited(h):
        return w.WaitForSingleObject(h, 0) == w.WAIT_OBJECT_0

    # a plain (blocking) pipe pair
    name = r"\\.\pipe\pyjvm-conf-winapi-%d-1" % pid
    srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_FIRST_PIPE_INSTANCE, w.PIPE_TYPE_MESSAGE | w.PIPE_READMODE_MESSAGE | w.PIPE_WAIT,
                            1, 4096, 4096, w.NMPWAIT_WAIT_FOREVER, w.NULL)
    print(isinstance(srv, int) and srv != 0)
    winerr(w.CreateNamedPipe, name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_FIRST_PIPE_INSTANCE, w.PIPE_WAIT, 1, 4096, 4096, 0, w.NULL)
    cli = w.CreateFile(name, w.GENERIC_READ | w.GENERIC_WRITE, 0, w.NULL, w.OPEN_EXISTING, 0, w.NULL)
    print(isinstance(cli, int) and cli != 0, w.GetFileType(cli) == w.FILE_TYPE_PIPE)
    winerr(w.ConnectNamedPipe, srv)                 # the client is already connected: ERROR_PIPE_CONNECTED
    w.CloseHandle(cli)
    w.CloseHandle(srv)

    # errors
    winerr(w.CreateFile, r"\\.\pipe\pyjvm-conf-winapi-missing-%d" % pid, w.GENERIC_READ, 0, w.NULL, w.OPEN_EXISTING, 0, w.NULL)
    err(w.CreateFile, "x", 0, 0, w.NULL, 3, 0)
    err(w.CreateFile, "x", 0, 0, None, 3, 0, 0)
    err(w.CreateFile, 5, 0, 0, 0, 3, 0, 0)
    err(w.CreateNamedPipe, name, 3, 0, 1, 0, 0, 0)
    err(w.CreateNamedPipe, name, 3, 0, 1, 0, 0, 0, None)
    err(w.CreateEventW, None, 1, 1, None)
    err(w.SetEvent)
    err(w.ConnectNamedPipe)
    winerr(w.ConnectNamedPipe, 0)
    winerr(w.ConnectNamedPipe, 0, overlapped=True)
    winerr(w.ConnectNamedPipe, 0, True)

    # overlapped connect that is completed by the client
    name = r"\\.\pipe\pyjvm-conf-winapi-%d-2" % pid
    srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 1, 4096, 4096, 0, w.NULL)
    ov = w.ConnectNamedPipe(srv, overlapped=True)
    print(type(ov).__name__, type(ov).__module__)
    print(ov.event != 0, waited(ov.event))
    err(ov.getbuffer)
    print(ov.GetOverlappedResult(False))
    cli = w.CreateFile(name, w.GENERIC_READ | w.GENERIC_WRITE, 0, w.NULL, w.OPEN_EXISTING, w.FILE_FLAG_OVERLAPPED, w.NULL)
    print(ov.GetOverlappedResult(True))
    print(waited(ov.event))
    print(ov.getbuffer())
    print(ov.cancel())
    w.CloseHandle(cli)
    w.CloseHandle(srv)

    # overlapped connect on a pipe whose client is already there
    name = r"\\.\pipe\pyjvm-conf-winapi-%d-3" % pid
    srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 1, 4096, 4096, 0, w.NULL)
    cli = w.CreateFile(name, w.GENERIC_READ | w.GENERIC_WRITE, 0, w.NULL, w.OPEN_EXISTING, w.FILE_FLAG_OVERLAPPED, w.NULL)
    ov = w.ConnectNamedPipe(srv, overlapped=True)
    print(waited(ov.event))
    winerr(ov.GetOverlappedResult, True)
    w.CloseHandle(cli)
    w.CloseHandle(srv)

    # a cancelled connect
    name = r"\\.\pipe\pyjvm-conf-winapi-%d-4" % pid
    srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_DUPLEX | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 1, 4096, 4096, 0, w.NULL)
    ov = w.ConnectNamedPipe(srv, overlapped=True)
    print(ov.cancel())
    print(ov.GetOverlappedResult(True))
    print(waited(ov.event))
    print(ov.getbuffer())
    w.CloseHandle(srv)

    # asyncio's pipe helper shape: connect, then wait for the result
    name = r"\\.\pipe\pyjvm-conf-winapi-%d-5" % pid
    srv = w.CreateNamedPipe(name, w.PIPE_ACCESS_INBOUND | w.FILE_FLAG_FIRST_PIPE_INSTANCE | w.FILE_FLAG_OVERLAPPED, w.PIPE_WAIT, 1, 0, 4096,
                            w.NMPWAIT_WAIT_FOREVER, w.NULL)
    cli = w.CreateFile(name, w.GENERIC_WRITE, 0, w.NULL, w.OPEN_EXISTING, w.FILE_FLAG_OVERLAPPED, w.NULL)
    ov = w.ConnectNamedPipe(srv, overlapped=True)
    print(ov.GetOverlappedResult(True))
    w.CloseHandle(cli)
    w.CloseHandle(srv)
