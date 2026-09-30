g = (x * x for x in range(5) if x % 2 == 0)
print(type(g))
print(next(g))
print(list(g))

def outer(n):
    base = 10
    return (base + x for x in range(n))

print(list(outer(3)))
print(list((x + y for x in range(2) for y in range(3) if y != 1)))

calls = []
def source():
    calls.append('called')
    return range(2)
g2 = (x for x in source())
print(calls)
print(list(g2))
