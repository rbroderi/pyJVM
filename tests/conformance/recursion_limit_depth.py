import sys

print("default", sys.getrecursionlimit())


def depth_reached(limit):
    sys.setrecursionlimit(limit)
    seen = [0]

    def rec(n):
        seen[0] = n
        rec(n + 1)

    try:
        rec(1)
    except RecursionError as e:
        return seen[0], str(e)


# the module frame and depth_reached's own frame count too: print the depth relative to the limit
for limit in (50, 100, 200, 1000):
    d, msg = depth_reached(limit)
    print(limit, limit - d, msg)

sys.setrecursionlimit(1000)


# method calls, generators, class bodies and properties count Python frames the same way
class A:
    def m(self, n):
        self.depth = n
        return self.m(n + 1)


def method_depth(limit):
    sys.setrecursionlimit(limit)
    a = A()
    try:
        a.m(1)
    except RecursionError:
        return limit - a.depth


print("method", method_depth(100), method_depth(300))
sys.setrecursionlimit(1000)


def gen_depth(limit):
    sys.setrecursionlimit(limit)
    seen = [0]

    def g(n):
        seen[0] = n
        yield from g(n + 1)

    try:
        list(g(1))
    except RecursionError:
        return limit - seen[0]


print("generator", gen_depth(100))
sys.setrecursionlimit(1000)

# the exception is catchable, the interpreter keeps working afterwards and the traceback has the frames
def inf():
    return inf()


try:
    inf()
except RecursionError as e:
    tb = e.__traceback__
    n = 0
    while tb is not None:
        n += 1
        tb = tb.tb_next
    print("traceback frames", n > 100, isinstance(e, RuntimeError), type(e).__name__)

print(inf.__name__)

# raising the limit lets deeper recursion succeed
sys.setrecursionlimit(5000)


def count(n):
    return 0 if n == 0 else 1 + count(n - 1)


print(count(2000))
sys.setrecursionlimit(1000)
try:
    count(2000)
except RecursionError as e:
    print("lowered", e)

# setrecursionlimit validation
for bad in (0, -1):
    try:
        sys.setrecursionlimit(bad)
    except ValueError as e:
        print("ValueError", e)
try:
    sys.setrecursionlimit("x")
except TypeError as e:
    print("TypeError", e)
# the limit may not be set at or below the current depth
try:
    def too_low():
        sys.setrecursionlimit(2)

    too_low()
except RecursionError as e:
    print("RecursionError", e)
print(sys.getrecursionlimit())
