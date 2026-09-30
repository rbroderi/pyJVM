def f(limit):
    for x in range(4):
        yield x
        if x == limit:
            break
    else:
        yield "for-else"

    n = 0
    while n < 3:
        n += 1
        yield n + 10
        if n == limit:
            break
    else:
        yield "while-else"

print(list(f(99)))
print(list(f(2)))
