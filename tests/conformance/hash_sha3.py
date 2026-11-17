# SPDX-License-Identifier: GPL-3.0-or-later
import _sha3


def t(label, f, *a, **k):
    try:
        print(label, repr(f(*a, **k)))
    except Exception as e:
        print(label, type(e).__name__, e)


names = ['sha3_224', 'sha3_256', 'sha3_384', 'sha3_512', 'shake_128', 'shake_256']
print(sorted(n for n in dir(_sha3) if not n.startswith('__')))
print(_sha3.implementation, hasattr(_sha3, '_GIL_MINSIZE'))
for n in names:
    ctor = getattr(_sha3, n)
    h = ctor()
    print(n, ctor.__name__, ctor.__module__, ctor.__doc__)
    print(h.name, h.digest_size, h.block_size, h._capacity_bits, h._rate_bits, h._suffix)
    print(type(h) is ctor, ctor.__mro__ == (ctor, object))

for n in names[:4]:
    ctor = getattr(_sha3, n)
    print(n, ctor(b'abc').hexdigest(), ctor(b'').hexdigest()[:8], ctor(data=b'abc').hexdigest()[:8])
    h = ctor()
    h.update(b'a')
    d1 = h.digest()
    h.update(bytearray(b'bc'))
    c = h.copy()
    c.update(memoryview(b'xyz'))
    print(h.hexdigest(), h.digest() == bytes.fromhex(h.hexdigest()), d1 == h.digest(), c.hexdigest()[:16])
    t('digest(1)', h.digest, 1)
    t('hexdigest(1)', h.hexdigest, 1)
    t('copy(1)', h.copy, 1)
    t('update()', h.update)
    t('update(2)', h.update, b'', b'')
    t('update(kw)', h.update, data=b'')
    t('update(str)', h.update, 'x')
    t('update(int)', h.update, 5)
    t('update(None)', h.update, None)

for n in names[4:]:
    ctor = getattr(_sha3, n)
    print(n, ctor(b'abc').hexdigest(40), ctor().digest(5).hex())
    h = ctor(b'abc')
    d8 = h.digest(8)
    print(d8 == h.digest(8), h.digest(5) == d8[:5], h.digest(0), h.hexdigest(0), h.hexdigest(length=3) == h.digest(length=3).hex())
    rate = h.block_size
    for ln in (0, 1, rate - 1, rate, rate + 1, 1000):
        print(ln, h.hexdigest(ln) == h.digest(ln).hex(), len(h.digest(ln)), h.digest(ln)[:6].hex())
    c = h.copy()
    h.update(b'more')
    print(h.digest(10) != c.digest(10), c.digest(10) == ctor(b'abc').digest(10))
    t('digest()', h.digest)
    t('hexdigest()', h.hexdigest)
    t('digest(1,2)', h.digest, 1, 2)
    t('digest(-1)', h.digest, -1)
    t('digest(a)', h.digest, 'a')
    t('digest(1.5)', h.digest, 1.5)
    t('digest(2**29)', h.digest, 2 ** 29)
    t('digest(2**70)', h.digest, 2 ** 70)
    t('digest(True)', h.digest, True)

ref = {1: 'c150125edc74b56f', 7: '', 200: ''}
for n in (134, 135, 136, 137, 143, 144, 145, 167, 168, 169):
    for name in names[:4]:
        whole = getattr(_sha3, name)(b'x' * n).hexdigest()
        for piece in (1, 7, 200):
            h = getattr(_sha3, name)()
            for i in range(0, n, piece):
                h.update(b'x' * min(piece, n - i))
            assert h.hexdigest() == whole, (name, n, piece)
    for name in names[4:]:
        whole = getattr(_sha3, name)(b'x' * n).hexdigest(100)
        h = getattr(_sha3, name)()
        for i in range(0, n, 7):
            h.update(b'x' * min(7, n - i))
        assert h.hexdigest(100) == whole, (name, n)
print(_sha3.sha3_256(b'x' * 135).hexdigest()[:16], _sha3.sha3_256(b'x' * 136).hexdigest()[:16])
print(_sha3.sha3_256(b'x' * 100000).hexdigest())

s = _sha3.sha3_256
t('str', s, 'text')
t('int', s, 5)
t('None', s, None)
t('data+string', s, b'a', string=b'b')
t('data=+string', s, data=b'a', string=b'b')
t('two positional', s, b'', b'')
t('two positional shake', _sha3.shake_256, b'', b'')
t('unknown kw', s, zz=1)
t('unknown kw shake', _sha3.shake_128, zz=1)
t('data twice', s, b'a', data=b'b')
t('usedforsecurity', lambda: s(b'a', usedforsecurity=False).hexdigest()[:8])
t('usedforsecurity pos', s, b'a', True)
t('shake str', _sha3.shake_128, 'text')
