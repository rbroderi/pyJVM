constructor = format
class Base:
    def __format__(self, spec): return 'base:' + spec
class Child(Base): pass
value = Child()
value.__format__ = lambda spec: 'instance'
print(format(value), constructor(value, 'spec'), f'{value:custom}')
print(value.__format__('spec'))
class Descriptor:
    def __get__(self, instance, owner):
        print('bind', instance is not None, owner.__name__)
        return lambda spec: 'descriptor:' + spec
class Managed:
    __format__ = Descriptor()
print(format(Managed(), 'x'))
class Meta(type):
    def __format__(cls, spec): return cls.__name__ + ':' + spec
class Created(metaclass=Meta): pass
print(format(Created, 'meta'), f'{Created:meta}')
class Plain:
    def __str__(self): return 'plain'
print(format(Plain()), object.__format__(value, '') == str(value))
class Bad:
    def __format__(self, spec): return 1
class Missing:
    __format__ = None
for item,spec in [(Bad(), ''), (Missing(), ''), (Plain(), 'x'), ('s', None), (None, 'x')]:
    try: format(item, spec)
    except TypeError: print('TypeError')
print('a'.__format__('>4s'), str.__format__('b', '^5'), int.__format__(17, '04x'))
for operation in [lambda: format(), lambda: format(1, '', ''), lambda: format(value=1), lambda: str.__format__(1, '')]:
    try: operation()
    except TypeError: print('signature error')
