class Thing:
    prefix = 'T'
    @classmethod
    def label(cls, x):
        return cls.prefix + str(x)
    @staticmethod
    def add(a, b):
        return a + b
    def inst(self, x):
        return x + 1

print(Thing.label(3))
print(Thing.add(2, 5))
print(Thing().label(4))
print(Thing().add(6, 7))
f = Thing.label
print(f(8))
g = Thing.add
print(g(9, 10))
h = Thing.inst
print(h(Thing(), 20))
