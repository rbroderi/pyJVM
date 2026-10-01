try:
    class C:
        __slots__ = ("__x",)
        __x = 1
except ValueError:
    print("conflict")
