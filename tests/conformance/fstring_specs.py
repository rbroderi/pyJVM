events = []
class Value:
    def __format__(self, spec):
        events.append('format:' + spec)
        return 'value:' + spec
    def __str__(self):
        events.append('str')
        return 'é🙂'
    def __repr__(self):
        events.append('repr')
        return 'é🙂'
def get():
    events.append('get')
    return Value()
def width():
    events.append('width')
    return 10
print(f'[{get():>{width()}}]')
print(events)
events.clear()
print(f'[{get()!s:>{width()}}]')
print(events)
print(f'{Value()!a:^20}')
print(f'{Value()!r:.1}')
number = 1.25
width_value, precision = 8, 2
print(f'{number:{width_value}.{precision}f}')
print(f'{number=:.1f}', f'{number=!r:>8}')
class Container:
    value = f'{17:#06x}'
    def method(self, value): return f'{value:.2f}'
print(Container.value, Container().method(2.675))
