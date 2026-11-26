# CPython-only measurement for N2 ticket 7 review: which objects tcsetwinsize accepts as its sequence, and what
# happens when len() fails. SAFETY: only a pipe this script creates is passed as the descriptor (the ioctl then fails
# with error 25 after the conversions); no terminal is touched; run under timeout, own session, stdin /dev/null.
import collections
import os
import sys
import termios

print(sys.version_info[:2])
rp, wp = os.pipe()


def t(label, o):
    try:
        print(label, "->", repr(termios.tcsetwinsize(rp, o)))
    except Exception as e:
        print(label, "!!", type(e).__name__, e.args)


class NoLen:
    def __getitem__(self, i): return 1


class BadLen:
    def __getitem__(self, i): return 1
    def __len__(self): raise RuntimeError("boom")


class Neg:
    def __getitem__(self, i): return 1
    def __len__(self): return -1


class Two:
    def __getitem__(self, i): return 1
    def __len__(self): return 2


class Big:
    def __getitem__(self, i): return 1
    def __len__(self): return 2**70


class Str(str): pass


try:
    t("deque", collections.deque([1, 2]))
    t("deque 3", collections.deque([1, 2, 3]))
    t("NoLen", NoLen())
    t("BadLen", BadLen())
    t("Neg", Neg())
    t("Two", Two())
    t("Big", Big())
    t("memoryview", memoryview(b"ab"))
    t("array", __import__("array").array("B", [1, 2]))
    t("dict subclass getitem", type("D", (dict,), {})({0: 1, 1: 2}))
    t("os.stat_result-like", os.times()[:2] and os.stat(".")[:2])
    t("mappingproxy", type(os.environ)) if False else None
    t("Str", Str("ab"))
    t("None", None)
    t("int", 5)
finally:
    os.close(rp)
    os.close(wp)
