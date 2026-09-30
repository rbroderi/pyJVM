def child():
    try:
        yield 1
    except ValueError:
        yield 2
    finally:
        print('child cleanup')
    return 9

def parent():
    try:
        value = yield from child()
        print('return', value)
    finally:
        print('parent cleanup')

g = parent()
print(next(g))
print(g.throw(ValueError('x')))
try:
    next(g)
except StopIteration:
    print('done')

h = parent()
print(next(h))
h.close()
print('closed')
