def t(v, spec):
    try:
        print(repr(spec), repr(format(v, spec)))
    except Exception as e:
        print(repr(spec), type(e).__name__, e)

for spec in ['', 's', '10', '<10', '>10', '^10', '*<10', '.3', '10.3s', '=10', '+', ' ', '#', '0', '010', ',', '_', ',s', 'd', 'x', 'q', '.', 'z', '5s', 'é>5', '€^6s', '<0', '1_0', 'ss', '%', 'c', 'n', 'e']:
    t('hello', spec)
class C:
    pass
t(C(), 'x')
t([1], '')
t([1], '10')
t((1,2), 's')
class D:
    def __format__(self, spec):
        return 'D<%s>' % spec
    def __str__(self): return 'str-D'
print(format(D(), 'abc'), '{:xyz}'.format(D()), f'{D():q}', f'{D()}', f'{D()!s:>8}', len(f'{D()!r:.5}'))
class E:
    def __format__(self, spec):
        return 1
try:
    format(E(), '')
except TypeError as e:
    print('TypeError')
class S(str):
    pass
class I(int):
    pass
class F(float):
    def __format__(self, spec): return 'F' + float.__format__(self, spec)
t(S('ab'), '>5')
t(I(5), '03d')
t(I(5), '')
t(F(1.5), '.2f')
t(1.5, 5)
try:
    format(1, None)
except Exception as e:
    print(type(e).__name__, e)
try:
    format()
except Exception as e:
    print(type(e).__name__, e)
x = 3.14159; w = 10; p = 3
print(f'{x:{w}.{p}}|{x!r}|{x=}|{x = :.2f}|{"a"!r:>6}|{"é"!a}|{w:#x}|{w:{w}}|{x:{w}{"."}{p}f}')
name = 'z'
print(f'{name=}', f'{name = !r}', f'{3+4=}', f"{'a'+'b'=:>5}")
print(f'{{}}', f'{{{w}}}', f'{w:{"<"}{w}}', f'{x:>{w}.{p}f}')
print(format(1+2j, ''), format(1.5-2j, '.2f'), format(1+2j, '10.1e'), format(1+2j, '<12g'), format(complex(0, 1), ''), format(complex(-0.0, -0.0), ''), format(1e16+1j, ''), format(1+2j, 'n'), format(complex('inf'), ''), format(complex(float('nan'), 1), ''), format(1+2j, '+.1f'), format(1+2j, '#.0f'), format(3j, 'g'))
t(1+2j, 'd'); t(1+2j, 'x'); t(1+2j, '%'); t(1+2j, 's'); t(1+2j, '010,.1f'); t(1+2j, '=10')
print(repr(1+2j), repr(-0.0+0j), repr(complex(1e16, 1e-5)), str(complex(1, float('-inf'))), repr(complex(float('nan'), float('nan'))))
