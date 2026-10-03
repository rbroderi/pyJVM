for constructor in [set, frozenset]:
    value = constructor([1,2,3])
    iterator = value.__iter__()
    print(type(iterator).__name__, iter(iterator) is iterator, iterator.__iter__() is iterator)
    print(iterator.__length_hint__())
    bound_next = iterator.__next__
    bound_hint = iterator.__length_hint__
    seen = [bound_next(), next(iterator), bound_next()]
    print(sorted(seen), bound_hint())
    try: next(iterator)
    except StopIteration: print('exhausted')
    if constructor is set: value.add(4)
    print(bound_hint())
    try: bound_next()
    except StopIteration: print('still exhausted')
    for method in [iterator.__iter__, bound_next, bound_hint]:
        try: method(1)
        except TypeError: print('arity')
value = set(); iterator = iter(value)
try: next(iterator)
except StopIteration: print('empty')
value.add(1)
try: next(iterator)
except StopIteration: print('empty stays exhausted')
value = set(); iterator = iter(value); value.add(1)
try: next(iterator)
except RuntimeError: print('empty before first next')
