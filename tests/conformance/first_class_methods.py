class Base:
    def collect(*args): return args
    normal = collect
    by_class = classmethod(collect)
    by_static = staticmethod(collect)
class Child(Base): pass
obj = Child()
print(obj.collect(1) == (obj, 1), obj.normal(2) == (obj, 2))
print(Base.by_class(1) == (Base, 1), obj.by_class(2) == (Child, 2))
print(Child.by_static(3), obj.by_static(4))
print(Child.by_class.__self__ is Child, obj.collect.__self__ is obj)
print(Child.by_class.__func__ is Base.__dict__['collect'])
print(super(Child, Child).by_class(5) == (Child, 5))
print(super(Child, obj).by_class(6) == (Child, 6))
raw_class = Base.__dict__['by_class']
raw_static = Base.__dict__['by_static']
print(isinstance(raw_class, classmethod), isinstance(raw_static, staticmethod))
print(raw_class.__func__ is raw_class.__wrapped__, raw_static.__get__(None, Child)(7))
print(raw_class.__get__(0, int)(8) == (int, 8), raw_class.__get__(0)(9) == (int, 9))
print(raw_static(10), callable(raw_static), callable(raw_class))
print(isinstance(classmethod(1), classmethod), isinstance(staticmethod(None), staticmethod))
print(raw_class.__name__, raw_class.__qualname__, raw_class.__module__)
print(raw_class.__func__.__dict__)
raw_class.__func__.tag = 'extra'
print(raw_class.__func__.__dict__, raw_class.__func__.tag)
raw_class.extra = 42
print(raw_class.extra, raw_class.__dict__['extra'])
del raw_class.extra
print(hasattr(raw_class, 'extra'))
obj.by_static = lambda value: value + 10
print(obj.by_static(5), Child.by_static(5))
for operation in [lambda: classmethod(1).__get__(1)(), lambda: classmethod(1, kw=2),
                  lambda: raw_class.__get__(None, None), lambda: staticmethod(1)()]:
    try: operation()
    except TypeError: print('TypeError')

class Caller:
    def __call__(self, value): return value + 1
class CallableStatic:
    method = staticmethod(Caller())
print(CallableStatic.method(4), CallableStatic().method(5))
print(callable(Caller()))

def replacement(self, value): return value * 2
Child.collect = replacement
print(obj.collect(5))
Child.collect = None
try: obj.collect(5)
except TypeError: print('masked base method')
del Child.collect
print(obj.collect(5) == (obj, 5))
