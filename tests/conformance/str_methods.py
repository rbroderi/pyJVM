s = "  Hello, World! héllo 🙂 x  "
print(s.strip(), "|", s.lstrip(), "|", s.rstrip(), "|")
print("a,b,,c".split(","), "a,b,,c".split(",", 1), "a,b,,c".rsplit(",", 1), "  a  b   c ".split(), "  a  b   c ".split(None, 1), "  a  b   c ".rsplit(None, 1))
print("abc".split("abc"), "".split(), "".split(","), "x".rsplit("x"), "aXbXc".split("X", 0), "a b".split(" ", -1))
print("one\ntwo\r\nthree\rfour\n".splitlines(), "a\nb\n".splitlines(True), "".splitlines(), "x".splitlines())
print("abc".partition("b"), "abc".partition("z"), "a.b.c".rpartition("."), "abc".rpartition("z"))
print("hello".find("l"), "hello".rfind("l"), "hello".find("z"), "hello".find("l", 3), "hello".find("l", 0, 3), "hello".find(""), "héllo🙂x".find("x"), "héllo🙂x".index("x"))
for call in ("'abc'.index('z')", "'abc'.rindex('z')"):
    try:
        eval(call, {})
    except ValueError as e:
        print("ValueError", e)
print("banana".count("a"), "banana".count("an"), "banana".count("a", 2), "banana".count("a", 1, 3), "abc".count(""), "héllo🙂🙂".count("🙂"))
print("hello world".replace("o", "0"), "hello".replace("l", "L", 1), "aaa".replace("a", "b", 2), "abc".replace("", "-"), "abc".replace("", "-", 2), "".replace("", "x"))
print("hello".startswith("he"), "hello".startswith(("x", "he")), "hello".startswith("lo", 3), "hello".endswith("lo"), "hello".endswith(("a", "lo")), "hello".endswith("l", 0, 4), "hello".startswith(""))
print("-".join(["a", "b", "c"]), "".join("xyz"), ",".join([]), "ab".join(["1", "2", "3"]))
try:
    "-".join([1, 2])
except TypeError as e:
    print("TypeError", e)
print("abc".center(9, "*"), "abc".center(8), "abc".ljust(6, "."), "abc".rjust(6), "abc".center(2), "5".zfill(3), "-5".zfill(4), "+5".zfill(4), "abc".zfill(2))
print("Hello World".title(), "hello wORLD".capitalize(), "Hello".swapcase(), "ÀB".lower(), "straße".upper(), "ǅ".lower(), "it's a dog's life".title())
print("abc".isalpha(), "ab1".isalpha(), "".isalpha(), "123".isdigit(), "²".isdigit(), "²".isdecimal(), "²".isnumeric(), "ab1".isalnum(), " \t".isspace(), "".isspace())
print("ABC".isupper(), "AbC".isupper(), "123".isupper(), "abc".islower(), "a1".islower(), "Hello World".istitle(), "Hello world".istitle(), "hello".istitle())
print("abc_1".isidentifier(), "1abc".isidentifier(), "".isidentifier(), "é".isidentifier(), "a-b".isidentifier(), "abc".isascii(), "é".isascii(), "".isascii())
print("a\tb".expandtabs(), "a\tb".expandtabs(4), "ab\tc\nd\te".expandtabs(3))
print("hello".removeprefix("he"), "hello".removesuffix("lo"), "hello".removeprefix("x"), "hello".removesuffix(""))
print("abc".translate({97: "x", 98: None, 99: 100}), "hello".translate({ord("l"): "L"}))
print("Hello".casefold(), "STRASSE".casefold(), "ß".casefold())
print("x" * 3, "ab" in "cabd", "abc"[1], "abc"[-1], "abcdef"[1:4], "abcdef"[::-1], len("héllo🙂"))
print("é🙂".encode("utf-16-be"), "é🙂".encode("utf-16-le"), "\ud800".encode("utf-16-be", errors="surrogatepass"), "a".encode(encoding="ascii", errors="strict"))
f = "a,b,c".split
print(f(","), f(sep=",", maxsplit=1), "x y z".rsplit(maxsplit=1), "a\nb".splitlines(keepends=True), "a\tb".expandtabs(tabsize=4), "aaa".replace("a", "b", count=2) if False else "ok")
up = "abc".upper
print(up(), "abc".encode(errors="ignore"))
