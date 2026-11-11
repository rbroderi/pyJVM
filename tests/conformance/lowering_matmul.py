class M:
    def __init__(self, v):
        self.v = v

    def __matmul__(self, other):
        return M(("mm", self.v, getattr(other, "v", other)))

    def __rmatmul__(self, other):
        return M(("rmm", other, self.v))

    def __imatmul__(self, other):
        self.v = ("imm", self.v, getattr(other, "v", other))
        return self

    def __repr__(self):
        return "M(%r)" % (self.v,)


a = M(1)
b = M(2)
print(a @ b)
print(a @ 3)
print(3 @ a)
a @= b
print(a)
a @= 9
print(a)


class OnlyM:
    def __matmul__(self, other):
        return "plain"


c = OnlyM()
c2 = c
c @= 1
print(c, c2 is c)


class NI:
    def __matmul__(self, other):
        return NotImplemented


for x, y in [(1, 2), ("a", "b"), (NI(), NI()), (1.5, 2), ([1], [2]), (None, 1)]:
    try:
        x @ y
    except TypeError as e:
        print("TypeError", e)

try:
    v = 1
    v @= 2
except TypeError as e:
    print("TypeError", e)

try:
    s = "s"
    s @= s
except TypeError as e:
    print("TypeError", e)

lst = [M(1)]
lst[0] @= M(5)
print(lst)


class H:
    def __init__(self):
        self.m = M(0)


h = H()
h.m @= 4
print(h.m)
print(M(1) @ M(2) @ M(3))
