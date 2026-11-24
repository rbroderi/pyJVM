# SPDX-License-Identifier: GPL-3.0-or-later
# select.select (select(2)) and select.poll (poll(2)) on kernel descriptors: readiness of pipes (data, hangup, write end
# whose reader is gone), closed descriptors, the 1024 limit, timeouts (sleeping, returning early, not shortened by a
# signal handler that returns, interrupted by one that raises), poll registration order, masks and their per-lane
# errors, objects with fileno(). POSIX only (kernel descriptors; PYJVM_POSIX_FDS=nio has no pipe).
import os
import select
import signal
import sys
import threading
import time

print(os.name)
if os.name == "nt":
    raise SystemExit(0)

NEW = sys.version_info >= (3, 15)
names = {}


def t(label, f, *a):
    try:
        print(label, repr(f(*a)))
    except BaseException as e:
        print(label, type(e).__name__, e.args)


def pipe(name):
    r, w = os.pipe()
    names[r] = name + ".r"
    names[w] = name + ".w"
    return r, w


class F:
    """An object with fileno(); select hands back the very objects it was given."""

    def __init__(self, fd, label):
        self.fd = fd
        self.label = label

    def fileno(self):
        return self.fd

    def __repr__(self):
        return "<" + self.label + ">"


def sel(r, w, x, timeout=0):
    """select.select with the descriptor numbers in the answer replaced by names."""
    return [[names.get(f, f) for f in lst] for lst in select.select(r, w, x, timeout)]


def nm(res):
    """A poll result with the descriptor numbers replaced by names."""
    return [(names.get(fd, fd), ev) for fd, ev in res]


print("consts", [(n, getattr(select, n)) for n in sorted(dir(select)) if n.startswith("POLL") or n == "PIPE_BUF"])
print("error", select.error is OSError, select.poll.__module__, select.poll.__name__)

# ---- pipes: data, readiness classes
r, w = pipe("a")
R, W = F(r, "R"), F(w, "W")
t("empty pipe", select.select, [R], [W], [W, R], 0)
os.write(w, b"hello")
t("with data", select.select, [R, W], [R, W], [], 0)
t("duplicates", sel, [R, R, r], [W, w], [], 0)
t("bool timeout", select.select, [R], [], [], True)
t("int timeout", select.select, [R], [], [], 0)
t("tuple args", select.select, (R,), (W,), (), 0.0)
t("iter args", select.select, iter([R]), iter([W]), iter([]), 0)
os.read(r, 100)

# ---- hangup: writer closed
r2, w2 = pipe("b")
os.write(w2, b"x")
os.close(w2)
t("hup data select", sel, [r2], [], [r2], 0)
p = select.poll()
p.register(r2)
t("hup data poll", lambda: nm(p.poll(0)))
os.read(r2, 10)
t("hup drained select", sel, [r2], [], [r2], 0)
t("hup drained poll", lambda: nm(p.poll(0)))

# ---- write end whose reader is gone
r3, w3 = pipe("c")
os.close(r3)
t("broken select", sel, [], [w3], [w3], 0)
p3 = select.poll()
p3.register(w3, select.POLLOUT)
t("broken poll", lambda: nm(p3.poll(0)))
p3.modify(w3, select.POLLIN)
t("broken poll in only", lambda: nm(p3.poll(0)))

# ---- closed descriptor, descriptor limits
r4, w4 = pipe("d")
os.close(r4)
os.close(w4)
t("closed select", lambda: sel([r4], [], [], 0))
t("closed select w", lambda: sel([], [w4], [], 0))
t("closed select x", lambda: sel([], [], [r4], 0))
p4 = select.poll()
p4.register(r4)
t("closed poll", lambda: nm(p4.poll(0)))
t("limit 1024", select.select, [1024], [], [], 0)
t("limit 1024 w", select.select, [], [1024], [], 0)
t("limit 1024 x", select.select, [], [], [1024], 0)
t("limit obj", select.select, [F(5000, "far")], [], [], 0)
t("limit 2**31", select.select, [2**31], [], [], 0)
t("limit 2**70", select.select, [2**70], [], [], 0)
t("negative fd", select.select, [-1], [], [], 0)
t("limit first", select.select, [1024, -1], [], [], 0)
r5, w5 = pipe("e")
os.write(w5, b"edge")
top = os.dup2(r5, 1023)
t("fd 1023", lambda: (select.select([top], [], [], 0) == ([top], [], [])))
p5 = select.poll()
p5.register(top, select.POLLIN)
t("poll 1023", lambda: p5.poll(0))
os.close(top)

# ---- argument errors
t("kw", lambda: select.select([], [], [], timeout=0))
t("two args", select.select, [], [])
t("five args", select.select, [], [], [], 0, 1)
t("not seq", select.select, 5, [], [], 0)
t("not seq 2", select.select, [], 5, [], 0)
t("not seq 3", select.select, [], [], 5, 0)
t("str item", select.select, ["x"], [], [], 0)
t("float item", select.select, [1.5], [], [], 0)
t("none item", select.select, [None], [], [], 0)
t("timeout str", select.select, [], [], [], "a")
t("timeout neg", select.select, [], [], [], -1)
t("timeout neg float", select.select, [], [], [], -0.5)
t("timeout neg zero", select.select, [], [], [], -0.0)
t("timeout nan", select.select, [], [], [], float("nan"))
t("timeout inf", select.select, [], [], [], float("inf"))
t("timeout huge", select.select, [], [], [], 1e100)
t("timeout huge int", select.select, [], [], [], 2**70)


class NoFileno:
    pass


t("no fileno", select.select, [NoFileno()], [], [], 0)

# ---- timing (bools with wide margins)
t0 = time.monotonic()
res = select.select([], [], [], 0.1)
dt = time.monotonic() - t0
print("sleep", res, dt >= 0.1, dt < 1.5)

t0 = time.monotonic()
res = select.select([], [], [], 0)
print("zero", res, time.monotonic() - t0 < 0.5)

r6, w6 = pipe("f")
threading.Timer(0.05, os.write, (w6, b"x")).start()
t0 = time.monotonic()
res = select.select([r6], [], [], 0.2)
dt = time.monotonic() - t0
print("early", res == ([r6], [], []), dt < 0.19)
os.read(r6, 10)

t0 = time.monotonic()
res = select.select([r6], [], [], 0.15)
dt = time.monotonic() - t0
print("idle", res, dt >= 0.15, dt < 1.5)

t0 = time.monotonic()
res = select.select([r6], [], [], 0.1 + 0.2 / 1e7)
print("fraction", res, time.monotonic() - t0 >= 0.1)

# ---- poll: order, masks, results
pa, pb, pc = pipe("p1"), pipe("p2"), pipe("p3")
pl = select.poll()
for x in (pc, pa, pb):
    pl.register(x[0])
for x in (pb, pc, pa):
    os.write(x[1], b"z")
t("order", lambda: nm(pl.poll(0)))
pl.register(pa[0], select.POLLIN | select.POLLOUT)
t("re-register keeps position", lambda: nm(pl.poll(0)))
pl.modify(pc[0], select.POLLOUT)
t("modify to POLLOUT only", lambda: nm(pl.poll(0)))
pl.unregister(pc[0])
t("unregistered", lambda: nm(pl.poll(0)))
pl.register(pc[0], select.POLLIN)
t("re-registered goes last", lambda: nm(pl.poll(0)))
pl.register(pa[1], select.POLLOUT)
pl.register(pb[1])
t("write ends", lambda: nm(pl.poll(0)))
t("poll none arg", lambda: nm(pl.poll(None)))
t("poll kw", lambda: pl.poll(timeout=0))
t("poll two args", lambda: pl.poll(1, 2))
t("poll float", lambda: nm(pl.poll(0.5)))
t("poll true", lambda: nm(pl.poll(True)))
t("poll negative", lambda: nm(pl.poll(-1)))
t("poll negative float", lambda: nm(pl.poll(-0.5)))
t("poll 2**31-1", lambda: nm(pl.poll(2**31 - 1)))
t("poll 2**31", lambda: nm(pl.poll(2**31)))
t("poll 2**40", lambda: nm(pl.poll(2**40)))
t("poll str", pl.poll, "a")
t("poll nan", pl.poll, float("nan"))
t("poll inf", pl.poll, float("inf"))
t("poll huge", pl.poll, 1e100)

empty = select.poll()
t0 = time.monotonic()
res = empty.poll(120.5)
dt = time.monotonic() - t0
print("poll empty set sleeps", res, dt >= 0.12, dt < 1.5)
t0 = time.monotonic()
res = empty.poll(0)
print("poll zero", res, time.monotonic() - t0 < 0.5)

pr, pw = pipe("q")
ps = select.poll()
ps.register(pr)
threading.Timer(0.05, os.write, (pw, b"x")).start()
t0 = time.monotonic()
res = nm(ps.poll(1000))
print("poll early", res, time.monotonic() - t0 < 0.9)
os.read(pr, 10)
t0 = time.monotonic()
res = ps.poll(0.15 * 1000)
dt = time.monotonic() - t0
print("poll idle", res, dt >= 0.15, dt < 1.5)

# ---- poll: registration errors and masks
t("register mask > 65535", pl.register, pa[0], 70000)
t("register mask 65536", pl.register, pa[0], 65536)
t("register mask 2**40", pl.register, pa[0], 2**40)
t("register mask -1", pl.register, pa[0], -1)
t("register mask 65535", pl.register, pa[0], 65535)
t("register mask float", pl.register, pa[0], 1.5)
t("register mask str", pl.register, pa[0], "a")
t("register mask None", pl.register, pa[0], None)
t("modify mask 65536", pl.modify, pa[0], 65536)
t("modify mask -1", pl.modify, pa[0], -1)
t("modify unregistered", pl.modify, 4000, 1)
t("modify unregistered bad mask", pl.modify, 4000, -1)
t("unregister unknown", pl.unregister, 4000)


def unregister_twice():
    pl.unregister(pc[0])
    try:
        pl.unregister(pc[0])
    except KeyError as e:
        return "KeyError", e.args == (pc[0],)


t("unregister twice", unregister_twice)
t("register negative fd", pl.register, -1, 1)
t("register fd 2**31", pl.register, 2**31, 1)
t("register str", pl.register, "x", 1)
t("register float", pl.register, 1.5, 1)
t("unregister str", pl.unregister, "x")
t("unregister negative", pl.unregister, -3)

# ---- poll: invalid descriptor, objects with fileno(), regular device
pn = select.poll()
pn.register(r4, select.POLLIN)
pn.register(r5, select.POLLIN)
t("nval and ready", lambda: nm(pn.poll(0)))
po = select.poll()
po.register(F(r5, "O"), select.POLLIN)
t("fileno object", lambda: nm(po.poll(0)))
po.unregister(F(r5, "O2"))
t("unregistered via object", lambda: po.poll(0))
nul = os.open(os.devnull, os.O_RDWR)
names[nul] = "null"
pz = select.poll()
pz.register(nul, select.POLLIN | select.POLLOUT | select.POLLPRI)
t("device", lambda: nm(pz.poll(0)))
pz.modify(nul, select.POLLOUT)
t("device out", lambda: nm(pz.poll(0)))
t("device select", sel, [nul], [nul], [nul], 0)
os.close(nul)

# ---- poll object
t("poll(1)", select.poll, 1)
t("poll(a=1)", lambda: select.poll(a=1))
print("poll type", type(pl).__name__, type(pl).__module__, repr(pl).startswith("<select.poll object at"))
print("methods", [n for n in dir(pl) if not n.startswith("_")])

# ---- signals: a handler that returns does not shorten the timeout; one that raises interrupts the wait
hits = []


def returns(num, frame):
    hits.append(num)


def raises(num, frame):
    raise TimeoutError("alarm")


signal.signal(signal.SIGALRM, returns)
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
res = select.select([], [], [], 0.4)
dt = time.monotonic() - t0
print("handler returns select", res, hits, dt >= 0.39, dt < 2.0)

del hits[:]
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
res = select.poll().poll(400)
dt = time.monotonic() - t0
print("handler returns poll", res, hits, dt >= 0.39, dt < 2.0)

del hits[:]
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
res = select.select([r6], [], [], 0.4)
dt = time.monotonic() - t0
print("handler returns idle pipe", res, hits, dt >= 0.39)

signal.signal(signal.SIGALRM, raises)
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
try:
    select.select([r6], [], [], 5)
except TimeoutError as e:
    print("select raised", e, time.monotonic() - t0 < 2.0)
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
try:
    select.select([r6], [], [])
except TimeoutError as e:
    print("select forever raised", e, time.monotonic() - t0 < 2.0)
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
try:
    ps.poll(5000)
except TimeoutError as e:
    print("poll raised", e, time.monotonic() - t0 < 2.0)
signal.setitimer(signal.ITIMER_REAL, 0.1)
t0 = time.monotonic()
try:
    ps.poll()
except TimeoutError as e:
    print("poll forever raised", e, time.monotonic() - t0 < 2.0)
for neg in (-0.5, -1e-9):                      # a negative fraction rounds away from zero: no timeout, not zero
    signal.setitimer(signal.ITIMER_REAL, 0.1)
    t0 = time.monotonic()
    try:
        print("poll", neg, "returned", ps.poll(neg))
    except TimeoutError as e:
        print("poll", neg, "raised", e, time.monotonic() - t0 < 2.0)
signal.setitimer(signal.ITIMER_REAL, 0)
signal.signal(signal.SIGALRM, signal.SIG_DFL)
if sys.version_info >= (3, 15):
    print("poll -2**40 is for ever on 3.15")
else:
    t("poll -2**40", ps.poll, -2**40)

# one poll object waited on from two threads
pc = select.poll()
pc.register(r6, select.POLLIN)
started = threading.Event()
def waiter():
    started.set()
    pc.poll(1000)
th = threading.Thread(target=waiter)
th.start()
started.wait()
time.sleep(0.2)
t("concurrent poll", pc.poll, 0)
th.join()

# the wait is still usable after an interrupted one
t("after interrupt", select.select, [], [W], [], 0)
print("done")
