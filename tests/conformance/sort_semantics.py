def show(f):
    try:
        print(f())
    except Exception as e:
        print(type(e).__name__, e)


# argument errors
show(lambda: [].sort(1))
show(lambda: [3, 1].sort(foo=1))
show(lambda: sorted())
show(lambda: sorted(1, 2))
show(lambda: sorted([], foo=1))
show(lambda: sorted([], None))
show(lambda: sorted(3))
show(lambda: sorted([1, 2], key=3))
show(lambda: [3, 1, 2].sort(key=None, reverse=True))
show(lambda: sorted([3, 1, 2], key=None, reverse=1))

# type errors
show(lambda: sorted([1, "a"]))
show(lambda: sorted(["a", 1]))
show(lambda: sorted([None, None]))
show(lambda: sorted([1, 2.5, 2, 0.5, True]))
show(lambda: sorted([(1, "b"), (1, "a"), (0, "z"), (1,)]))
show(lambda: sorted([(1, 2), (1, "a")]))
show(lambda: sorted([[3], [1, 2], [1]]))
show(lambda: sorted([{1}, {2}]))
show(lambda: sorted(["b", "A", "a", "B"]))
show(lambda: sorted("hello"))
show(lambda: sorted({3: 1, 1: 2, 2: 3}))
show(lambda: sorted(range(5, 0, -1), reverse=True))
show(lambda: sorted([3, 1, 2], key=lambda x: -x))
show(lambda: sorted([1.5, float("inf"), -float("inf"), 0.0, -0.0]))
show(lambda: sorted([2 ** 70, 1, -2 ** 70, 5]))


# only __lt__ is used
class OnlyLt:
    def __init__(self, v):
        self.v = v

    def __lt__(self, other):
        return self.v < other.v

    def __gt__(self, other):
        raise AssertionError("__gt__ used")

    def __eq__(self, other):
        raise AssertionError("__eq__ used")

    __hash__ = None


print([x.v for x in sorted([OnlyLt(3), OnlyLt(1), OnlyLt(2), OnlyLt(1)])])


class NoLt:
    def __gt__(self, other):
        return True


show(lambda: len(sorted([NoLt(), NoLt()])))
show(lambda: len(sorted([NoLt()])))


# subclasses of builtins
class MyInt(int):
    def __lt__(self, other):
        return int(self) > int(other)


print(sorted([MyInt(1), MyInt(3), MyInt(2)]))
print(sorted([MyInt(1), 3, MyInt(2), 0]))


class MyList(list):
    pass


ml = MyList([3, 1, 2])
ml.sort()
print(ml, type(ml).__name__, type(sorted(ml)).__name__)


# exceptions from comparisons / keys leave the list valid
class Boom:
    def __init__(self, v):
        self.v = v

    def __lt__(self, other):
        if self.v == 5 or other.v == 5:
            raise ValueError("boom")
        return self.v < other.v


data = [Boom(v) for v in (9, 8, 7, 5, 4, 3, 2, 1)]
orig = list(data)
try:
    data.sort()
except ValueError as e:
    print("raised", e)
print(len(data), sorted(id(x) for x in data) == sorted(id(x) for x in orig))

big = [Boom(v) for v in list(range(100, 0, -1)) + [5] + list(range(200, 300))]
ids = sorted(id(x) for x in big)
try:
    big.sort()
except ValueError as e:
    print("raised big", e)
print(len(big), sorted(id(x) for x in big) == ids)

calls = []


def badkey(x):
    calls.append(x)
    if x == 3:
        raise KeyError(x)
    return x


lst = [5, 4, 3, 2, 1]
try:
    lst.sort(key=badkey)
except KeyError as e:
    print("key failed", e)
print(lst, calls)

lst = [5, 4, 3, 2, 1, 6, 7]
try:
    lst.sort(key=lambda x: (_ for _ in ()).throw(RuntimeError("k")) if x == 1 else x, reverse=True)
except RuntimeError as e:
    print("rev key failed", e, lst)

# list is empty during the sort
seen = []
target = [3, 1, 2]


def lenkey(x):
    seen.append(len(target))
    return x


target.sort(key=lenkey)
print(seen, target)

seen2 = []
target2 = [3, 1, 2]


class Peek:
    def __init__(self, v):
        self.v = v

    def __lt__(self, other):
        seen2.append(len(target2))
        return self.v < other.v


target2[:] = [Peek(3), Peek(1), Peek(2)]
target2.sort()
print(seen2, [p.v for p in target2])

# modification during sort
m = [4, 3, 2, 1]


def mutate_append(x):
    m.append(0)
    return x


try:
    m.sort(key=mutate_append)
except ValueError as e:
    print("ValueError", e)
print(m)

m2 = [Peek(3), Peek(1), Peek(2)]


class Mut:
    def __init__(self, v, lst):
        self.v = v
        self.lst = lst

    def __lt__(self, other):
        self.lst.append("x")
        return self.v < other.v


lm = []
lm.extend([Mut(2, lm), Mut(1, lm), Mut(3, lm)])
try:
    lm.sort()
except ValueError as e:
    print("ValueError", e)
print(sorted(x.v for x in lm if not isinstance(x, str)) if all(not isinstance(x, str) for x in lm) else len(lm))

# stability and reverse stability
recs = [(1, "a"), (0, "b"), (1, "c"), (0, "d"), (1, "e")]
print(sorted(recs, key=lambda r: r[0]))
print(sorted(recs, key=lambda r: r[0], reverse=True))
l2 = list(recs)
l2.sort(key=lambda r: r[0], reverse=True)
print(l2, l2 == recs)

# key called exactly once per element, in order
order = []
sorted([3, 1, 2, 5, 4], key=lambda x: order.append(x) or x)
print(order)
order.clear()
sorted([7], key=lambda x: order.append(x) or x)
print(order)
order.clear()
sorted([], key=lambda x: order.append(x) or x)
print(order)

# sort returns None, sorted returns new list
a = [2, 1]
print(a.sort(), a, sorted(a) is a)
t = (3, 1, 2)
print(sorted(t), sorted(iter([3, 2, 1])), sorted(x for x in "cba"))

# bound method call forms
f = a.sort
f(reverse=True)
print(a)
print(list.sort(a), a)
show(lambda: list.sort(a, key=1))
list.sort(a, reverse=True)
print(a)
show(lambda: len(sorted([NoLt(), 1])))
show(lambda: [OnlyLt(1), 2].sort())
show(lambda: sorted([1, 2], reverse=None))
show(lambda: sorted([1, 2], key=None))
