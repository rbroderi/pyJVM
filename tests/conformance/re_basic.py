import re
import copy

# module functions, flags, objects
print(re.match("a+b", "aab"))
print(re.search("b+", "aabbb").span())
print(re.fullmatch("a.c", "abc"), re.fullmatch("a.c", "abcd"))
print(re.findall(r"\d+", "a1b22c333"))
print(re.findall(r"(\d)(\w)", "1a2b3c"))
print(re.findall(r"(\d)", "1a2b3c"))
print(re.findall(r"x*", "axb"))
print(re.sub(r"\s+", " ", "a   b \t c"))
print(re.subn(r"a", "b", "aaa", count=2))
print(re.sub(r"(\w+) (\w+)", r"\2 \1", "hello world"))
print(re.sub(r"(?P<x>a)", r"[\g<x>]", "banana"))
print(re.sub("a", lambda m: m.group().upper(), "banana"))
print(re.sub("x*", "-", "abc"))
print(re.split(r"\W+", "a, b; c"))
print(re.split(r"(\W+)", "a, b; c"))
print(re.split(r"x*", "axbc"))
print(re.split(r",", "a,b,c,d", maxsplit=2))
print([m.span() for m in re.finditer(r"\w+", "ab cd  ef")])
print(re.escape("a.b*c"))
print(re.compile("a|b", re.I | re.M))
print(repr(re.compile("a")), repr(re.compile(b"a", re.A)))
print(repr(re.compile("a" * 300))[-12:], len(repr(re.compile("a" * 300))))

p = re.compile(r"(?P<first>\w+) (?P<second>\w+)?(x)?")
m = p.match("hello world")
print(m)
print(p.groups, dict(p.groupindex), p.flags, p.pattern)
print(m.group(), m.group(0), m.group(1), m.group("second"), m.group(1, 2), m.group(3))
print(m[0], m[1], m["first"])
print(m.groups(), m.groups("d"))
print(m.groupdict(), m.groupdict("d"))
print(m.start(), m.end(), m.span(), m.span(2), m.start("second"), m.end(3), m.span(3))
print(m.regs, m.lastindex, m.lastgroup, m.pos, m.endpos, m.string, m.re is p)
print(m.expand(r"\2-\1-\g<first>"))
for bad in (7, "nope", -1, 1.5):
    try:
        m.group(bad)
    except Exception as e:
        print(type(e).__name__, e)

# pos / endpos
q = re.compile(r"\d+")
print(q.match("ab123", 2), q.match("ab123", 2, 4), q.search("ab123", 3), q.fullmatch("ab123", 2, 5))
print(q.findall("1 22 333", 2, 6), q.sub("#", "1 22 333"), q.split("a1b22c", 1))
print(re.compile("^b").search("ab", 1), re.compile("b$").search("abc", 0, 2))
print(re.compile(r"\Bb").search("ab"), re.compile(r"\bb").search("ab", 1))

# scanner
sc = re.compile(r"\w").scanner("abc")
print(sc.match().group(), sc.search().group(), sc.match().group(), sc.match(), sc.search())
it = re.finditer("a", "aaa")
print(type(it).__name__, [x.start() for x in it])

# bytes and bytearray
print(re.match(b"a(b)", b"abc"), re.findall(b"[a-c]", b"abcd"))
print(re.sub(b"b", b"X", b"abc"), re.sub(rb"(b)", rb"<\1>", b"abc"))
print(re.split(b",", b"a,b"), re.compile(b"b").search(bytearray(b"abc")))
ba = re.compile(b"b+").search(bytearray(b"abbc"))
print(type(ba.group()).__name__, ba.group())
print(re.sub(b"b", b"x", bytearray(b"abc")))
for f in (lambda: re.match("a", b"a"), lambda: re.match(b"a", "a"), lambda: re.match("a", 3), lambda: re.sub("a", 3, "a")):
    try:
        f()
    except Exception as e:
        print(type(e).__name__, e)

# unicode and non-BMP
print(re.findall(r".", "a\U0001F600b"), re.search("b", "\U0001F600\U0001F600b").span())
print(re.sub("\U0001F600", "x", "a\U0001F600b"), re.match(r"\w+", "héllo 中").group())
print(re.findall(r"(?i)straße", "STRASSE Straße"), re.match("(?i)É", "é"))
print(re.match(r"(?a)\w+", "abé").group(), re.match(r"\d+", "٣٤").group())

# copy / eq / hash
print(copy.copy(p) is p, copy.deepcopy(p) is p, p == re.compile(p.pattern, p.flags), p != re.compile("x"))
print(hash(re.compile("a")) == hash(re.compile("a")), copy.copy(m) is m, copy.deepcopy(m) is m)
print(re.Pattern, re.Match, re.Pattern[str], re.Match[bytes])
print(re.compile("(a)|(b)").match("b").lastindex, re.compile("(?P<n>a)|(b)").match("a").lastgroup)

# errors
for pat in ("(", "a**", "[b-a]", "(?P<1>a)", r"\1", "(?<=a*)b", "a{2,1}", r"\p"):
    try:
        re.compile(pat)
    except re.error as e:
        print("error", e.msg, e.pos)

# cache, purge, flags
re.purge()
print(re.IGNORECASE | re.MULTILINE, re.I.name if hasattr(re.I, "name") else "I", int(re.X))
print(re.compile(r"""a # c
  b""", re.X).match("ab"))
