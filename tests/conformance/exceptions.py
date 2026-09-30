try:
    raise ValueError('bad')
except ValueError as e:
    print('caught', e)

try:
    print(1 // 0)
except ZeroDivisionError:
    print('zero')

try:
    assert False, 'nope'
except AssertionError as e:
    print('assert', e)

try:
    raise TypeError('x')
except (ValueError, TypeError):
    print('tuple-catch')
