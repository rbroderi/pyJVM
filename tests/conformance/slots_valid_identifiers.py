class Good:
    __slots__ = ("a", "a_b", "_a", "A0123456789Z")
    def __init__(self):
        self.a = 1
        self.a_b = 2
        self._a = 3
        self.A0123456789Z = 4

g = Good()
print(g.a, g.a_b, g._a, g.A0123456789Z)

class Single:
    __slots__ = "abc"
    def __init__(self):
        self.abc = 5

print(Single().abc)
