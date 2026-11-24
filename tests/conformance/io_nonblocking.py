# SPDX-License-Identifier: GPL-3.0-or-later
# Non-blocking descriptors under _io: FileIO returns None when the call would block, the buffered layer tells "would
# block" from end of file (read() -> None, read1/readline/peek -> b''), a BufferedWriter over a full pipe raises
# BlockingIOError with characters_written and keeps the bytes it accepted, and TextIOWrapper reads ''. POSIX only
# (kernel descriptors; PYJVM_POSIX_FDS=nio has no pipe).
import io
import os

print(os.name)
if os.name == "nt":
    raise SystemExit(0)


def nb_pipe():
    r, w = os.pipe()
    os.set_blocking(r, False)
    os.set_blocking(w, False)
    return r, w


def fill(w):
    """Fills the non-blocking pipe whose write end is ``w``; returns the byte count written."""
    total = 0
    for chunk in (65536, 4096, 1):
        try:
            while True:
                total += os.write(w, b"x" * chunk)
        except BlockingIOError:
            pass
    return total


def drain(r):
    out = bytearray()
    while True:
        try:
            d = os.read(r, 65536)
        except BlockingIOError:
            return bytes(out)
        if not d:
            return bytes(out)
        out += d


def show_error(label, call):
    try:
        print(label, "->", repr(call()))
    except BlockingIOError as e:
        cw = getattr(e, "characters_written", "absent")
        print(label, "->", type(e).__name__, e.errno, repr(e.strerror), "characters_written", cw, "args", e.args)
    except OSError as e:
        print(label, "->", type(e).__name__, e.errno)


# ---- FileIO ----
r, w = nb_pipe()
raw = io.FileIO(r, "r", closefd=False)
print("FileIO.read(10)", repr(raw.read(10)))
print("FileIO.readall()", repr(raw.readall()))
buf = bytearray(8)
print("FileIO.readinto", repr(raw.readinto(buf)), bytes(buf))
wraw = io.FileIO(w, "w", closefd=False)
print("FileIO.write partial", wraw.write(b"p" * 70000), "of 70000")
print("FileIO.write full", repr(wraw.write(b"q" * 10)))
print("drained", len(drain(r)))
os.write(w, b"abc")
print("FileIO.readall() partial", repr(raw.readall()))
raw.close(); wraw.close()
os.close(r); os.close(w)

# ---- BufferedReader ----
r, w = nb_pipe()
br = io.BufferedReader(io.FileIO(r, "r", closefd=False))
print("BufferedReader.read(10)", repr(br.read(10)))
print("BufferedReader.read()", repr(br.read()))
buf = bytearray(8)
print("BufferedReader.readinto", repr(br.readinto(buf)))
print("BufferedReader.readinto1", repr(br.readinto1(buf)))
print("BufferedReader.read1(10)", repr(br.read1(10)))
print("BufferedReader.readline()", repr(br.readline()))
print("BufferedReader.peek(1)", repr(br.peek(1)))
os.write(w, b"abc")
print("BufferedReader.read(10) with 3", repr(br.read(10)))
os.write(w, b"abc")
print("BufferedReader.read() with 3", repr(br.read()))
os.write(w, b"abc")
print("BufferedReader.readline() with 3", repr(br.readline()))
os.write(w, b"abc")
buf = bytearray(8)
print("BufferedReader.readinto with 3", repr(br.readinto(buf)), bytes(buf[:3]))
os.close(w)
print("BufferedReader.read() at EOF", repr(br.read()))
print("BufferedReader.read(10) at EOF", repr(br.read(10)))
br.close(); os.close(r)

# ---- TextIOWrapper ----
r, w = nb_pipe()
t = io.TextIOWrapper(io.BufferedReader(io.FileIO(r, "r", closefd=False)), encoding="ascii")
print("TextIOWrapper.read(5)", repr(t.read(5)))
print("TextIOWrapper.readline()", repr(t.readline()))
t.close(); os.close(r); os.close(w)

# ---- BufferedWriter over a full pipe ----
r, w = nb_pipe()
filled = fill(w)
bw = io.BufferedWriter(io.FileIO(w, "w", closefd=False), 16)
show_error("BufferedWriter.write(8)", lambda: bw.write(b"12345678"))
show_error("BufferedWriter.write(25)", lambda: bw.write(b"ABCDEFGHIJKLMNOPQRSTUVWXY"))
show_error("BufferedWriter.flush()", bw.flush)
show_error("BufferedWriter.write(40) buffer full", lambda: bw.write(b"z" * 40))
got = drain(r)
print("drained", len(got) == filled, set(got) == {ord("x")})
show_error("BufferedWriter.flush() after drain", bw.flush)
print("pipe content", repr(drain(r)))
bw.close(); os.close(r); os.close(w)

# ---- BufferedWriter: a large write that blocks part way (the pipe takes 65536 bytes) ----
r, w = nb_pipe()
bw = io.BufferedWriter(io.FileIO(w, "w", closefd=False), 16)
data = bytes(i % 251 for i in range(70000))
show_error("BufferedWriter.write(70000)", lambda: bw.write(data))
got = drain(r)
show_error("BufferedWriter.flush() after drain", bw.flush)
got += drain(r)
print("pipe content", len(got), got == data[:len(got)])
bw.close(); os.close(r); os.close(w)

# ---- os.write ----
r, w = nb_pipe()
fill(w)
show_error("os.write full", lambda: os.write(w, b"z"))
os.close(r); os.close(w)

# ---- print through a line-buffered TextIOWrapper over a full pipe: raises instead of spinning ----
r, w = nb_pipe()
filled = fill(w)
tw = io.TextIOWrapper(io.BufferedWriter(io.FileIO(w, "w", closefd=False), 16), encoding="ascii", line_buffering=True)
show_error("print to full pipe", lambda: print("hi", file=tw))
print("drained", len(drain(r)) == filled)
tw.close()
print("pipe content", repr(drain(r)))
os.close(r); os.close(w)
