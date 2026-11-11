# pyjvm: requires-python >= 3.15
def t(f, a):
    try:
        r = f % a
        print(type(r).__name__, repr(r))
    except Exception as e:
        print(type(e).__name__, e)
cases = [(b'%d', 3), (b'%5d|%-5d|%05d|%+d', (1, 2, 3, 4)), (b'%x %X %o %#x', (255, 255, 8, 255)), (b'%i %u', (3.9, 5)), (b'%e %E %f %F %g %G', (1.5, 1.5, 1.5, 1.5, 1.5, 1.5)),
 (b'%s', b'abc'), (b'%b', b'abc'), (b'%s', bytearray(b'xy')), (b'%s', 'str'), (b'%s', 5), (b'%b', memoryview(b'mv')), (b'%r', 'é'), (b'%a', 'é'), (b'%r %a', (b'x', b'x')), (b'%r', 1.5),
 (b'%c', 65), (b'%c', b'A'), (b'%c', bytearray(b'B')), (b'%c', 256), (b'%c', -1), (b'%c', b'AB'), (b'%c', 'a'), (b'%c', 1.5), (b'%%', ()), (b'%s', ()), (b'%s', (b'a', b'b')),
 (b'%(a)s %(b)d', {'a': b'x', 'b': 2}), (b'%(a)s', {b'a': 1}), (b'%(a)s', {}), (b'%(a)s', (1,)),
 (b'%*d', (5, 3)), (b'%.*f', (2, 3.14159)), (b'%-*d|', (5, 3)), (b'%.2s', b'abcdef'), (b'%10s|', b'ab'), (b'%-10s|', b'ab'), (b'%05d', -3),
 (b'%d', 'x'), (b'%x', 1.5), (b'%z', 1), (b'%', ()), (b'%5', ()), (b'abc', 1), (b'abc', ()), (b'abc', {}), (b'%s %s', (b'a',)), (b'%d', 2**70), (b'%.3d', 5),
 (b'%s', None), (b'%b', [1]), (b'%\xe9', 1), (b'\xe9%d', 1), (b'%f', float('inf')), (b'%d', float('nan')), (b'%ld', 5), (b'%(a)*d', {'a': 1}), (b'%s', b'%s'),
 (bytearray(b'%d'), 3), (bytearray(b'%s'), b'ab'), (bytearray(b'%b %c'), (bytearray(b'q'), 66)), (bytearray(b'abc'), 1), (bytearray(b'%(k)s'), {'k': b'v'})]
for f, a in cases:
    t(f, a)
for f in [b'%!', b'%\n', b"%'", b'%\xe9', b'% ', b'%\x7f', b'%j', b'%-5', b'%(a)', b'%.5', b'%*', b'%.*']:
    t(f, 1)
    t(f, (1, 2))
class B:
    def __bytes__(self): return b'BYTES'
    def __index__(self): return 66
t(b'%s %b', (B(), B()))
t(b'%c', B())
t(b'%x', B())
class M:
    def __getitem__(self, k): return k
t(b'%(abc)s', M())
try:
    print(b'%s' % [b'y'])
except Exception as e:
    print(type(e).__name__, e)
try:
    print(b'%d' % (1,2))
except Exception as e:
    print(type(e).__name__, e)
try:
    b'' % 1
    print('ok')
except Exception as e:
    print(type(e).__name__, e)
x = b'ab'
x %= ()
print(x)
