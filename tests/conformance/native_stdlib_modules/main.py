import itertools as it
import math
import errno
import time
import contextvars
from collections import deque, defaultdict

print(list(it.islice(it.count(3, 2), 4)))
print(list(it.chain([1, 2], (3,), "ab")))
print(list(it.chain.from_iterable([[1], [2, 3]])))
print(list(it.accumulate([1, 2, 3, 4])))
print(list(it.product("ab", repeat=2)))
print(list(it.permutations(range(3), 2)))
print(list(it.combinations("abcd", 2)))
print([(k, list(g)) for k, g in it.groupby("aabbbc")])
print(list(it.zip_longest("ab", "xyz", fillvalue="-")))
print(list(it.takewhile(lambda v: v < 3, range(10))))
print(list(it.starmap(pow, [(2, 3), (3, 2)])))

print(math.floor(2.7), math.ceil(2.1), math.gcd(12, 18), math.isqrt(17), math.factorial(6))
print(round(math.sqrt(2), 6), math.copysign(3, -0.0), math.isnan(math.nan), math.inf > 10**300)
print(math.comb(5, 2), math.perm(5, 2), math.fsum([0.1] * 10), math.hypot(3, 4))

print(errno.ENOENT, errno.EEXIST, errno.errorcode[errno.EACCES])

t0 = time.monotonic()
t1 = time.perf_counter()
print(time.time() > 1_000_000_000, time.monotonic() >= t0, time.perf_counter() >= t1)

var = contextvars.ContextVar("v", default=7)
print(var.get())
token = var.set(8)
print(var.get())
ctx = contextvars.copy_context()
var.reset(token)
print(var.get(), ctx.run(var.get))

d = deque([1, 2, 3], maxlen=3)
d.append(4)
d.appendleft(0)
print(d, d.popleft(), len(d))
d.rotate(1)
print(list(d))

dd = defaultdict(list)
dd["a"].append(1)
dd["b"]
print(sorted(dd.items()), dd.default_factory is list)
