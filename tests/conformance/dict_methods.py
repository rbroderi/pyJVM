d = {"a": 1, "b": 2}
print(d.setdefault("z", 5), d.setdefault("a", 99), d)
print(d.popitem(), d.popitem(), d)
d.update({"x": 1}, y=2)
d.update([("p", 1), ("q", 2)])
print(d, len(d))
print(d.pop("x"), d.pop("nope", "dflt"))
try:
    d.pop("nope")
except KeyError as e:
    print("KeyError", e)
try:
    {}.popitem()
except KeyError as e:
    print("KeyError", e)
d.clear()
print(d, len(d), bool(d))

print(dict.fromkeys("abc"), dict.fromkeys([1, 2], 0), dict.fromkeys(range(3), "v"))
a = {"a": 1, "b": 2}
b = {"b": 3, "c": 4}
print(a | b, b | a, a, b)
a |= b
a |= [("z", 26)]
print(a)
print(list(reversed(a)), list(reversed(a.items())), list(reversed(a.values())))
print(len(a.keys()), len(a.values()), len(a.items()))
print(a.__contains__("a"), a.__len__(), a.__getitem__("b"), a.__eq__({}), a.__ne__({}))
a.__setitem__("w", 1)
a.__delitem__("z")
print(a, list(a.__iter__()))
print(dict(a=1, b=2), dict([("k", "v")], extra=1), dict({"m": 1}, m=2))
print(sorted(a.items()), min(a), max(a.values()), sum(a.values()))
print({k: v for k, v in zip("abc", range(3))}, {**a, "k": 1}.keys() == {*a, "k"})
nested = {"a": {"b": [1, {"c": 2}]}}
print(nested["a"]["b"][1]["c"], nested == {"a": {"b": [1, {"c": 2}]}}, nested == {"a": {"b": [1, {"c": 3}]}})
counts = {}
for ch in "mississippi":
    counts[ch] = counts.get(ch, 0) + 1
print(counts, sorted(counts, key=counts.get))
try:
    hash({})
except TypeError as e:
    print("TypeError")
print({} is {}, {} == {}, not {})

ka = {"a": 1, "b": 2}
kb = {"b": 9, "c": 3}
print(sorted(ka.keys() & kb.keys()), sorted(ka.keys() | kb.keys()), sorted(ka.keys() - kb.keys()), sorted(ka.keys() ^ kb.keys()))
print(sorted(ka.keys() & ["a", "z"]), ka.keys() == {"a", "b"}, {"a", "b"} == ka.keys(), ka.keys() == kb.keys())
print(sorted(ka.items() & {("a", 1), ("x", 0)}), ka.items() == {("a", 1), ("b", 2)}, ka.values() == ka.values() or True)
