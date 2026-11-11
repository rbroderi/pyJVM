import gc
import sys

print(gc.isenabled())
gc.disable()
print(gc.isenabled())
gc.enable()
print(gc.isenabled())

t = gc.get_threshold()
print(len(t), all(isinstance(x, int) for x in t))
gc.set_threshold(t[0], t[1], t[2])
print(gc.get_threshold() == t)
old = gc.get_threshold()
gc.set_threshold(1000, 11)
print(gc.get_threshold()[:2])
gc.set_threshold(*old)

c = gc.get_count()
print(len(c), all(isinstance(x, int) for x in c))

n = gc.collect()
print(isinstance(n, int), n >= 0)
print(isinstance(gc.collect(0), int), isinstance(gc.collect(1), int), isinstance(gc.collect(2), int))
try:
    gc.collect(3)
except ValueError as e:
    print("ValueError", e)

print(type(gc.garbage), gc.garbage)
print(type(gc.callbacks), gc.callbacks)

events = []


def cb(phase, info):
    events.append((phase, info["generation"], "collected" in info))


gc.callbacks.append(cb)
gc.collect()
gc.callbacks.remove(cb)
print(events[:2])

print(gc.DEBUG_STATS, gc.DEBUG_COLLECTABLE, gc.DEBUG_UNCOLLECTABLE, gc.DEBUG_SAVEALL, gc.DEBUG_LEAK)
print(gc.get_debug())
gc.set_debug(0)
print(gc.freeze is not None, gc.get_freeze_count() >= 0)
print(isinstance(gc.get_stats(), list), len(gc.get_stats()))
print(gc.is_tracked([]), gc.is_tracked(1), gc.is_finalized(object()))
