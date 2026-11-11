# SPDX-License-Identifier: GPL-3.0-or-later
# pyjvm: requires-python >= 3.15
import array
import copy


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)


A = array.array
print(array.typecodes)
for tc in ('e', 'Zf', 'Zd'):
    a = A(tc)
    print(tc, a.typecode, a.itemsize, a)
h = A('e', [1.0, 0.5, -2.25, 65504.0, 1e-8, float('inf'), float('nan'), 0.1, 3])
print(h, h.tobytes())
t(h.append, 70000.0)
t(h.append, 'a')
t(h.append, 1j)
print(h.tolist()[:3], h.itemsize, h[2:4], h.count(0.5), h.index(0.5))
c = A('Zf', [1 + 2j, 3.5, 4])
print(c, c.tobytes(), c.tolist(), c[1], len(c), c.itemsize)
d = A('Zd', [1 + 2j, -0.5j, 3])
print(d, d.tobytes(), d[0].real, d[0].imag)
t(c.append, 'x')
t(c.append, None)
print(d == A('Zd', [1 + 2j, -0.5j, 3]), d + d, d * 2)
c.byteswap()
print(c.tobytes())
print(copy.copy(c) == c, c.__reduce_ex__(3)[0] is A, c.__reduce_ex__(3)[1][:2])
print(A('Zf', [1j]) == A('Zf', [1j]))
t(A, 'Zx')
t(A, 'Z')
t(A, 'ZZ')
t(A, 'ee')
t(A, 'e', b'\x00\x3c')
t(A, 'Zf', b'\x00' * 8)
t(A, 'Zf', b'\x00' * 7)
t(A, 'e', [1, 2.5, True])
print(A('e', [1]).__reduce_ex__(2), A('e', [1]).__reduce_ex__(3)[1][1:3], c.__reduce_ex__(3)[1][1:3], d.__reduce_ex__(3)[1][1:3])
for r in (A('e', [1.5, -2.0]), c, d):
    fn, args, st = r.__reduce_ex__(3)
    print(fn(*args) == r)
print(array._array_reconstructor(A, 'Zf', 23, b'?\x80\x00\x00@\x00\x00\x00'), array._array_reconstructor(A, 'Zd', 25, b'?\xf0' + b'\x00' * 6 + b'@' + b'\x00' * 7), array._array_reconstructor(A, 'e', 27, b'<\x00'))
t(array._array_reconstructor, A, 'e', 28, b'')
