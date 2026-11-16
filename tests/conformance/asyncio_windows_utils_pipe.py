# SPDX-License-Identifier: GPL-3.0-or-later
import os

# asyncio.windows_utils: overlapped named-pipe pairs, PipeHandle and Popen with overlapped pipes (Windows only).
print(os.name)

if os.name == "nt":
    import _overlapped as o
    import _winapi as w
    from asyncio import windows_utils as wu

    def read(handle, n=100):
        ov = o.Overlapped(o.NULL)
        ov.ReadFile(handle, n)
        return ov.getresult(True)

    def write(handle, data):
        ov = o.Overlapped(o.NULL)
        ov.WriteFile(handle, data)
        return ov.getresult(True)

    # simplex: the first handle reads, the second writes
    h1, h2 = wu.pipe()
    print(isinstance(h1, int), isinstance(h2, int), h1 != h2)
    print(w.GetFileType(h1) == w.FILE_TYPE_PIPE, w.GetFileType(h2) == w.FILE_TYPE_PIPE)
    print(write(h2, b"simplex"), read(h1))
    w.CloseHandle(h2)
    try:
        read(h1)
    except OSError as e:
        print(type(e).__name__, e.winerror)
    w.CloseHandle(h1)

    # duplex: both directions
    h1, h2 = wu.pipe(duplex=True)
    print(write(h1, b"to client"), read(h2))
    print(write(h2, b"to server"), read(h1))
    w.CloseHandle(h1)
    w.CloseHandle(h2)

    # blocking ends are not overlapped
    h1, h2 = wu.pipe(overlapped=(True, False))
    print(isinstance(h1, int) and isinstance(h2, int))
    w.CloseHandle(h1)
    w.CloseHandle(h2)
    h1, h2 = wu.pipe(overlapped=(False, False))
    print(isinstance(h1, int) and isinstance(h2, int))
    w.CloseHandle(h1)
    w.CloseHandle(h2)

    # PipeHandle
    h1, h2 = wu.pipe()
    ph = wu.PipeHandle(h1)
    print(ph.handle == h1, ph.fileno() == h1)
    print(write(h2, b"via handle"), read(ph.fileno()))
    ph.close()
    print(ph.handle)
    try:
        ph.fileno()
    except ValueError as e:
        print("ValueError", e)
    ph.close()
    w.CloseHandle(h2)

    # Popen with overlapped pipes
    comspec = os.environ["ComSpec"]
    p = wu.Popen([comspec, "/c", "echo hi&echo err>&2"], stdin=wu.PIPE, stdout=wu.PIPE, stderr=wu.PIPE)
    print(type(p.stdin).__name__, type(p.stdout).__name__, type(p.stderr).__name__)
    print(read(p.stdout.handle).strip(), read(p.stderr.handle).strip())
    print(p.wait())
    p.stdin.close()
    p.stdout.close()
    p.stderr.close()
