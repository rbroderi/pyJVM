# fcntl.ioctl edges: request and argument conversion, buffer write-back, the 3.14+ overflow guard at the 0/1/2/3-byte
# boundary, odd argument types, argument-error order. SAFETY: only FIONREAD (0x541B) and unknown requests (ENOTTY) on
# pipes the script creates (the pipe write is small, so a 4 KiB pipe cannot block it); no terminal, no fd 0/1/2.
import os

print(os.name)
if os.name == "posix":
    import array
    import fcntl
    import sys
    import warnings

    FIONREAD = 0x541B
    warnings.simplefilter("error")
    r, w = os.pipe()
    os.write(w, b"hello")

    def t(name, *a):
        try:
            v = fcntl.ioctl(*a)
            print(name, "->", repr(v) if not isinstance(v, bytes) or len(v) < 100 else len(v))
        except BaseException as e:
            print(name, "!!", type(e).__name__, e.args)

    for req in (-1, 2**31, 2**32, 2**33, -2**31, -2**31 - 1, 2**64, -2**63, 2**63):
        t("req %d" % req, r, req)
    for arg in (0, -1, 2**31, 2**32, 2**64, -2**31 - 1, True):
        t("arg %r" % (arg,), r, FIONREAD, arg)
    t("req True", r, True)
    t("req float", r, 1.5)
    t("req None", r, None)

    class I:
        def __index__(self):
            return FIONREAD

    t("req index", r, I())
    t("arg index", r, FIONREAD, I())
    t("mv ro", r, FIONREAD, memoryview(b"\0\0\0\0"))
    t("mv rw", r, FIONREAD, memoryview(bytearray(4)))
    mv = memoryview(bytearray(8))[2:6]
    print(fcntl.ioctl(r, FIONREAD, mv), bytes(mv.obj))
    a = array.array("i", [0, 7])
    print(fcntl.ioctl(r, FIONREAD, a), a)
    a = array.array("h", [0, 7, 9])
    print(fcntl.ioctl(r, FIONREAD, a), a)
    ba = bytearray(3)
    t("ba short", r, FIONREAD, ba)
    print(ba)
    ba = bytearray(b"\1\1\1\1\1")
    print(fcntl.ioctl(r, FIONREAD, ba), ba)
    t("ba nomut", r, FIONREAD, ba, False)
    print(ba)
    t("mutate 0", r, FIONREAD, bytearray(4), 0)
    t("empty ba", r, FIONREAD, bytearray())
    t("empty bytes", r, FIONREAD, b"")
    t("arg4 none", r, FIONREAD, bytearray(4), None)
    t("arg4 str", r, FIONREAD, bytearray(4), "x")
    t("noargs", r)
    t("5 args", r, 1, 2, 3, 4)
    t("bytes sub", r, FIONREAD, bytes(4))
    t("mv nonc", r, FIONREAD, memoryview(bytearray(8))[::2])
    t("mv nonc ro", r, FIONREAD, memoryview(bytes(8))[::2])
    t("mv nonc nomut", r, FIONREAD, memoryview(bytearray(8))[::2], False)
    t("array nomut", r, FIONREAD, array.array("i", [0]), False)
    t("str", r, FIONREAD, "abcd")
    t("str surrogate", r, FIONREAD, "\udc80")
    t("fd str", "x", 1)
    t("fd -1", -1, 1)
    t("closed", 9999, FIONREAD, bytearray(4))
    os.close(r)
    os.close(w)

    # the overflow guard: FIONREAD writes 4 bytes; with 5 pending the written bytes are 5,0,0,0 (buf[len] stays 0 for
    # len 3), with 256 pending 0,1,0,0 (a nonzero byte at offset len for len 1)
    for pending in (5, 256):
        r, w = os.pipe()
        os.write(w, b"x" * pending)
        print("== pending", pending)
        for n in (0, 1, 2, 3, 4, 5):
            ba = bytearray(b"\xaa" * n)
            t("ba %d" % n, r, FIONREAD, ba)
            print("   after", bytes(ba))
            t("ba nomut %d" % n, r, FIONREAD, ba, False)
            t("bytes %d" % n, r, FIONREAD, b"\xaa" * n)
            t("str %d" % n, r, FIONREAD, "a" * n)
        a = array.array("h", [3, 3])
        t("arr h2", r, FIONREAD, a)
        print("   after", a)
        os.close(r)
        os.close(w)

    r, w = os.pipe()
    os.write(w, b"x" * 256)
    big = bytearray(b"\xaa" * 1030)
    t("big mut", r, FIONREAD, big)
    print("   after", bytes(big[:6]), bytes(big[-2:]))
    big = bytearray(b"\xaa" * 1024)
    t("1024 mut", r, FIONREAD, big)
    print("   after", bytes(big[:6]))
    big = bytearray(b"\xaa" * 1025)
    t("1025 nomut", r, FIONREAD, big, False)
    t("1025 mut", r, FIONREAD, big)
    print("   after", bytes(big[:6]))
    t("bytes sub", r, FIONREAD, type("B", (bytes,), {})(b"\0" * 4))
    t("float", r, FIONREAD, 1.5)
    t("None", r, FIONREAD, None)
    t("list", r, FIONREAD, [1])

    class J:
        def __index__(self):
            return 0

    t("index", r, FIONREAD, J())
    t("index mutate_flag", r, FIONREAD, bytearray(4), J())
    t("neg2**63", r, FIONREAD, -2**63)
    t("neg2**63-1", r, FIONREAD, -2**63 - 1)
    t("2**63-1", r, FIONREAD, 2**63 - 1)
    t("2**63", r, FIONREAD, 2**63)
    t("req -2**63-1", r, -2**63 - 1)
    t("req -2**63", r, -2**63)
    t("req 2**64-1", r, 2**64 - 1)
    t("req True", r, True)
    t("req I", r, I())
    t("req str", r, "x")

    # argument order: mutate_flag is converted before the audit event, the request before both
    events = []
    sys.addaudithook(lambda name, args: events.append(name) if name == "fcntl.ioctl" else None)

    class Boom:
        def __bool__(self):
            raise ValueError("boom")

    t("bad mutate_flag", r, FIONREAD, bytearray(4), Boom())
    print("audit after bad mutate_flag", events)
    t("bad request", r, "x", bytearray(4))
    print("audit after bad request", events)
    t("good", r, FIONREAD, bytearray(4))
    print("audit after good", events)
    os.close(r)
    os.close(w)
