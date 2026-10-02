events = []
def combine(a, b):
    events.append((a, b))
    return a + b
m = map(combine, [1, 2, 3], [10, 20])
print(events, iter(m) is m, isinstance(m, map), type(m) is map)
print(next(m), events)
print(list(m), events)
try:
    next(m)
except StopIteration:
    print("done")
print(list(map(ord, iter(["a", "b"]))))
first = iter([1, 2, 3])
second = iter([10])
n = map(combine, first, second)
print(next(n))
try:
    next(n)
except StopIteration:
    print("shortest")
print(next(first))

def stop(value):
    if value == 1:
        raise StopIteration
    return value
p = map(stop, [1, 2])
try:
    next(p)
except StopIteration:
    print("callback stopped")
print(next(p))
