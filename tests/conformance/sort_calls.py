# Comparison-call sequences of list.sort / sorted must match CPython's Timsort exactly.

class Rng:
    def __init__(self, seed):
        self.s = seed * 2654435761 % 4294967296 + 12345

    def next(self):
        self.s = (self.s * 1103515245 + 12345) % 2147483648
        return self.s >> 8

    def randrange(self, a, b=None):
        if b is None:
            a, b = 0, a
        return a + self.next() % (b - a)

    def random(self):
        return (self.next() % 1000) / 1000

LOG = []


class V:
    def __init__(self, k, tag=0):
        self.k = k
        self.tag = tag

    def __lt__(self, other):
        LOG.append((self.k, other.k))
        return self.k < other.k


def trace(data, **kw):
    del LOG[:]
    items = [V(x, i) for i, x in enumerate(data)]
    out = sorted(items, **kw)
    h = 0
    for a, b in LOG:
        h = (h * 1000003 + a * 31 + b) % 1000000007
    res = [(v.k, v.tag) for v in out]
    print(len(data), len(LOG), h, res[:12], sum(t * (i + 1) for i, (k, t) in enumerate(res)))


for n in (0, 1, 2, 3, 5, 8, 17, 31, 64, 65, 100, 127, 128, 129, 200, 300, 513):
    rnd = Rng(n)
    trace([rnd.randrange(1000) for _ in range(n)])
    trace([rnd.randrange(5) for _ in range(n)])
    trace(list(range(n)))
    trace(list(range(n, 0, -1)))
    trace([3] * n)
    trace([rnd.randrange(7) for _ in range(n)], reverse=True)
    trace(list(range(n)), reverse=True)
    trace([rnd.randrange(50) for _ in range(n)], key=lambda v: V(v.k // 3))

# partially ordered data: runs, descending with equal blocks, organ pipe, sawtooth
rnd = Rng(7)
runs = []
for _ in range(40):
    r = rnd.randrange(1, 40)
    s = rnd.randrange(100)
    runs.extend(sorted((s + rnd.randrange(30) for _ in range(r)), reverse=rnd.random() < 0.5))
trace(runs)
trace([5, 5, 4, 4, 4, 3, 3, 9, 9, 1, 1, 1, 0])
trace([2, 2, 2, 1, 1, 3, 3, 3, 3, 2])
trace([4, 4, 4, 3, 5])
trace([1, 1, 2, 2, 1])
trace(list(range(100)) + list(range(100, 0, -1)))
trace([i % 10 for i in range(300)])
trace(list(range(0, 400, 2)) + list(range(1, 400, 2)))
trace(list(range(200)) + [0] * 50 + list(range(200)))

# galloping-heavy shapes and larger inputs
blocks = []
for b in range(30):
    blocks.extend(range(b * 7, b * 7 + 40, 1 + b % 3))
trace(blocks)
trace(list(range(1000, 2000)) + list(range(0, 1000)))
trace([i * 37 % 1009 for i in range(1500)])
trace([(i // 50) * 3 + (i % 2) for i in range(900)])
trace([x for i in range(40) for x in range(i, i + 60, 2)])
trace([Rng(11).randrange(100000) for _ in range(1)] + [Rng(i).randrange(100) for i in range(2100)])
trace(list(range(2000, 0, -3)) + list(range(0, 2000, 5)))

# key + reverse stability
words = ["bb", "a", "cc", "d", "ee", "f", "gg", "h"]
print(sorted(words, key=len))
print(sorted(words, key=len, reverse=True))
pairs = [(i % 3, i) for i in range(20)]
print(sorted(pairs, key=lambda p: p[0], reverse=True))
