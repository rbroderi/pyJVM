# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import io

print(sorted(hashlib.algorithms_guaranteed))
for name in sorted(hashlib.algorithms_guaranteed):
    ctor = getattr(hashlib, name)
    if name.startswith('shake_'):
        a = hashlib.new(name, b'abc').hexdigest(20)
        b = ctor(b'abc').hexdigest(20)
    else:
        a = hashlib.new(name, b'abc').hexdigest()
        b = ctor(b'abc').hexdigest()
    print(name, a == b, a)
import hmac
for name in sorted(hashlib.algorithms_guaranteed):
    if name.startswith('shake_'):
        continue    # hmac needs a fixed digest size
    h = hmac.new(b'key', b'abc', name)
    print('hmac', name, h.digest_size, h.block_size, h.hexdigest(), hmac.digest(b'key', b'abc', name) == h.digest())
print(hmac.new(b'k' * 150, b'abc', hashlib.sha3_512).hexdigest())
print(hmac.new(b'key', b'abc', lambda: hashlib.blake2b(digest_size=20)).hexdigest())
print(hashlib.blake2b(b'x', digest_size=16, key=b'k').hexdigest())
print(hashlib.blake2s(b'x', digest_size=16, key=b'k', salt=b'ab', person=b'cd').hexdigest())
print(hashlib.file_digest(io.BytesIO(b'abc'), 'blake2s').hexdigest())
print(hashlib.file_digest(io.BytesIO(b'abc' * 100000), 'sha3_256').hexdigest())
