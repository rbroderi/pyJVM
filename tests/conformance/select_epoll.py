# SPDX-License-Identifier: GPL-3.0-or-later
# select.epoll on kernel descriptors: construction and its errors, register/modify/unregister and their errors,
# level-triggered readiness, EPOLLONESHOT, EPOLLHUP, several descriptors in one poll (the packed 12-byte event), maxevents,
# the closed-object errors and the order in which arguments are checked, the context manager, fromfd, selectors, timeouts
# (sleeping, returning early, not shortened by a handler that returns, interrupted by one that raises) and the collection
# of an epoll object that was never closed. POSIX only (kernel descriptors; PYJVM_POSIX_FDS=nio has no pipe).
import gc
import os
import select
import selectors
import signal
import sys
import threading
import time
import warnings

print(os.name)
if os.name == "nt":
    raise SystemExit(0)

NEW = sys.version_info >= (3, 15)
names = {}


def t(label, f, *a, **k):
    try:
        print(label, repr(f(*a, **k)))
    except BaseException as e:
        print(label, type(e).__name__, e.args)


def pipe(name):
    r, w = os.pipe()
    names[r] = name + ".r"
    names[w] = name + ".w"
    return r, w


def nm(res):
    """A poll result with the descriptor numbers replaced by names, sorted."""
    return sorted((names.get(fd, fd), ev) for fd, ev in res)


print("consts", [(n, getattr(select, n)) for n in sorted(dir(select)) if n.startswith("EPOLL")])
print("epoll names", [n for n in sorted(dir(select.epoll)) if not n.startswith("__")])
print("class", select.epoll.__module__, select.epoll.__name__)

# ---- construction ------------------------------------------------------------------------------------------------
e = select.epoll()
print("fileno", e.fileno() > 2, "closed", e.closed, "inheritable", os.get_inheritable(e.fileno()))
print("repr", repr(e).startswith("<select.epoll object at"))
t("epoll(0)", select.epoll, 0)
t("epoll(-5)", select.epoll, -5)
t("epoll(flags=12)", select.epoll, flags=12)
t("epoll(1, 12)", select.epoll, 1, 12)
for args, kw in (((-1,), {}), ((5,), {}), ((), {"sizehint": 3}), ((), {"flags": select.EPOLL_CLOEXEC}), ((1, select.EPOLL_CLOEXEC), {})):
    x = select.epoll(*args, **kw)
    print("epoll", args, kw, x.fileno() > 2, os.get_inheritable(x.fileno()))
    x.close()
t("epoll('a')", select.epoll, "a")
t("epoll(1.5)", select.epoll, 1.5)
t("epoll(1, 2, 3)", select.epoll, 1, 2, 3)
t("epoll(x=1)", select.epoll, x=1)

# ---- register / modify / unregister -------------------------------------------------------------------------------
r1, w1 = pipe("p1")
t("register(r1)", e.register, r1, select.EPOLLIN)
t("register twice", e.register, r1, select.EPOLLIN)
t("modify unknown", e.modify, w1, select.EPOLLOUT)
t("unregister unknown", e.unregister, w1)
t("register 9999", e.register, 9999)
t("register -1", e.register, -1)
t("register 'x'", e.register, "x")
t("register None", e.register, None)
t("register 1.5", e.register, 1.5)
t("register mask -1", e.register, w1, -1)
t("register mask 'x'", e.register, w1, "x")
t("register mask 1.5", e.register, w1, 1.5)
f = open(__file__, "rb")
t("register file", e.register, f.fileno(), select.EPOLLIN)
t("register file obj", e.register, f, select.EPOLLIN)
f.close()
t("register r1 keyword", e.modify, r1, eventmask=select.EPOLLIN | select.EPOLLOUT)
t("unregister r1", e.unregister, r1)
t("unregister again", e.unregister, r1)
t("register default mask", e.register, w1)
print("default mask", nm(e.poll(0)))
e.unregister(w1)


class Fd:
    def __init__(self, fd):
        self.fd = fd

    def fileno(self):
        return self.fd


t("register fileno()", e.register, Fd(r1), select.EPOLLIN)
t("unregister fileno()", e.unregister, Fd(r1))
t("register 2**40", e.register, 2**40)
for v in (2**32, 2**40, -2**31, -2**31 - 1, 2**32 - 1):      # 3.15 warns for a mask outside [-2**31, 2**32)
    with warnings.catch_warnings(record=True) as ws:
        warnings.simplefilter("always")
        t("register mask %d" % v, e.register, w1, v)
        t("modify mask %d" % v, e.modify, w1, v)
        print("warnings", [(x.category.__name__, str(x.message)) for x in ws])
    try:
        e.unregister(w1)
    except OSError:
        pass

# ---- poll arguments -------------------------------------------------------------------------------------------------
t("poll(0, 0)", e.poll, 0, 0)
t("poll(0, -2)", e.poll, 0, -2)
t("poll(0, -1)", e.poll, 0, -1)
t("poll(timeout=0, maxevents=1)", e.poll, timeout=0, maxevents=1)
t("poll(0, 'x')", e.poll, 0, "x")
t("poll(0, 1.5)", e.poll, 0, 1.5)
t("poll('x')", e.poll, "x")
t("poll('x', 0)", e.poll, "x", 0)
t("poll('x', 'y')", e.poll, "x", "y")
t("poll(0.5, 'y')", e.poll, 0.5, "y")
t("poll('x', -5)", e.poll, "x", -5)
t("poll(None, 0)", e.poll, None, 0)
t("poll(float nan)", e.poll, float("nan"))
t("poll(2**70)", e.poll, 2**70)
t("poll(1e30)", e.poll, 1e30)
print("poll(0) empty", e.poll(0))
print("poll(0.0, 3) empty", e.poll(0.0, 3))

# ---- level-triggered readiness on a pipe --------------------------------------------------------------------------
e.register(r1, select.EPOLLIN)
os.write(w1, b"abc")
print("level 1", nm(e.poll(1)))
print("level 2", nm(e.poll(1)))
os.read(r1, 10)
print("drained", e.poll(0))

# modify
e.modify(r1, select.EPOLLOUT)
print("modified to OUT", nm(e.poll(0)))
e.modify(r1, select.EPOLLIN)
os.write(w1, b"x")
print("modified to IN", nm(e.poll(0)))
os.read(r1, 10)

# EPOLLHUP after the writer closes
os.close(w1)
print("hup", nm(e.poll(1)))
e.unregister(r1)
os.close(r1)

# data and then close: IN|HUP
r2, w2 = pipe("p2")
e.register(r2, select.EPOLLIN)
os.write(w2, b"zz")
os.close(w2)
print("data then hup", nm(e.poll(1)))
e.unregister(r2)
os.close(r2)

# the write end whose reader is gone: EPOLLERR (and OUT when requested)
r3, w3 = pipe("p3")
e.register(w3, select.EPOLLOUT)
print("writable", nm(e.poll(0)))
os.close(r3)
print("reader gone", nm(e.poll(0)))
e.unregister(w3)
os.close(w3)

# EPOLLONESHOT reports once; modify re-arms
r4, w4 = pipe("p4")
e.register(r4, select.EPOLLIN | select.EPOLLONESHOT)
os.write(w4, b"1")
print("oneshot 1", nm(e.poll(1)))
print("oneshot 2", nm(e.poll(0)))
e.modify(r4, select.EPOLLIN | select.EPOLLONESHOT)
print("oneshot rearmed", nm(e.poll(0)))
e.unregister(r4)

# edge-triggered: reported once per new data
e.register(r4, select.EPOLLIN | select.EPOLLET)
print("edge first", nm(e.poll(0)))
print("edge second", nm(e.poll(0)))
os.write(w4, b"2")
print("edge new data", nm(e.poll(0)))
e.unregister(r4)
os.read(r4, 10)

# ---- several descriptors in one poll: the 12-byte packed event ------------------------------------------------------
pairs = [pipe("m%d" % i) for i in range(5)]
for r, w in pairs:
    e.register(r, select.EPOLLIN)
for r, w in pairs[1::2]:
    os.write(w, b"x")
print("two ready", nm(e.poll(1)))
for r, w in pairs[::2]:
    os.write(w, b"x")
print("five ready", nm(e.poll(1)))
print("five ready, bigger maxevents", nm(e.poll(1, 100)))
got = e.poll(1, 1)
print("maxevents=1", len(got), got[0][1])
got = e.poll(1, maxevents=2)
print("maxevents=2", len(got))
print("event types", [(type(fd).__name__, type(ev).__name__) for fd, ev in e.poll(0)][:1])
for r, w in pairs:
    e.unregister(r)
    os.close(r)
    os.close(w)

# a descriptor number that does not fit a signed byte is carried through the event data
r5, w5 = os.pipe()
d = os.dup2(r5, 300)
e.register(d, select.EPOLLIN)
os.write(w5, b"x")
print("fd 300", e.poll(1) == [(300, select.EPOLLIN)])
e.unregister(d)
for fd in (r5, w5, d):
    os.close(fd)

# ---- timeouts ---------------------------------------------------------------------------------------------------------
r6, w6 = pipe("p6")
e.register(r6, select.EPOLLIN)
t0 = time.monotonic()
print("timeout 0.2", e.poll(0.2), time.monotonic() - t0 >= 0.19, time.monotonic() - t0 < 1.5)
t0 = time.monotonic()
print("timeout 1ms", e.poll(0.001), time.monotonic() - t0 < 1.0)


def later(d, fd):
    time.sleep(d)
    os.write(fd, b"x")


th = threading.Thread(target=later, args=(0.05, w6))
th.start()
t0 = time.monotonic()
print("early", nm(e.poll(5)), time.monotonic() - t0 < 2.0)
th.join()
os.read(r6, 10)
th = threading.Thread(target=later, args=(0.05, w6))
th.start()
t0 = time.monotonic()
print("None blocks", nm(e.poll()), time.monotonic() - t0 < 2.0, time.monotonic() - t0 >= 0.04)
th.join()
os.read(r6, 10)
th = threading.Thread(target=later, args=(0.05, w6))
th.start()
t0 = time.monotonic()
print("negative blocks", nm(e.poll(-1)), time.monotonic() - t0 < 2.0, time.monotonic() - t0 >= 0.04)
th.join()
os.read(r6, 10)

# ---- signals ----------------------------------------------------------------------------------------------------------
calls = []
signal.signal(signal.SIGALRM, lambda s, f: calls.append(s))
signal.setitimer(signal.ITIMER_REAL, 0.05)
t0 = time.monotonic()
print("handler returns", e.poll(0.4), time.monotonic() - t0 >= 0.39, len(calls))


def boom(s, f):
    raise TimeoutError("alarm")


signal.signal(signal.SIGALRM, boom)
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
try:
    e.poll(5)
except TimeoutError as x:
    print("poll raised", x, time.monotonic() - t0 < 2.0)
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
try:
    e.poll()
except TimeoutError as x:
    print("poll forever raised", x, time.monotonic() - t0 < 2.0)
for neg in (-1e-9, -0.0005, -0.001, -0.5):        # whole milliseconds rounded up: -0.0005 s is 0 ms, -0.001 s waits for ever
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    t0 = time.monotonic()
    try:
        print("poll", neg, "returned", e.poll(neg), time.monotonic() - t0 < 0.09)
    except TimeoutError as x:
        print("poll", neg, "raised", x, time.monotonic() - t0 < 2.0)
t("poll 3e6", e.poll, 3e6)
t("poll -3e6", e.poll, -3e6)
t("poll 2**31", e.poll, 2**31)
t("poll -3e12", e.poll, -3e12)
t("poll maxevents 2**31", e.poll, 0, 2**31)
signal.setitimer(signal.ITIMER_REAL, 0)
signal.signal(signal.SIGALRM, signal.SIG_DFL)
t("usable after interrupt", e.poll, 0)
e.unregister(r6)
os.close(r6)
os.close(w6)

# ---- closed object ----------------------------------------------------------------------------------------------------
c = select.epoll()
cf = c.fileno()
print("close", c.close(), c.closed, "again", c.close())
d = select.epoll()
os.close(d.fileno())
t("close after os.close", d.close)               # 3.13 and 3.14 ignore the failing close, 3.15 raises
print("closed", d.closed)
try:
    os.fstat(cf)
    print("descriptor still open")
except OSError as x:
    print("descriptor closed", x.errno)
t("closed fileno", c.fileno)
t("closed poll", c.poll, 0)
t("closed register", c.register, 0)
t("closed modify", c.modify, 0, 1)
t("closed unregister", c.unregister, 0)
t("closed poll('x')", c.poll, "x")
t("closed poll(0, 'x')", c.poll, 0, "x")
t("closed poll(0, 0)", c.poll, 0, 0)
t("closed poll(maxevents='x')", c.poll, 0, "x")
t("closed register('x')", c.register, "x")
t("closed register(-1)", c.register, -1)
t("closed register(0, 'x')", c.register, 0, "x")
with warnings.catch_warnings(record=True) as ws:
    warnings.simplefilter("always")
    t("closed register(0, 2**32)", c.register, 0, 2**32)
    print("warnings", [(x.category.__name__, str(x.message)) for x in ws])
t("closed modify('x', 1)", c.modify, "x", 1)
t("closed modify(0, 'x')", c.modify, 0, "x")
t("closed unregister('x')", c.unregister, "x")
t("closed __enter__", c.__enter__)
t("closed __exit__", c.__exit__, None, None, None)
print("closed repr", repr(c).startswith("<select.epoll object at"))

# ---- context manager ----------------------------------------------------------------------------------------------------
with select.epoll() as cm:
    cmf = cm.fileno()
    print("with", cm.closed, type(cm).__name__)
print("after with", cm.closed)
try:
    os.fstat(cmf)
    print("with: descriptor still open")
except OSError as x:
    print("with: descriptor closed", x.errno)
try:
    with select.epoll() as cm2:
        raise KeyError("in with")
except KeyError as x:
    print("with raised", x.args, cm2.closed)

# ---- fromfd -------------------------------------------------------------------------------------------------------------
e2 = select.epoll()
r7, w7 = pipe("p7")
e2.register(r7, select.EPOLLIN)
os.write(w7, b"x")
dup = os.dup(e2.fileno())
shared = select.epoll.fromfd(dup)
print("fromfd", type(shared).__name__, shared.fileno() == dup, shared.closed)
print("shared registrations", nm(shared.poll(1)))
shared.close()
print("fromfd closed the number", shared.closed, end=" ")
try:
    os.fstat(dup)
    print("still open")
except OSError as x:
    print("closed", x.errno)
print("original works", nm(e2.poll(0)))
t("fromfd('x')", select.epoll.fromfd, "x")
t("fromfd(9999)", lambda: select.epoll.fromfd(9999).poll(0))
y = select.epoll.fromfd(-1)          # -1 asks for a new epoll descriptor
print("fromfd(-1)", y.fileno() > 2, y.closed, os.get_inheritable(y.fileno()), y.poll(0))
y.close()
t("fromfd(r7) poll", lambda: select.epoll.fromfd(os.dup(r7)).poll(0))
e2.unregister(r7)
e2.close()


def subclass():
    class Sub(select.epoll):
        pass


t("subclass", subclass)

# ---- selectors ----------------------------------------------------------------------------------------------------------
print("default selector", selectors.DefaultSelector().__class__.__name__)
r8, w8 = pipe("p8")
with selectors.DefaultSelector() as sel:
    key = sel.register(r8, selectors.EVENT_READ, "payload")
    print("selector empty", sel.select(0))
    os.write(w8, b"x")
    got = sel.select(1)
    print("selector ready", [(k.data, ev) for k, ev in got], got[0][0] is key)
    sel.modify(r8, selectors.EVENT_READ, "other")
    print("selector modified", [(k.data, ev) for k, ev in sel.select(1)])
    sel.unregister(r8)
    print("selector unregistered", sel.select(0))
    try:
        sel.unregister(r8)
    except KeyError as x:
        print("selector unregister again KeyError")
os.close(r8)
os.close(w8)

# ---- an epoll object that is dropped without close() is collected and its descriptor closed -----------------------------
fds = []
for _ in range(5):
    x = select.epoll()
    fds.append(x.fileno())
    del x
closed = False
for _ in range(100):
    gc.collect()
    time.sleep(0.05)
    open_fds = []
    for fd in fds:
        try:
            os.fstat(fd)
            open_fds.append(fd)
        except OSError:
            pass
    if not open_fds:
        closed = True
        break
print("collected epoll closed its descriptor", closed)

e.close()
print("done")
