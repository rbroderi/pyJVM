class Value:
    def __init__(self): self.storage = 3
    def get(self):
        'value doc'
        return self.storage
    def set(self, value): self.storage = value
    def delete(self): del self.storage
    item = property(get, set, delete)
    readonly = property(get)
obj = Value()
print(obj.item, Value.item.__doc__, Value.item.__name__)
obj.item = 7
print(obj.item, Value.item.fget is Value.get, isinstance(Value.item, property))
obj.__dict__['item'] = 99
print(obj.item)
del obj.item
print(hasattr(obj, 'storage'))
def first(self):
    'first doc'
def second(self):
    'second doc'
raw = property(first)
print(raw.__name__, raw.__doc__, raw.getter(second).__name__, raw.getter(second).__doc__)
print(property(first, doc='explicit').getter(second).__doc__)
obj.storage = 5
try: obj.readonly = 9
except AttributeError: print('read-only')
try: del obj.readonly
except AttributeError: print('no deleter')
print(Value.item.__get__(None, Value) is Value.item)
Value.item.__set__(obj, 10)
print(Value.item.__get__(obj))
Value.item.__delete__(obj)
print(hasattr(obj, 'storage'))
class Empty:
    value = property()
try: Empty().value
except AttributeError: print('no getter')
class Decorated:
    @property
    def item(self): return self.storage
    @item.setter
    def item(self, value): self.storage = value + 1
    @item.deleter
    def item(self): del self.storage
obj = Decorated()
obj.item = 4
print(obj.item)
del obj.item
print(hasattr(obj, 'storage'))
