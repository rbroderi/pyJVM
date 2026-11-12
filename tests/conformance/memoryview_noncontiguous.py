# SPDX-License-Identifier: GPL-3.0-or-later
# Stepped memoryview slices are strided views of the exporter, not copies, and are not C-contiguous:
# C functions asking for a simple buffer (binascii, zlib, struct, ...) reject them with BufferError.
import binascii
import struct
import zlib

data = bytearray(b'noncontig')
m = memoryview(data)
rev = m[::-2]
print(bytes(rev), rev.tolist(), len(rev), rev.nbytes)
print(rev.strides, rev.c_contiguous, rev.f_contiguous, rev.contiguous, rev.readonly)
print(m.strides, m.c_contiguous, m[2:5].contiguous)
print(m[::2][1:].tobytes(), m[::2][::2].tobytes(), m[::2][::2].strides, m[::-1][::-1].strides)
print(m[::4][:1].contiguous, m[5:5:2].contiguous, m[::-1].contiguous, m[::-1].tobytes())

# writes through a strided view reach the exporter
rev[0] = ord('G')
m[::4][1] = ord('O')
print(data, rev[0], rev[-1])
m[1::2] = b'1234'
print(data)

for name, function in [('b2a_hex', binascii.b2a_hex), ('hexlify', binascii.hexlify),
                       ('b2a_base64', binascii.b2a_base64), ('a2b_hex', binascii.a2b_hex),
                       ('crc32', binascii.crc32), ('zlib.crc32', zlib.crc32),
                       ('zlib.compress', zlib.compress)]:
    try:
        function(rev)
        print(name, 'accepted')
    except (BufferError, TypeError) as e:
        print(name, type(e).__name__, e)
try:
    struct.unpack('5B', rev)
except BufferError as e:
    print('struct.unpack', 'BufferError', e)
try:
    rev.cast('b')
except TypeError as e:
    print('cast', e)

# contiguous views, and one-item strided views, are still accepted
print(binascii.b2a_hex(m[2:6]), binascii.b2a_hex(m[::9]), m[5:5:2].tobytes(), binascii.b2a_hex(memoryview(b'abc')))
print(binascii.b2a_hex(bytes(rev)), rev.hex())

ro = memoryview(b'abcdef')[::2]
print(ro.readonly, ro.tobytes(), ro == b'ace', hash(ro) == hash(b'ace'))
try:
    ro[0] = 1
except TypeError as e:
    print('TypeError', e)
