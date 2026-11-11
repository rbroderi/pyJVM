# SPDX-License-Identifier: GPL-3.0-or-later
# pyjvm: requires-python >= 3.15
import zlib


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)


a, b = b'hello, ', b'combine world' * 100
print(zlib.crc32_combine(zlib.crc32(a), zlib.crc32(b), len(b)) == zlib.crc32(a + b))
print(zlib.adler32_combine(zlib.adler32(a), zlib.adler32(b), len(b)) == zlib.adler32(a + b))
print(zlib.crc32_combine(5, 7, 0), zlib.adler32_combine(5, 7, 0), zlib.crc32_combine(0, 0, 10 ** 6))
print(zlib.crc32_combine(123456, 654321, 77), zlib.adler32_combine(0x12345, 0x54321, 12345))
t(zlib.crc32_combine, 1, 2, -1)
print(zlib.crc32(b'abc', -1), zlib.crc32(b'abc', 2 ** 40), zlib.adler32(b'abc', -1))
print(zlib.crc32(b'abc', 2 ** 32 - 1), zlib.adler32(b'abc', 0))
