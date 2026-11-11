import sys


class Idx:
    def __init__(self, v):
        self.v = v

    def __index__(self):
        return self.v


print(list(range(Idx(3))))
print(list(range(Idx(1), Idx(4))))
print(list(range(Idx(10), Idx(0), Idx(-3))))
print(list(range(True, 4)))
print(list(range(2**70, 2**70 + 3)))
for bad in (1.5, "3", None, [1]):
    try:
        range(bad)
    except TypeError as e:
        print("TypeError", e)
for args in ((), (1, 2, 3, 4)):
    try:
        range(*args)
    except TypeError as e:
        print("TypeError", e)
try:
    range(1, 2, 0)
except ValueError as e:
    print("ValueError", e)
try:
    range(start=1)
except TypeError as e:
    print("TypeError", e)
r = range(Idx(2), Idx(9), Idx(3))
print(r, r.start, r.stop, r.step)

print("a", "b", file=sys.stdout)
print("c", file=sys.stdout, flush=True)
print("d", flush=False)
print("e", "f", sep="", end="!\n", file=None, flush=True)
print(file=sys.stdout)
print()
print("g", file=sys.stdout, end="", flush=True)
print()
try:
    print("x", bogus=1)
except TypeError as e:
    print("TypeError", e)
try:
    print("x", sep=1)
except TypeError as e:
    print("TypeError", e)
try:
    print("x", end=1)
except TypeError as e:
    print("TypeError", e)
try:
    print("x", file=3)
except AttributeError as e:
    print("AttributeError")


class Sink:
    def __init__(self):
        self.parts = []
        self.flushed = 0

    def write(self, s):
        self.parts.append(s)

    def flush(self):
        self.flushed += 1


s = Sink()
print("a", "b", file=s)
print("c", file=s, flush=True)
print(s.parts, s.flushed)
print("a", sep=None, end=None, file=s)
print(s.parts)
