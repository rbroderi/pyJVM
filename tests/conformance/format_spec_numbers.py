def t(v, spec):
    try:
        print(repr(spec), repr(format(v, spec)))
    except Exception as e:
        print(repr(spec), type(e).__name__, e)

for spec in ['', 'd', '5d', '<5d', '^7d', '>7d', '=+7d', '+d', ' d', '-d', '07d', '+07d', '*^9d', ',d', '_d', '010,d',
             'b', '#b', '#o', '#x', '#X', 'x', 'X', '_x', '_b', '#012_x', ',x', 'c', 'n', '.2d', 'e', 'f', 'g', '%',
             '.3f', '.0e', '#.0f', 'z', 'zd', 's', 'q', '10', '<', '1.2.3', 'dd', '#c', '+c', ',n', '_n', '.', '.f', '5,', ',_d', '=5', '{}']:
    t(12345, spec)
    t(-12345, spec)
for spec in ['', '#x', '_d', ',d', '30,d', '#_b', 'e', '.3g', 'x']:
    t(2**100, spec)
    t(-(2**100), spec)
t(True, '')
t(True, 'd')
t(True, '5')
t(True, 's')
t(None, '')
t(None, '>5')
t(65, 'c')
t(0x110000, 'c')
t(-1, 'c')
t(1114111, 'c')

floats = [0.0, -0.0, 1.0, 0.1, 1e16, 1e-5, 123456.789, 1e22, 1.5e300, 2.5, 3.5, 0.5, 1/3, float('inf'), float('-inf'), float('nan'), 1234567.0, 0.000123456, 9.995, 99999.5, 1e100]
specs = ['', 'f', '.0f', '.2f', 'e', '.0e', '.3E', 'g', '.0g', '.3g', '#g', '#.3g', 'G', 'F', '%', '.1%', 'n', '.3n', '10.3f', '<10.3f', '^+10.2e', '010.2f', '+', ' f', ',.2f', '_.3f', ',', '.10g', '#f', '#.0e', '.17g', '.20f', '010,.1f', 'z.1f', 'zf', 'x', 'd', 'c', 's', '=10', '010']
for f in floats:
    for s in specs:
        t(f, s)
print(repr(1e16), repr(1e15), repr(1.5e-7), repr(123456789012345678.0), repr(5e-324), repr(1.7976931348623157e308), repr(0.1+0.2), 1e16, str(1e22), '%r' % 1e16)
print(format(1e16, ''), format(1e15, ''), format(1.0, ''), format(1e-4, ''), format(1e-5, ''))
print(round(2.675, 2), format(2.675, '.2f'), format(0.125, '.2f'), format(0.375, '.2f'), format(1e23, '.0f'), format(5e-324, '.3g'))
print(format(0.5, '.0f'), format(1.5, '.0f'), format(2.5, '.0f'), format(1234.5, '.3g'), format(0.00001234, 'g'), format(123456789.0, 'g'), format(1234567.0, 'n'))
