def g():
    try:
        return 5
    finally:
        yield 1

x=g()
print(next(x))
try:
    next(x)
except StopIteration as e:
    print(e.value)

def h():
    for i in range(2):
        try:
            break
        finally:
            yield 9
    yield 10

y=h()
print(next(y))
print(next(y))
