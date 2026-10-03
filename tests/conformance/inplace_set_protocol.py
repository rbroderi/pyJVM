class Base:
    def __ior__(self, other):
        print('inplace', other)
        return self
value = Base(); alias = value
value.__ior__ = lambda other: None
value |= 3
print(value is alias)
class Descriptor:
    def __get__(self, instance, owner):
        print('bind', instance is not None, owner.__name__)
        return lambda other: other + 1
class Custom:
    __iand__ = Descriptor()
value = Custom(); value &= 3
print(value)
class Fallback:
    def __ixor__(self, other): return NotImplemented
    def __xor__(self, other): return other + 2
value = Fallback(); value ^= 3
print(value)
class Right:
    def __ror__(self, other): return ('reflected', sorted(other))
value = {1}; value |= Right()
print(value)
class Difference:
    def __isub__(self, other): return other + 4
value = Difference(); value -= 3
print(value)
class Disabled:
    __ior__ = None
value = Disabled()
try: value |= 1
except TypeError: print('TypeError')
for operand in [[], 'a', 1]:
    value = {1}; alias = value
    try: value |= operand
    except TypeError: print('TypeError', value is alias, sorted(value))
value = 7; value &= 3; value |= 8; value ^= 2; value -= 1
print(value)
