def lit(x):
    match x:
        case 0: return "zero"
        case -1: return "minus one"
        case 1.5: return "one and a half"
        case "a" | "b": return "a-or-b"
        case b"x": return "bytes x"
        case None: return "none"
        case True: return "true"
        case False: return "false"
        case _: return "other"

for v in [0, -1, 1.5, "a", "b", "c", b"x", None, True, False, 0.0, 2]:
    print(repr(v), lit(v))

def seq(x):
    match x:
        case []: return "empty"
        case [a]: return "one %r" % (a,)
        case [a, b]: return "two %r %r" % (a, b)
        case [1, *rest]: return "starts-1 %r" % (rest,)
        case [*init, 9]: return "ends-9 %r" % (init,)
        case [first, *mid, last]: return "fml %r %r %r" % (first, mid, last)
        case _: return "no"

for v in [[], (), [1], (2, 3), [1, 2, 3], (4, 5, 9), [4, 5, 6, 7], "ab", b"ab", {1, 2}, range(2), 5, {"a": 1}]:
    print(repr(v), seq(v))

def nested(x):
    match x:
        case [[a, b], [c, d]]: return a + b + c + d
        case (1, (2, *r)): return r
        case [_, _, _]: return "three"
        case _: return None

print(nested([[1, 2], [3, 4]]), nested((1, (2, 3, 4))), nested([0, 0, 0]), nested(7))

def capture_persists(x):
    match x:
        case [a, 99]: pass
        case _: pass
    return a

print(capture_persists([5, 99]))
try:
    print(capture_persists([5, 1]))
except UnboundLocalError as e:
    print("unbound")

def whole(x):
    match x:
        case [1, 2] as pair: return pair
        case y: return ("any", y)

print(whole([1, 2]), whole(3))

n = 0
match n:
    case 0: print("module-level zero")
match [1, 2, 3]:
    case [a, b, c]: print(a, b, c)
