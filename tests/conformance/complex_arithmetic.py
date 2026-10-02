values = [0j, 1j, -1j, 1+2j, -3+4j, complex(-0.0, -0.0)]
for z in values:
    print(z, bool(z), type(z) is complex, isinstance(z, complex))
    print(+z, -z, z.conjugate(), z.__getnewargs__())
    for real in [-2, 0, 3, 0.5]:
        print(z + real, real + z, z - real, real - z, z * real, real * z)
        if real:
            print(z / real)
        if z:
            print(real / z)
    for other in [1j, 2+1j]:
        print(z + other, z - other, z * other, z / other)
        print(z == other, z != other)
    for power in [0, 1, 2, 3, -1, -2]:
        if z or power >= 0:
            print(z ** power)
print(abs(3+4j))
print(pow(1j, 2, None))
try:
    pow(1j, 2, 3)
except ValueError:
    print('complex modulo')
print((1j ** 0.5).real > 0.7, (1j ** 0.5).imag > 0.7)
print(complex(-0.0, -0.0) + -0.0, -0.0 - complex(0.0, 0.0))
print(complex(float('inf'), 1) * 2, complex(float('inf'), 1) * 0)
print((1e200+1e200j) / (1e200+1e200j))
print((1e-200+1e-200j) / (1e-200+1e-200j))
print((3+4j) / complex(float('inf'), 1))
try:
    (1e200+1e200j) ** 2
except OverflowError:
    print('power overflow')
for z in [1j, 1+2j]:
    for operation in [lambda: z / 0, lambda: z // 2, lambda: z % 2,
                      lambda: z < 2, lambda: int(z), lambda: float(z),
                      lambda: z + None, lambda: z * (10 ** 1000)]:
        try:
            operation()
        except (TypeError, ZeroDivisionError, OverflowError) as error:
            print(type(error).__name__)
try:
    print(0j ** -1)
except ZeroDivisionError:
    print('zero power')
