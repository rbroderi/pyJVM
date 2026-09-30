def f(a, /, *, b):
    return a + b

f(a=1, b=2)
