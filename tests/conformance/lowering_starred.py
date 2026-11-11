a = [1, 2]
b = (3, 4)
c = {5, 6}


def ret(a, b):
    return *a, b


def ret2(a):
    return *a, *a


print(ret(a, b))
print(ret2(a))
print([*a, *b])
print([*a, 0, *b, *"xy"])
print((*a, *b))
print((*a,))
print(sorted({*a, *b, *c}))
print(sorted({*a, 99}))
for x in *a, b:
    print(x)
for x in *a, *b:
    print(x)
print(list(range(*[2, 5])))
print(list(range(*b)))
print([*range(3)])


class Idx:
    def __getitem__(self, key):
        return key


i = Idx()
print(i[*a])
print(i[*a, 5])
print(i[0, *a])
print(i[*a, *b])
print(i[*[7]])

first, *rest = 1, 2, 3
print(first, rest)
*init, last = [1, 2, 3]
print(init, last)
[x, *mid, y] = "abcde"
print(x, mid, y)
for p, *q in [(1, 2, 3), (4,)]:
    print(p, q)
(m, *n), o = (1, 2, 3), 4
print(m, n, o)
(*u,) = range(3)
print(u)
print({**{"a": 1}, **{"b": 2}})
print(print(*a, *b, sep="-"))


def gen():
    yield *a, 1
    yield *b,


print(list(gen()))
d = {"k": [*a, *b]}
print(d)
print([*a] + [*b])
t = *a, 5
print(t)
