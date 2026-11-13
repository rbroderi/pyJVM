# _tokenize.TokenizerIter calls readline one line at a time, only when the tokens already read run out.
import io
import itertools
import token
import tokenize


def logged(text, name):
    lines = io.StringIO(text).readlines()
    it = iter(lines)

    def readline():
        try:
            line = next(it)
        except StopIteration:
            print(f"  [{name} readline -> EOF]")
            return ""
        print(f"  [{name} readline -> {line!r}]")
        return line
    return readline


def show(text, name):
    print("---", name)
    for tok in tokenize.generate_tokens(logged(text, name)):
        print("   ", token.tok_name[tok.type], repr(tok.string), tok.start, tok.end)


show("x = 1\ny = 2\n", "simple")
show("if a:\n    b\n\n# c\nc\n", "indent")
show("x = (1,\n     2,\n     3)\ny\n", "brackets")
show("s = '''a\nb\nc'''\nz\n", "triple")
show("x = 1 + \\\n    2\ny\n", "backslash")
show("f = f'''{a\n}'''\nw\n", "fstring")
show("x = 1", "no newline")
show("", "empty")


def failing():
    n = 0

    def readline():
        nonlocal n
        n += 1
        print(f"  [failing readline #{n}]")
        if n == 3:
            raise ValueError("boom")
        return f"x{n} = {n}\n"
    return readline


print("--- readline raises")
it = tokenize.generate_tokens(failing())
try:
    for tok in it:
        print("   ", token.tok_name[tok.type], repr(tok.string))
except ValueError as e:
    print("ValueError", e)

print("--- readline raises StopIteration")
lines = iter(["a = 1\n", "b = 2\n"])
for tok in tokenize.generate_tokens(lambda: next(lines)):
    print("   ", token.tok_name[tok.type], repr(tok.string))

print("--- infinite source")
counter = itertools.count()
it = tokenize.generate_tokens(lambda: f"v{next(counter)} = {next(counter)}\n")
for tok in itertools.islice(it, 12):
    print("   ", token.tok_name[tok.type], repr(tok.string), tok.start)
print("lines read:", next(counter))

print("--- bad readline results")
for bad in (lambda: 5, lambda: None):
    try:
        list(tokenize.generate_tokens(bad))
    except Exception as e:
        print(type(e).__name__)

print("--- bytes lines")
data = iter([b"a = 1\n", b"b = 'caf\xc3\xa9'\n"])
toks = list(tokenize.tokenize(lambda: next(data, b"")))
print([(token.tok_name[t.type], t.string) for t in toks])
