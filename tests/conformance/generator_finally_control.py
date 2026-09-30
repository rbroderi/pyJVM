def ret():
    try:
        yield 1
        return 7
    finally:
        print('ret cleanup')

def loop():
    for x in range(4):
        try:
            if x == 1:
                continue
            if x == 3:
                break
            yield x
        finally:
            print('cleanup', x)
    yield 9

g = ret()
print(next(g))
try:
    next(g)
except StopIteration as e:
    print('ret', e.value)
print(list(loop()))
