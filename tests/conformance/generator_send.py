def echo():
    x = yield 'ready'
    yield x

g = echo()
print(next(g))
print(g.send(42))
g.close()

h = echo()
try:
    h.send(1)
except TypeError:
    print('start error')
