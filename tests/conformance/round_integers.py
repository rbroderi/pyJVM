for value in [0, 1, -1, 5, 15, 25, 35, 45, 55, 65, 95, -5, -15, -25, -35, 2**63-1, 2**63, -(2**63), 10**100+5*10**80, -(10**100+5*10**80), True, False]:
    print(round(value), type(round(value)).__name__)
    for digits in [None, 0, 1, -1, -2, -20, -80, -100, -101, 10**100, -1000]:
        print(round(value, digits), type(round(value, digits)).__name__)
class Index:
    def __index__(self):
        print('index')
        return -1
print(round(25, Index()), round(2.55, Index()))
class Descriptor:
    def __get__(self, instance, owner): return lambda: 2
class Managed:
    __index__ = Descriptor()
print(round(2.675, Managed()))
class Bad:
    def __index__(self): return 1.0
class OnlyInt:
    def __int__(self): return 2
for digits in [1.0, '2', Bad(), OnlyInt()]:
    try: round(1, digits)
    except TypeError: print('TypeError')
class Meta(type):
    def __index__(cls): return 2
class ClassIndex(metaclass=Meta): pass
print(round(2.675, ClassIndex))
