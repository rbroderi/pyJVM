import _sre
import re

print(_sre.MAGIC, _sre.CODESIZE, _sre.MAXREPEAT, _sre.MAXGROUPS, _sre.getcodesize())
print(sorted(n for n in dir(_sre) if not n.startswith("__")))
print([_sre.ascii_iscased(c) for c in (65, 97, 48, 233)], [_sre.unicode_iscased(c) for c in (65, 48, 233, 0x1c5, 0x2160)])
print([_sre.ascii_tolower(c) for c in (65, 90, 97, 233, 0x130)], [_sre.unicode_tolower(c) for c in (65, 233, 0xc9, 0x130, 0x3a3, 0x1e9e, 0x10400)])
print(_sre.unicode_iscased(0x10400), _sre.unicode_tolower(0x10400), _sre.unicode_tolower(0x212a), _sre.unicode_tolower(0x1c5))

# the compiled program is what the pure-Python compiler produced
code = re._compiler._code(re._parser.parse("a(b)?", 0), 32)
p = _sre.compile("a(b)?", 32, code, 1, {}, (None, None))
print(p, p.groups, p.flags, dict(p.groupindex), p.match("ab").groups())
print(p == re.compile("a(b)?"), type(p).__name__, type(p.match("a")).__name__, type(p.scanner("a")).__name__)

# invalid programs are rejected before they run
for bad in ([], [1], [0], [14, 4, 0, 0, 0, 1], [16, 97], [13, 5, 16, 97, 1], [12, 0, 4, 1], [9, 1, 1]):
    try:
        _sre.compile("x", 32, bad, 0, {}, ())
        print("accepted", bad)
    except Exception as e:
        print(type(e).__name__, e)
for args in ((1,), ("x", 0, [1], -1, {}, ())):
    try:
        _sre.compile(*args)
    except Exception as e:
        print(type(e).__name__)

# template objects
t = _sre.template(p, ["<", 1, ">"])
print(type(t).__name__)
print(re.compile("(a)(b)").sub(lambda m: m.expand(r"\2\1"), "ab"))
print(re.sub("(a)", r"<\g<1>>", "xax"), re.sub("a", r"\n", "a") == "\n")

# the scanner protocol
s = re.compile(r"\d").scanner("a1b2")
print(s.pattern.pattern, s.search().span(), s.search().span(), s.search())
s = re.compile(r"\d*").scanner("12a")
spans = []
while True:
    m = s.match()
    if m is None:
        break
    spans.append(m.span())
print(spans, s.match())

# type checks on the subject
for subject in (None, 5, [], ("a",), object()):
    try:
        re.compile("a").search(subject)
    except Exception as e:
        print(type(e).__name__, e)
print(re.compile(b"a").search(memoryview(b"xa")))

# match and pattern subclass restrictions
for cls in (re.Pattern, re.Match):
    try:
        cls()
    except Exception as e:
        print(type(e).__name__, e)
