for value in (0.0,-0.0,0.1,1.0,1.25,9.999,12.0,123.0,1e-5,1e-4,1e6,1e16,1e-300,5e-324,1.7976931348623157e308,float('inf'),-float('inf'),float('nan')):
    for spec in ('', 'f', '.2f', '.0e', '.3g', '#.3g', '>12.2f', '+012.2f', 'z.1f', '+z.0f', '*^14.2e', '.1', '.3', '10', '.2%', '_f', ',.2f'):
        print(format(value,spec))
for value in (True, 17, 2**80):
    print(format(value,'.2f'),format(value,'.3g'),format(value,'.2%'))
for value,spec in [(1.0,'d'),(1.0,'s'),(1.0,',n')]:
    try: format(value,spec)
    except ValueError: print('ValueError')
for value in (1.25, -1.25, 12345.0):
    for spec in ('010,.2f', '+012_.2f', '015,.2e', '010,.1%'):
        print(format(value,spec))
