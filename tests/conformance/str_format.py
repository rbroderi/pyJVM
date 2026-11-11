class P:
    def __init__(self):
        self.x = 3
        self.items = [10, 20]
print("{} {}".format(1, "a"), "{1} {0} {1}".format("a", "b"), "{name} is {age}".format(name="Ann", age=30), "{0}{n}".format("x", n="y"))
print("{:>6}|{:<6}|{:^6}|{:06.2f}|{:,}|{:x}|{:#o}|{:e}".format("ab", "cd", "ef", 3.14159, 1234567, 255, 8, 12345.678))
print("{!r} {!s} {!r:>8}".format("q", "q", "q"), "{{literal}} {}".format(5), "{0.x} {0.items[1]} {1[a]} {1[b]}".format(P(), {"a": 1, "b": 2}))
print("{:{w}.{p}f}|{:{}}".format(3.14159, 7, 5, w=9, p=3), "{0:{1}}".format("z", 4) + "|")
print("{a}-{b}".format_map({"a": 1, "b": 2}), "".format(), "no fields".format(1, 2), "{:d}".format(True))
for bad, exc in (("{}", IndexError), ("{0} {}", ValueError), ("{} {1}", ValueError), ("{x}", KeyError), ("{", ValueError), ("}", ValueError), ("{!z}", ValueError)):
    try:
        bad.format()
    except (IndexError, ValueError, KeyError) as e:
        print(type(e).__name__)
f = "{}-{}".format
print(f(1, 2))
