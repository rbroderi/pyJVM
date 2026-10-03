for mutate in [lambda s: s.add(4), lambda s: s.remove(1), lambda s: s.clear()]:
    value = {1,2,3}
    iterator = iter(value)
    mutate(value)
    print(iterator.__length_hint__())
    for attempt in range(2):
        try: next(iterator)
        except RuntimeError as error: print(str(error))
    value.clear(); value.update([1,2,3])
    try: next(iterator)
    except RuntimeError: print('sticky')
value = {1,2,3}
try:
    for item in value:
        value.add(4)
except RuntimeError as error: print('loop', str(error))
value = {1,2,3}
def source():
    yield 4
    raise ValueError('callback')
iterator = iter(value)
try: value.update(source())
except ValueError: print('callback')
try: list(iterator)
except RuntimeError: print('list invalidated')
value = {1}; iterator = iter(value); next(iterator)
print(iterator.__length_hint__())
value.add(2)
try: next(iterator)
except RuntimeError: print('last yield is not exhaustion')
value = {1,2}; iterator = iter(value); value.add(3)
print(iterator.__length_hint__())
value.remove(3)
print(iterator.__length_hint__(), sorted(list(iterator)))
