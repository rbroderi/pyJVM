# pyjvm: requires-python >= 3.14
# BytesIO, StringIO and IncrementalNewlineDecoder: semantics, error messages and newline handling.
import _io


def show(label, fn):
    try:
        r = fn()
    except BaseException as e:
        print(label, type(e).__name__, e)
    else:
        print(label, repr(r))


b = _io.BytesIO(b"line1\nline2\r\nlast")
show("bytes read", lambda: (b.read(3), b.tell(), b.readline(), b.readline(2), b.readline(), b.readline(), b.tell()))
show("bytes seek", lambda: (b.seek(0), b.seek(2, 1), b.seek(-2, 2), b.read(), b.seek(100), b.read(), b.tell()))
show("bytes seek neg", lambda: b.seek(-1))
show("bytes seek whence", lambda: b.seek(0, 3))
show("bytes seek float", lambda: b.seek(1.5))
b = _io.BytesIO()
show("bytes write", lambda: (b.write(b"abc"), b.write(bytearray(b"de")), b.write(memoryview(b"f")), b.getvalue()))
show("bytes write str", lambda: b.write("x"))
show("bytes write pad", lambda: (b.seek(10), b.write(b"Z"), b.getvalue()))
show("bytes truncate", lambda: (b.truncate(4), b.getvalue(), b.tell(), b.truncate(), b.getvalue()))
show("bytes truncate neg", lambda: b.truncate(-1))
show("bytes lines", lambda: (b.seek(0), b.writelines([b"a\n", b"b\n", b"c"]), b.seek(0), b.readlines(), b.seek(0), b.readlines(3)))
show("bytes iter", lambda: (b.seek(0), list(b)))
ba = bytearray(4)
b = _io.BytesIO(b"123456")
show("bytes readinto", lambda: (b.readinto(ba), ba, b.readinto(ba), ba, b.readinto(ba)))
show("bytes flags", lambda: (b.readable(), b.writable(), b.seekable(), b.isatty(), b.closed))
buf = b.getbuffer()
show("bytes getbuffer", lambda: (bytes(buf), len(buf)))
buf.release() if hasattr(buf, "release") else None
b.close()
b.close()
show("bytes closed", lambda: (b.closed, b.readable()))
show("bytes closed read", lambda: b.read())
show("bytes closed getvalue", lambda: b.getvalue())
show("bytes closed ctx", lambda: b.__enter__())
show("bytes init type", lambda: _io.BytesIO("str"))
with _io.BytesIO(b"ctx") as c:
    show("bytes ctx", lambda: c.read())
show("bytes ctx closed", lambda: c.closed)
show("bytes state", lambda: _io.BytesIO(b"abc").__getstate__())
show("bytes read1", lambda: (_io.BytesIO(b"abcdef").read1(2), _io.BytesIO(b"abcdef").read1()))
show("bytes read none", lambda: _io.BytesIO(b"abc").read(None))
show("bytes read -1", lambda: _io.BytesIO(b"abc").read(-5))


class MyBytes(_io.BytesIO):
    def extra(self):
        return self.getvalue().upper()


mb = MyBytes(b"sub")
show("bytes subclass", lambda: (mb.extra(), isinstance(mb, _io.BufferedIOBase), isinstance(mb, _io.IOBase)))

# ---- StringIO ----
s = _io.StringIO("alpha\nbeta\ngamma")
show("sio read", lambda: (s.read(3), s.tell(), s.readline(), s.readline(2), s.readline(), s.readline(), s.tell()))
show("sio seek", lambda: (s.seek(0), s.read(), s.seek(0, 2), s.seek(2), s.seek(0, 1)))
show("sio seek cur", lambda: s.seek(1, 1))
show("sio seek end", lambda: s.seek(1, 2))
show("sio seek neg", lambda: s.seek(-1))
show("sio seek whence", lambda: s.seek(0, 5))
s = _io.StringIO()
show("sio write", lambda: (s.write("héllo"), s.write("😀"), s.getvalue(), s.tell(), len(s.getvalue())))
show("sio write bytes", lambda: s.write(b"x"))
show("sio write int", lambda: s.write(5))
show("sio pad", lambda: (s.seek(10), s.write("!"), repr(s.getvalue())))
show("sio truncate", lambda: (s.truncate(3), s.getvalue(), s.tell(), s.truncate()))
show("sio truncate neg", lambda: s.truncate(-2))
show("sio lines", lambda: (s.seek(0), s.truncate(0), s.writelines(["a\n", "b\n"]), s.seek(0), s.readlines(), list(iter(s.readline, ""))))
show("sio flags", lambda: (s.readable(), s.writable(), s.seekable(), s.isatty(), s.closed, s.line_buffering, s.encoding, s.errors))
show("sio newlines default", lambda: (_io.StringIO("a\r\nb").read(), _io.StringIO("a\r\nb").newlines))
for nl in (None, "", "\n", "\r", "\r\n"):
    x = _io.StringIO(newline=nl)
    show("sio nl write %r" % (nl,), lambda: (x.write("a\nb\r\nc\rd"), repr(x.getvalue()), x.newlines))
    x.seek(0)
    show("sio nl lines %r" % (nl,), lambda: x.readlines())
    y = _io.StringIO("a\nb\r\nc\rd", newline=nl)
    show("sio nl init %r" % (nl,), lambda: (repr(y.getvalue()), y.readlines(), y.newlines))
show("sio bad newline", lambda: _io.StringIO(newline="x"))
show("sio bad newline type", lambda: _io.StringIO(newline=1))
show("sio bad init", lambda: _io.StringIO(5))
show("sio bad init bytes", lambda: _io.StringIO(b"x"))
s = _io.StringIO("closing")
s.close()
show("sio closed", lambda: (s.closed,))
show("sio closed read", lambda: s.read())
show("sio closed getvalue", lambda: s.getvalue())
show("sio closed write", lambda: s.write("x"))
show("sio iter", lambda: list(_io.StringIO("a\nb\nc")))
show("sio detach", lambda: _io.StringIO("x").detach())
show("sio readline size", lambda: (_io.StringIO("abc\ndef").readline(0), _io.StringIO("abc\ndef").readline(-1), _io.StringIO("abc\ndef").readline(None)))
show("sio state", lambda: _io.StringIO("abc", newline="\r").__getstate__()[:3])
show("sio astral", lambda: (_io.StringIO("a😀b").read(2), _io.StringIO("a😀b").seek(2), len(_io.StringIO("a😀b").read())))

# ---- IncrementalNewlineDecoder ----
for translate in (True, False):
    d = _io.IncrementalNewlineDecoder(None, translate)
    show("ind %s" % translate, lambda: (d.decode("a\r"), d.getstate(), d.decode("\nb\r\nc\r", final=False), d.newlines, d.decode("", final=True), d.newlines))
    d.reset()
    show("ind reset %s" % translate, lambda: (d.newlines, d.getstate()))
    d.setstate((b"", 1))
    show("ind setstate %s" % translate, lambda: (d.decode("x", final=True), d.getstate()))
import codecs
dd = _io.IncrementalNewlineDecoder(codecs.getincrementaldecoder("utf-8")(), True)
show("ind utf8", lambda: (dd.decode(b"a\xc3"), dd.getstate(), dd.decode(b"\xa9\r\n", final=True), dd.newlines))
show("ind bad input", lambda: _io.IncrementalNewlineDecoder(None, True).decode(b"x"))
