# SPDX-License-Identifier: GPL-3.0-or-later
# pyjvm: requires-python >= 3.14
import binascii as b


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except Exception as e:
        print(type(e).__name__, e)


t(b.b2a_base64, b'a' * 50, wrapcol=20)
t(b.b2a_base64, b'a' * 50, wrapcol=20, newline=False)
t(b.b2a_base64, b'a' * 50, wrapcol=21)
t(b.b2a_base64, b'a' * 50, wrapcol=3)
t(b.b2a_base64, b'a' * 3, wrapcol=4)
t(b.b2a_base64, b'a' * 6, wrapcol=4)
t(b.b2a_base64, b'ab', padded=False)
t(b.b2a_base64, b'ab', padded=False, newline=False)
t(b.b2a_base64, b'a', padded=False)
t(b.b2a_base64, b'abcd', alphabet=b.URLSAFE_BASE64_ALPHABET)
t(b.b2a_base64, b'\xfb\xff', alphabet=b.URLSAFE_BASE64_ALPHABET)
t(b.b2a_base64, b'ab', alphabet=b'abc')
t(b.b2a_base64, b'ab', alphabet='A' * 64)
t(b.b2a_base64, b'ab', wrapcol=-1)
t(b.a2b_base64, b'YQ==', strict_mode=True, ignorechars=b'')
t(b.a2b_base64, b'YQ', padded=False)
t(b.a2b_base64, b'YQ=', padded=False)
t(b.a2b_base64, b'YQ==', padded=False)
t(b.a2b_base64, b'YQ==', padded=False, strict_mode=True)
t(b.a2b_base64, b'YQ', padded=True, strict_mode=True)
t(b.a2b_base64, b'YR==', canonical=True)
t(b.a2b_base64, b'YR==')
t(b.a2b_base64, b'-_', alphabet=b.URLSAFE_BASE64_ALPHABET)
t(b.a2b_base64, b'-_')
t(b.a2b_base64, b'YQ==', alphabet=b'abc')
t(b.a2b_base64, b'YQ==', ignorechars='')
t(b.a2b_base64, b'Y Q==', ignorechars=b' ')
t(b.a2b_base64, b'Y Q==', ignorechars=b'')
t(b.a2b_base64, b'Y Q==', strict_mode=False, ignorechars=b'')
t(b.a2b_base64, b'Y Q==', strict_mode=True)
t(b.a2b_base64, b'YQ==Q', strict_mode=True)
t(b.a2b_base64, b'YWJj=', strict_mode=True)
t(b.a2b_base64, b'YWJj=')
t(b.a2b_base64, b'YWJj==')
for s in [b'ab===', b'abc=====', b'=abcd', b' =abcd', b'a=b==', b'a=bc=', b'ab=c=', b'ab=cd', b'abcd=', b'abcd=efgh', b'ab==c=', b'ab==cd', b'abc=d', b'ab:(){:|:&};:==']:
    t(b.a2b_base64, s, strict_mode=True)
    t(b.a2b_base64, s, strict_mode=True, ignorechars=b' =:;(){}|&')
    t(b.a2b_base64, s)
for s in [b'', b'YQ==', b'YWI=', b'YWJj', b'Y=WJj', b'YW=Jj', b'YWJ=j']:
    t(b.a2b_base64, s, padded=False, ignorechars=b'=')
    t(b.a2b_base64, s, padded=False, strict_mode=True)
print(b.BASE64_ALPHABET == b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/', len(b.URLSAFE_BASE64_ALPHABET))
t(b.a2b_hex, b'61 62', ignorechars=b' ')
t(b.unhexlify, b'6 1 62', ignorechars=b' ')
t(b.unhexlify, b'616', ignorechars=b' ')
t(b.unhexlify, b'61_', ignorechars=b' ')
