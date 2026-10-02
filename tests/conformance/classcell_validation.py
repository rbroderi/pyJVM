class Missing(type):
    def __new__(meta, name, bases, namespace):
        namespace.pop('__classcell__', None)
        return super().__new__(meta, name, bases, namespace)
class Plain(metaclass=Missing): pass
try:
    class Required(metaclass=Missing):
        def f(self): return __class__
except RuntimeError as error:
    print('__class__ not set' in str(error), '__classcell__ propagated' in str(error))
class Overwrite(type):
    def __new__(meta, name, bases, namespace, bad):
        namespace['__classcell__'] = bad
        return super().__new__(meta, name, bases, namespace)
for value in (None, 0, '', object()):
    try:
        class Bad(metaclass=Overwrite, bad=value): pass
    except TypeError:
        print('bad cell')
class Wrong(type):
    def __new__(meta, name, bases, namespace):
        first = super().__new__(meta, name, bases, namespace)
        type('Another', (), namespace)
        return first
try:
    class Required(metaclass=Wrong):
        def f(self): return __class__
except TypeError:
    print('wrong class')
class Substitute(type):
    def __new__(meta, name, bases, namespace):
        return type('Replacement', (), {})
try:
    class Required(metaclass=Substitute):
        def f(self): return __class__
except RuntimeError:
    print('unfilled replacement')
class Early(type):
    def __new__(meta, name, bases, namespace):
        try:
            namespace['f'](None)
        except RuntimeError as error:
            print(str(error))
        return super().__new__(meta, name, bases, namespace)
class Child(metaclass=Early):
    def f(self): return super()
print(Child().f().__thisclass__ is Child)
class OnlyLocals(metaclass=Missing):
    def f(self):
        __class__ = 17
        return __class__
print(OnlyLocals().f(), OnlyLocals.f.__closure__ is None)
class NestedOnly(metaclass=Missing):
    class Inner:
        def f(self): return __class__
print(NestedOnly.Inner().f() is NestedOnly.Inner)
class NoCellForBareReference(metaclass=Missing):
    try:
        __class__
    except NameError:
        pass
print(hasattr(NoCellForBareReference, '__classcell__'))
class LambdaLocal(metaclass=Missing):
    f = lambda __class__: __class__
print(LambdaLocal.f(23), LambdaLocal.f.__closure__ is None)
