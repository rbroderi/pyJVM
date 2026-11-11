# SPDX-License-Identifier: GPL-3.0-or-later
import binascii


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except Exception as e:
        print(type(e).__name__, e)


print(binascii.Error.__mro__[1].__name__, binascii.Incomplete.__mro__[1].__name__, binascii.Error.__module__)
print(sorted(x for x in dir(binascii) if x in ('a2b_base64', 'a2b_hex', 'a2b_qp', 'a2b_uu', 'b2a_base64', 'b2a_hex', 'b2a_qp', 'b2a_uu', 'crc32', 'crc_hqx', 'hexlify', 'unhexlify')))

# hex
for data in [b'', b'\x00', b'\xb9\x01\xef', bytes(range(20)), bytearray(b'abc'), memoryview(b'xyz')]:
    t(binascii.hexlify, data)
    t(binascii.b2a_hex, data)
t(binascii.hexlify, b'\xb9\x01\xef', ':')
t(binascii.hexlify, b'\xb9\x01\xef', b'_', 2)
t(binascii.hexlify, b'\xb9\x01\xef', b'_', -2)
t(binascii.hexlify, b'\xb9\x01\xef', b'_', 0)
t(binascii.hexlify, b'\xb9\x01\xef', b'_', 1)
t(binascii.hexlify, b'\xb9\x01\xef\x10\x20', b'-', 3)
t(binascii.hexlify, b'\xb9\x01\xef\x10\x20', b'-', -3)
t(binascii.hexlify, b'\xb9\x01\xef\x10\x20', b'-', 10)
t(binascii.hexlify, b'\xb9\x01\xef', 'ab')
t(binascii.hexlify, b'\xb9\x01\xef', 'é')
t(binascii.hexlify, b'\xb9\x01\xef', 5)
t(binascii.hexlify, b'\xb9\x01\xef', sep=':', bytes_per_sep=2)
t(binascii.hexlify, 'abc')
t(binascii.hexlify, 5)
t(binascii.hexlify)
for h in [b'', b'00', b'b901EF', 'b901ef', b'b9 01', b'b90', b'zz', 'b9é', bytearray(b'6162'), b'6', b'0g']:
    t(binascii.unhexlify, h)
    t(binascii.a2b_hex, h)

# crc
for d in [b'', b'a', b'hello world', bytes(range(256)) * 3]:
    t(binascii.crc32, d)
    t(binascii.crc32, d, 12345)
    t(binascii.crc_hqx, d, 0)
    t(binascii.crc_hqx, d, 0xffff)
t(binascii.crc32, 'a')
t(binascii.crc32, b'a', 1.5)

# base64
vectors = [b'', b'a', b'ab', b'abc', b'abcd', b'\xff\xfe\xfd\xfc\xfb', bytes(range(60)), bytes(range(256))]
for d in vectors:
    e = binascii.b2a_base64(d)
    print(e)
    print(binascii.b2a_base64(d, newline=False))
    print(binascii.a2b_base64(e) == d)
    t(binascii.a2b_base64, e, strict_mode=True)
for s in [b'YQ==', b'YQ=', b'YQ', b'Y', b'YWJj\n', b'YW Jj', b'YW!Jj', b'YQ==YQ==', b'YQ===', b'=YQ==', b'YQ=a', b'Y===', b'YWJjZA', b'YWJjZ', b'====', b'', b'\n', b'YQ==\n', b'YQ=\n=', b'Y\nQ==', 'YQ==', 'YQé==', b'YW\xe9Jj', b'A', b'AA', b'AAA', b'AAAA=', b'AAAA==', b'AAA=A']:
    t(binascii.a2b_base64, s)
    t(binascii.a2b_base64, s, strict_mode=True)
t(binascii.b2a_base64, b'abc', True)
t(binascii.a2b_base64, b'YQ==', True)
t(binascii.a2b_base64)
t(binascii.b2a_base64, 'abc')

# uu
for d in [b'', b'a', b'hello world', bytes(range(45)), bytes(range(46))[:45]]:
    e = binascii.b2a_uu(d)
    print(e, binascii.a2b_uu(e) == d)
    e = binascii.b2a_uu(d, backtick=True)
    print(e, binascii.a2b_uu(e) == d)
t(binascii.b2a_uu, bytes(46))
t(binascii.b2a_uu, b'abc', backtick=True)
t(binascii.b2a_uu, b'abc', True)
for s in [b'#86)C\n', b'#86)C', b' \n', b'', b'$86)C\n', b'#86)C  \n', b'#86)C x\n', b'#86) \n', b'\x7f', b'#86)C\n\n', b'M' + b'a' * 60, '#86)C\n', b'!    \n', b'`\n']:
    t(binascii.a2b_uu, s)

# quoted-printable
qp = [b'', b'abc', b'hello=world', b'a b\tc', b'trailing space \n', b'trailing tab\t\n', b'caf\xc3\xa9', b'line1\nline2\r\nline3', b'x' * 100, b'x=' * 40,
      b'.\n', b'. \n', b'From x\n', b'\x00\x01\xff', b'a\tb \n\t', b'end ', b'end\t', b'?_x', b'a_b c', b'a\rb', b'~}{', b'a\n\n\nb', b'ab\r\n\r\ncd', b'z' * 74 + b'\n', b'z' * 75 + b'\n', b'z' * 76 + b'\n', b'z' * 75, b'z' * 74 + b' ']
for d in qp:
    for kw in [{}, {'quotetabs': True}, {'istext': False}, {'header': True}, {'quotetabs': True, 'istext': False, 'header': True}]:
        t(binascii.b2a_qp, d, **kw)
dec = [b'', b'abc', b'a=3Db', b'a=3db', b'a=\nb', b'a=\r\nb', b'a=', b'a=3', b'a=zz', b'=\n', b'a_b', b'caf=C3=A9', b'x=\n=\ny', b'line \nnext', b'a=\rb', b'==', b'=3D=3D', b'abc=\n', b'abc= \n', b'a =\n', b'=4', b'=41=42']
for d in dec:
    t(binascii.a2b_qp, d)
    t(binascii.a2b_qp, d, header=True)
t(binascii.a2b_qp, 'a=3D')
t(binascii.a2b_qp, 'a=3Dé')
