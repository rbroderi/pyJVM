def broken():
    yield 1
    raise ValueError('callback')
value = {1,2,3}; alias = value
try: value.difference_update(broken())
except ValueError: print('difference', value is alias, sorted(value))
value = {1,2,3}
try: value.difference_update([1, []])
except TypeError: print('unhashable', sorted(value))
value.difference_update(value)
print(sorted(value))
for operation in ['intersection_update', 'symmetric_difference_update']:
    value = {1,2}; alias = value
    try: getattr(value, operation)(broken())
    except ValueError: print(operation, value is alias, sorted(value))
class Count:
    calls = 0
    def __hash__(self):
        Count.calls += 1
        return 1
item = Count()
source = {item}
target = set()
target.update(source)
print(Count.calls)
target.difference_update(source)
print(Count.calls, len(target))
