def add(a, b=10):
    return a + b

alias = add
print(alias(5))
print(alias(*(2, 3)))
print(alias(4, **{"b": 8}))

def fact(n):
    if n <= 1:
        return 1
    return n * fact(n - 1)

print(fact(10))

def collect(a, *args, **kwargs):
    print(a, args, kwargs)

values = [2, 3]
options = {"x": 4, "y": 5}
collect(1, *values, **options)
