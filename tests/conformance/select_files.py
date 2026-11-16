# SPDX-License-Identifier: GPL-3.0-or-later
import os
import select

import time


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)


import sys

if sys.platform == 'win32':
    # Windows: select() takes sockets only (no poll/epoll/PIPE_BUF/POLL*); errors are WinErrors.
    def w(f, *a, **k):
        try:
            print(repr(f(*a, **k)))
        except BaseException as e:
            print(type(e).__name__, e.args[0], getattr(e, 'winerror', None), e)

    print(sorted(n for n in dir(select) if not n.startswith('__')))
    print(select.error is OSError, hasattr(select, 'poll'), hasattr(select, 'epoll'), hasattr(select, 'PIPE_BUF'), hasattr(select, 'POLLIN'))
    w(select.select, [], [], [], 0)     # WSAStartup has not run: no socket module imported yet
    import socket
    w(select.select, [], [], [], 0)
    w(select.select, [], [], [], 0.05)
    w(select.select, [], [], [], None)
    w(select.select, [-1], [], [], 0)
    w(select.select, [0], [], [], 0)
    w(select.select, [], [1], [], 0)
    w(select.select, [], [], [2], 0)
    w(select.select, ['x'], [], [], 0)
    w(select.select, 5, [], [], 0)
    w(select.select, [], [], [], -1)
    w(select.select, [], [], [], 'a')
    w(select.select, [], [], [], 0, 1)
    w(select.select, [])
    w(select.select, [0] * 513, [], [], 0)

    class Obj:
        def fileno(self):
            return 7

    w(select.select, [Obj()], [], [], 0)
    raise SystemExit(0)

print(select.select([], [], [], 0))
t(select.select, [-1], [], [], 0)
t(select.select, ['x'], [], [], 0)
t(select.select, 5, [], [], 0)
t(select.select, [], [], [], -1)
t(select.select, [], [], [], 'a')
t(select.select, [], [], [], 0, 1)
t(select.select, [])
t0 = time.monotonic()
print(select.select([], [], [], 0.05), time.monotonic() - t0 >= 0.04)
path = os.path.join('/tmp', 'pyjvm_select_%d.txt' % os.getpid())
with open(path, 'wb') as f:
    f.write(b'data')
f = open(path, 'rb')
print(select.select([f], [f], [f], 0) == ([f], [f], []))
print(select.select([f.fileno()], [], [], 0) == ([f.fileno()], [], []))


class Obj:
    def fileno(self):
        return f.fileno()


o = Obj()
print(select.select([o], [o], [], 0) == ([o], [o], []))
print(select.select([f, f], [], [], None) == ([f, f], [], []))
p = select.poll()
p.register(f, select.POLLIN | select.POLLOUT)
print(p.poll(0) == [(f.fileno(), select.POLLIN | select.POLLOUT)])
p.modify(f, select.POLLIN)
print(p.poll(0) == [(f.fileno(), select.POLLIN)], p.poll(1) == [(f.fileno(), select.POLLIN)])
p.unregister(f)
print(p.poll(0))
try:
    p.unregister(f)
except KeyError as e:
    print('KeyError', type(e.args[0]).__name__)
t(p.modify, f, 1)
t(p.register, 'x')
t(p.poll, 'x')
q = select.poll()
q.register(f.fileno())
print(q.poll(0) == [(f.fileno(), select.POLLIN | select.POLLOUT)])
f.close()
os.remove(path)
print(type(p).__name__, select.error is OSError, select.POLLIN, select.POLLOUT, select.POLLERR, select.POLLHUP, select.POLLNVAL, select.POLLPRI, select.PIPE_BUF)
print(hasattr(select, 'poll'), sorted(n for n in dir(select) if n.startswith('POLL')))
t(select.poll, 1)
