a, *mid, z = range(6)
print(a, mid, z)
print([0, *[1,2], 3])
print((0, *[1,2], 3))
print({0, *[1,2,2], 3})
print({'a': 1, **{'b': 2}, 'a': 3})

xs = [3,1,2]
print(sorted(xs), xs)
print(list(reversed(xs)))
it = iter([10,20])
print(next(it), next(it))

print(' AbC '.strip().lower())
print('x,y,z'.split(','))
print('-'.join(['a','b','c']))

xs.extend([4,5])
print(xs.pop(), xs.count(1), xs.index(2))
xs.reverse()
print(xs)

d = {'a':1}
d.update({'b':2})
print(d.get('a'), d.get('x', 9), d.items())
print(d.pop('b'), d)

s = {1,2}
s.add(3)
s.discard(2)
print(s)

del xs[0]
print(xs)

class C:
    def __init__(self):
        self.x = 5
c=C()
del c.x
try:
    print(c.x)
except Exception:
    print('missing')
