def gen():
    try:
        yield 1
    except ValueError:
        yield 2
    finally:
        print('cleanup')

g = gen()
print(next(g))
print(g.throw(ValueError('boom')))
try:
    next(g)
except StopIteration:
    print('done')

h = gen()
print(next(h))
h.close()
print('closed')
