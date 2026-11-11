# A running generator cannot be resumed again, from itself or from another thread.
import threading

gen = None
def selfref():
    try:
        next(gen)
    except ValueError as exc:
        yield str(exc)
gen = selfref()
print(next(gen))

inside = threading.Event()
release = threading.Event()
def slow():
    inside.set()
    release.wait()
    yield 1
g = slow()
out = []
t = threading.Thread(target=lambda: out.append(next(g)))
t.start()
inside.wait()
try:
    next(g)
except ValueError as exc:
    print("ValueError", exc)
release.set()
t.join()
print(out)

# independent generators advance fine in parallel
def count(n):
    for i in range(n): yield i
results = []
def drain(n):
    results.append(sum(count(n)))
ts = [threading.Thread(target=drain, args=(1000,)) for _ in range(6)]
for x in ts: x.start()
for x in ts: x.join()
print(results)
