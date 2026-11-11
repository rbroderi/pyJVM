# FileIO, Buffered*, TextIOWrapper and open(): behaviour on real files in a temp directory.
import _io
import os



def show(label, fn):
    try:
        r = fn()
    except BaseException as e:
        print(label, type(e).__name__, str(e).replace(D, "<D>"))
    else:
        print(label, repr(r).replace(D, "<D>"))


D = "/tmp/pyjvm_io_files_conf"
os.makedirs(D, exist_ok=True)
P = D + "/f.bin"

# ---- FileIO ----
f = _io.FileIO(P, "w")
show("fio write", lambda: (f.write(b"hello\nworld\n"), f.tell(), f.seekable(), f.readable(), f.writable(), f.mode, f.closefd, f.name == P))
show("fio read on w", lambda: f.read())
f.close()
show("fio closed", lambda: (f.closed, f.close()))
show("fio closed op", lambda: f.write(b"x"))
f = _io.FileIO(P, "r+")
show("fio rw", lambda: (f.read(3), f.seek(0, 2), f.write(b"!"), f.seek(0), f.readall(), f.readinto(bytearray(4)), f.mode))
show("fio truncate", lambda: (f.truncate(5), f.seek(0), f.read(), f.truncate(), f.tell()))
show("fio readinto", lambda: (f.seek(0), f.readinto(bytearray(3))))
show("fio readinto bad", lambda: f.readinto(b"abc"))
show("fio write str", lambda: f.write("x"))
show("fio seek bad", lambda: f.seek(-100))
show("fio fileno", lambda: isinstance(f.fileno(), int))
show("fio isatty", lambda: f.isatty())
f.close()
f = _io.FileIO(P, "a")
show("fio append", lambda: (f.write(b"Z"), f.tell()))
f.close()
show("fio read all", lambda: open(P, "rb").read())
show("fio x exists", lambda: _io.FileIO(P, "x"))
show("fio missing", lambda: _io.FileIO(D + "/nope", "r"))
show("fio dir", lambda: _io.FileIO(D, "r"))
show("fio bad mode", lambda: _io.FileIO(P, "z"))
show("fio bad mode2", lambda: _io.FileIO(P, "rw"))
show("fio no mode", lambda: _io.FileIO(P, ""))
show("fio closefd", lambda: _io.FileIO(P, "r", closefd=False).name == P)
fd = os.open(P, os.O_RDONLY)
g = _io.FileIO(fd, "r", closefd=False)
show("fio fd", lambda: (g.fileno() == fd, g.read(2), g.name == fd, repr(g).startswith("<_io.FileIO name=")))
g.close()
os.close(fd)
g = _io.FileIO(P)
show("fio repr", lambda: repr(g).replace(D, "<D>"))
g.close()
show("fio repr closed", lambda: repr(g).replace(D, "<D>"))
show("fio bytes path", lambda: _io.FileIO(P.encode()).read(2))
show("fio bad type", lambda: _io.FileIO(1.5))
show("fio neg fd", lambda: _io.FileIO(-1))

# ---- open() argument checks ----
show("open bad mode", lambda: open(P, "rz"))
show("open rb enc", lambda: open(P, "rb", encoding="utf-8"))
show("open rb errors", lambda: open(P, "rb", errors="strict"))
show("open rb newline", lambda: open(P, "rb", newline=""))
show("open rt+b", lambda: open(P, "rtb"))
show("open no mode kind", lambda: open(P, "+"))
show("open unbuffered text", lambda: open(P, "r", buffering=0))
show("open bad file", lambda: open(None))
show("open bad newline", lambda: open(P, newline="x"))
show("open bad buffering", lambda: open(P, buffering="a"))
show("open unknown kw", lambda: open(P, foo=1))
show("open missing", lambda: open(D + "/missing"))
show("open dir", lambda: open(D))
show("open unknown encoding", lambda: open(P, encoding="nonesuch"))
show("open unknown errors", lambda: open(P, "w", errors="nonesuch"))
show("open types", lambda: (type(open(P, "rb", buffering=0)).__name__, type(open(P, "rb")).__name__, type(open(P, "wb")).__name__,
                            type(open(P, "r+b")).__name__, type(open(P, "r")).__name__))
show("open opener", lambda: open(P, "rb", opener=lambda path, flags: os.open(path, flags)).read(1))
show("open opener bad", lambda: open(P, "rb", opener=lambda path, flags: -5))
show("open int fd", lambda: open(os.open(P, os.O_RDONLY), "rb").read(2))

# ---- Buffered ----
with open(P, "wb") as w:
    show("bw", lambda: (w.write(b"0123456789"), w.tell(), w.writable(), w.readable(), w.seekable(), w.name == P, w.mode, w.raw.__class__.__name__))
    show("bw seek", lambda: (w.seek(2), w.write(b"AB"), w.tell(), w.flush()))
    show("bw read", lambda: w.read())
    show("bw truncate", lambda: (w.truncate(8), w.tell()))
with open(P, "rb") as r:
    show("br", lambda: (r.read(2), r.peek(3)[:3], r.read1(2), r.tell(), r.readline(), r.read(), r.read(), r.tell()))
    show("br seek", lambda: (r.seek(1), r.read(2), r.seek(-2, 1), r.read(1), r.seek(-1, 2), r.read(), r.seek(0)))
    show("br readinto", lambda: (r.readinto(bytearray(3)), r.readinto1(bytearray(3)), r.tell()))
    show("br write", lambda: r.write(b"x"))
    show("br iter", lambda: (r.seek(0), list(r)))
    show("br bad seek", lambda: r.seek(-100))
    show("br bad whence", lambda: r.seek(0, 9))
    show("br detach", lambda: type(r.raw).__name__)
show("br closed", lambda: (r.closed, r.read()))
with open(P, "w+b") as rw:
    show("brw", lambda: (rw.write(b"abcdef"), rw.seek(1), rw.read(2), rw.write(b"XY"), rw.seek(0), rw.read(), type(rw).__name__))
    show("brw trunc", lambda: (rw.truncate(3), rw.seek(0), rw.read()))
    show("brw peek", lambda: (rw.seek(0), rw.peek(1)))
raw = _io.FileIO(P, "r")
br = _io.BufferedReader(raw, 4)
show("br small", lambda: (br.read(3), br.peek(), br.read(), br.detach() is raw, raw.closed))
show("br detached", lambda: br.read())
raw.close()
show("br bad size", lambda: _io.BufferedReader(_io.FileIO(P), 0))
show("br not readable", lambda: _io.BufferedReader(_io.FileIO(P, "a")))
show("bw not writable", lambda: _io.BufferedWriter(_io.FileIO(P, "r")))
pair = _io.BufferedRWPair(_io.BytesIO(b"in"), _io.BytesIO())
show("pair", lambda: (pair.read(1), pair.write(b"out"), pair.readable(), pair.writable(), pair.seekable(), pair.isatty()))
show("pair flush", lambda: (pair.flush(), pair.close(), pair.closed))
bio = _io.BytesIO(b"over bytesio\nline2")
bb = _io.BufferedReader(bio)
show("br on bytesio", lambda: (bb.readline(), bb.peek(2)[:2], bb.read(), bb.tell()))
sink = _io.BytesIO()
bw2 = _io.BufferedWriter(sink, 4)
show("bw on bytesio", lambda: (bw2.write(b"ab"), sink.getvalue(), bw2.write(b"cdef"), sink.getvalue(), bw2.flush(), sink.getvalue()))
show("bw close flush", lambda: (bw2.write(b"!"), bw2.close(), sink.getvalue(), sink.closed))
show("bw repr", lambda: repr(_io.BufferedWriter(_io.BytesIO())).replace("0x", "")[:20])

# ---- TextIOWrapper ----
T = D + "/t.txt"
with open(T, "w", encoding="utf-8") as t:
    show("tw write", lambda: (t.write("héllo\n"), t.write("日本\r\n"), t.write("x\r"), t.tell() > 0, t.encoding, t.errors, t.newlines, t.name == T, t.mode, t.line_buffering, t.writable(), t.readable()))
    show("tw read", lambda: t.read())
with open(T, "rb") as t:
    show("tw bytes", lambda: t.read())
with open(T, encoding="utf-8") as t:
    show("tw universal", lambda: (t.readline(), t.readline(), t.read(), t.newlines))
with open(T, encoding="utf-8", newline="") as t:
    show("tw nl ''", lambda: (t.readlines(), t.newlines))
with open(T, encoding="utf-8", newline="\n") as t:
    show("tw nl LF", lambda: (t.readlines(), t.newlines))
with open(T, encoding="utf-8", newline="\r\n") as t:
    show("tw nl CRLF", lambda: (t.readlines(), t.newlines))
with open(T, "w", encoding="utf-8", newline="\r\n") as t:
    t.write("a\nb\n")
with open(T, "rb") as t:
    show("tw write crlf", lambda: t.read())
with open(T, "w", encoding="utf-8", newline="") as t:
    t.write("a\nb\r\n")
with open(T, "rb") as t:
    show("tw write ''", lambda: t.read())
with open(T, "w", encoding="latin-1") as t:
    show("tw latin1", lambda: (t.write("é"), t.encoding))
    show("tw latin1 bad", lambda: t.write("日"))
with open(T, "rb") as t:
    show("tw latin1 bytes", lambda: t.read())
with open(T, "w", encoding="ascii", errors="replace") as t:
    t.write("aé")
with open(T, "rb") as t:
    show("tw ascii replace", lambda: t.read())
with open(T, "w", encoding="utf-16") as t:
    t.write("ab")
    t.write("c")
with open(T, "rb") as t:
    show("tw utf16 bytes", lambda: t.read())
with open(T, encoding="utf-16") as t:
    show("tw utf16 read", lambda: t.read())
with open(T, "wb") as t:
    t.write(b"ok\xffbad\n")
with open(T, encoding="utf-8") as t:
    show("tw decode err", lambda: t.read())
with open(T, encoding="utf-8", errors="replace") as t:
    show("tw decode replace", lambda: t.read())
with open(T, encoding="utf-8", errors="backslashreplace") as t:
    show("tw decode bsr", lambda: t.read())
with open(T, "w", encoding="utf-8") as t:
    t.write("one\ntwo\nthree\nfour\n")
with open(T, encoding="utf-8") as t:
    show("tw tell/seek", lambda: (t.readline(), t.tell(), t.readline(), t.seek(4), t.readline(), t.seek(0), t.read(3), t.tell()))
    cookie = None
    def mark():
        global cookie
        t.seek(0)
        t.readline()
        cookie = t.tell()
        return cookie
    show("tw cookie", lambda: (mark(), t.read(), t.seek(cookie), t.readline()))
    show("tw seek end", lambda: (t.seek(0, 2), t.read(), t.seek(0, 1)))
    show("tw seek bad", lambda: t.seek(1, 1))
    show("tw seek bad2", lambda: t.seek(-1))
    show("tw iter", lambda: (t.seek(0), [x for x in t]))
    show("tw tell in iter", lambda: (t.seek(0), next(t), t.tell()))
    show("tw read size", lambda: (t.seek(0), t.read(0), t.read(2), t.read(-1)))
    show("tw readline size", lambda: (t.seek(0), t.readline(2), t.readline(100), t.readline(0)))
    show("tw write on r", lambda: t.write("x"))
    show("tw truncate on r", lambda: t.truncate())
with open(T, "w", encoding="utf-8") as t:
    show("tw read on w", lambda: t.read())
    show("tw write bytes", lambda: t.write(b"x"))
    show("tw truncate", lambda: (t.write("abcdef"), t.truncate(3), t.tell()))
with open(T, "a", encoding="utf-8") as t:
    show("tw append", lambda: (t.write("Z"), t.tell()))
with open(T, "r+", encoding="utf-8") as t:
    show("tw r+", lambda: (t.read(), t.write("!"), t.seek(0), t.read()))
with open(T, "w", encoding="utf-8", buffering=1) as t:
    show("tw linebuf", lambda: (t.line_buffering, t.write("a\n"), open(T).read()))
    show("tw flush", lambda: (t.write("b"), t.flush(), open(T).read()))
with open(T, "w", encoding="utf-8") as t:
    show("tw reconfigure", lambda: (t.reconfigure(line_buffering=True), t.line_buffering, t.reconfigure(newline="\r\n"), t.write("x\n")))
    show("tw reconfigure enc", lambda: t.reconfigure(encoding="latin-1"))
    show("tw reconfigure bad", lambda: t.reconfigure(foo=1))
    show("tw writelines", lambda: t.writelines(["p", "q"]))
show("tw closed", lambda: (t.closed,))
dt = _io.TextIOWrapper(_io.BytesIO(), encoding="utf-8")
show("tw detach", lambda: (type(dt.detach()).__name__,))
show("tw detached", lambda: dt.write("x"))
show("tw detached close", lambda: dt.closed)
tw = _io.TextIOWrapper(_io.BytesIO(b"caf\xc3\xa9\nx"), encoding="utf-8")
show("tw bytesio", lambda: (tw.readline(), tw.read(), tw.buffer.tell(), tw.seekable(), tw.fileno() if False else None))
show("tw bytesio fileno", lambda: tw.fileno())
tw = _io.TextIOWrapper(_io.BytesIO(), encoding="utf-8", write_through=True)
show("tw through", lambda: (tw.write("abc"), tw.buffer.getvalue(), tw.write_through))
show("tw bad newline", lambda: _io.TextIOWrapper(_io.BytesIO(), "utf-8", newline="q"))
show("tw bad buffer", lambda: _io.TextIOWrapper("str"))
show("tw bad enc", lambda: _io.TextIOWrapper(_io.BytesIO(), "nonesuch"))
show("tw repr", lambda: repr(_io.TextIOWrapper(_io.BytesIO(), "utf-8")).split(" at ")[0])
show("tw repr file", lambda: repr(open(P, "r", encoding="utf-8")).replace(D, "<D>"))
show("tw large", lambda: len(_io.TextIOWrapper(_io.BytesIO(("é" * 20000 + "\n").encode()), "utf-8").read()))
show("tw chunks", lambda: [len(x) for x in _io.TextIOWrapper(_io.BytesIO(("abc\n" * 5000).encode()), "utf-8").readlines()][:3])

# ---- os-level cleanup ----
for n in os.listdir(D):
    os.remove(D + "/" + n)
os.rmdir(D)
show("cleanup", lambda: os.path.exists(D))
