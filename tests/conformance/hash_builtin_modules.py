# SPDX-License-Identifier: GPL-3.0-or-later
import _md5
import _sha1
import _sha2


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except Exception as e:
        print(type(e).__name__, e)


for ctor in (_md5.md5, _sha1.sha1, _sha2.sha224, _sha2.sha256, _sha2.sha384, _sha2.sha512):
    h = ctor()
    print(h.name, h.digest_size, h.block_size, h.hexdigest())
    h.update(b'abc')
    c = h.copy()
    h.update(bytearray(b'def'))
    c.update(memoryview(b'xyz'))
    print(h.hexdigest(), c.hexdigest(), h.digest() == bytes.fromhex(h.hexdigest()))
    print(ctor(b'hello').hexdigest())
    t(h.update, 'text')
    t(h.update, 5)
    t(ctor, 'text')
