# SPDX-License-Identifier: GPL-3.0-or-later
# pyjvm: requires-python >= 3.15
import warnings
import _blake2
import _md5
import _sha2
import _sha3

for ctor in (_md5.md5, _sha2.sha256, _sha3.sha3_256, _sha3.shake_128, _blake2.blake2b, _blake2.blake2s):
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter('always')
        h = ctor(string=b'abc')
        print(ctor.__name__, h.hexdigest(*([8] if ctor is _sha3.shake_128 else []))[:16])
        print([(x.category.__name__, str(x.message), x.filename.endswith('hash_string_kw_315.py')) for x in w])
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter('always')
        ctor(data=b'abc')
        ctor(b'abc')
        print(len(w))
    for kw in ({'string': None}, {'data': b'a', 'string': b'b'}):
        try:
            ctor(**kw)
        except Exception as e:
            print(type(e).__name__, e)
