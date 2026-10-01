class Secret:
    __slots__ = ("__value",)

    def __init__(self):
        self.__value = 3

    def bump(self):
        self.__value += 2
        return self.__value

    def __hidden(self):
        return self.__value

    def call_hidden(self):
        return self.__hidden()

s = Secret()
print(s.bump())
print(s.call_hidden())
print(hasattr(s, "__value"))
print(hasattr(s, "_Secret__value"))
print(s._Secret__value)
del s._Secret__value
try:
    s.call_hidden()
except AttributeError:
    print("deleted")
