class Point:
    __slots__ = ("x", "y")
    def __init__(self, x):
        self.x = x

p = Point(3)
print(p.x)
print(hasattr(p, "y"))
p.y = 4
print(p.y)
del p.x
print(hasattr(p, "x"))

try:
    p.z = 5
except AttributeError:
    print("no-z")

try:
    print(p.__dict__)
except AttributeError:
    print("no-dict")
