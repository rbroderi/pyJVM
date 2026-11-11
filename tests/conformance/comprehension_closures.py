def f(refs, locs):
    return sorted({name for name in refs if any(name in s for s in locs)})
print(f(["a", "b", "z"], [{"a"}, {"b", "c"}]))
def g(refs, locs):
    return [[x * y for y in locs] for x in refs]
print(g([1, 2], [10, 20]))
def h(refs):
    return [(lambda: x)() for x in refs]
print(h([1, 2, 3]))
def k(refs):
    return sorted({n for n in refs if all(n > m for m in range(n))})
print(k([0, 1, 2, 3]))
class C:
    def m(self, refs, locs):
        return sorted({name for name in refs if any(name in s for s in reversed(locs))})
print(C().m(["a", "q"], [{"a"}]))
