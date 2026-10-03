for constructor in [set, frozenset]:
    a, b = constructor([1,2,3]), constructor([3,4])
    for other in [set(b), frozenset(b)]:
        for result in [a | other, a & other, a - other, a ^ other]:
            print(type(result).__name__, sorted(result))
    for result in [a.union([4],[5]), a.intersection([2,3],[3,4]), a.difference([1],[2]), a.symmetric_difference([3,4])]:
        print(type(result).__name__, sorted(result))
    print(a.issubset([1,2,3,4]), a.issuperset([1,2]), a.isdisjoint([4,5]))
    print(a < constructor([1,2,3,4]), a <= a, a >= a, a > b, a < b)
    print(a == [1,2,3], a != [1,2,3])
    for call in [lambda: a | [4], lambda: a < [4], lambda: a.symmetric_difference()]:
        try: call()
        except TypeError: print('TypeError')
value = {1,2}
value.update([2,3], [4])
print(sorted(value))
value.intersection_update([2,3,4], [3,4,5])
print(sorted(value))
value.difference_update([3])
value.symmetric_difference_update([4,5])
print(sorted(value))
copy = value.copy()
copy.add(6)
print(sorted(value), sorted(copy))
print(len(set([True,1,1.0])))
