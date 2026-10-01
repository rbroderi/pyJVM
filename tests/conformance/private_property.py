class C:
    __slots__ = ("__value",)

    def __init__(self):
        self.__value = 2

    @property
    def __prop(self):
        return self.__value

    @__prop.setter
    def __prop(self, value):
        self.__value = value

    def read(self):
        return self.__prop

    def write(self, value):
        self.__prop = value

c = C()
print(c.read())
c.write(9)
print(c.read())
print(C._C__prop.__class__.__name__ if False else hasattr(C, "_C__prop"))
