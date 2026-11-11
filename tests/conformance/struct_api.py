# SPDX-License-Identifier: GPL-3.0-or-later
import struct
def t(f, *a, **k):
    try: print(repr(f(*a, **k)))
    except Exception as e: print(type(e).__name__, e)
t(struct.pack)
t(struct.unpack); t(struct.unpack,'i'); t(struct.unpack,'i',b'abcd',1); t(struct.unpack, fmt='i')
t(struct.unpack_from); t(struct.unpack_from, 'i'); t(struct.unpack_from, 'i', b'abcdefgh', 4, 4); t(struct.unpack_from, 'i', buffer=b'abcdefgh', offset=4);t(struct.unpack_from, 'i', b'abcdefgh', x=4)
t(struct.calcsize); t(struct.calcsize, 'i', 'i'); t(struct.calcsize, format='i')
t(struct.iter_unpack); t(struct.iter_unpack,'i')
t(struct.pack_into); t(struct.pack_into, 'i'); t(struct.pack_into, 'i', bytearray(4)); t(struct.pack_into, 'i', bytearray(4), 'a', 1);t(struct.pack_into, 'i', bytearray(4), 2**70, 1)
s = struct.Struct('i')
t(s.pack_into); t(s.pack_into, bytearray(4)); t(s.pack_into, bytearray(4), 0); t(s.unpack); t(s.unpack, b'abcd', b'a'); t(s.unpack_from); t(s.iter_unpack); t(s.pack_into, bytearray(4), 0, 1, 2)
t(struct.Struct); t(struct.Struct, 'i', 'i'); t(struct.Struct, format='i')
t(struct.pack, 'P', 2**64); t(struct.pack, 'P', -2**63-1); t(struct.pack, 'P', 1.5)
t(struct.unpack, 'i', b'abcd', ); t(s.unpack, bytearray(b'abcd'))
t(s.unpack_from, b'abcd', offset='a'); t(s.unpack_from, b'abcd', offset=2**70)
t(struct.unpack_from, 'i', b'abcd', None)
print(struct.Struct('i') == struct.Struct('i'), repr(struct.Struct(b'<2h')))
class S(struct.Struct):
    def __init__(self, f): super().__init__(f)
t(lambda: S('<h').pack(5))
class S2(struct.Struct): pass
t(lambda: S2('<h').unpack(b'\x01\x00'))
t(lambda: S2().pack(5))
t(lambda: struct.Struct.__new__(struct.Struct).pack())
t(lambda: struct.pack('i', 2**31))
import sys
print(struct.unpack('<H', b'\xff\xff'), struct.unpack('>h', b'\xff\xfe'))
print(struct.pack('<I', 2**32-1), struct.pack('>Q', 2**64-1), struct.pack('<q', -2**63))
print(struct.unpack('=Ld', struct.pack('=Ld', 4, 1.5)))
print(struct.pack("@hhl", 1, 2, 3), struct.pack("@2h4x", 1, 2))
print(struct.pack('@i', True), struct.pack('d', float('nan')), struct.pack('<d', -0.0))
t(struct.unpack, '<f', b'\x00\x00\xc0\x7f'); t(struct.unpack, '<d', b'\x00\x00\x00\x00\x00\x00\xf8\x7f')
t(struct.pack, '<f', 3.4028235677973366e+38); t(struct.pack, '<f', 3.4028235e38);
t(struct.pack, '<f', 16777217.0); t(struct.unpack, '<f', struct.pack('<f', 0.1))
t(struct.pack, '<d', 10**400)
t(struct.pack, '<e', 10**400); t(struct.pack, '<e', 65519.99); t(struct.pack, '<e', 2**-14); t(struct.pack,'<e',2**-15);t(struct.pack,'<e',2**-26);t(struct.pack,'<e',1.00048828125);t(struct.pack,'<e',1.0004882812500001)
