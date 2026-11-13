# Lexical errors reported through tokenize.py / _tokenize: messages, offsets and the tokens yielded before the error.
import io
import itertools
import tokenize
import _tokenize


def run(text, extra=True):
    out = []
    try:
        if extra:
            it = tokenize.generate_tokens(io.StringIO(text).readline)
        else:
            lines = iter(text.splitlines(True))
            it = _tokenize.TokenizerIter(lambda: next(lines, ""), extra_tokens=False)
        for t in it:
            out.append(tuple(t)[:2] if extra else (t[0], t[1]))
        return "OK %d %s" % (len(out), out[-2:])
    except BaseException as e:
        a = e.args
        if isinstance(e, SyntaxError) and not isinstance(e, tokenize.TokenError):
            a = (e.msg, e.lineno, e.offset, e.text, e.end_lineno, e.end_offset)
        return "%d %s %s" % (len(out), type(e).__name__, a)


NUMBERS = ["0x", "0x_", "0xg", "0xf_", "0xff", "0X1_F", "1_", "1__0", "1_0", "0b", "0b2", "0b12", "0b1_", "0b_1", "0b1_1", "0o", "0o8",
           "0o78", "0o7_", "0o17", "0_7", "09", "00", "0_", "1e", "1e5", "1e+", "1e+5", "1e-", "1e5_", "1e_5", "1E5j", "1.e", "1.e+",
           "1.5", "1.5.2", "1..2", ".5", ".5e", ".5e1", "5.", "5.j", "1j", "1J_", "1j_", "1a", "1_a", "0xfor", "1if", "1or", "1and",
           "1in", "1is", "1else", "1not", "1for", "1x", "1b", "0b1x", "0o7x", "1.5x", "1e5x", "0_0", "0_0_", "0.", "0e0", "0j", "01",
           "01.5", "01e5", "0_1j", "1\u00e9", "1_000_000", "1_000_", "0xdead_beef", "0xdead__beef"]
print("--- numbers")
for src in NUMBERS:
    for form in ("%s\n", "x = %s + 1\n", "(%s,\n"):
        text = form % src
        print(repr(text), run(text), run(text, False))

print("--- tabs and spaces")
INDENTS = ["    ", "\t", "        ", "  ", " \t", "\t ", "\t\t", "    \t", "\f", "\f    ", "   \t"]
for a, b in itertools.product(INDENTS, repeat=2):
    text = "if 1:\n%sx\n%sy\n" % (a, b)
    print(repr(text), run(text))
for a, b, c in itertools.product(["    ", "\t", "  "], ["        ", "\t\t", "\t    ", " \t"], ["", "    ", "\t", "  "]):
    text = "if 1:\n%sif 2:\n%sx\n%sy\n" % (a, b, c)
    print(repr(text), run(text))
for text in ["if 1:\n\tx\n        # c\n\ty\n", "if 1:\n\tx\n        \n\ty\n", "if 1:\n\tx\n\ty", "if 1:\n        x\n\ty", "if 1:\n\n\tx\n  \ty\n",
             "if 1:\n x\n  \n\ty\n", "class A:\n\tdef f(self):\n\t\tpass\n        def g(self):\n\t\tpass\n", "\tx = 1\n        y = 2\n",
             "if 1:\n  x = (1,\n\t2)\n  y\n"]:
    print(repr(text), run(text))

print("--- unterminated and EOF")
for text in ["x = '''abc\n", "x = '''abc\ndef\nghi", "x = '''abc\n\n", "x = 'abc", "x = 'abc\n", "x = \"abc\n", "x = 'abc\\\n", "x = 'a\\\nb\n", "x = b'abc\n",
             "x = rb'abc", "x = u'abc\n", "x = f'abc\n", "x = f'''abc\ndef\n", "x = f'{a\n", "x = f'{a\n}'\n", "x = f'''{a\n", "x = f'{a:{b\n", "x = f'{a",
             "x = rf'{a!r\n", "x = (1,\n", "x = (1,\n2\n", "x = (1,\n2", "x = (1,\n\n\n", "x = (\n", "(", "x = [1,\n# c\n", "def f(\n  a,\n  b", "x = {1:\n",
             "x = (1\n  + 2\n", "a \\\n", "a \\\nb \\\n", "a \\\n\n", "a\\", "a \\", "x = 1\n  \\\n", "\\\n", "\\", "x=1\n\\\n", "if 1:\n  x \\\n",
             "a\\ b\n", "a = 1\nb \\ c\n", "x = 1 \\ \n", "a\\\tb\n", "  a \\x\n", "a \\\nb \\ c\n", "(a,\nb \\ c)\n", "'''a\n\\\n", "x = '''a\\\n"]:
    print(repr(text), run(text))

print("--- unmatched and odd characters")
for text in ["x = 1)\n", "x = (1]\n", "x = [(1]\n", "x = $\n", "?\n", "a ! b\n", "\u00a0x\n", "a\u00a0b\n", "x = `a`\n", "\u2018x\u2019\n", "x = 1 \\\n  + 2\n",
             "a = \x00\n", "x = \u00e9t\u00e9\n", "$\n", "a $ b ? c\n", "x = \u1234\n", "if 1:\n  x\n y\n", " x\n y\n", "\fx\n", "x = 1;\n", "x = 1 # c\n"]:
    print(repr(text), run(text))
