# Several threads importing the same modules at once all see one fully initialised module.
import threading

results = []
errors = []
def imp():
    try:
        import collections, heapq, bisect, functools, itertools, queue
        results.append((id(collections.OrderedDict), id(queue.Queue), collections.OrderedDict.__name__))
    except Exception as exc:
        errors.append(repr(exc))
ts = [threading.Thread(target=imp) for _ in range(8)]
for t in ts: t.start()
for t in ts: t.join()
print(errors, len(results), len({r[0] for r in results}), len({r[1] for r in results}), results[0][2])

# classes created concurrently are distinct and correct
classes = []
def make(i):
    for k in range(50):
        C = type("C%d_%d" % (i, k), (object,), {"v": k, "get": lambda self: self.v})
        classes.append((C, k))
ts = [threading.Thread(target=make, args=(i,)) for i in range(4)]
for t in ts: t.start()
for t in ts: t.join()
print(len(classes), all(C().get() == k for C, k in classes), len({id(C) for C, _ in classes}))
