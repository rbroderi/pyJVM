def gen():
    x = yield 1
    yield x

g = gen()
print(next(g))
try:
    g.throw(ValueError('boom'))
except ValueError as e:
    print(e.args)
try:
    next(g)
except StopIteration:
    print('stopped')

h = gen()
print(next(h))
print(h.close())
try:
    next(h)
except StopIteration:
    print('closed')

fresh = gen()
try:
    fresh.throw(TypeError('fresh'))
except TypeError as e:
    print(e.args)
