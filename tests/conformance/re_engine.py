import re
import copyreg


def show(pat, s, flags=0, fn="search"):
    m = getattr(re.compile(pat, flags), fn)(s)
    print(pat, repr(s), None if m is None else (m.span(), m.groups(), m.lastindex))


# backtracking, lazy, possessive and atomic constructs
show(r"(a+)+b", "aaaaaaaaaaaaaaaaaaaaaaab")
show(r"a.*?b", "xaxxbxxb")
show(r"a.*b", "xaxxbxxb")
show(r"(a|ab)(c|bcd)(d*)", "abcd")
show(r"a++a", "aaaa")
show(r"(?>a+)b", "aaab")
show(r"a*+b", "aaab")
show(r"(?:a|b)*+c", "ababc")
show(r"a{2,3}?", "aaaa")
show(r"a{2,3}", "aaaa")
show(r"(ab){2}", "ababab")
show(r"(a)|(b)", "b")
show(r"((a)|b)+", "ab")
show(r"(?:(a)|b)*", "ab")

# anchors, boundaries, lookaround
show(r"^b", "a\nb", re.M)
show(r"a$", "a\nb", re.M)
show(r"\Aa", "a")
show(r"a\Z", "a\n")
show(r"a$", "a\n")
show(r"\bfoo\b", "a foo b")
show(r"\Bfoo", "afoo")
show(r"(?<=a)b", "ab")
show(r"(?<!a)b", "ab cb")
show(r"a(?=b)", "ab")
show(r"a(?!b)", "ab ac")
show(r"(?<=ab|cc)d", "abd ccd")
show(r"(?<=\d{3})x", "123x")

# backreferences and conditionals
show(r"(a)\1", "aa")
show(r"(?P<n>a)(?P=n)", "aa")
show(r"(a)?(?(1)b|c)", "ab")
show(r"(a)?(?(1)b|c)", "c")
show(r"(?i)(a)\1", "aA")
show(r"(['\"]).*?\1", "x 'abc' y")

# flags
show(r"a.b", "a\nb", re.S)
show(r"a.b", "a\nb")
show(r"A", "a", re.I)
show(r"[A-C]+", "abcd", re.I)
show(r"(?i:a)b", "Ab")
show(r"(?s:.)", "\n")
show(r"\w+", "a_1é", re.A)
show(r"\W+", "aé!", re.A)
show(r"\s+", " \t\n\x0b\x0c\r  ")
show(r"\S+", " ab ")
show(r"[\d]+", "١٢x")
show(r"\d+", "12", re.A)
show(r"(?x) a b # c", "ab")
show(r"(?m)^$", "a\n\nb")

# classes and ranges
show(r"[a-c-e]+", "abc-e")
show(r"[^a-c]+", "abcxyz")
show(r"[\]\\]+", "a]\\b")
show(r"[\w-]+", "a-b c")
show(r"[Ā-ǿ]+", "aĀǿb")
show(r"[\U00010000-\U0001ffff]+", "a\U00010000\U0001f600b")
show(r"[\d\s]+", "a1 2b")
show(r"[^\W\d]+", "12ab3")
show(r"[a-zà-ÿ]+", "xyéz", re.I)
show(r"(?i)ı", "I")
show(r"(?i)K", "k")
show(r"(?i)[K]", "K")
show(r"(?i)ſ", "S")
show(r"(?i)ß", "ẞ")

# empty matches and splitting rules
print(re.findall(r"\b", "ab cd"), re.findall(r"^", "a\nb", re.M), re.findall(r"$", "a\nb", re.M))
print(re.sub(r"\b", "|", "ab cd"), re.sub(r"(?=b)", "-", "abab"), re.sub(r"a*", "X", "baac"))
print(re.split(r"\b", "ab cd"), re.split(r"(?=b)", "abab"), re.split(r"(a)|b", "xaybz"))
print([m.span() for m in re.finditer(r"a*", "baac")])
print([m.span() for m in re.finditer(r"(?=a)|b", "ab")])
print(re.subn(r"(a)|b", r"[\1]", "ab"))
print(re.sub(r"(?P<q>b)", r"\g<q>\g<q>\g<0>\n\t\\", "abc"))
print(re.sub(r"a", r"\101\07", "a"))
print(re.sub(b"a", rb"\101", b"a"))
print(re.sub("a", "\\\\", "a"), re.sub("(a)", lambda m: None or "", "aa"))
for bad in (r"\g<9>", r"\g<x>", r"\8", r"\g<", r"\q"):
    try:
        re.sub("(a)", bad, "a")
    except Exception as e:
        print(type(e).__name__, e)

# match object API on edge cases
m = re.search(r"(?P<a>x)?(?P<b>y)", "y")
print(m.groups(), m.groupdict(), m.span("a"), m.start("a"), m.end("a"), m.lastgroup, m.lastindex)
print(m.group("a", "b"), m["b"], m.regs)
m = re.compile("").match("abc", 1)
print(m.span(), m.pos, m.endpos, re.compile("a").match("abc", 1, 2))
m = re.compile("b").search("abc", 1, 2)
print(m.pos, m.endpos, m.string)
print(re.compile(".").match("abc", -5, 100).span(), re.compile(".").match("abc", 5))
print(re.match(r"(?P<a>.)(?P<b>.)", "xy").groupdict(), re.match("(.)(.)", "xy").expand(r"\2\1"))

# deep repetition must not overflow the stack
print(len(re.match(r"(?:a|b)*", "ab" * 50000).group()))
print(re.match(r"(a)*", "a" * 100000).span(), re.match(r"(?:a)*?b", "a" * 100000 + "b").end())
print(re.match(r"(.)*", "x" * 70000).group(1), len(re.findall(r"a|b|c", "abc" * 30000)))
print(re.search(r"(?:x+x+)+y", "x" * 18) is None)
print(re.fullmatch(r"(?:ab|a)+c", "ab" * 20000 + "c") is not None)
print(len(re.sub(r"\s", "", "a b c " * 20000)), len(re.split(r",", "a," * 50000)))
print(len(re.findall(r"\w+", "word " * 40000)), len(re.findall(r"(\w)(\w)", "abcd" * 20000)))

# repeat limits
print(re.match(r"a{0,65535}", "a" * 10).end(), re.match(r"a{3}", "aaaa").end())
try:
    re.compile(r"a{4294967296}")
except Exception as e:
    print(type(e).__name__)
try:
    re.compile("(" * 100 + ")" * 100)
    print("nested ok")
except Exception as e:
    print(type(e).__name__)

# copyreg reduction and caching
p = re.compile(r"(\d+)-(?P<x>\w)", re.I)
fn, args = copyreg.dispatch_table[re.Pattern](p)
q = fn(*args)
print(q == p, q.pattern, q.flags, dict(q.groupindex))
print(re.compile("a") is re.compile("a"), re.compile(p) is p)
try:
    re.compile(p, re.I)
except Exception as e:
    print(type(e).__name__, e)
print(re.escape("a b\n-"), re.escape(b"a.b"))
