class Sequence:
    def __getitem__(self, index):
        if index >= 3: raise IndexError(index)
        return index * 2
value = Sequence()
value.__getitem__ = lambda index: 99
print(list(value))
class Descriptor:
    def __get__(self, instance, owner):
        print('bind')
        def get(index):
            if index >= 2: raise StopIteration
            return index + 1
        return get
class Managed:
    __getitem__ = Descriptor()
print(list(Managed()))
class Bad:
    __getitem__ = property(lambda self: 1/0)
try: list(Bad())
except ZeroDivisionError: print('property')
class Raising:
    def __getitem__(self, index): raise ValueError('callback')
try: list(Raising())
except ValueError as error: print(str(error))
class Disabled(Sequence):
    __iter__ = None
try: list(Disabled())
except TypeError: print('disabled iteration')
class IterDescriptor:
    def __get__(self, instance, owner): return lambda: iter([4, 5])
class Explicit(Sequence):
    __iter__ = IterDescriptor()
print(list(Explicit()))
