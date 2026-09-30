def bad():
    yield 1
    raise StopIteration('boom')

g = bad()
print(next(g))
try:
    next(g)
except RuntimeError as e:
    print('runtime', e)
