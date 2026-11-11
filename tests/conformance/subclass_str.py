class S(str):
    pass

s = S("hello")
print(isinstance(s, str), isinstance(s, S), issubclass(S, str), type(s) is S, type(s).__name__)
print(s, repr(s), str(s), len(s), s + "!", "<" + s, s * 2, s[1], s[1:3], s[::-1])
print(type(s + "!") is str, type(s[1:3]) is str, type(s.upper()) is str, type(str(s)) is str)
print(s == "hello", "hello" == s, s != "x", s < "z", hash(s) == hash("hello"), bool(S("")), bool(s))
print(s.upper(), s.split("l"), s.replace("l", "L"), s.startswith("he"), s.find("l"), s.center(9, "*"), s.title())
print("e" in s, "x" in s, list(s), sorted(S("cab")), "-".join(S("ab")), ",".join([S("a"), S("b")]))
print("%s|%r|%5s" % (s, s, S("ab")), "{}|{!r}|{:>7}".format(s, s, s), f"{s}|{s!r}|{s:^9}")
print(str.__len__(s), str.upper(s), str.__add__(s, "x"), str.__repr__(s), str.__eq__(s, "hello"))
print({s: 1}["hello"], S("a") + S("b"), "a".join(S("xyz")), int(S("12")), float(S("1.5")))
s.tag = 1
print(s.tag, s.__dict__)

class T(str):
    def __new__(cls, value, extra=None):
        self = super().__new__(cls, value.lower())
        self.extra = extra
        return self
    def __repr__(self):
        return "T<" + str.__repr__(self) + ">"
    def __len__(self):
        return 99
    def __getitem__(self, i):
        return "item%s" % (i,)
    def __contains__(self, x):
        return True
    def __eq__(self, other):
        return str(other).lower() == str(self)
    def __hash__(self):
        return 7
    def __add__(self, other):
        return T(str(self) + str(other), "added")
    def __iter__(self):
        return iter(["i", "j"])

t = T("ABC", 5)
print(str(t), repr(t), len(t), t[0], "z" in t, t == "ABC", hash(t), (t + "D").extra, list(t))
print(str.__len__(t), str.__getitem__(t, 0), str.upper(t), t.upper(), t.extra)
print([x for x in t], sorted(t), "".join(t))

class U(str):
    __slots__ = ()
u = U("abc")
print(u, u.upper())
try:
    u.x = 1
except AttributeError:
    print("no dict")

print(S("a%s") % "b", S("x{}").format(3), S("abc").encode(), S(b"hi", "ascii"), S(12), S())
print(S.__mro__ == (S, str, object), S.__base__ is str)
