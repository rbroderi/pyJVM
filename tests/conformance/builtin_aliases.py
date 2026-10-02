length, advance, reverse, inspect = len, next, reversed, getattr
print(length([1, 2]), callable(length))
r = reverse([1, 2, 3])
print(iter(r) is r, advance(r), list(r), advance(r, 'end'))
it = iter([9])
print(advance(it), advance(it, None))
class Item:
    value = 7
print(inspect(Item(), 'value'), inspect(Item(), 'missing', 11))
check = isinstance
print(check(Item(), Item), check(3, (str, int)))
power = pow
print(power(2, 10), power(2, 10, 7), power(3, -1, 11), power(3, 3, -7))
try:
    power(2, -1, 4)
except ValueError:
    print('not invertible')
def consume():
    values = iter([3, 4, 0, 8])
    def fetch():
        return next(values)
    result = iter(fetch, 0)
    print(next(result), list(result), next(result, 'done'), next(values))
consume()
def shadow(len):
    return len([1])
def replacement(value):
    return 'shadowed'
print(shadow(replacement))
len = replacement
print(len([]))
del len
print(len([1, 2, 3]))
try:
    length(value=[])
except TypeError:
    print('positional only')
try:
    advance([])
except TypeError:
    print('not iterator')
