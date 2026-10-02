Local = "module binding"
def make(value):
    class Local:
        attr = value
        def get(self):
            return value
        def change(self, new):
            nonlocal value
            value = new
        def recursive_class(self):
            return Local is self.__class__
    return Local

First = make(10)
Second = make(20)
f = First()
s = Second()
print(f.get(), s.get(), f.attr, s.attr, First is Second)
f.change(33)
print(f.get(), s.get(), f.recursive_class(), s.recursive_class())
print(First.__name__, First.__qualname__, Second.__qualname__)

print(Local)
