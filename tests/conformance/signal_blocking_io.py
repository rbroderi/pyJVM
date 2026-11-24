# SPDX-License-Identifier: GPL-3.0-or-later
# Signals around blocking descriptor I/O on the main thread (PEP 475): a handler runs while os.read waits on a pipe, a
# handler that raises interrupts a read or a write before anything was transferred, a handler that returns lets the call
# go on, and signal.set_wakeup_fd on POSIX. POSIX only (kernel descriptors; PYJVM_POSIX_FDS=nio has no pipe).
import io
import os
import signal
import threading
import time

print(os.name)
if os.name == "nt":
    raise SystemExit(0)


def fill(w):
    """Fills the pipe whose write end is ``w`` (left blocking again)."""
    os.set_blocking(w, False)
    for chunk in (65536, 1):
        try:
            while True:
                os.write(w, b"x" * chunk)
        except BlockingIOError:
            pass
    os.set_blocking(w, True)


def alarm_raises(num, frame):
    raise TimeoutError("alarm")


def interrupted(label, f):
    """Runs ``f`` with a 0.2 s ITIMER_REAL whose handler raises; prints what came out of ``f``."""
    signal.signal(signal.SIGALRM, alarm_raises)
    signal.setitimer(signal.ITIMER_REAL, 0.2)
    t0 = time.monotonic()
    try:
        r = f()
    except TimeoutError as e:
        print(label, "TimeoutError", e, time.monotonic() - t0 >= 0.15)
    else:
        print(label, "returned", repr(r))
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)


# (a) another thread signals the main thread while it waits in os.read; the handler writes to the pipe, so after the
# handler ran the read returns that data
r, w = os.pipe()
got = []


def on_usr1(num, frame):
    got.append(num)
    os.write(w, b"from handler")


signal.signal(signal.SIGUSR1, on_usr1)


def kick():
    time.sleep(0.2)
    signal.pthread_kill(threading.main_thread().ident, signal.SIGUSR1)


th = threading.Thread(target=kick)
th.start()
print("a", os.read(r, 100), got == [signal.SIGUSR1])
th.join()

# (b) a handler that raises interrupts os.read on an empty pipe, a buffered read through io.open, and os.write on a full
# blocking pipe; nothing was read or written, so the pipes are unchanged afterwards
interrupted("b os.read", lambda: os.read(r, 1))
r2, w2 = os.pipe()
f2 = io.open(r2, "rb")
interrupted("b io.open.read", lambda: f2.read(1))
os.write(w2, b"later")
print("b after", f2.read(5))
f2.close()
os.close(w2)
r3, w3 = os.pipe()
fill(w3)
interrupted("b os.write full", lambda: os.write(w3, b"y"))
os.set_blocking(r3, False)
total = 0
try:
    while True:
        chunk = os.read(r3, 65536)
        if b"y" in chunk:
            print("b stray byte")
        total += len(chunk)
except BlockingIOError:
    pass
print("b drained", total > 0)
os.close(r3)
os.close(w3)

# (c) a handler that returns: the read goes on and returns the data that arrives later
hits = []
signal.signal(signal.SIGALRM, lambda n, f: hits.append(n))
signal.setitimer(signal.ITIMER_REAL, 0.1)


def late():
    time.sleep(0.4)
    os.write(w, b"late data")


th = threading.Thread(target=late)
th.start()
t0 = time.monotonic()
print("c", os.read(r, 100), hits == [signal.SIGALRM], time.monotonic() - t0 >= 0.3)
th.join()
signal.setitimer(signal.ITIMER_REAL, 0)

# (d) set_wakeup_fd: a blocking descriptor is refused, a bad one is EBADF; on a non-blocking pipe every signal with a
# Python handler writes its number
r4, w4 = os.pipe()
try:
    signal.set_wakeup_fd(w4)
except ValueError as e:
    print("d blocking", str(e).replace(str(w4), "W"))
try:
    signal.set_wakeup_fd(9999)
except OSError as e:
    print("d bad fd", type(e).__name__, e.errno)
os.set_blocking(w4, False)
signal.signal(signal.SIGUSR1, lambda n, f: got.append(n))
print("d first", signal.set_wakeup_fd(w4))
signal.raise_signal(signal.SIGUSR1)
signal.raise_signal(signal.SIGUSR1)
print("d byte", os.read(r4, 10) == bytes([int(signal.SIGUSR1)] * 2))
signal.signal(signal.SIGUSR1, signal.SIG_IGN)
signal.raise_signal(signal.SIGUSR1)
os.set_blocking(r4, False)
try:
    print("d ignored", os.read(r4, 10))
except BlockingIOError:
    print("d ignored nothing written")
print("d previous", signal.set_wakeup_fd(-1) == w4, signal.set_wakeup_fd(-1))
os.close(r4)
os.close(w4)

# (e) a non-blocking descriptor on the main thread with a handler installed: BlockingIOError at once, no waiting
r5, w5 = os.pipe()
os.set_blocking(r5, False)
signal.signal(signal.SIGUSR1, on_usr1)
for attempt in range(2):          # the first one warms the error path up; the second is timed
    t0 = time.monotonic()
    try:
        os.read(r5, 10)
    except BlockingIOError as e:
        took, err = time.monotonic() - t0, e.errno
print("e", err, took < 0.05)
for fd in (r, w, r5, w5):
    os.close(fd)
