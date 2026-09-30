class C:
    def __init__(self, *, name="default", **kw):
        self.name = name
        self.init_kw = list(kw)

    def meth(self, a, /, b=2, *args, c=3, **kw):
        return (self.name, a, b, args, c, list(kw))

    @classmethod
    def cmeth(cls, a=1, *, b=2, **kw):
        return (a, b, list(kw))

    @staticmethod
    def smeth(a=1, *args, b=2, **kw):
        return (a, args, b, list(kw))

c = C(name="x", z=1, y=2)
print(c.name)
print(c.init_kw)
print(c.meth(10, 20, 30, 40, c=50, z=60, y=70))
print(c.meth(10, c=9))
print(C.cmeth(b=7, z=8, y=9))
print(c.cmeth(a=5, y=6))
print(C.smeth(5, 6, 7, b=8, z=9))
print(c.smeth(a=4, y=5))
