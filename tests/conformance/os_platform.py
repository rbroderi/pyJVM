import errno
import os
import stat

print(os.name, os.sep, repr(os.linesep), os.pathsep)
print(errno.ENOENT, errno.ENOTEMPTY, errno.EWOULDBLOCK, errno.errorcode[errno.EWOULDBLOCK], errno.errorcode[errno.EAGAIN])
print(os.strerror(errno.ENOENT), "|", os.strerror(errno.ENOTEMPTY))

base = os.path.join(os.environ.get("TEMP") or os.environ.get("TMPDIR") or "/tmp", "pyjvm_os_platform_%d" % os.getpid())
os.mkdir(base)
try:
    f = os.path.join(base, "data.txt")
    with open(f, "w") as fh:
        fh.write("hello\n")
    st, dst = os.stat(f), os.stat(base)
    print(stat.S_ISREG(st.st_mode), stat.S_ISDIR(dst.st_mode), st.st_size == os.path.getsize(f), st.st_nlink)
    print(oct(stat.S_IMODE(st.st_mode)) if os.name == "nt" else "posix", oct(stat.S_IMODE(dst.st_mode)) if os.name == "nt" else "posix")
    print(os.path.samefile(f, f), os.path.samefile(f, base), st.st_ino != 0, st.st_dev == dst.st_dev)
    print([e.name for e in os.scandir(base)], [e.path == os.path.join(base, e.name) for e in os.scandir(base)])
    print(os.listdir(base), os.path.isfile(f), os.path.isdir(base), os.path.exists(f + ".missing"))
    if os.name == "nt":
        os.chmod(f, stat.S_IREAD)
        print(oct(stat.S_IMODE(os.stat(f).st_mode)), hex(os.stat(f).st_file_attributes & 1))
        os.chmod(f, stat.S_IREAD | stat.S_IWRITE)
        print(oct(stat.S_IMODE(os.stat(f).st_mode)), os.lstat(f).st_reparse_tag, type(os.stat(f).st_birthtime_ns).__name__)
    try:
        os.stat(os.path.join(base, "missing"))
    except OSError as e:
        print(type(e).__name__, e.errno, str(e).rsplit(":", 2)[0] if os.name == "nt" else str(e).split(":")[0],
              e.filename == os.path.join(base, "missing"), getattr(e, "winerror", "no winerror"))
    try:
        os.rmdir(base)
    except OSError as e:
        print(type(e).__name__, e.errno == errno.ENOTEMPTY)
    os.remove(f)
finally:
    os.rmdir(base)
print(os.path.exists(base))

if os.name == "nt":
    import winreg
    print(winreg.HKEY_CURRENT_USER, winreg.KEY_READ, winreg.REG_SZ, winreg.HKEYType.__name__, winreg.error is OSError)
    path = r"Software\pyjvm-conformance-%d" % os.getpid()
    k = winreg.CreateKey(winreg.HKEY_CURRENT_USER, path)
    try:
        winreg.SetValueEx(k, "s", 0, winreg.REG_SZ, "text")
        winreg.SetValueEx(k, "e", 0, winreg.REG_EXPAND_SZ, "%SystemRoot%")
        winreg.SetValueEx(k, "d", 0, winreg.REG_DWORD, 0xFFFFFFFF)
        winreg.SetValueEx(k, "q", 0, winreg.REG_QWORD, 2**40)
        winreg.SetValueEx(k, "m", 0, winreg.REG_MULTI_SZ, ["a", "bc"])
        winreg.SetValueEx(k, "b", 0, winreg.REG_BINARY, b"\x00\x01")
        winreg.SetValueEx(k, "n", 0, winreg.REG_BINARY, None)
        winreg.SetValue(k, "child", winreg.REG_SZ, "default")
        print(sorted(winreg.EnumValue(k, i) for i in range(winreg.QueryInfoKey(k)[1])))
        print(winreg.QueryValueEx(k, "d"), winreg.QueryValue(k, "child"), winreg.EnumKey(k, 0), winreg.QueryInfoKey(k)[:2])
        for bad in (lambda: winreg.EnumKey(k, 5), lambda: winreg.QueryValueEx(k, "missing"), lambda: winreg.OpenKey(k, "nope")):
            try:
                bad()
            except OSError as e:
                print(type(e).__name__, e.errno, e.winerror, e)
        try:
            winreg.SetValueEx(k, "x", 0, winreg.REG_DWORD, "nope")
        except (TypeError, ValueError) as e:
            print(type(e).__name__, e)
        winreg.DeleteKey(k, "child")
        winreg.DeleteValue(k, "s")
        print(winreg.QueryInfoKey(k)[:2])
    finally:
        k.Close()
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, path)
    print(bool(k), int(k))
    print(winreg.ExpandEnvironmentStrings("%PYJVM_SURELY_UNSET%|x"))
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Software") as s:
        print(type(s).__name__, bool(s), str(s).startswith("<PyHKEY:0x"))
    print(bool(s))
    # the POSIX-only libc modules and the os terminal functions do not exist on Windows (N2)
    for name in ("fcntl", "termios", "resource", "pwd", "grp"):
        try:
            __import__(name)
        except ImportError as e:
            print(name, type(e).__name__, e)
        else:
            print(name, "imported")
    print(hasattr(os, "openpty"))
