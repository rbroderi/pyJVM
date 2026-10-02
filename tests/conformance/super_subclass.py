class A:
    def method(self): return 'A'
class B(A): pass
class Custom(super):
    def __init__(self, *args):
        super().__init__(*args)
    def label(self): return 'custom'
class Child(Custom): pass
value = B()
s = Custom(B, value)
print(s.method(), s.label(), type(s) is Custom)
print(isinstance(s, super), isinstance(s, Custom), issubclass(Custom, super), issubclass(Child, super))
unbound = Child(B)
B.saved = unbound
print(B.saved is unbound, value.saved.method(), type(value.saved) is Child)
class DiamondA:
    def method(self): return 'A'
class DiamondB(DiamondA):
    def method(self): return 'B' + super().method()
class DiamondC(DiamondA):
    def method(self): return 'C' + super().method()
class DiamondD(DiamondC, DiamondB):
    def method(self): return 'D' + Custom(DiamondD, self).method()
print(DiamondD().method())
