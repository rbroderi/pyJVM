# OSError(errno, strerror, ...) built from Python returns the errno-mapped subclass, as in CPython.
# Only errno values that are equal on Linux and Windows are used.


def show(label, e):
    print(label, type(e).__name__, e.args, e.errno, e.strerror, e.filename, e.filename2, str(e), repr(e))


show("enoent", OSError(2, "No such file or directory"))
show("enoent file", OSError(2, "No such file or directory", "a.txt"))
e = OSError(2, "No such file or directory", "a.txt", None, "b.txt")   # str() differs on Windows (winerror slot)
print("enoent two files", type(e).__name__, e.args, e.errno, e.strerror, e.filename, e.filename2)
show("filename none", OSError(2, "x", None))
show("eacces", OSError(13, "Permission denied"))
show("eperm", OSError(1, "Operation not permitted"))
show("eexist", OSError(17, "File exists", "p"))
show("eisdir", OSError(21, "Is a directory"))
show("enotdir", OSError(20, "Not a directory"))
show("eintr", OSError(4, "Interrupted"))
show("esrch", OSError(3, "No such process"))
show("echild", OSError(10, "No child processes"))
show("eagain", OSError(11, "Try again"))
show("epipe", OSError(32, "Broken pipe"))
show("eio unmapped", OSError(5, "Input/output error"))
show("bool errno", OSError(True, "x"))
show("str errno", OSError("2", "x"))
show("none errno", OSError(None, "x"))
show("float errno", OSError(2.0, "x"))

# Not remapped: fewer than two or more than five arguments.
for args in [(), (2,), (2, "a", "b", None, "c", "d")]:
    e = OSError(*args)
    print(len(args), type(e).__name__, e.args, e.errno, e.strerror, e.filename)

# Subclasses called directly keep their class.
show("PermissionError(2)", PermissionError(2, "x"))
show("FileNotFoundError(13)", FileNotFoundError(13, "x"))
show("ConnectionError(2)", ConnectionError(2, "x"))
show("BlockingIOError(2)", BlockingIOError(2, "x"))


# User subclasses of OSError are not remapped.
class MyErr(OSError):
    pass


e = MyErr(2, "x")
print(type(e).__name__, e.args, e.errno, e.strerror, e.filename, str(e), repr(e))
e = MyErr(2, "x", "f")
print(type(e).__name__, e.args, e.errno, e.strerror, e.filename, str(e), repr(e))

# The mapped instance is caught by the subclass and by OSError.
try:
    raise OSError(2, "gone", "f")
except FileNotFoundError as e:
    print("caught", type(e).__name__, isinstance(e, OSError), str(e))
print(type(OSError(13, "x")) is PermissionError, type(OSError(2, "x")) is OSError)

import sys

if sys.platform == "win32":
    # The fourth argument is winerror; it maps through winerror -> errno before the subclass lookup.
    for args in [(None, "x", None, 5), (0, "x", "f", 2), (1, "x", None, 183), (1, "x", None, None), (1, "x", None, "w"),
                 (None, "x", "f", 32, "g"), (None, "x", None, 99999)]:
        e = OSError(*args)
        print(args, type(e).__name__, e.args, e.errno, e.strerror, e.filename, e.filename2, e.winerror, str(e), repr(e))
    e = OSError(2, "x")
    print(e.winerror, str(e))
    print(type(FileNotFoundError(5, "x", None, 5)).__name__, FileNotFoundError(5, "x", None, 5).errno)
