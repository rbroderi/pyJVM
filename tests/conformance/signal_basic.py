# SPDX-License-Identifier: GPL-3.0-or-later
import os
import signal
import time


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)


print(*map(int, (signal.SIGINT, signal.SIGTERM, signal.SIGKILL, signal.SIGUSR1, signal.SIGALRM, signal.SIGCHLD, signal.SIGRTMIN, signal.SIGRTMAX)), signal.NSIG)
print(repr(signal.SIGINT), signal.SIGINT.name, int(signal.SIGHUP), repr(signal.Signals(15)), signal.Signals.SIGPIPE.value)
print(int(signal.SIG_DFL), int(signal.SIG_IGN), repr(signal.SIG_IGN), int(signal.Handlers.SIG_DFL), int(signal.Sigmasks.SIG_BLOCK), signal.ITIMER_REAL)
print(signal.getsignal(signal.SIGINT) is signal.default_int_handler, int(signal.getsignal(signal.SIGTERM)), int(signal.getsignal(signal.SIGPIPE)))
print(signal.strsignal(signal.SIGINT), signal.strsignal(signal.SIGTERM), signal.strsignal(signal.SIGSEGV), signal.strsignal(34), signal.strsignal(64), signal.strsignal(32))
print(sorted(signal.valid_signals())[:5], len(signal.valid_signals()), signal.SIGKILL in signal.valid_signals())
t(signal.strsignal, 0)
t(signal.strsignal, 65)
t(signal.signal, 65, signal.SIG_IGN)
t(signal.signal, 0, signal.SIG_IGN)
t(signal.signal, signal.SIGKILL, signal.SIG_IGN)
t(signal.signal, signal.SIGUSR1, 'x')
t(signal.signal, signal.SIGUSR1, 5)
t(signal.getsignal, 99)

seen = []


def handler(num, frame):
    seen.append(num)


old = signal.signal(signal.SIGUSR1, handler)
print(int(old), signal.getsignal(signal.SIGUSR1) is handler)
signal.raise_signal(signal.SIGUSR1)
os.kill(os.getpid(), signal.SIGUSR1)
print(seen)
signal.signal(signal.SIGUSR1, signal.SIG_IGN)
signal.raise_signal(signal.SIGUSR1)
print(seen, int(signal.getsignal(signal.SIGUSR1)))
t(signal.default_int_handler, 2, None)
signal.signal(signal.SIGINT, signal.default_int_handler)
t(signal.raise_signal, signal.SIGINT)
signal.signal(signal.SIGUSR2, lambda n, f: 1 / 0)
t(signal.raise_signal, signal.SIGUSR2)

# alarm and sleep: the handler runs while sleeping and sleep keeps going
hits = []
signal.signal(signal.SIGALRM, lambda n, f: hits.append(n))
print(signal.alarm(100), signal.alarm(0))
signal.alarm(1)
t0 = time.monotonic()
time.sleep(1.4)
print(hits, time.monotonic() - t0 >= 1.3)
print(signal.alarm(0))

# interval timers
print(signal.getitimer(signal.ITIMER_REAL))
print(signal.setitimer(signal.ITIMER_REAL, 0.2, 0.2))
time.sleep(0.9)
old = signal.setitimer(signal.ITIMER_REAL, 0)
print(len(hits) >= 3, old[1], signal.getitimer(signal.ITIMER_REAL))
try:
    signal.setitimer(9, 1)
except signal.ItimerError as e:
    print(type(e).__name__, e.args)

# masks
signal.signal(signal.SIGUSR1, handler)
print(signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1]))
seen.clear()
signal.raise_signal(signal.SIGUSR1)
print(seen, signal.sigpending())
print(signal.pthread_sigmask(signal.SIG_BLOCK, []))
print(int(signal.sigwait([signal.SIGUSR1])), signal.sigpending())
signal.raise_signal(signal.SIGUSR1)
print(signal.pthread_sigmask(signal.SIG_UNBLOCK, [signal.SIGUSR1]), seen)
print(signal.pthread_sigmask(signal.SIG_SETMASK, []))
t(signal.pthread_sigmask, 9, [])
t(signal.pthread_sigmask, signal.SIG_BLOCK, [0])
t(signal.pthread_sigmask, signal.SIG_BLOCK, [65])
t(signal.set_wakeup_fd, -1)
t(signal.siginterrupt, signal.SIGUSR1, False)
t(signal.siginterrupt, 0, False)
t(os.kill, os.getpid(), 0)
t(os.kill, os.getpid(), 65)
print(sorted(n for n in dir(signal) if n.startswith(('pthread', 'sig', 'set', 'get', 'raise', 'alarm', 'pause', 'valid', 'strsig')) and n not in ('sigtimedwait', 'sigwaitinfo')))

try:
    signal.signal(signal.SIGUSR1, None)
except TypeError as e:
    print("TypeError", e)
