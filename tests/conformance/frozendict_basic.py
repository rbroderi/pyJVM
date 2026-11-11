# pyjvm: requires-python >= 3.15
fd = frozendict({"a": 1, "b": 2})
print(fd, len(fd), fd["a"], "a" in fd, "z" in fd, fd.get("z", 0))
print(type(fd).__name__, isinstance(fd, frozendict), isinstance(fd, dict), isinstance({}, frozendict))
print(list(fd), list(fd.values()), list(fd.items()), fd.copy() is fd)
print(fd == {"a": 1, "b": 2}, {"b": 2, "a": 1} == fd, fd == frozendict(b=2, a=1), fd != frozendict())
print(hash(fd) == hash(frozendict(b=2, a=1)), len({fd: 1, frozendict(a=1, b=2): 2}))
print(fd | {"c": 3}, type(fd | {"c": 3}).__name__, {"x": 1} | fd, type({"x": 1} | fd).__name__)
print(frozendict(), frozendict([("k", "v")]), frozendict.fromkeys("ab", 0), frozendict(x=1, **{"y": 2}))
for action in (lambda: fd.__setitem__("a", 2), lambda: fd.update({}), lambda: hash(frozendict(a=[])), lambda: fd["zz"]):
    try:
        action()
    except (TypeError, AttributeError, KeyError) as e:
        print(type(e).__name__)
d = {1: 2}
fz = frozendict(d)
d[3] = 4
print(fz, d)
