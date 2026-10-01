try:
    class C:
        __slots__ = ("x",)
        x = 1
except ValueError as e:
    print(type(e))
