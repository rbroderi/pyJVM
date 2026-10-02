choose_min, choose_max, order = min, max, sorted
print(choose_min(3, 1, 2), choose_max([3, 1, 2]), order((3, 1, 2)))
print(min('bac'), max('bac'), sorted('bac'))
print(min([], default=None), max((), default=7, key=1))
print(min(1, 2, 3, key=lambda x: -x), max(1, 2, 3, key=lambda x: -x))
print(sorted([2, 1], reverse=None), sorted([2, 1], reverse=0.0))
values = [(2, 'first'), (1, 'middle'), (2, 'last')]
events = []
def key(value):
    events.append(value[1])
    return value[0]
print(min(values, key=key), events)
events.clear()
print(max(values, key=key), events)
events.clear()
print(sorted(values, key=key, reverse=True), events, values)
print(sorted([None], key=None))
for operation in [min, max]:
    for args, kwargs in [((), {}), (((),), {}), ((1,), {}), ((1, 2), {'default': None}), (([1],), {'key': 1}), (([1],), {'invalid': 2})]:
        try:
            operation(*args, **kwargs)
        except TypeError:
            print('TypeError')
        except ValueError:
            print('ValueError')
for args, kwargs in [((), {}), (([], None), {}), ((), {'iterable': []}), (([1],), {'key': 1}), (([],), {'default': 2})]:
    try:
        sorted(*args, **kwargs)
    except TypeError:
        print('TypeError')
class Namespace:
    small = min(2, 3)
    large = max([2, 3])
    ordered = sorted([2, 1], reverse=True)
print(Namespace.small, Namespace.large, Namespace.ordered)
def shadow():
    min = lambda *values: 'shadow'
    return min(1, 2)
print(shadow())
