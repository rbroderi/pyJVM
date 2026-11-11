class L(list):
    pass

l = L([3, 1, 2])
print(isinstance(l, list), isinstance(l, L), issubclass(L, list), type(l) is L, type(l).__name__)
print(l, repr(l), len(l), l[0], l[1:], l[-1], 3 in l, list(l), l == [3, 1, 2], [3, 1, 2] == l, l != [], bool(l), bool(L()))
l.append(4)
l.extend([5, 6])
l.insert(0, 0)
print(l, l.pop(), l.index(3), l.count(1), type(l[1:]).__name__, type(l + [1]).__name__, type(l * 2).__name__)
l.sort()
l.reverse()
print(l, sorted(l), list(reversed(l)), min(l), max(l), sum(l), len(L(range(4))))
l[0] = 10
del l[1]
l[1:3] = [7, 8, 9]
print(l, l + [0], [0] + l, l * 2, 2 * l, l[::2])
l += [100]
print(l, type(l).__name__)
l *= 2
print(len(l), type(l).__name__)
print(list.__len__(l), list.__getitem__(l, 0), list.append(l, 5), list.__contains__(l, 5), l[-1])
for x in L([1, 2]):
    print("x", x)
l.attr = "a"
print(l.attr, l.__dict__)
try:
    hash(l)
except TypeError:
    print("unhashable")
a, b, *c = L([1, 2, 3, 4])
print(a, b, c)
print(" ".join(map(str, L([1, 2]))), tuple(L([1, 2])), set(L([1, 1])), dict(L([(1, 2)])), [*L([1, 2])], (lambda *a: a)(*L([4, 5])))

class Logged(list):
    def __init__(self, *args):
        self.log = []
        super().__init__(*args)
    def append(self, x):
        self.log.append(x)
        super().append(x)
    def __getitem__(self, i):
        return ("got", super().__getitem__(i))
    def __setitem__(self, i, v):
        self.log.append(("set", i))
        super().__setitem__(i, v)
    def __len__(self):
        return 42
    def __iter__(self):
        yield "custom"
    def __contains__(self, x):
        return x == "magic"
    def __repr__(self):
        return "Logged!"
    def __add__(self, other):
        return "added"
    def __eq__(self, other):
        return "eq"
    __hash__ = None
    def __bool__(self):
        return False

g = Logged([1, 2])
g.append(3)
g[0] = 9
print(g.log, g[1], len(g), list(g), "magic" in g, 1 in g, repr(g), str(g), g + [1], bool(g == 1), bool(g), [x for x in g])
print(list.__len__(g), list.__getitem__(g, 0), list.__repr__(g), sorted(g), sum([1]), tuple(g))

class Stack(list):
    push = list.append
    def peek(self):
        return self[-1]
s = Stack()
s.push(1)
s.push(2)
print(s, s.peek(), s.pop(), s)

class Slotted(list):
    __slots__ = ("extra",)
sl = Slotted([1])
sl.extra = 5
print(sl, sl.extra)
try:
    sl.other = 1
except AttributeError:
    print("no dict")
print(L.__mro__ == (L, list, object), L.__base__ is list, L.__bases__ == (list,))
print(f"{L([1])}", "%s" % (L([1]),), "{}".format(L([1])), L(), L("ab"), L((1, 2)))
