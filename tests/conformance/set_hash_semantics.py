class Value:
    calls = 0
    def __init__(self, key): self.key = key
    def __hash__(self):
        Value.calls += 1
        return self.key
    def __eq__(self, other): return isinstance(other, Value) and self.key == other.key
values = [Value(1), Value(2), Value(1)]
frozen = frozenset(values)
print(len(frozen), Value.calls)
first = hash(frozen)
print(hash(frozen) == first, Value.calls)
print(frozenset(frozen) is frozen, Value.calls)
print(Value(1) in frozen, len(set(values)))
class Unhashable:
    __hash__ = None
for constructor in [set, frozenset]:
    try: constructor([Unhashable()])
    except TypeError: print('TypeError')
    nan = float('nan')
    print(len(constructor([nan,nan])), nan in constructor([nan]))
    print(len(constructor([float('nan'),float('nan')])))
    print(len(constructor([1, True, 1.0, 1+0j])))
    print(hash(frozenset([1])) == hash(frozenset([1+0j])))
def broken():
    yield 1
    raise ValueError('callback')
value = set()
try: value.update(broken())
except ValueError as error: print(str(error), sorted(value))
for constructor in [set, frozenset]:
    try: constructor(broken())
    except ValueError as error: print(str(error))
print('add' in dir(frozenset()), 'union' in dir(frozenset()))
