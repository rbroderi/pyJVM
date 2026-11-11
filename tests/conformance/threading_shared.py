# Many threads mutating shared containers: totals are deterministic because each operation is atomic.
import threading
from collections import deque

N, M = 8, 2000

def run(fn):
    ts = [threading.Thread(target=fn, args=(i,)) for i in range(N)]
    for t in ts: t.start()
    for t in ts: t.join()

d = {}
def fill_dict(i):
    for k in range(M):
        d[(i, k)] = k
        d.setdefault("shared", 0)
run(fill_dict)
print(len(d))

l = []
def fill_list(i):
    for k in range(M): l.append(k)
run(fill_list)
print(len(l), sum(l))

s = set()
def fill_set(i):
    for k in range(M): s.add((i, k)); s.add(k)
run(fill_set)
print(len(s))

dq = deque()
def fill_deque(i):
    for k in range(M):
        dq.append(k)
        if k % 2: dq.popleft()
run(fill_deque)
print(len(dq))

ba = bytearray()
def fill_ba(i):
    for k in range(M): ba.append(k % 256)
run(fill_ba)
print(len(ba), sum(ba))

# pops are atomic: every item is delivered to exactly one thread
work = list(range(N * M))
taken = [[] for _ in range(N)]
def drain(i):
    while True:
        try:
            taken[i].append(work.pop())
        except IndexError:
            return
run(drain)
print(sum(len(t) for t in taken), len({x for t in taken for x in t}))

shared = {"n": 0}
lock = threading.Lock()
def incr(i):
    for _ in range(M):
        with lock: shared["n"] += 1
run(incr)
print(shared["n"])

# string building and sorting
parts = []
def build(i):
    for k in range(200): parts.append("%d-%d" % (i, k))
run(build)
print(len(parts), len("".join(parts)) > 0, len(set(parts)))
parts.sort()
print(parts[0], parts[-1])

# iteration while another thread mutates must not crash badly
stop = threading.Event()
m = {i: i for i in range(100)}
def mutate():
    k = 100
    while not stop.is_set():
        m[k] = k; m.pop(k - 50, None); k += 1
mt = threading.Thread(target=mutate); mt.start()
ok = True
for _ in range(300):
    try:
        for key in m: pass
        list(m.items())
    except RuntimeError as exc:
        ok = ok and "changed size" in str(exc)
stop.set(); mt.join()
print(ok)
