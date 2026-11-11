# threading.local: per-thread attribute dictionaries.
import threading

data = threading.local()
data.x = 1
seen = []
def other():
    seen.append(hasattr(data, "x"))
    data.x = 2
    seen.append(data.x)
t = threading.Thread(target=other); t.start(); t.join()
print(seen, data.x)

class Counter(threading.local):
    initialized = []
    def __init__(self, start):
        self.start = start
        Counter.initialized.append((threading.current_thread().name, start))
c = Counter(10)
out = []
def use():
    out.append(c.start)
    c.start += 1
    out.append(c.start)
ts = [threading.Thread(target=use, name="t%d" % i) for i in range(3)]
for t in ts: t.start(); t.join()
print(out, c.start, sorted(Counter.initialized, key=str))

l = threading.local()
l.a = 1
print(l.__dict__)
del l.a
print(hasattr(l, "a"))
try:
    del l.a
except AttributeError:
    print("AttributeError")
l.__dict__["z"] = 5
print(l.z)

# values are really private per thread
store = threading.local()
results = {}
barrier = threading.Barrier(4)
def private(i):
    store.v = i
    barrier.wait()
    results[i] = store.v
ts = [threading.Thread(target=private, args=(i,)) for i in range(4)]
for t in ts: t.start()
for t in ts: t.join()
print(sorted(results.items()))
