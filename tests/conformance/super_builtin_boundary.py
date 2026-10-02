class ParentMeta(type):
    @property
    def label(cls): return cls.__name__
    def inherited(cls): return cls.__name__
class Meta(ParentMeta):
    def __new__(cls, name, bases, namespace):
        namespace['made'] = 'made'
        return super().__new__(cls, name, bases, namespace)
    def __init__(cls, name, bases, namespace):
        super().__init__(name, bases, namespace)
    def read(cls):
        return super().label, super().inherited()
class Created(metaclass=Meta): pass
print(Created.made, Created.read())
class Base:
    def method(self): return 'base'
class Child(Base): pass
sup = super(Child, Child)
print(sup.method(Child()), sup.__self__ is Child, sup.__self_class__ is Child)
try:
    sup.__init__()
except TypeError:
    print('unbound builtin method')
value = Child()
print(super(Child, value).__init__())
class Hook:
    def __init_subclass__(cls):
        super().__init_subclass__()
        cls.hooked = True
class Hooked(Hook): pass
print(Hooked.hooked)

class LiteralOnly: ...
print(LiteralOnly.__name__)
class RankedMeta(type):
    @property
    def rank(cls): return 'meta'
class Ranked(metaclass=RankedMeta): rank = 'class'
print(Ranked.rank)
