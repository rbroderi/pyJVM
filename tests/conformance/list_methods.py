a = [3, 1, 2, 1]
a.insert(0, 9); a.insert(100, 8); a.insert(-1, 7); a.insert(-100, 6)
print(a)
a.remove(1); print(a)
try:
    a.remove(99)
except ValueError as e:
    print("ValueError", e)
print(a.index(2), a.index(1), a.index(2, 1), a.index(8, 1, 100) if 8 in a else "n/a", a.count(1))
try:
    a.index(99)
except ValueError as e:
    print("ValueError", e)
b = [5, 2, 9, 1]
b.sort(); print(b)
b.sort(reverse=True); print(b)
b.sort(key=lambda x: -x % 5); print(b)
words = ["bb", "a", "ccc", "dd"]
words.sort(key=len); print(words)
words.sort(key=len, reverse=True); print(words)
srt = words.sort
srt(); print(words)
push = b.append
push(100); print(b)
print([1.5, 1, True].index(1), [1, 2, 3].index(True) if False else "ok", ["x", "y"].index("y"))
c = [1, 2, 3]
c.extend(c); print(c, c.pop(), c.pop(0), c)
