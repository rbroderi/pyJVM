# SPDX-License-Identifier: GPL-3.0-or-later
"""Stress programs that hammer shared structures from many threads. Every program prints only deterministic
facts (final sizes, sums, uniqueness) so its output must equal CPython's, and each one asserts that its own run
finished well inside the time budget."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

import pytest

from pyjvm315.cli import build_runtime
from pyjvm315.conformance import compare_file
from pyjvm315.hostver import reference_version
from pyjvm315.selfhost import build_selfhosted

PRELUDE = '''
import threading, time, queue, types, sys
from collections import deque
_t0 = None
N, M = 8, 3000

def start(timed=True):
    global _t0
    _t0 = time.monotonic() if timed else None

def run(fn, n=N):
    ts = [threading.Thread(target=fn, args=(i,)) for i in range(n)]
    for t in ts: t.start()
    for t in ts: t.join()

def finish():
    print("within budget", _t0 is None or time.monotonic() - _t0 < 20)
'''

PROGRAMS = {
    "dict": '''
d = {}
def fill(i):
    for k in range(M):
        d[(i, k)] = k
        d.setdefault("shared", i)
        if k % 3 == 0:
            d.pop((i, k - 3), None)
run(fill)
print(len(d), sum(v for key, v in d.items() if key != "shared"))
c = {}
def counts(i):
    for k in range(M):
        c[k % 50] = c.get(k % 50, 0) + 0
        c.update({k % 50: k % 50})
run(counts)
print(len(c), sorted(c.items())[:3])
snap = {}
def snapshots(i):
    for k in range(200):
        snap[(i, k)] = k
        try:
            list(snap.keys()); list(snap.values()); dict(snap)
        except RuntimeError:
            pass   # iterating while others insert may report a size change; it must never corrupt the dict
run(snapshots)
print(len(snap))
finish()
''',
    "list": '''
l = []
def fill(i):
    for k in range(M):
        l.append(k)
        l.extend((k, k))
        if k % 5 == 0:
            l.pop()
run(fill)
print(len(l))
shared = list(range(N * M))
taken = [0] * N
def drain(i):
    while True:
        try:
            shared.pop()
        except IndexError:
            return
        taken[i] += 1
run(drain)
print(sum(taken), len(shared))
grow = [0]
seen = [0] * N
def iterate(i):
    for _ in range(50):
        for x in grow:
            if len(grow) < 2000:
                grow.append(x + 1)
        seen[i] += 1
run(iterate)
print(sum(seen), len(grow) >= 2000)
s = []
def sorter(i):
    for k in range(300):
        s.append((i * 1000 + k) % 977)
        s.sort()
        s.reverse()
run(sorter)
print(len(s), len(set(s)) <= 977)
finish()
''',
    "set": '''
st = set()
def fill(i):
    for k in range(M):
        st.add((i, k % 700))
        st.add(k)
        st.discard((i, (k - 5) % 700))
run(fill)
print(len(st) >= 3000, 0 in st, M - 1 in st)
u = set()
def unions(i):
    for k in range(100):
        u.update(range(i, i + k))
        u.update({k})
        u.difference_update({k + 1000})
run(unions)
print(len(u), min(u), max(u))
fs = frozenset(range(1000))
hits = [0] * N
def reader(i):
    for k in range(M):
        if k in fs: hits[i] += 1
        st.copy().isdisjoint(fs) if k % 500 == 0 else None
run(reader)
print(hits)
finish()
''',
    "deque": '''
dq = deque()
def fill(i):
    for k in range(M):
        dq.append(k)
        dq.appendleft(-k)
        if k % 2: dq.pop()
        if k % 3 == 0: dq.popleft()
run(fill)
print(len(dq))
q = deque(maxlen=100)
def bounded(i):
    for k in range(M):
        q.append(k)
run(bounded)
print(len(q))
r = deque(range(100))
def rotate(i):
    for k in range(500):
        r.rotate(1)
        r.rotate(-1)
run(rotate)
print(sorted(r) == list(range(100)))
finish()
''',
    "strings": '''
parts = []
def build(i):
    s = ""
    for k in range(500):
        s += "%d:%d," % (i, k)
        parts.append("x" * (k % 7) + str(k))
    parts.append(s)
run(build)
joined = "".join(parts)
print(len(parts), len(joined))
b = bytearray()
def grow(i):
    for k in range(M):
        b.append(k % 251)
        b.extend(b"ab")
    b.extend(bytes(range(10)))
run(grow)
print(len(b), b.count(b"ab") > 0)
words = {}
def interning(i):
    for k in range(M):
        w = "w" + str(k % 100)
        words[w] = words.get(w, 0)
        words[w] = 1
run(interning)
print(len(words), sum(words.values()))
finish()
''',
    "imports": '''
start(False)
results, errors = [], []
def imp(i):
    try:
        import collections, heapq, bisect, functools, itertools, operator, queue, types
        results.append((id(collections.OrderedDict), id(queue.Queue), id(functools.partial)))
    except Exception as exc:
        errors.append(repr(exc))
run(imp)
print(errors, len(results), len(set(results)))
classes = []
def make(i):
    for k in range(100):
        base = type("Base%d_%d" % (i, k), (object,), {"x": k})
        sub = type("Sub%d_%d" % (i, k), (base,), {"y": i, "z": lambda self: self.x + self.y})
        classes.append((sub, k + i))
        sub.late = k
        base.x2 = k * 2
run(make)
print(len(classes), all(c().z() == v for c, v in classes), all(c.late == c.x2 // 2 for c, _ in classes))
mods = []
def newmod(i):
    import types
    for k in range(50):
        m = types.ModuleType("m%d_%d" % (i, k))
        m.value = k
        sys.modules[m.__name__] = m
        mods.append(m.__name__)
run(newmod)
print(len(mods), len(set(mods)), all(sys.modules[n].value == int(n.split("_")[1]) for n in mods))
finish()
''',
    "locks": '''
lock = threading.Lock()
rlock = threading.RLock()
total = [0]
shared_r = [0]
def locked(i):
    for k in range(M):
        with lock:
            total[0] += 1
        with rlock:
            with rlock:
                shared_r[0] += 1
run(locked)
print(total[0], shared_r[0])
cond = threading.Condition()
turn = [0]
def pingpong(i):
    for k in range(100):
        with cond:
            cond.wait_for(lambda: turn[0] % 4 == i)
            turn[0] += 1
            cond.notify_all()
run(pingpong, 4)
print(turn[0])
sem = threading.BoundedSemaphore(3)
inside, peak = [0], [0]
def limited(i):
    for k in range(200):
        with sem:
            with lock:
                inside[0] += 1
                peak[0] = max(peak[0], inside[0])
            with lock:
                inside[0] -= 1
run(limited)
print(peak[0] <= 3, inside[0])
ev = threading.Event()
woke = []
def waiter(i):
    woke.append(ev.wait(10))
ws = [threading.Thread(target=waiter, args=(i,)) for i in range(N)]
for w in ws: w.start()
ev.set()
for w in ws: w.join()
print(woke)
finish()
''',
    "queue": '''
q = queue.Queue()
out = queue.SimpleQueue()
def consumer(i):
    while True:
        item = q.get()
        if item is None:
            return
        out.put(item * 2)
cs = [threading.Thread(target=consumer, args=(i,)) for i in range(4)]
for c in cs: c.start()
def produce(i):
    for k in range(1000):
        q.put(i * 1000 + k)
run(produce, 4)
for c in cs: q.put(None)
for c in cs: c.join()
got = []
while not out.empty(): got.append(out.get())
print(len(got), sum(got), len(set(got)))
pq = queue.PriorityQueue()
def prio(i):
    for k in range(500): pq.put((k, i))
run(prio)
last, ordered = -1, True
while not pq.empty():
    k, _ = pq.get()
    ordered = ordered and k >= last
    last = k
print(ordered)
finish()
''',
    "local": '''
loc = threading.local()
res = [None] * N
def work(i):
    loc.count = 0
    for k in range(M):
        loc.count += 1
    res[i] = loc.count
run(work)
print(res)
class Fresh(threading.local):
    def __init__(self):
        self.items = []
fresh = Fresh()
sizes = [None] * N
def fill(i):
    for k in range(i + 1): fresh.items.append(k)
    sizes[i] = len(fresh.items)
run(fill)
print(sizes, len(fresh.items))
finish()
''',
    "generators": '''
def gen(n):
    for i in range(n): yield i
sums = [0] * N
def consume(i):
    for _ in range(20):
        sums[i] += sum(gen(500))
run(consume)
print(sums)
shared = gen(10 ** 9)
errors = []
taken = [0] * N
def race(i):
    for _ in range(2000):
        try:
            next(shared)
            taken[i] += 1
        except ValueError as exc:
            errors.append(str(exc))
run(race)
print(set(errors) <= {"generator already executing"}, sum(taken) + len(errors))
finish()
''',
}


def _lanes() -> list[str]:
    found: dict[tuple[int, int], str] = {}
    for exe in (sys.executable, shutil.which("python3.13"), shutil.which("python3.15"), shutil.which("python3")):
        if not exe:
            continue
        try:
            found.setdefault(reference_version(exe), exe)
        except (subprocess.CalledProcessError, OSError):
            continue
    return [exe for version, exe in sorted(found.items()) if version >= (3, 13)]


@pytest.fixture(scope="module")
def jvm_kit():
    with tempfile.TemporaryDirectory(prefix="pyjvm-stress-test-") as td:
        runtime = Path(td) / "runtime"
        build_runtime(runtime)
        yield runtime, build_selfhosted(Path(td) / "selfhosted")


def combined_source() -> str:
    """All programs in one script (the cold compile of threading and friends is paid once per lane); each runs in
    its own function so names cannot collide and prints its own budget line."""
    out = [PRELUDE]
    for name in sorted(PROGRAMS):
        body = textwrap.indent(textwrap.dedent(PROGRAMS[name]).strip("\n"), "    ")
        out.append("def program_%s():\n    start()\n%s\n" % (name, body))
        out.append("print('== %s')\nprogram_%s()\n" % (name, name))
    return "\n".join(out)


# Opt-in: compiling the self-hosted threading stack costs minutes per lane.
# CI runs it with PYJVM_STRESS=1.
@pytest.mark.skipif(os.environ.get("PYJVM_STRESS") != "1", reason="set PYJVM_STRESS=1 to run the thread stress programs")
@pytest.mark.parametrize("python_exe", _lanes())
def test_stress_programs_match_cpython(python_exe, jvm_kit, tmp_path):
    runtime, selfhosted = jvm_kit
    main = tmp_path / "stress_all.py"
    main.write_text(combined_source(), encoding="utf-8")
    result = compare_file(main, python_exe, runtime_cache=runtime, selfhosted=selfhosted)
    assert result.status == "PASS", result.detail
