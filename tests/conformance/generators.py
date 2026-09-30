def simple():
    yield 1
    yield 2
    return 9

g = simple()
print(type(g))
print(next(g))
print(next(g))
try:
    next(g)
except StopIteration:
    print('done')

def squares(n):
    for i in range(n):
        yield i * i

print(list(squares(5)))

def filtered(n):
    i = 0
    while i < n:
        if i % 2 == 0:
            yield i
        i += 1

print(list(filtered(7)))
