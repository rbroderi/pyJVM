# args/repr of exceptions the runtime raises for a Win32 error (Windows only; POSIX prints just this line).
import sys

print("start")

if sys.platform == "win32":
    import os
    import socket
    import winreg
    import _winapi

    def show(label, e):
        print(label, type(e).__name__, e.args, e.errno, e.strerror, e.filename, e.filename2, e.winerror, repr(e))
        again = type(e)(*e.args)
        print(" round trip", type(again).__name__, again.args == e.args)

    try:
        winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Software\\pyjvm_no_such_key_x1")
    except OSError as e:
        show("winreg", e)

    try:
        _winapi.CloseHandle(987654)
    except OSError as e:
        show("CloseHandle", e)

    try:
        os.stat("pyjvm_no_such_file_x1")
    except OSError as e:
        show("stat", e)

    s = socket.socket()
    s.close()
    try:
        s.send(b"x")
    except OSError as e:
        print("closed socket", type(e).__name__, e.errno, len(e.args))
