class C(object):
    x=3

c=C()
print(c.x)
print(isinstance(c, object))
print(issubclass(C, object))
print(C.__bases__)
print(C.__mro__)
print(C.__dict__["x"])
