constructor = frozenset
value = constructor([1, 2, 2, 3])
print(sorted(value), len(value), bool(value), type(value).__name__)
print(isinstance(value, frozenset), isinstance(value, set), isinstance(set(value), set))
print(frozenset(value) is value, value.copy() is value, frozenset() == frozenset())
print(str(frozenset()), str(frozenset([1])))
print(len(frozenset([1, True, 1.0])), frozenset([1]) == {True}, {True} == frozenset([1]))
print(hash(frozenset()), hash(frozenset([1,2,3])), hash(frozenset([3,2,1])))
print(hash(frozenset([1])) == hash(frozenset([True,1.0])))
lookup = {frozenset([1, 2]): 'key'}
print(lookup[frozenset([2, 1])])
nested = frozenset([frozenset([1]), frozenset([2]), frozenset([1])])
print(len(nested), frozenset([1]) in nested, {1} in nested)
for member in [[], {}, set(), bytearray()]:
    try: frozenset([member])
    except TypeError: print('unhashable')
for name in ['add', 'remove', 'discard', 'clear', 'pop', 'update']:
    print(name, hasattr(value, name))
    try: getattr(value, name)(1)
    except AttributeError: print('immutable')
print(frozenset.copy(value) is value, value.__contains__(2))
for call in [lambda: frozenset([], []), lambda: frozenset(iterable=[]), lambda: set.copy(value), lambda: frozenset.copy(set())]:
    try: call()
    except TypeError: print('signature')
