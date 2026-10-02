constructor = super
class A:
    def method(self, value): return 'A' + str(value)
    @classmethod
    def owner(cls): return cls
    @staticmethod
    def static(value): return value + 1
    @property
    def property(self): return 'base property'
class B(A):
    def method(self, value): return 'B' + constructor(B, self).method(value)
    def automatic(self):
        alias = super
        return alias().method(3)
    @classmethod
    def automatic_class(cls): return super().owner()
class C(B): pass
value = C()
s = constructor(B, value)
print(value.method(2), value.automatic(), s.static(2), s.property)
print(s.__thisclass__ is B, s.__self__ is value, s.__self_class__ is C)
print(B.automatic_class() is B, C.automatic_class() is C)
print(super(B, C).owner() is C, super(B, C).property is A.property)
unbound = constructor(B)
print(unbound.__self__, unbound.__self_class__, unbound.__thisclass__ is B)
print(unbound.__get__(None, B) is unbound)
print(unbound.__get__(value).method(4))
print(super.__get__(unbound, value).method(4))
B.saved = unbound
print(B.saved is unbound, value.saved.method(5))
print(s.__get__(value) is s)
for args in [(1,), (B, 2), (B, A()), (A, A, A)]:
    try:
        constructor(*args)
    except TypeError:
        print('TypeError')
try:
    constructor(B, value=value)
except TypeError:
    print('TypeError')
try:
    unbound.method
except AttributeError:
    print('unbound')
for name in ['__self__', '__thisclass__', '__self_class__']:
    try:
        setattr(s, name, None)
    except AttributeError:
        print('readonly')
print(super(float, 1.0).__self_class__ is float)
sp = super(float, 1.0)
super.__init__(sp, int, 3)
print(sp.__self__, sp.__thisclass__ is int, sp.__self_class__ is int)
class Namespace:
    bound = super(B, value)
print(Namespace.bound.method(6), type(s) is super)

try:
    s.extra = 1
except AttributeError:
    print('no instance dict')
