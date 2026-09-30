def shape(a, b=2, /, c=3, *args, d, e=5, **kw):
    print(a, b, c, args, d, e, kw)
    return a + b + c + d + e + len(args) + len(kw)

print(shape(1, 10, 20, 30, 40, d=4, extra=9))
print(shape(1, d=4))

def accumulate(x, bucket=[]):
    bucket.append(x)
    return len(bucket)

print(accumulate(1), accumulate(2))

def only(a, /, **kw):
    print(a, kw)

only(1, a=99)
