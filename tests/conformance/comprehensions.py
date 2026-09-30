x = 100
print([x * x for x in range(6) if x % 2 == 0])
print(x)
print({x for x in range(5) if x > 2})
print({x: x * x for x in range(4)})
print([(a, b) for a in range(3) for b in range(3) if a != b])
print(any([0, 0, 3]), all([1, 2, 3]), all([]))
