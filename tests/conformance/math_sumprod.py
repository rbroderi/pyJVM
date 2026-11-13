import math
from fractions import Fraction

print(math.sumprod([1, 2, 3], [4, 5, 6]))
print(math.sumprod([], []))
print(math.sumprod([0.1, 0.2, 0.3], [1, 1, 1]))
print(math.sumprod([1e100, 1.0, -1e100], [1.0, 1.0, 1.0]))
print(math.sumprod([2 ** 70, 3], [2 ** 70, 5]))
print(math.sumprod([Fraction(1, 3), Fraction(1, 6)], [3, 6]))
print(math.sumprod([True, 2], [3, 4]))
print(math.sumprod([float("inf"), 1.0], [1.0, 1.0]))
print(math.isnan(math.sumprod([float("nan")], [1.0])))
try:
    math.sumprod([1, 2], [1])
except ValueError as exc:
    print(exc)
