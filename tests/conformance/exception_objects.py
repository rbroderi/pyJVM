def ending():
    yield 1
    return 42

g = ending()
print(next(g))
try:
    next(g)
except StopIteration as e:
    print('value', e.value)
    print('args', e.args)

try:
    raise ValueError('bad')
except ValueError as e:
    print('caught', e)
    print('args', e.args)
