import fcntl, os, sys, array
def t(name, *a):
    try:
        print(name, "->", repr(fcntl.ioctl(*a)))
    except BaseException as e:
        print(name, "!!", type(e).__name__, e.args)
for pending in (5, 256, 65536):
    r, w = os.pipe()
    os.write(w, b"x" * pending)
    print("== pending", pending)
    for n in (0, 1, 2, 3, 4, 5):
        ba = bytearray(b"\xaa" * n)
        t("ba %d" % n, r, 0x541B, ba); print("   after", bytes(ba))
        t("ba nomut %d" % n, r, 0x541B, ba, False)
        t("bytes %d" % n, r, 0x541B, b"\xaa" * n)
        t("str %d" % n, r, 0x541B, "a" * n)
    a = array.array("h", [3, 3])
    t("arr h2", r, 0x541B, a); print("   after", a)
    os.close(r); os.close(w)
# 1030-byte buffer: direct path in CPython for mutable > 1024
r, w = os.pipe()
os.write(w, b"x" * 256)
big = bytearray(b"\xaa" * 1030)
t("big mut", r, 0x541B, big); print("   after", bytes(big[:6]), bytes(big[-2:]))
big = bytearray(b"\xaa" * 1024)
t("1024 mut", r, 0x541B, big); print("   after", bytes(big[:6]))
big = bytearray(b"\xaa" * 1025)
t("1025 nomut", r, 0x541B, big, False)
t("1025 mut", r, 0x541B, big); print("   after", bytes(big[:6]))
# 3rd arg odd types
t("bytes sub", r, 0x541B, type("B", (bytes,), {})(b"\0" * 4))
t("float", r, 0x541B, 1.5)
t("None", r, 0x541B, None)
t("list", r, 0x541B, [1])
class I:
    def __index__(self): return 0
t("index", r, 0x541B, I())
t("index mutate_flag", r, 0x541B, bytearray(4), I())
t("neg2**63", r, 0x541B, -2**63)
t("neg2**63-1", r, 0x541B, -2**63 - 1)
t("2**63-1", r, 0x541B, 2**63 - 1)
t("2**63", r, 0x541B, 2**63)
t("req -2**63-1", r, -2**63 - 1)
t("req -2**63", r, -2**63)
t("req 2**64-1", r, 2**64 - 1)
t("req True", r, True)
t("req I", r, I())
t("req str", r, "x")
