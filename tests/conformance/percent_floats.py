for value in (0.0, -0.0, 1.0, -1.0, 0.1, 1.005, 2.675, 2.5, 3.5, 9.999, 99.95, 1e-5, 1e-4, 1e6, 1e20, 1e-300, 5e-324, 1.7976931348623157e308, float('inf'), -float('inf'), float('nan')):
    for template in ('%f','%e','%g','%.0f','%.2f','%.0e','%.2e','%.0g','%.2g','%.6g','%#.0f','%#.0e','%#.3g','%+012.2f','% 012.2e','%-12.2g','%E','%F','%G'):
        print(template % value)
class Real:
    def __float__(self): return 1.25
class Indexed:
    def __index__(self): return 7
print('%f %e %g' % (Real(), Real(), Real()))
print('%f %g' % (Indexed(), Indexed()))
for value in (True, 2**80):
    print('%.17g %.2f' % (value, value))
