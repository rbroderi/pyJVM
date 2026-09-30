def child():
    yield 1
    received = yield 2
    return received + 10

def parent():
    result = yield from child()
    yield result

g = parent()
print(next(g))
print(next(g))
print(g.send(7))

# Delegating to an ordinary iterable is lazy and has a None result.
def seq():
    r = yield from [4, 5]
    print('result', r)

s = seq()
print(next(s))
print(next(s))
try:
    next(s)
except StopIteration:
    print('done')
