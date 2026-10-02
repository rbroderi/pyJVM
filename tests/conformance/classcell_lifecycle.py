events = []
snapshots = []
class Meta(type):
    def __new__(meta, name, bases, namespace):
        snapshots.append(namespace.copy())
        value = super().__new__(meta, name, bases, namespace)
        if 'f' in namespace:
            events.append(value.f() is value)
        return value
    def __init__(cls, name, bases, namespace):
        if 'f' in namespace:
            events.append(cls.f() is cls)
        super().__init__(name, bases, namespace)
class Plain(metaclass=Meta):
    pass
class Reference(metaclass=Meta):
    @staticmethod
    def f(): return __class__
print('__classcell__' not in snapshots[0])
cell = snapshots[1]['__classcell__']
print(events, cell is Reference.f.__closure__[0])
print(cell.cell_contents is Reference, hasattr(Reference, '__classcell__'))
class DeferredMeta(type):
    def __new__(meta, name, bases, namespace):
        snapshots.append(namespace)
        try:
            print(namespace['f']())
        except NameError:
            print('unbound during new')
        return None
class Deferred(metaclass=DeferredMeta):
    @staticmethod
    def f(): return __class__
print(Deferred is None)
namespace = snapshots[-1]
try:
    print(namespace['__classcell__'].cell_contents)
except ValueError as error:
    print(str(error))
Late = type('Late', (), namespace)
print(Late.f() is Late, namespace['__classcell__'].cell_contents is Late)
Other = type('Other', (), namespace)
print(Late is not Other, Late.__name__, Other.__name__, Late.f() is Other)

def factory(value):
    class Local:
        def read(self): return __class__, value
    return Local
first, second = factory(1), factory(2)
print(first().read() == (first, 1), second().read() == (second, 2))
print(first.read.__closure__[0] is not second.read.__closure__[0])
print(len(first.read.__closure__), first.read.__closure__[1].cell_contents)
class DecoratedOriginal:
    @staticmethod
    def f(): return __class__
def decorate(original):
    print(original.f() is original)
    return None
@decorate
class Decorated:
    @staticmethod
    def f(): return __class__
print(Decorated is None, DecoratedOriginal.f() is DecoratedOriginal)
print(Reference.f.__closure__ is Reference.f.__closure__)
class Preserve(type):
    def __new__(meta, name, bases, namespace):
        value = super().__new__(meta, name, bases, namespace.copy())
        namespace['original'] = 'changed'
        value.added = 9
        return value
class Copied(metaclass=Preserve):
    original = 'original'
    def f(self): return __class__
print(Copied.original, Copied.added, Copied().f() is Copied)
def nested():
    class Local(metaclass=Preserve):
        def f(self): return __class__
    return Local
Local = nested()
print(Local.__qualname__, Local().f() is Local)
def nonlocal_class_name():
    __class__ = 'outer'
    class Local:
        nonlocal __class__
        before = __class__
        __class__ = 'changed'
        after = __class__
        def f(self): return __class__
    print(Local.before, Local.after, __class__, Local().f() is Local)
nonlocal_class_name()
