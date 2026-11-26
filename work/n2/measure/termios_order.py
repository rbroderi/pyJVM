# CPython-only measurement for N2 ticket 7: the order of tcsetattr's checks and edge conversions of termios.
# SAFETY (work/n2_plan.md 2.3): every terminal call is on the pty pair from os.openpty() or on a pipe this script
# creates; never on descriptors 0/1/2, never on /dev/tty. No read from a pty at all. Run under `timeout`, own session,
# stdin /dev/null.
import os
import sys
import termios

print(sys.version_info[:2], os.name)


def t(label, f, *a):
    try:
        r = f(*a)
        print(label, "->", repr(r))
        return r
    except Exception as e:
        print(label, "!!", type(e).__name__, e.args, isinstance(e, OSError))


m, s = os.openpty()
rp, wp = os.pipe()
try:
    a = termios.tcgetattr(s)

    def mod(**kw):
        b = [list(x) if isinstance(x, list) else x for x in a]
        for k, v in kw.items():
            if k == "cc":
                b[6] = v
            elif k.startswith("cc"):
                b[6][int(k[2:])] = v
            else:
                b[int(k[1:])] = v
        return b

    bad_cc = mod(cc=[b"a"] * 31)
    # --- order of checks
    t("pipe + 6-element list", termios.tcsetattr, rp, 0, a[:6])
    t("pipe + bad cc length", termios.tcsetattr, rp, 0, bad_cc)
    t("pipe + bad flag", termios.tcsetattr, rp, 0, mod(f0="x"))
    t("pipe + bad when", termios.tcsetattr, rp, "x", a)
    t("pipe + tuple", termios.tcsetattr, rp, 0, tuple(a))
    t("pipe + valid", termios.tcsetattr, rp, 0, a)
    t("closed + bad flag", termios.tcsetattr, 9999, 0, mod(f0="x"))
    t("closed + 6-element list", termios.tcsetattr, 9999, 0, a[:6])
    t("bad fd + bad when", termios.tcsetattr, "x", "x", a)
    t("bad fd + bad list", termios.tcsetattr, "x", 0, 5)
    t("bad when 99 + bad cc length", termios.tcsetattr, s, 99, bad_cc)
    t("bad when 99 + bad cc item", termios.tcsetattr, s, 99, mod(cc1="zz"))
    t("bad when 99 + bad flag", termios.tcsetattr, s, 99, mod(f0="x"))
    t("bad when 99 + bad speed", termios.tcsetattr, s, 99, mod(f4=12345))
    t("bad when str + bad flag", termios.tcsetattr, s, "x", mod(f0="x"))
    t("bad when str + 6-element", termios.tcsetattr, s, "x", a[:6])
    t("bad flag + bad cc", termios.tcsetattr, s, 0, mod(f0="x", cc=[b"a"] * 31))
    t("bad cc length + bad speed", termios.tcsetattr, s, 0, mod(f4="x", cc=[b"a"] * 31))
    t("bad speed value + bad cc item", termios.tcsetattr, s, 0, mod(f4=12345, cc1="zz"))
    t("bad ospeed value + bad cc item", termios.tcsetattr, s, 0, mod(f5=12345, cc1="zz"))
    t("bad ispeed type + bad cc item", termios.tcsetattr, s, 0, mod(f4="x", cc1="zz"))
    t("bad flag 2 + bad flag 0", termios.tcsetattr, s, 0, mod(f2=1.5, f0="x"))
    t("bad when 99 + all bad", termios.tcsetattr, s, 99, mod(f0="x", f4=12345, cc1="zz"))
    t("restore", termios.tcsetattr, s, 0, a)
    # --- conversions of the attributes
    class Idx:
        def __index__(self): return 5
    t("flag float", termios.tcsetattr, s, 0, mod(f0=1.5))
    t("flag None", termios.tcsetattr, s, 0, mod(f0=None))
    t("flag bool", termios.tcsetattr, s, 0, mod(f0=True, f1=a[1], f2=a[2], f3=a[3]))
    t("flag __index__ (cflag)", termios.tcsetattr, s, 0, mod(f1=Idx()))
    t("flag 2**64", termios.tcsetattr, s, 0, mod(f0=2**64))
    t("flag 2**63", termios.tcsetattr, s, 0, mod(f0=2**63))
    t("flag 2**63-1", termios.tcsetattr, s, 0, mod(f0=2**63 - 1))
    t("flag -2**63", termios.tcsetattr, s, 0, mod(f0=-2**63))
    t("flag -2**63-1", termios.tcsetattr, s, 0, mod(f0=-2**63 - 1))
    print("after flag trunc", termios.tcgetattr(s)[:4])
    t("restore", termios.tcsetattr, s, 0, a)
    t("ispeed str", termios.tcsetattr, s, 0, mod(f4="x"))
    t("ispeed float", termios.tcsetattr, s, 0, mod(f4=15.0))
    t("ispeed -1", termios.tcsetattr, s, 0, mod(f4=-1))
    t("ispeed 2**32+15", termios.tcsetattr, s, 0, mod(f4=2**32 + 15))
    t("ispeed 2**40", termios.tcsetattr, s, 0, mod(f4=2**40))
    t("ispeed 2**64", termios.tcsetattr, s, 0, mod(f4=2**64))
    t("ispeed 0 (hangup)", termios.tcsetattr, s, 0, mod(f4=0))
    print("speeds", termios.tcgetattr(s)[4:6])
    t("ospeed 9600", termios.tcsetattr, s, 0, mod(f5=termios.B9600))
    print("speeds", termios.tcgetattr(s)[4:6])
    t("restore", termios.tcsetattr, s, 0, a)
    t("cc item bytes 0", termios.tcsetattr, s, 0, mod(cc1=b""))
    t("cc item bytes 2", termios.tcsetattr, s, 0, mod(cc1=b"ab"))
    t("cc item bytearray", termios.tcsetattr, s, 0, mod(cc1=bytearray(b"a")))
    t("cc item str", termios.tcsetattr, s, 0, mod(cc1="a"))
    t("cc item float", termios.tcsetattr, s, 0, mod(cc1=1.5))
    t("cc item None", termios.tcsetattr, s, 0, mod(cc1=None))
    t("cc item bool", termios.tcsetattr, s, 0, mod(cc1=True))
    t("cc item -1", termios.tcsetattr, s, 0, mod(cc1=-1))
    t("cc item 2**63", termios.tcsetattr, s, 0, mod(cc1=2**63))
    t("cc item 2**64", termios.tcsetattr, s, 0, mod(cc1=2**64))
    t("cc item __index__", termios.tcsetattr, s, 0, mod(cc1=Idx()))
    print("cc after", termios.tcgetattr(s)[6][:4])
    t("restore", termios.tcsetattr, s, 0, a)
    t("cc as bytes", termios.tcsetattr, s, 0, mod(cc=bytes(32)))
    t("cc as tuple", termios.tcsetattr, s, 0, mod(cc=tuple(a[6])))
    t("cc 33", termios.tcsetattr, s, 0, mod(cc=a[6] + [b"a"]))
    t("attributes 8", termios.tcsetattr, s, 0, a + [0])
    t("attributes dict", termios.tcsetattr, s, 0, {})
    t("attributes None", termios.tcsetattr, s, 0, None)
    t("when float", termios.tcsetattr, s, 0.0, a)
    t("when 2**31", termios.tcsetattr, s, 2**31, a)
    t("when -1", termios.tcsetattr, s, -1, a)
    t("when True", termios.tcsetattr, s, True, a)
    # --- ICANON off: VMIN/VTIME as ints on read; what the write accepts back
    b = termios.tcgetattr(s)
    b[3] &= ~termios.ICANON
    termios.tcsetattr(s, 0, b)
    c = termios.tcgetattr(s)
    print("noncanon", [type(x).__name__ for x in c[6]].count("int"), c[6][termios.VMIN], c[6][termios.VTIME], c[6][termios.VEOF], c[6][termios.VEOL])
    t("noncanon write back", termios.tcsetattr, s, 0, c)
    c[6][termios.VMIN] = b"\x07"
    t("noncanon vmin bytes", termios.tcsetattr, s, 0, c)
    print("vmin", termios.tcgetattr(s)[6][termios.VMIN])
    termios.tcsetattr(s, 0, a)
    # --- argument counts and keywords
    t("tcgetattr()", termios.tcgetattr)
    t("tcgetattr(1,2)", termios.tcgetattr, s, 2)
    t("tcsetattr(s)", termios.tcsetattr, s)
    t("tcsetattr(s,0)", termios.tcsetattr, s, 0)
    t("tcsetattr 4 args", termios.tcsetattr, s, 0, a, 1)
    t("tcsetattr keyword", lambda: termios.tcsetattr(s, 0, attributes=a))
    t("tcsendbreak(s)", termios.tcsendbreak, s)
    t("tcdrain()", termios.tcdrain)
    t("tcflush(s)", termios.tcflush, s)
    t("tcflow(s)", termios.tcflow, s)
    t("tcgetwinsize()", termios.tcgetwinsize)
    t("tcsetwinsize(s)", termios.tcsetwinsize, s)
    t("tcsetwinsize kw", lambda: termios.tcsetwinsize(s, winsize=(1, 2)))
    # --- C int conversions of the plain arguments
    t("tcsendbreak 2**31", termios.tcsendbreak, s, 2**31)
    t("tcsendbreak float", termios.tcsendbreak, s, 0.0)
    t("tcflush 2**40", termios.tcflush, s, 2**40)
    t("tcflush None", termios.tcflush, s, None)
    t("tcgetattr 2**31", termios.tcgetattr, 2**31)
    t("tcgetattr True", termios.tcgetattr, True)
    t("tcgetattr 1.0", termios.tcgetattr, 1.0)
    # --- window size
    t("setwinsize str", termios.tcsetwinsize, s, "ab")
    t("setwinsize bytes", termios.tcsetwinsize, s, b"ab")
    t("setwinsize dict", termios.tcsetwinsize, s, {1: 2, 3: 4})
    t("setwinsize set", termios.tcsetwinsize, s, {1, 2})
    t("setwinsize iter", termios.tcsetwinsize, s, iter((1, 2)))
    t("setwinsize range", termios.tcsetwinsize, s, range(2))
    t("setwinsize float", termios.tcsetwinsize, s, (1.0, 2))
    t("setwinsize bool", termios.tcsetwinsize, s, (True, 2))
    t("setwinsize Idx", termios.tcsetwinsize, s, (Idx(), 2))
    t("setwinsize 65535", termios.tcsetwinsize, s, (65535, 65535))
    t("winsize after", termios.tcgetwinsize, s)
    t("setwinsize 65536", termios.tcsetwinsize, s, (65536, 1))
    t("setwinsize second bad", termios.tcsetwinsize, s, (1, "x"))
    t("setwinsize first -1 second str", termios.tcsetwinsize, s, (-1, "x"))
    t("setwinsize 2**63", termios.tcsetwinsize, s, (2**63, 1))
    t("setwinsize pipe valid", termios.tcsetwinsize, rp, (1, 2))
    t("setwinsize pipe bad", termios.tcsetwinsize, rp, (1, 2, 3))
    t("setwinsize pipe 70000", termios.tcsetwinsize, rp, (70000, 1))
    t("setwinsize closed", termios.tcsetwinsize, 9999, (1, 2))
    t("getwinsize closed", termios.tcgetwinsize, 9999)
    # pixel fields kept: set through the master's own ioctl is ticket 5; here only a roundtrip
    termios.tcsetwinsize(s, (7, 9))
    t("winsize 7x9", termios.tcgetwinsize, s)
    # --- error object
    try:
        termios.tcgetattr(rp)
    except termios.error as e:
        print(type(e).__mro__, e.args, e.__dict__, str(e), repr(e), e.__module__, e.__reduce__())
    print(termios.error.__module__, termios.error.__qualname__, termios.error.__doc__, termios.error.__dict__.keys() - {"__module__", "__doc__"} if False else "")
    t("construct error", lambda: termios.error(1, "x").args)
    t("construct error 0", lambda: termios.error().args)
    # --- module attributes
    print(sorted(n for n in dir(termios) if n.startswith("__")))
    print(termios.__name__, termios.__doc__ is not None, getattr(termios, "__file__", None), termios.__spec__.name, termios.__spec__.origin, termios.__loader__.__name__)
    print(termios.tcgetattr.__doc__.splitlines()[0] if termios.tcgetattr.__doc__ else None, termios.tcgetattr.__module__, termios.tcgetattr.__qualname__, termios.tcgetattr.__name__)
finally:
    for f in (m, s, rp, wp):
        os.close(f)
