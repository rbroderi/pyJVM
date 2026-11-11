print(list(zip([1, 2, 3], "ab", (True, False, None))))
print(list(zip()), list(zip([1])))
z = zip([1, 2], [3, 4])
print(next(z), next(z))
try:
    next(z)
except StopIteration:
    print("done")

def gen():
    i = 0
    while True:
        yield i
        i += 1

print(list(zip(gen(), "abc")))
print(list(zip("abc", gen())))

for args in [([1, 2, 3], [4, 5]), ([1], [4, 5]), ([1, 2], [3, 4], [5])]:
    try:
        print(list(zip(*args, strict=True)))
    except ValueError as e:
        print("ValueError", e)

print(list(enumerate("ab")), list(enumerate("ab", 5)), list(enumerate("ab", start=-1)))
e = enumerate(gen())
print(next(e), next(e), next(e))
print(list(filter(None, [0, 1, "", "a", None, [], [0]])))
print(list(filter(lambda x: x % 2, range(7))))
f = filter(str.isdigit if False else (lambda s: s > "b"), "abcd")
print(list(f), list(f))

print(type(zip()).__name__, type(enumerate([])).__name__, type(filter(None, [])).__name__)
print(isinstance(zip([1], [2]), zip), isinstance(enumerate([]), enumerate))
unzipped = list(zip(*[(1, "a"), (2, "b"), (3, "c")]))
print(unzipped)
d = dict(zip("abc", range(3)))
print(d)
fn = zip
print(list(fn([1, 2], [3, 4])))
print(list(map(lambda a, b: a + b, [1, 2], [3, 4])))

print(dict([("a", 1), ["b", 2]]), dict(), dict({"x": 1}))
for bad in ([1], [(1, 2, 3)], 5):
    try:
        dict(bad)
    except (TypeError, ValueError) as e:
        print(type(e).__name__, e if isinstance(e, ValueError) or bad == 5 else "")
