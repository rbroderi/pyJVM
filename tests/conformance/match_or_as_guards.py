def g(x, limit):
    match x:
        case int(n) if n > limit: return "big %d" % n
        case int(n) if n < 0: return "neg %d" % n
        case int(): return "int"
        case [a, b] if a == b: return "twin"
        case [a, b] if a < b: return "ascending"
        case [a, b]: return "other pair"
        case _: return "else"

for v in [100, -4, 3, [1, 1], [1, 2], [2, 1], "s"]:
    print(repr(v), g(v, 10))

def alt(x):
    match x:
        case (0, y) | (y, 0): return "has-zero %r" % (y,)
        case [1 | 2 | 3, _]: return "small-first"
        case {"k": (1 | 2) as small}: return "small %r" % (small,)
        case _: return "none"

for v in [(0, 5), (7, 0), [2, 9], {"k": 2}, {"k": 9}, (4, 4)]:
    print(repr(v), alt(v))

calls = []
def spy(v):
    calls.append(v)
    return v

def evaluated_once():
    match spy([1, 2]):
        case [1, 3]: return "a"
        case [1, 4]: return "b"
        case [1, 2]: return "c"

print(evaluated_once(), calls)


class Color:
    RED = 1
    GREEN = 2

def value_pattern(c):
    match c:
        case Color.RED: return "red"
        case Color.GREEN: return "green"
        case _: return "unknown"

print(value_pattern(1), value_pattern(2), value_pattern(3))

def loop_control():
    out = []
    for i in range(6):
        match i:
            case 1: continue
            case 4: break
            case _: out.append(i)
    return out

print(loop_control())

def walrus_guard(x):
    match x:
        case [a, b] if (s := a + b) > 5: return s
        case _: return -1

print(walrus_guard([3, 4]), walrus_guard([1, 1]))
