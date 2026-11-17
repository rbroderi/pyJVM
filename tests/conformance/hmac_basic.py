# SPDX-License-Identifier: GPL-3.0-or-later
# hmac and secrets on top of _operator._compare_digest and the builtin digests.
import hashlib
import hmac
import secrets

key = b'secret-key'
msg = b'The quick brown fox jumps over the lazy dog'


def t(f, *a, **k):
    try:
        r = f(*a, **k)
        print('ok', repr(r))
    except BaseException as e:
        print(type(e).__name__, e)


for name in ('md5', 'sha1', 'sha256', 'sha512', 'sha3_256', 'sha3_512', 'blake2b', 'blake2s'):
    h = hmac.new(key, msg, name)
    g = hmac.new(key, digestmod=getattr(hashlib, name))
    g.update(msg[:10])
    g.update(msg[10:])
    c = h.copy()
    c.update(b'more')
    print(name, h.name, h.digest_size, h.block_size, h.hexdigest(), g.hexdigest() == h.hexdigest(), c.hexdigest() != h.hexdigest())
    print(hmac.digest(key, msg, name).hex() == h.hexdigest(), hmac.digest(key, msg, name) == h.digest())
    print(hmac.compare_digest(h.digest(), g.digest()), hmac.compare_digest(h.hexdigest(), g.hexdigest()), hmac.compare_digest(h.digest(), c.digest()))

# keys longer than the block size, empty key and message
print(hmac.new(b'k' * 200, msg, 'sha256').hexdigest())
print(hmac.new(b'', b'', 'sha256').hexdigest())
print(hmac.new(b'k' * 200, msg, 'sha3_256').hexdigest())
print(hmac.new(b'k' * 100, msg, 'blake2b').hexdigest())
print(hmac.new(bytearray(key), memoryview(msg), 'sha256').hexdigest())

# compare_digest rules
print(hmac.compare_digest('abc', 'abc'), hmac.compare_digest('abc', 'abd'), hmac.compare_digest(b'abc', b'abc'), hmac.compare_digest(b'abc', b'ab'))
t(hmac.compare_digest, 'abc', b'abc')
t(hmac.compare_digest, b'abc', 'abc')
t(hmac.compare_digest, 'caf\xe9', 'caf\xe9')
t(hmac.compare_digest, 1, 1)
t(hmac.compare_digest, bytearray(b'abc'), b'abc')
t(hmac.compare_digest, [1], [1])
t(hmac.new, 'str key', msg, 'sha256')
t(hmac.new, key, 'str message', 'sha256')
t(hmac.new, key, msg)

# secrets
print(len(secrets.token_hex()), len(secrets.token_hex(8)), len(secrets.token_bytes(5)), len(secrets.token_urlsafe(12)) > 0)
print(secrets.compare_digest is hmac.compare_digest, secrets.compare_digest('x', 'x'), secrets.compare_digest(b'x', b'y'))
print(0 <= secrets.randbelow(10) < 10, secrets.choice('abc') in 'abc', secrets.randbits(8) < 256)
t(secrets.randbelow, 0)
