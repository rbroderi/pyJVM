for constructor in [set, frozenset]:
    value = constructor([1,2,3]); alias = value
    value |= frozenset([3,4])
    print(value is alias, sorted(value), sorted(alias), type(value).__name__)
    alias = value
    value &= set([2,3,4])
    print(value is alias, sorted(value), sorted(alias))
    alias = value
    value ^= frozenset([3,5])
    print(value is alias, sorted(value), sorted(alias))
    alias = value
    value -= set([2])
    print(value is alias, sorted(value), sorted(alias))
value = {1,2}; alias = value
value |= value
value &= value
print(value is alias, sorted(value))
value ^= value
print(value is alias, sorted(value))
value = {1,2}; alias = value
value -= value
print(value is alias, sorted(value))
class Holder: pass
holder = Holder(); holder.value = {1}; alias = holder.value
holder.value |= {2}
items = [holder.value]
items[0] ^= {1,3}
print(items[0] is alias, holder.value is alias, sorted(alias))
value = {1}
print(value.__ior__([2]) is NotImplemented, value.__ior__({2}) is value)
print(set.__isub__(value, {1}) is value, sorted(value))
for invoke in [lambda: value.__iand__(), lambda: set.__ior__(frozenset(), set())]:
    try: invoke()
    except TypeError: print('TypeError')
