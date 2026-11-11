# SPDX-License-Identifier: GPL-3.0-or-later
import _random
import random


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except Exception as e:
        print(type(e).__name__, e)


r = random.Random(12345)
print([r.random() for _ in range(5)])
print([r.getrandbits(k) for k in (1, 7, 31, 32, 33, 64, 100, 200)])
print([r.randint(1, 100) for _ in range(10)])
print([r.randrange(0, 10 ** 30, 7) for _ in range(3)])
print([r.uniform(-1, 1) for _ in range(3)])
print([r.gauss(0, 1) for _ in range(3)])
print(r.choice('abcdefgh'), r.sample(range(100), 5), r.choices('abc', k=5))
xs = list(range(10))
r.shuffle(xs)
print(xs)
print(r.randbytes(8))
print([r.expovariate(2.0) for _ in range(2)], r.betavariate(2, 3), r.gammavariate(2, 3), r.triangular(0, 10, 3))
for seed in (0, 1, -5, 2 ** 32, 2 ** 64 + 5, 3 ** 100, 1.5, 'hello', b'bytes', bytearray(b'ba'), None.__class__ and 'x' * 100):
    r.seed(seed)
    print(repr(seed)[:20], r.random(), r.getrandbits(40))
r.seed(7, version=1)
print(r.random())
r.seed('abc', version=1)
print(r.random())
r.seed(99)
st = r.getstate()
a = [r.random() for _ in range(3)]
r.setstate(st)
print(a == [r.random() for _ in range(3)], st[0], len(st[1]), st[1][:3], st[1][-1], st[2])
for i in range(700):
    r.random()
print(r.getstate()[1][-1], r.getstate()[1][5])
t(r.seed, [1, 2])
t(r.setstate, (3, (1,) * 625, None))
t(r.getrandbits, -1)
t(r.getrandbits, 'a')
t(r.randrange, 0)
t(r.randint, 5, 1)
t(r.setstate, (4, (), None))

b = _random.Random(5)
print(b.random(), b.getrandbits(3))
b.seed(5)
print(b.random())
b.seed()
print(0.0 <= b.random() < 1.0)
s = b.getstate()
print(len(s), s[-1], type(s))
t(b.setstate, 5)
t(b.setstate, s[:-1])
t(b.setstate, tuple(range(624)) + (625,))
t(b.setstate, tuple(range(624)) + (-1,))
t(b.setstate, tuple([-1] + list(range(623))) + (1,))
t(b.setstate, tuple(['a'] + list(range(623))) + (1,))
t(b.getrandbits, 0)
t(b.getrandbits, 2 ** 70)
t(_random.Random, 1, 2)
t(_random.Random, x=1)
b2 = _random.Random(2 ** 80)
print(b2.random())
b2.seed(-3)
print(b2.random())
b2.seed(2.5)
print(b2.random() == _random.Random(2.5).random())


class Sub(_random.Random):
    pass


print(Sub(3).random(), Sub().random() < 1)
print(random.SystemRandom().random() < 1, len(random.SystemRandom().randbytes(4)), random.SystemRandom().getrandbits(10) < 1024)
random.seed(1)
print(random.random(), random.randint(0, 9), random.getrandbits(5))
print(len(random.__all__), 'Random' in random.__all__)
