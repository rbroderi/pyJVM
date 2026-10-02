print(NotImplemented, Ellipsis, ... is Ellipsis)
try:
    bool(NotImplemented)
except TypeError:
    print('boolean rejected')
events = []
class Left:
    def __add__(self, other):
        events.append('left')
        return NotImplemented
    def __eq__(self, other):
        events.append('eq left')
        return NotImplemented
class Right:
    def __radd__(self, other):
        events.append('right')
        return 9
    def __eq__(self, other):
        events.append('eq right')
        return NotImplemented
print(Left() + Right(), events)
events.clear()
a, b = Left(), Right()
print(a == b, a != b, a == a, events)
class Base:
    def __add__(self, other):
        events.append('base')
        return 4
class Child(Base):
    def __radd__(self, other):
        events.append('child')
        return 5
events.clear()
print(Base() + Child(), events)
print(0.0 == -0.0, float('nan') == float('nan'), True == 1.0)
print(2**100 == float(2**100), 2**100 + 1 == float(2**100))
