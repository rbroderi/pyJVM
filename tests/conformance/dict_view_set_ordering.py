# keys() and items() views order like sets.
a = {1: "a", 2: "b"}
b = {1: "a", 2: "b", 3: "c"}
print(a.keys() <= b.keys(), a.keys() < b.keys(), b.keys() >= a.keys(), b.keys() > a.keys(), a.keys() < a.keys())
print(a.items() <= b.items(), b.items() <= a.items(), a.keys() <= {1, 2, 3}, {1, 2, 3} >= a.keys())
print(a.keys() == {1, 2}, a.keys() & b.keys() == {1, 2}, (b.keys() - a.keys()) == {3})
try:
    a.values() <= b.values()
except TypeError as exc:
    print("TypeError")
try:
    a.keys() <= [1, 2]
except TypeError as exc:
    print("TypeError")
