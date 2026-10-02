# Selection uses > for max and < for min; sorting uses only <.
events = []
class Item:
    def __init__(self, value, name): self.value, self.name = value, name
    def __lt__(self, other):
        events.append('lt')
        return self.value < other.value
    def __gt__(self, other):
        events.append('gt')
        return self.value > other.value
items = [Item(2, 'first'), Item(1, 'middle'), Item(2, 'last')]
print(min(items).name, events)
events.clear()
print(max(items).name, events)
events.clear()
print([item.name for item in sorted(items)], all(event == 'lt' for event in events))
events.clear()
print([item.name for item in sorted(items, reverse=True)], all(event == 'lt' for event in events))
class Truth:
    def __bool__(self): return True
class Reflected:
    def __lt__(self, other): return NotImplemented
class Right:
    def __gt__(self, other): return Truth()
print(bool(Reflected() < Right()))
class Parent:
    def __lt__(self, other): return False
class Child(Parent):
    def __gt__(self, other): return True
print(Parent() < Child())
for value in [min, max, sorted]:
    try:
        value([object(), object()])
    except TypeError:
        print('unorderable')
floating = float(2**53)
ordered = sorted([2**53 + 1, floating, 2**53])
print(ordered[0] is floating, ordered[1] == 2**53, ordered[2] == 2**53 + 1)
print(sorted(['\U00010000', '\ue000', 'a']))
print(sorted([(2, 0), (1, 5), (1, 3)]), min([[2], [1]]))
print(sorted([b'z', b'\x80', bytearray(b'a')]))
nan = float('nan')
print(min(nan, 1.0) is nan, max(nan, 1.0) is nan)
print(min(1.0, nan), max(1.0, nan))
print(nan < 1, nan > 1, nan <= 1, nan >= 1)

minus, plus = -0.0, 0.0
print(minus < plus, minus > plus, minus <= plus, minus >= plus)
print(min(plus, minus) is plus, max(minus, plus) is minus)
zeros = sorted([plus, minus], reverse=True)
print(zeros[0] is plus, zeros[1] is minus)
