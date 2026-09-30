class Base:
    KIND = 'base'
    def name(self):
        return 'Base'

class Child(Base):
    KIND = 'child'
    def __init__(self, x):
        self._x = x
    @property
    def x(self):
        return self._x
    @x.setter
    def x(self, value):
        self._x = value
    def name(self):
        return super().name() + ':' + self.KIND

c = Child(7)
print(c.x)
c.x = 11
print(c.x)
print(c.KIND, Child.KIND)
print(c.name())
print(super(Child, c).name())
