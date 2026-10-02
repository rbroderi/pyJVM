inspect = dir
class Parent:
    shared = 1
    def method(self): pass
class Child(Parent):
    own = 2
value = Child()
value.z = 3
value.a = 4
print([name for name in inspect(value) if not name.startswith('_')])
print([name for name in dir(Child) if not name.startswith('_')])
del value.z
print('z' in dir(value), 'a' in dir(value), 'shared' in dir(value))
class Custom:
    def __dir__(self): return ('z', 'a', 'a')
custom = Custom()
custom.__dir__ = lambda: ['instance']
print(dir(custom), custom.__dir__())
class Descriptor:
    def __get__(self, instance, owner):
        print('bind', instance is not None, owner.__name__)
        return lambda: (name for name in ['b', 'a'])
class Managed:
    __dir__ = Descriptor()
print(dir(Managed()))
class Meta(type):
    def __dir__(cls): return ['z', cls.__name__]
class Created(metaclass=Meta): pass
print(dir(Created))
class Bad:
    __dir__ = None
class Invalid:
    def __dir__(self): return 7
class Mixed:
    def __dir__(self): return ['a', 1]
for item in [Bad(), Invalid(), Mixed()]:
    try: dir(item)
    except TypeError: print('TypeError')
class Failing:
    def __dir__(self): raise ValueError('callback')
try: dir(Failing())
except ValueError as error: print(str(error))
for invoke in [lambda: dir(1, 2), lambda: dir(object=1)]:
    try: invoke()
    except TypeError: print('signature')
print(sorted(object.__dir__(value)) == dir(value))
print(sorted([].__dir__()) == dir([]))
