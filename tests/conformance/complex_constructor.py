alias = complex
for value in [0, True, 12, 1.5, 1j, complex(-0.0, -0.0),
              '1', '1j', '+j', '-j', '(1-2j)', '  ( -0-0j )  ',
              '1e-3+2e+2j', '1_000+2_000j', 'inf+nanj', '-Infinityj']:
    print(alias(value))
print(alias(), alias(real=2, imag=3), alias(imag=-0.0))
print(1e500j, -1e500j)
class AsComplex:
    def __complex__(self): return 3+4j
class AsFloat:
    def __float__(self): return 2.5
class AsIndex:
    def __index__(self): return 5
print(alias(AsComplex()), alias(AsFloat()), alias(AsIndex()))
for value in ['', '1 +2j', '1++2j', '1e+j', '1_', '1+2', '()', '0x1j']:
    try:
        alias(value)
    except ValueError:
        print('malformed', repr(value))
for operation in [lambda: alias('1j', 2), lambda: alias(None),
                  lambda: alias(1, real=2), lambda: alias(1, 2, 3),
                  lambda: alias(foo=1), lambda: alias(10 ** 1000)]:
    try:
        operation()
    except (TypeError, OverflowError) as error:
        print(type(error).__name__)
z = 3+4j
print(alias(z) is z, callable(z.conjugate), z.conjugate(), z.conjugate.__class__ is type(z.conjugate))
print(hasattr(z, 'unknown'))
try:
    z.real = 1
except AttributeError:
    print('read-only real')
