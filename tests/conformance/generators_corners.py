# Suspension inside expressions that previously could not be lowered.
def comp_first_iter():
    got = [x * 2 for x in [(yield "a")]]
    print("listcomp", got)
    got = {x for x in (yield "b")}
    print("setcomp", sorted(got))
    got = {x: x + 1 for x in (yield "c")}
    print("dictcomp", got)
    got = list(x for x in (yield "d"))
    print("genexp", got)
    return "end"

g = comp_first_iter()
print(next(g))
print(g.send([1, 2]))
print(g.send([3, 4]))
print(g.send([5]))
try:
    g.send([6, 7])
except StopIteration as e:
    print("stop", e.value)

def unary():
    a = -(yield 1)
    b = not (yield 2)
    c = ~(yield 3)
    d = +(yield 4)
    return (a, b, c, d)

g = unary()
next(g)
g.send(5)
g.send(0)
g.send(7)
try:
    g.send(9)
except StopIteration as e:
    print(e.value)

def fstr(y):
    f'y:{yield y * 2}'
    s = f'{yield}|{(yield "third")!r:>6}|'
    return s

g = fstr(4)
print(next(g))
print(next(g))
print(g.send("one"))
try:
    g.send("two")
except StopIteration as e:
    print(repr(e.value))

def fspec():
    x = f'{(yield 1):{yield 2}}'
    return x

g = fspec()
next(g)
g.send(42)
try:
    g.send(">6")
except StopIteration as e:
    print(repr(e.value))

def ifexp_boolop():
    a = (yield 1) if (yield 0) else (yield 2)
    b = (yield 3) or (yield 4)
    c = (yield 5) and (yield 6)
    return (a, b, c)

g = ifexp_boolop()
print(next(g))
print(g.send(False))
print(g.send("else"))
print(g.send(0))
print(g.send("or"))
print(g.send(1))
try:
    g.send("and")
except StopIteration as e:
    print(e.value)

def nested_yield():
    r = yield (yield 1)
    r2 = yield from (yield 2)
    return (r, r2)

g = nested_yield()
print(next(g))
print(g.send("inner"))
print(g.send("outer"))
print(g.send([10, 11]))
print(next(g))
try:
    next(g)
except StopIteration as e:
    print(e.value)

def subscript_attr():
    d = {"k": 5}
    v = d[(yield 1)] + len((yield 2))
    return v

g = subscript_attr()
next(g)
g.send("k")
try:
    g.send("abc")
except StopIteration as e:
    print(e.value)

f = lambda: (yield 1)
print(type(f()).__name__, next(f()))
def inner_gen():
    return (yield 7)
f2 = lambda: (yield from inner_gen())
g = f2()
print(next(g))
try:
    g.send("sent")
except StopIteration as e:
    print(e.value)
f3 = lambda a, b=2: (yield a + b)
print(next(f3(1)))
