value = set(range(20)); iterator = iter(value)
value.clear(); value.update(range(20))
print(sorted(list(iterator)))
for operation in ['union', 'intersection', 'difference', 'symmetric_difference']:
    value = {1,2,3}; iterator = iter(value)
    if operation == 'union': value |= {1,2}
    elif operation == 'intersection': value &= {1,2,3}
    elif operation == 'difference': value -= set()
    else: value ^= set()
    print(sorted(list(iterator)))
value = {1,2,3}; iterator = iter(value)
value.add(1); value.discard(4); value.update(value)
print(sorted(list(iterator)))
class Count:
    calls = 0
    def __hash__(self):
        Count.calls += 1
        return 1
value = {Count()}; iterator = iter(value)
next(iterator)
print(Count.calls)
value = {1,2,3}; iterator = iter(value)
value.remove(1); value.add(4)
print(sorted(list(iterator)))
value = set(range(20)); iterator = iter(value)
first = next(iterator)
value.clear(); value.update(range(20))
remaining = list(iterator)
print(len(remaining), all(item in value for item in remaining))
