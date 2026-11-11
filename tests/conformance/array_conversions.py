import array, sys
print(sys.version_info[:2], array.typecodes)
def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)
class Idx:
    def __index__(self): return 3
class Flt:
    def __float__(self): return 2.5
for tc in 'bBhHiIlLqQfd':
    a = array.array(tc)
    print(tc, a.itemsize)
    for v in (-129, 128, 255, 256, -1, 65536, -32769, 2**31, -2**31 - 1, 2**32, 2**63, -2**63 - 1, 2**64, 1.5, 'a', None, Idx(), Flt(), True, 1e40, float('nan')):
        t(a.append, v)
    t(array.array, tc, [1, 2, 3.5])
    t(array.array, tc, b'\x01')
    t(array.array, tc, 'abc')
    t(array.array, tc, 5)
    t(array.array, tc, [1, 'a'])
for tc in 'uw':
    a = array.array(tc)
    for v in ('a', 'ab', '', 1, b'a', '\U0001f600', '\ud800'):
        t(a.append, v)
    t(array.array, tc, 'héllo')
    t(array.array, tc, ['a', 'b'])
    t(array.array, tc, [1])
    t(array.array, tc, b'abcd')
    t(array.array, tc, array.array('i', [65]))
t(array.array, 'x')
t(array.array, 'ii')
t(array.array, 1)
t(array.array)
t(array.array, 'i', 1, 2)
t(array.array, typecode='i')
t(array.array, 'i', initializer=[1])
t(array.array, b'i')
t(array.array, 'i', array.array('d', [1.5, 2]))
t(array.array, 'd', array.array('i', [1, 2]))
t(array.array, 'i', array.array('u', 'ab'))
t(array.array, 'u', array.array('i', [65]))
t(array.array, 'b', iter([1, 2, 3]))
t(array.array, 'b', range(5))
t(array.array, 'b', (x for x in [1, 2]))
t(array.array, 'b', bytearray(b'ab'))
t(array.array, 'b', memoryview(b'ab'))
t(array.array, 'i', b'abc')
t(array.array, 'i', b'abcd')
t(array.array, 'f', [1, 2.5, float('inf')])
t(array.array, 'f', [1e39])
t(array.array, 'f', [0.1])
t(array.array, 'd', [0.1])
