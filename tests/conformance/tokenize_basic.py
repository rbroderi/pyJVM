import io
import token
import tokenize

SOURCE = '''# leading comment
import os, sys

def f(a, b=2, *args, **kw):
    """Doc
    string."""
    x = [1, 2.5, 3j, 0x1F, 0o17, 0b11, 1_000, .5e-3,
         'single', "double", r'raw\\d', b'bytes']   # trailing
    if a <= b and not x:
        return a ** b // 2 if kw else -a
    elif a != b:
        y = lambda z: z @ z
        x[0] += 1; x[1:2] = (y,)

    while True:
        break
    return f"{a!r:>{b}} and {x[0]:.2f}{{literal}}" + \\
        "continued"

class C(object):
\tpass

async def g():
    async with f() as h:
        await h
    return ...

naïve = "ünïcödé"   # non-ASCII
z = a if b else (c :=
                 3)
x->y
'''


def show(tok):
    print(token.tok_name[tok.type], token.tok_name[tok.exact_type], repr(tok.string), tok.start, tok.end, repr(tok.line))


print("--- generate_tokens")
tokens = list(tokenize.generate_tokens(io.StringIO(SOURCE).readline))
for tok in tokens:
    show(tok)

print("--- tokenize (bytes)")
data = SOURCE.encode("utf-8")
btokens = list(tokenize.tokenize(io.BytesIO(data).readline))
print(token.tok_name[btokens[0].type], btokens[0].string)
print(len(btokens), [tuple(t) for t in btokens[1:]] == [tuple(t) for t in tokens])

print("--- coding cookie")
latin = "# -*- coding: latin-1 -*-\ns = 'caf\xe9'\n".encode("latin-1")
for tok in tokenize.tokenize(io.BytesIO(latin).readline):
    print(token.tok_name[tok.type], repr(tok.string))
print(tokenize.detect_encoding(io.BytesIO(latin).readline))

print("--- untokenize round trip")
print(tokenize.untokenize(tokens) == SOURCE)
short = tokenize.untokenize((t.type, t.string) for t in tokenize.generate_tokens(io.StringIO("a = (1 +\n  2)\n").readline))
print(repr(short))

print("--- no trailing newline, blank lines, dedents at EOF")
for text in ["x = 1", "if a:\n    b\n    # c", "", "\n", "\n\n  \n", "if a:\n  if b:\n    c", "a\r\nb\r\n", "x = 1  # c"]:
    print(repr(text))
    for tok in tokenize.generate_tokens(io.StringIO(text, newline="").readline):
        print("  ", token.tok_name[tok.type], repr(tok.string), tok.start, tok.end, repr(tok.line))

print("--- odd characters")
for tok in tokenize.generate_tokens(io.StringIO("a = $ ? b\n").readline):
    print(token.tok_name[tok.type], token.tok_name[tok.exact_type], repr(tok.string), tok.start)

print("--- errors")
for text in ["x = '''abc\n", "a = (1,\n", "if a:\n        b\n    c\n", "s = 'abc\n"]:
    seen = []
    try:
        for tok in tokenize.generate_tokens(io.StringIO(text).readline):
            seen.append(token.tok_name[tok.type])
    except (tokenize.TokenError, SyntaxError) as exc:
        print(repr(text), seen, type(exc).__name__, exc.args[0])
    else:
        print(repr(text), seen, "no error")

print("--- iterator protocol")
import _tokenize
it = _tokenize.TokenizerIter(io.StringIO("a+1\n").readline, extra_tokens=True)
first = next(it)
print(type(first).__name__, len(first), first[1:])
print(len(list(it)))
try:
    next(it)
except StopIteration:
    print("StopIteration")
exact = [(t[0], t[1]) for t in _tokenize.TokenizerIter(io.StringIO("a+=(1)\n").readline, extra_tokens=False)]
print(exact == [(token.NAME, "a"), (token.PLUSEQUAL, "+="), (token.LPAR, "("), (token.NUMBER, "1"), (token.RPAR, ")"),
                (token.NEWLINE, ""), (token.ENDMARKER, "")])
