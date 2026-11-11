# SPDX-License-Identifier: GPL-3.0-or-later
import zlib


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)


def lcg(n, seed=12345):
    out = bytearray()
    x = seed
    for _ in range(n):
        x = (x * 1103515245 + 12345) & 0x7fffffff
        out.append(97 + (x >> 16) % 7)
    return bytes(out)


data = lcg(70000) + bytes(range(256)) * 50 + b'tail' * 1000
for level in (-1, 0, 1, 5, 9):
    z = zlib.compress(data, level)
    print(level, len(z), zlib.crc32(z), zlib.decompress(z) == data)
for wb in (15, -15, 31, 9, -9, 25):
    z = zlib.compress(data, 6, wb)
    print(wb, zlib.decompress(z, wb) == data, zlib.decompressobj(wb).decompress(z) == data)
print(zlib.decompress(zlib.compress(data, wbits=31), 47) == data, zlib.decompress(zlib.compress(data), 47) == data)
print(zlib.decompress(zlib.compress(data, wbits=31), 15 | 32) == data)

# streaming compression in odd chunk sizes with flush modes
c = zlib.compressobj(6)
parts = []
for i in range(0, len(data), 777):
    parts.append(c.compress(data[i:i + 777]))
    if i % 7 == 0:
        parts.append(c.flush(zlib.Z_SYNC_FLUSH))
    if i % 11 == 0:
        parts.append(c.flush(zlib.Z_FULL_FLUSH))
parts.append(c.flush())
blob = b''.join(parts)
print(zlib.decompress(blob) == data, blob[-8:] == zlib.compress(b'')[-4:] or True)
print(zlib.crc32(blob) == zlib.crc32(blob), zlib.adler32(data) == int.from_bytes(blob[-4:], 'big'))

# streaming decompression: byte-at-a-time, max_length and unconsumed_tail
z = zlib.compress(data)
d = zlib.decompressobj()
out = bytearray()
for i in range(0, len(z), 1):
    out += d.decompress(z[i:i + 1])
out += d.flush()
print(bytes(out) == data, d.eof, d.unused_data, d.unconsumed_tail)
d = zlib.decompressobj()
out = bytearray()
pending = z + b'EXTRA'
while pending and not d.eof:
    out += d.decompress(pending, 1000)
    pending = d.unconsumed_tail
print(bytes(out) == data, d.eof, d.unused_data, d.unconsumed_tail)
d = zlib.decompressobj()
first = d.decompress(z, 10)
print(len(first), len(d.unconsumed_tail) > 0, d.eof)
print(len(d.flush()) + len(first), d.eof)

# copy
c = zlib.compressobj()
head = c.compress(data[:5000])
c2 = c.copy()
print(c.compress(data[5000:]) + c.flush() == c2.compress(data[5000:]) + c2.flush())
d = zlib.decompressobj()
d.decompress(z[:100])
d2 = d.copy()
print(d.decompress(z[100:]) == d2.decompress(z[100:]))
import copy
print(copy.copy(zlib.compressobj()).compress(b'a') == zlib.compressobj().compress(b'a'))
print(copy.deepcopy(zlib.decompressobj()).decompress(z) == data)

# gzip framing
c = zlib.compressobj(9, zlib.DEFLATED, 31)
g = c.compress(b'hello gzip') + c.flush()
print(g[:4], g[8:10], len(g), zlib.decompress(g, 31))
c = zlib.compressobj(1, zlib.DEFLATED, 31)
g1 = c.compress(b'x') + c.flush()
print(g1[:10])
g = zlib.compress(b'abc' * 10, wbits=31)
t(zlib.decompress, g[:-1], 31)
t(zlib.decompress, g[:-8] + b'\0' * 8, 31)
t(zlib.decompress, g[:-4] + b'\0' * 4, 31)
t(zlib.decompress, b'\x1f\x8b\x07' + g[3:], 31)
t(zlib.decompress, b'\x1f\x8c' + g[2:], 31)
d = zlib.decompressobj(31)
r = d.decompress(g + b'trailing')
print(r, d.eof, d.unused_data)
hdr = bytes([0x1f, 0x8b, 8, 0x1e, 0, 0, 0, 0, 0, 3]) + (b'\x03\x00abc') + b'name\0' + b'comment\0'
import struct
body = zlib.compressobj(6, zlib.DEFLATED, -15)
raw = body.compress(b'payload') + body.flush()
fhdr = hdr
fhdr += struct.pack('<H', zlib.crc32(fhdr) & 0xffff)
trailer = struct.pack('<II', zlib.crc32(b'payload'), 7)
print(zlib.decompress(fhdr + raw + trailer, 31))
d = zlib.decompressobj(31)
whole = fhdr + raw + trailer
got = b''.join(d.decompress(whole[i:i + 1]) for i in range(len(whole)))
print(got, d.eof)
t(zlib.decompress, fhdr[:-2] + b'\0\0' + raw + trailer, 31)

# auto-detect with split headers
d = zlib.decompressobj(47)
print(d.decompress(z[:1]), d.decompress(z[1:]) == data)
d = zlib.decompressobj(47)
gz = zlib.compress(b'q' * 100, wbits=31)
print(d.decompress(gz[:1]), d.decompress(gz[1:]), d.eof)

# _ZlibDecompressor
D = zlib._ZlibDecompressor()
out = b''
src = z + b'zzz'
while not D.eof:
    out += D.decompress(b'', 500) if not D.needs_input else D.decompress(src[:300], 500)
    if D.needs_input:
        src = src[300:]
    if not src and not D.eof and D.needs_input:
        break
print(out == data, D.eof, D.unused_data, D.needs_input)
D = zlib._ZlibDecompressor(-15)
print(D.decompress(raw), D.eof)
D = zlib._ZlibDecompressor(31)
print(D.decompress(g[:7]), D.needs_input, D.decompress(g[7:]), D.eof, D.needs_input)
t(zlib._ZlibDecompressor, 3)

# raw streams and dictionaries
zd = b'common prefix words dictionary'
c = zlib.compressobj(wbits=-15, zdict=zd)
r = c.compress(b'common prefix words') + c.flush()
d = zlib.decompressobj(-15, zdict=zd)
print(d.decompress(r), len(r) < 25)
c = zlib.compressobj(zdict=zd)
r = c.compress(b'common prefix words dictionary common') + c.flush()
print(zlib.decompressobj(zdict=zd).decompress(r))
d = zlib.decompressobj()
t(d.decompress, r)

# argument handling
t(zlib.compress, b'x', level=9)
t(zlib.compress, b'x', 1, wbits=-15)
t(zlib.decompress, z, wbits=15, bufsize=1)
t(zlib.decompress, z[:20])
print(type(zlib.compressobj(level=1, wbits=-15, strategy=zlib.Z_FILTERED)).__name__)
for strat in (zlib.Z_DEFAULT_STRATEGY, zlib.Z_FILTERED, zlib.Z_HUFFMAN_ONLY):
    c = zlib.compressobj(6, zlib.DEFLATED, 15, 8, strat)
    print(strat, zlib.decompress(c.compress(data) + c.flush()) == data)
t(zlib.compressobj().compress, 5)
t(zlib.decompressobj().decompress, 'x')
t(zlib.decompressobj().decompress, b'x', max_length=-3)
t(zlib.crc32, b'x', 'y')
print(zlib.crc32(bytearray(b'abc')), zlib.crc32(memoryview(b'abc')), zlib.adler32(bytearray(b'abc')))
print(zlib.crc32(data, 0xffffffff), zlib.adler32(data, 0xffff), zlib.crc32(data[:10000]) == zlib.crc32(data[:10000], 0))
print(zlib.crc32(data[5000:], zlib.crc32(data[:5000])) == zlib.crc32(data), zlib.adler32(data[5000:], zlib.adler32(data[:5000])) == zlib.adler32(data))
print(zlib.adler32(b'\xff' * 100000), zlib.crc32(b'\xff' * 100000, 12345))
