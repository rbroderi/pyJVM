class Base:
    def get(self):
        return 5

def factory(value):
    class Child(Base):
        def get(self):
            return super().get() + value
        @property
        def number(self):
            return value
        @number.setter
        def number(self, replacement):
            nonlocal value
            value = replacement
        @classmethod
        def identify(cls):
            return cls.__name__, value
        @staticmethod
        def twice():
            return value * 2
    return Child

A = factory(10)
B = factory(20)
a = A()
b = B()
print(a.get(), b.get(), a.number, b.number)
a.number = 30
print(a.get(), b.get(), A.identify(), B.identify(), a.twice(), B.twice())
