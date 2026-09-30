f = lambda x, y=2: x + y
print(f(3), f(3, 4))

def make(a):
    b = 5
    return lambda x: a + b + x

print(make(10)(7))
print((lambda *xs: sum(xs))(1, 2, 3, 4))
