class ___C:
    __slots__ = ("__x",)
    def __init__(self):
        self.__x = 7
    def value(self):
        return self.__x

x = ___C()
print(x.value())
print(x._C__x)

class ___:
    __slots__ = ("__x",)
    def __init__(self):
        self.__x = 8
    def value(self):
        return self.__x

y = ___()
print(y.value())
print(getattr(y, "__x"))
