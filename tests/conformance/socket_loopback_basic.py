# SPDX-License-Identifier: GPL-3.0-or-later
import os

# Real Winsock sockets on Windows (_socket, select.select on sockets). Windows only: the POSIX runtime has no sockets yet,
# so on POSIX both interpreters print just the first line.
print(os.name)

if os.name == "nt":
    import _socket
    import select
    import socket
    import time

    def w(label, f, *a, **k):
        try:
            print(label, repr(f(*a, **k)))
        except BaseException as e:
            print(label, type(e).__name__, getattr(e, "winerror", None), getattr(e, "errno", None), str(e)[:60])

    print(socket.AF_INET, socket.AF_INET6, socket.SOCK_STREAM, socket.SOCK_DGRAM, socket.SOL_SOCKET, socket.SO_REUSEADDR,
          socket.SO_ERROR, socket.SO_TYPE, socket.SOMAXCONN, socket.IPPROTO_TCP, socket.TCP_NODELAY, socket.has_ipv6)
    print(socket.AddressFamily.AF_INET6, socket.SocketKind.SOCK_STREAM, socket.AF_UNSPEC, socket.SHUT_RDWR, socket.MSG_PEEK)
    print(hasattr(socket, "AF_UNIX"), hasattr(socket, "SO_REUSEPORT"), hasattr(socket, "SOCK_NONBLOCK"), hasattr(socket, "MSG_DONTWAIT"))
    print(hasattr(_socket, "getaddrinfo") or True, socket.timeout is TimeoutError, socket.error is OSError, _socket.socket is _socket.SocketType)
    print(socket.htons(1), socket.ntohs(0x0100), socket.htonl(1), socket.ntohl(1), socket.getdefaulttimeout())

    a, b = socket.socketpair()
    print(type(a).__name__, a.family, a.type, a.proto, a.gettimeout(), a.getblocking(), a.fileno() != b.fileno(), a.fileno() > 0)
    print(a.getsockname()[0], a.getpeername()[0], a.getsockname() == b.getpeername(), a.getpeername() == b.getsockname())
    print(a.send(b"hello"), b.recv(100))
    a.sendall(b"abc" * 3)
    time.sleep(0.05)
    buf = bytearray(20)
    n = b.recv_into(buf)
    print(n, bytes(buf[:n]))
    b.send(b"xyz")
    time.sleep(0.05)
    print(a.recv(2), a.recv(10))
    w("recv negative", a.recv, -1)

    # non-blocking and timeouts
    b.setblocking(False)
    print(b.gettimeout(), b.getblocking())
    w("recv would block", b.recv, 10)
    b.settimeout(0.05)
    print(b.gettimeout(), b.getblocking())
    t0 = time.monotonic()
    w("recv timeout", b.recv, 10)
    print(time.monotonic() - t0 >= 0.04)
    b.settimeout(None)
    print(b.gettimeout(), b.getblocking())
    w("settimeout -1", b.settimeout, -1)
    w("settimeout nan", b.settimeout, float("nan"))
    w("settimeout str", b.settimeout, "x")
    b.settimeout(0)
    print(b.gettimeout(), b.getblocking())
    b.setblocking(True)

    # select on sockets
    print(select.select([a], [], [], 0))
    r, wr, x = select.select([a, b], [a, b], [], 0)
    print(len(r), len(wr), x)
    a.send(b"!")
    time.sleep(0.05)
    r, wr, x = select.select([a, b], [], [], 1.0)
    print(r == [b], wr, x)
    print(b.recv(5))
    w("select empty", select.select, [], [], [], 0)
    w("select non-socket", select.select, [0], [], [], 0)
    t0 = time.monotonic()
    print(select.select([a], [], [], 0.1), time.monotonic() - t0 >= 0.09)

    # options and shutdown
    print(a.getsockopt(socket.SOL_SOCKET, socket.SO_TYPE), a.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR))
    a.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    print(a.getsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR) != 0, len(a.getsockopt(socket.SOL_SOCKET, socket.SO_TYPE, 8)) <= 8)
    a.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(a.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY) != 0)
    w("getsockopt buflen", a.getsockopt, socket.SOL_SOCKET, socket.SO_TYPE, 0)
    a.shutdown(socket.SHUT_WR)
    print(b.recv(10))
    w("send after shutdown", a.send, b"x")
    w("shutdown bad", a.shutdown, 7)

    # close, detach, fileno
    fd = b.fileno()
    d = b.detach()
    print(d == fd, b.fileno())
    w("recv detached", b.recv, 1)
    b.close()
    w("getsockname closed", b.getsockname)
    a.close()
    a.close()
    print(a.fileno())
    w("send closed", a.send, b"x")
    w("settimeout closed", a.settimeout, 1)
    s2 = socket.socket(fileno=d)
    print(s2.family, s2.type, s2.proto, s2.fileno() == d)
    s2.close()

    # listening, accept, connect
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen()
    host, port = srv.getsockname()
    print(host, port > 0, srv.family == socket.AF_INET)
    srv.setblocking(False)
    w("accept would block", srv.accept)
    srv.settimeout(0.05)
    w("accept timeout", srv.accept)
    srv.setblocking(True)
    cli = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    cli.connect((host, port))
    conn, addr = srv.accept()
    print(addr == cli.getsockname(), conn.getpeername() == cli.getsockname(), conn.family, conn.type, conn.gettimeout())
    cli.sendall(b"ping")
    print(conn.recv(10))
    conn.close()
    time.sleep(0.05)
    print(cli.recv(10))
    cli.close()
    w("bind in use", socket.socket().bind, (host, port))
    w("bind str", srv.bind, "x")
    w("bind port", srv.bind, ("127.0.0.1", 70000))
    w("bind pair", srv.bind, ("127.0.0.1",))
    w("bind host int", srv.bind, (1, 0))
    srv.close()
    refused = socket.socket()
    refused.bind(("127.0.0.1", 0))
    rport = refused.getsockname()[1]
    refused.close()
    c = socket.socket()
    w("connect refused", c.connect, ("127.0.0.1", rport))
    c.close()
    c = socket.socket()
    c.setblocking(False)
    print(c.connect_ex(("127.0.0.1", rport)))
    c.close()
    w("not connected getpeername", socket.socket().getpeername)
    w("not connected send", socket.socket().send, b"x")
    w("recv not connected", socket.socket().recv, 1)

    # IPv6 loopback, if the machine has it
    try:
        s6 = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
        s6.bind(("::1", 0))
        name = s6.getsockname()
        print(len(name), name[0], name[2:], s6.family)
        s6.close()
    except OSError as e:
        print("no ipv6", e.winerror)
    w("socket family", socket.socket, 12345)
    s = socket.socket()
    print(repr(s).startswith("<socket.socket fd="), "family=2" in repr(s) or "family=AddressFamily.AF_INET" in repr(s))
    s.close()
    print("closed" in repr(s))
