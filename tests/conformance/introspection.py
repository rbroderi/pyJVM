print(type(1), type('x'), type([]))
print(isinstance(True, int), isinstance(1, (str, int)))
print(issubclass(bool, int), issubclass(int, object))
print(callable(lambda x: x), callable(3))

class A:
    def __init__(self):
        self.x = 4
class B(A):
    pass
b=B()
print(type(b), isinstance(b, B), isinstance(b, A))
print(hasattr(b, 'x'), getattr(b, 'x'), getattr(b, 'z', 9))
setattr(b, 'z', 7)
print(b.z)
delattr(b, 'z')
print(hasattr(b, 'z'))

try:
    raise UnboundLocalError('u')
except NameError:
    print('name hierarchy')
