# SPDX-License-Identifier: GPL-3.0-or-later
import os

# _overlapped (Windows only): constants, the Overlapped type, and the argument checks of its functions.
print(os.name)

if os.name == "nt":
    import _overlapped as o
    import _winapi

    consts = ["ERROR_IO_PENDING", "ERROR_NETNAME_DELETED", "ERROR_OPERATION_ABORTED", "ERROR_PIPE_BUSY",
              "ERROR_PORT_UNREACHABLE", "ERROR_SEM_TIMEOUT", "INFINITE", "INVALID_HANDLE_VALUE", "NULL",
              "SO_UPDATE_ACCEPT_CONTEXT", "SO_UPDATE_CONNECT_CONTEXT", "TF_REUSE_SOCKET"]
    for name in consts:
        print(name, getattr(o, name))
    print(sorted(n for n in dir(o) if n.isupper()) == sorted(consts))

    # the functions of the part implemented here
    for name in ["CreateEvent", "CreateIoCompletionPort", "FormatMessage", "GetQueuedCompletionStatus", "PostQueuedCompletionStatus",
                 "RegisterWaitWithQueue", "ResetEvent", "SetEvent", "UnregisterWait", "UnregisterWaitEx", "ConnectPipe"]:
        print(name, callable(getattr(o, name)))

    O = o.Overlapped
    print(O.__module__, O.__name__, type(O).__name__)
    for name in ["getresult", "cancel", "ReadFile", "ReadFileInto", "WriteFile", "ConnectNamedPipe"]:
        print(name, callable(getattr(O, name)))
    ov = O(0)
    print(ov.pending, ov.error, ov.event, isinstance(ov.address, int) and ov.address != 0)
    ov2 = O()
    print(ov2.event != 0, ov2.pending, ov2.error)
    ov2 = None
    ov3 = O(event=0)
    print(ov3.event)

    def err(f, *a, **k):
        try:
            print("OK", f(*a, **k))
        except Exception as e:
            print(type(e).__name__, e)

    err(o.CreateIoCompletionPort, 1, 2, 3)
    err(o.GetQueuedCompletionStatus, 1)
    err(o.CreateIoCompletionPort, port=1)
    err(O, 1, 2)
    err(O, foo=1)
    err(O(0).ReadFile, 1)
    err(O(0).getresult, 1, 2)
    err(o.FormatMessage, -1)
    err(o.FormatMessage, 2**32)
    err(o.SetEvent, None)
    err(o.ConnectPipe, 5)
    err(o.ConnectPipe, "a\0b")
    err(o.CreateEvent, None, 1, 1, 5)

    # _winapi.Overlapped cannot be created directly
    print(_winapi.Overlapped.__module__, _winapi.Overlapped.__name__)
    err(_winapi.Overlapped)
