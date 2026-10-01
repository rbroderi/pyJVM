class Base:
    __slots__ = ("x",)

class Child(Base):
    pass

c = Child()
c.x = 1
c.extra = 2
print(c.x, c.extra, c.__dict__)

class Tight(Base):
    __slots__ = ("y",)

t = Tight()
t.x = 3
t.y = 4
print(t.x, t.y)
try:
    t.extra = 5
except AttributeError:
    print("tight")
