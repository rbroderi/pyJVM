def t(*a, **k):
    f = a[0]
    try:
        print(repr(f.format(*a[1:], **k)))
    except Exception as e:
        print(type(e).__name__, e)

class O:
    attr = 'A'
    def __init__(self): self.d = {'k': [10, 20, {'z': 'deep'}]}; self.items = [1,2]
    def __getitem__(self, i): return 'item%s' % (i,)
    def __format__(self, s): return 'O(%s)' % s
o = O()
t('{} {} {}', 1, 2, 3)
t('{0} {1} {0}', 'a', 'b')
t('{} {1}', 1, 2)
t('{1} {}', 1, 2)
t('{0} {}', 1, 2)
t('{a} {b}', a=1, b=2)
t('{a}', b=1)
t('{} {}', 1)
t('{5}', 1)
t('{0.attr} {0[3]} {0.d[k][2][z]} {0.d[k][0]}', o)
t('{a[0]} {a[1]}', a=[7, 8])
t('{a[x]}', a={'x': 1})
t('{a[-1]}', a=[1, 2])
t('{a[01]}', a=[1, 2])
t('{a[x}', a=1)
t('{a.}', a=1)
t('{a.b}', a=1)
t('{a[0]b}', a=[1])
t('{a[0]]}', a=[1])
t('{!r}', 'x')
t('{!s:>5}', 'x')
t('{!a}', 'é')
t('{!x}', 'x')
t('{!}', 'x')
t('{!rr}', 'x')
t('{0!r:>{1}}', 'x', 6)
t('{:{}} {:{}}', 1, 3, 2, 4)
t('{:{}{}}', 1, '>', 3)
t('{:{:{}}}', 1, 2, 3)
t('{a:{b:{c}}}', a=1, b=2, c=3)
t('{{}} {{{}}}', 5)
t('}', )
t('{', )
t('{{}', )
t('}}{', )
t('{0', 1)
t('{0!', 1)
t('{0!r', 1)
t('{0:', 1)
t('{0:{1}', 1, 2)
t('{0:{1}}', 1)
t('{:{}}', 1)
t('{0.0}', 1)
t('{ 0}', 1)
t('{ a}', **{' a': 3})
t('{a-b}', **{'a-b': 4})
t('{!r:}', 'x')
t('{:}', 'x')
t('{:s}', 1)
t('{:>5}', 'ab')
t('{:5}', 1.5)
t('{0:abc}', o)
t('{0!s:abc}', o)
t('{0[0]:abc}', o)
t('{[0]}', [5])
t('{.real}', 5)
t('{[a]}', {'a': 'x'})
t('{:{}.{}f}', 3.14159, 10, 2)
t('{0:{1}{0}}', 5, '>')
t('é{}ü{}', 1, 2)
t('{:{{}}}', 1)
t('{:}}', 1)
t('{a!r:^{w}}', a='x', w=5)
t('{0:{1:{2}}}', 1, 2, 3)
t('{0:{1:{2:{3}}}}', 1, 2, 3, 4)
t('{0.__class__.__name__}', 5)
t('{0[1:2]}', 'abc')
t('{:{}', 1)
t('{99999999999999999999}', 1)
t('{0:x} {1:b} {2:e}', 255, 5, 1.5)
t('{:d}', True)
t('{:!<5}', 'a')
t('{:{<5}', 'a')
t('{!r}{!s}', 1, 'a')
print('{a}'.format_map({'a': 1}), '{a[0]}'.format_map({'a': [3]}))
class Dd(dict):
    def __missing__(self, k): return '<%s>' % k
print('{x}-{y}'.format_map(Dd(x=1)))
try:
    '{a}'.format_map({})
except Exception as e:
    print(type(e).__name__, e)
try:
    '{}'.format_map({})
except Exception as e:
    print(type(e).__name__, e)
try:
    '{a}'.format_map(1)
except Exception as e:
    print(type(e).__name__, e)
try:
    '{a}'.format_map()
except Exception as e:
    print(type(e).__name__, e)
print(str.format('{}', 3), str.format_map('{a}', {'a': 4}))
print('{self}'.format(self=1), '{a}{a}'.format(a=2), '{} {}'.format(*[1, 2]), '{a}'.format(**{'a': 9}))
print('%s' % ('{}'.format('%s')))
try:
    '{}'.format()
except Exception as e:
    print(type(e).__name__, e)
try:
    str.format()
except Exception as e:
    print(type(e).__name__, e)
