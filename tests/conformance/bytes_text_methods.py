# bytes/bytearray: rsplit, splitlines, removeprefix/removesuffix, padding and the ASCII predicates
for T in (bytes, bytearray):
    print(T.__name__)
    s = T(b"  a bc  d  ")
    print(s.rsplit(), s.rsplit(None, 1), s.rsplit(maxsplit=2), s.rsplit(b" "), s.rsplit(b" ", 2), T(b"").rsplit(), T(b"").rsplit(b"x"))
    print(T(b"a,b,,c").rsplit(b","), T(b"a,b,,c").rsplit(b",", maxsplit=1), T(b"a,b,,c").rsplit(sep=b",", maxsplit=1))
    try:
        T(b"a").rsplit(b"")
    except ValueError as e:
        print("ValueError", e)
    t = T(b"one\ntwo\r\nthree\rfour\n\nlast")
    print(t.splitlines(), t.splitlines(True), t.splitlines(keepends=True), T(b"x\n").splitlines(), T(b"").splitlines())
    print(T(b"\x0bv\x0cf\x1cs").splitlines())       # bytes split only on \n, \r and \r\n
    print(T(b"prefix-body").removeprefix(b"prefix-"), T(b"body").removeprefix(b"x"), T(b"body").removeprefix(b""))
    print(T(b"body-suffix").removesuffix(b"-suffix"), T(b"body").removesuffix(bytearray(b"dy")), T(b"ab").removesuffix(b"abc"))
    for w in (0, 3, 6, 7):
        print(w, T(b"abc").center(w), T(b"ab").center(w, b"*"), T(b"abc").ljust(w, bytearray(b"-")), T(b"abc").rjust(w))
    try:
        T(b"a").center(5, b"ab")
    except TypeError as e:
        print("TypeError", e)
    try:
        T(b"a").ljust(5, "x")
    except TypeError as e:
        print("TypeError", e)
    print(T(b"42").zfill(5), T(b"-42").zfill(5), T(b"+42").zfill(5), T(b"").zfill(3), T(b"abc").zfill(2), T(b"-").zfill(3))
    print(T(b"a\tb\tc").expandtabs(), T(b"a\tbc\td").expandtabs(4), T(b"ab\n\tc\r\td").expandtabs(tabsize=3), T(b"a\tb").expandtabs(0))
    for v in (b"", b"abc", b"ABC", b"Abc", b"abc1", b"123", b" \t\n", b"Hello World", b"Hello world", b"HELLO", b"\x80", b"a b", b"1A"):
        x = T(v)
        print(v, x.isalnum(), x.isalpha(), x.isascii(), x.isdigit(), x.islower(), x.isspace(), x.istitle(), x.isupper())
    try:
        T(b"a").isalpha(1)
    except TypeError:
        print("TypeError")
    r = T(b"keep").rsplit()
    print(type(r[0]).__name__, type(T(b"a\nb").splitlines()[0]).__name__, type(T(b"ab").removeprefix(b"a")).__name__,
          type(T(b"a").center(3)).__name__, type(T(b"1").zfill(3)).__name__, type(T(b"\t").expandtabs()).__name__)


class B(bytes):
    pass


print(B(b"a b").rsplit(), B(b"x\ny").splitlines(), B(b"1").zfill(3), B(b"ab").isalpha())
