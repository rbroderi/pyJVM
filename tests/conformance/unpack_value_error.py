# Wrong-length unpacking raises ValueError (email.message relies on catching it).
def attempt(f):
    try:
        f()
    except ValueError as exc:
        return type(exc).__name__, str(exc).startswith(("not enough", "too many"))
    return None


def short():
    a, b = [1]


def long():
    a, b = [1, 2, 3]


def star():
    a, *b, c = [1]


print(attempt(short), attempt(long), attempt(star))
try:
    a, b = "x"
except ValueError as exc:
    print(str(exc))
try:
    name, val = "bare".split("=", 1)
except ValueError:
    name, val = "bare", ""
print(name, repr(val))
