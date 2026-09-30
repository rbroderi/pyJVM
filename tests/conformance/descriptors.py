class Descriptor:
    def __init__(self, label):
        self.label = label

    def __get__(self, obj, owner):
        if obj is None:
            return "class:" + self.label
        return "get:" + self.label + ":" + obj.name

class DataDescriptor:
    def __get__(self, obj, owner):
        return obj._value

    def __set__(self, obj, value):
        obj._value = value * 2

    def __delete__(self, obj):
        obj._value = -1

class C:
    d = Descriptor("d")
    x = DataDescriptor()

    def __init__(self, name):
        self.name = name
        self._value = 3

    def __getattr__(self, name):
        return "missing:" + name

c = C("bob")
print(C.d)
print(c.d)
print(c.x)
c.x = 5
print(c.x)
del c.x
print(c.x)
c.d = "shadow"
print(c.d)
print(c.nope)
