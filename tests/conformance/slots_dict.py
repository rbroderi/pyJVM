class C:
    __slots__ = ("x", "__dict__")

c = C()
c.x = 1
c.extra = 2
print(c.x, c.extra)
print(c.__dict__)
