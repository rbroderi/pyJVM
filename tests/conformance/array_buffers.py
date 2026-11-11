# SPDX-License-Identifier: GPL-3.0-or-later
import array
import binascii
import io
import struct


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)


A = array.array
a = A('B', [0] * 8)
struct.pack_into('<I', a, 2, 0xdeadbeef)
print(a)
print(struct.unpack_from('<I', a, 2), struct.unpack('<8B', a), struct.calcsize('4s'))
w = A('i', [0, 0])
struct.pack_into('<ii', w, 0, 1, -1)
print(w)
t(struct.pack_into, '<I', A('B', [0]), 0, 1)
b = A('h', [1, 2, 3])
print(bytes(b), bytearray(b), bytes(memoryview(b)), memoryview(b).tobytes() == b.tobytes())
ba = bytearray(b'xy')
ba.extend(A('B', [1, 2]))
ba += A('B', [3])
print(ba)
print(b''.join([A('B', [65, 66]), b'C']) if False else b'', binascii.hexlify(A('B', [1, 171])), binascii.crc32(A('B', [1, 2, 3])))
f = io.BytesIO()
f.write(A('B', [104, 105]))
f.write(A('h', [0x6968]))
print(f.getvalue())
g = io.BytesIO(b'abcdefgh')
r = A('B', [0] * 4)
print(g.readinto(r), r)
r2 = A('H', [0, 0])
print(g.readinto(r2), r2)
print(bytes(A('d', [1.0])), bytes(A('u', 'a')), int.from_bytes(A('B', [1, 0]), 'little'))
print(b'abc'.startswith(b'a'), bytes(A('B')) == b'', A('B', b'abc').tobytes() == b'abc')
print(struct.Struct('<2H').pack_into(r2, 0, 5, 6), r2, struct.Struct('<2H').unpack_from(r2))
