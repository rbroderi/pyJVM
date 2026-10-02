constructor = round
class Base:
    def __round__(self, *args): return ('base', args)
class Child(Base): pass
value = Child()
value.__round__ = lambda *args: ('instance', args)
print(constructor(value), round(value, None), round(value, 'raw'), value.__round__(3))
class Descriptor:
    def __get__(self, instance, owner):
        print('bind', instance is not None, owner.__name__)
        return lambda *args: ('descriptor', args)
class Managed:
    __round__ = Descriptor()
print(round(Managed()), round(Managed(), 2))
class Meta(type):
    def __round__(cls, *args): return (cls.__name__, args)
class Created(metaclass=Meta): pass
print(round(Created, 3))
class Missing: pass
class Disabled:
    __round__ = None
for item in [Missing(), Disabled(), None, 's', 1j]:
    try: round(item)
    except TypeError: print('TypeError')
class Failing:
    def __round__(self): raise ValueError('callback')
try: round(Failing())
except ValueError as error: print(str(error))
print(round(number=2.675, ndigits=2), round(ndigits=-1, number=25))
for operation in [lambda: round(), lambda: round(1, 2, 3), lambda: round(1, number=2), lambda: round(1, 2, ndigits=3), lambda: round(1, unknown=2)]:
    try: operation()
    except TypeError: print('signature')
print(int.__round__(25, -1), float.__round__(2.675, 2), True.__round__())
alias = (2.675).__round__
print(alias(2), alias(None))
for operation in [lambda: float.__round__(1), lambda: int.__round__(1.0), lambda: (1).__round__(1, 2)]:
    try: operation()
    except TypeError: print('descriptor error')
print('__round__' in dir(int), '__round__' in dir(1.0))
