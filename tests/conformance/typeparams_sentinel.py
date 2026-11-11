# pyjvm: requires-python >= 3.15
# The 3.15 builtin sentinel(name, /, *, repr=None) creates unique, truthy, identity-compared markers.
missing = sentinel("MISSING")
other = sentinel("MISSING")
print(repr(missing), str(missing), type(missing).__name__, isinstance(missing, sentinel), type(missing) is sentinel)
print(missing.__name__, missing.__module__ == __name__)
print(missing is other, missing == missing, missing == other, missing != other)
print(bool(missing), hash(missing) == hash(missing), missing in [other, missing])
print({missing: 1}[missing])

with_repr = sentinel("WITH_REPR", repr="custom")
without_repr = sentinel("WITHOUT_REPR", repr=None)
print(repr(with_repr), str(with_repr), repr(without_repr), str(without_repr))

for call in (lambda: sentinel(), lambda: sentinel("a", "b"), lambda: sentinel(name="a"),
             lambda: sentinel(1), lambda: sentinel("a", repr=42)):
    try:
        call()
    except TypeError:
        print("TypeError")

try:
    missing.attribute = "value"
except AttributeError:
    print("AttributeError set")
try:
    missing.__name__ = "CHANGED"
except AttributeError:
    print("AttributeError name")
try:
    del missing.__name__
except AttributeError:
    print("AttributeError del name")
missing.__module__ = "changed"
print(missing.__module__)
del missing.__module__
try:
    missing.__module__
except AttributeError:
    print("AttributeError module")
try:
    sentinel.attribute = "value"
except TypeError:
    print("TypeError immutable type")
try:
    class SubSentinel(sentinel):
        pass
except TypeError:
    print("TypeError subclass")


def lookup(default=sentinel("NO_DEFAULT")):
    return default


print(lookup(), lookup(None), lookup() is lookup())
print(lookup().__name__)


class Holder:
    MARK = sentinel("Holder.MARK")


print(Holder.MARK, Holder.MARK is Holder.MARK)
