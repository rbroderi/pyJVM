class C:
    __answer = 42

    @classmethod
    def read(cls):
        return cls.__answer

    def __method(self):
        return "private"

    def call(self):
        return self.__method()

print(C.read())
print(C._C__answer)
print(C().call())
print(hasattr(C, "__answer"))
print(hasattr(C, "_C__answer"))
