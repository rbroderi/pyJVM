def make_counter(start):
    n = start
    def inc(step=1):
        nonlocal n
        n += step
        return n
    return inc

c = make_counter(10)
print(c(), c(5), c())

def outer(x):
    y = 3
    def middle(z):
        def inner(w):
            return x + y + z + w
        return inner
    return middle

print(outer(10)(20)(30))
